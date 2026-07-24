package com.taosun.hanterm.ssh

import android.content.Context
import com.taosun.hanterm.logging.AppLog
import com.taosun.hanterm.logging.LogClassification
import com.taosun.hanterm.ssh.auth.Auth
import com.taosun.hanterm.terminal.BufferedPtyBridge
import com.taosun.hanterm.terminal.PtyBridgeEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Connection runtime — sole owner of the live SSH resource graph (session,
 * bridge, adapter job, FGS stop ordering) and of the [ConnectionView]
 * capability surface the UI consumes.
 *
 * ## Lifetime
 *
 * Process-scoped. Constructed once in [com.taosun.hanterm.HanTermApplication]
 * (tests may construct an ephemeral instance). Activity recreation reuses the
 * same Application-held instance — there is no degraded re-attach path and no
 * [ActiveSshSessionStore].
 *
 * ## Threading
 *
 * Adapter work runs on an internal
 * `CoroutineScope(SupervisorJob() + ioDispatcher)`. [ioDispatcher] is
 * injectable for tests.
 *
 * ## Concurrent teardown safety
 *
 * [disconnect] uses a single-winner [teardownGuard]. An in-flight [connect]
 * carries an [epoch] token; a concurrent disconnect bumps the epoch so a
 * late handshake success cannot republish [ConnectionState.Connected].
 *
 * ## Canonical teardown order
 *
 * Encoded in [teardownInternal]:
 *
 *  1. Stamp UserInitiated on the live session when requested (before socket close).
 *  2. `bridge.close()` — EOF both queues.
 *  3. `adapterJob.cancelAndJoin()` — after bridge.close.
 *  4. Null internal refs.
 *  5. `KeepAliveNudgeRegistry.set(null)` — Issue #17. A FGS tick that fires
 *     in the < 1 ms gap before step 6 sees null and returns false.
 *  6. `SshKeepAliveService.stop` — FGS before sshj.
 *  7. `connector.disconnect`.
 *  8. Publish idle [ConnectionView] + final [ConnectionState].
 *
 * Issue #15 / `TeardownState` seam: the runtime also publishes
 * [teardownState] — a [TeardownState] `Idle` / `TearingDown` / `Complete`
 * triple that lets observers learn when the 7-step order has begun and
 * settled, without coupling to [state] which already mirrors the teardown's
 * final state. The runtime-side stamp is synchronous at the top of
 * [disconnect] and after step 7; `HanTermAppViewModel`'s mirror copies it
 * into Compose [State] for UI consumers.
 *
 * See `docs/superpowers/specs/2026-07-22-connection-runtime-design.md` and
 * issue #15 (`Move SSH teardown off the main thread`).
 */
class ConnectionRuntime(
    private val context: Context,
    private val connector: SshConnector,
    private val idleTimeoutMs: Long = SshConfig.SO_TIMEOUT_MS.toLong() * 2,
    /**
     * Dispatcher used both for [ioScope] and (via [SshBridgeAdapter]'s
     * `childDispatcher`) for the bridge adapter's outbound / inbound /
     * watchdog children. Production defaults to [Dispatchers.IO]; tests
     * inject a `StandardTestDispatcher` so the children tick the same
     * virtual clock as the test body — the deferred half of issue #15.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val tag = "ConnectionRuntime"

    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _view = MutableStateFlow<ConnectionView>(IdleConnectionView())
    val view: StateFlow<ConnectionView> = _view.asStateFlow()

    /**
     * Issue #15 — public seam for the in-flight teardown lifecycle. See
     * [TeardownState]. Initial value is [TeardownState.Idle].
     * [disconnect] stamps [TeardownState.TearingDown] synchronously at
     * the top of its body (before the 7-step order runs); [teardownInternal]
     * stamps [TeardownState.Complete] after step 7 publishes the idle
     * pair. The guard semantics deliberately match the [teardownGuard]
     * CAS: a duplicate `disconnect` call while one is in flight is a
     * no-op (no second `TearingDown` stamp), so observers see at most
     * one TearingDown → Complete transition per accepted intent.
     */
    private val _teardownState = MutableStateFlow<TeardownState>(TeardownState.Idle)
    val teardownState: StateFlow<TeardownState> = _teardownState.asStateFlow()

    /**
     * Single-winner guard for [disconnect]. Cleared on connect success/failure
     * so a subsequent disconnect can run.
     */
    private val teardownGuard = AtomicReference<Unit?>(null)

    /** Mutex around connect/disconnect state transitions. */
    private val transitionLock = Mutex()

    /**
     * Bumped by [disconnect] so a handshake that started before teardown
     * cannot publish success afterwards.
     */
    private val epoch = AtomicLong(0)

    private var bridge: BufferedPtyBridge? = null
    private var adapterJob: Job? = null
    private var liveSession: SshSession? = null

    /**
     * Connect to a remote SSH host.
     *
     * - Bails if already [ConnectionState.Connecting].
     * - If already connected (or holding live resources), performs a full
     *   [disconnect] first so the previous SSH client is closed (no leak).
     * - On success, [state] becomes Connected and [view] is a live capability.
     * - On failure, [state] becomes Error and [view] falls back to idle.
     */
    suspend fun connect(
        host: String,
        port: Int,
        username: String,
        auth: Auth,
    ): Result<SshConnectResult> {
        transitionLock.withLock {
            if (_state.value is ConnectionState.Connecting) {
                AppLog.w(tag, "connect ignored: already connecting")
                return Result.failure(IllegalStateException("connect already in progress"))
            }
        }

        // Full teardown before a fresh handshake when anything live remains.
        if (liveSession != null || bridge != null || _state.value is ConnectionState.Connected) {
            AppLog.i(tag, "connect: tearing down previous session before reconnect")
            // Suspends inline — disconnect() hops to ioDispatcher for the
            // blocking teardown, so the new handshake doesn't race with
            // the old sshj close.
            disconnect(userInitiated = true, finalState = ConnectionState.Disconnected)
        }

        val myEpoch = transitionLock.withLock {
            if (_state.value is ConnectionState.Connecting) {
                AppLog.w(tag, "connect ignored: already connecting")
                return Result.failure(IllegalStateException("connect already in progress"))
            }
            _state.value = ConnectionState.Connecting
            // Re-arm so Disconnect during handshake can win the guard.
            teardownGuard.set(null)
            epoch.get()
        }

        return try {
            val result = connector.connect(host, port, username, auth)
            transitionLock.withLock {
                if (epoch.get() != myEpoch || _state.value !is ConnectionState.Connecting) {
                    AppLog.w(tag, "connect success/failure discarded: epoch invalidated")
                    abandonHandshake(result)
                    return Result.failure(
                        CancellationException("connect cancelled by concurrent disconnect"),
                    )
                }
                result.fold(
                    onSuccess = { sshResult ->
                        handleConnectSuccess(sshResult, username, host, port)
                        Result.success(sshResult)
                    },
                    onFailure = { t ->
                        handleConnectFailure(t)
                        Result.failure(SshException(t.message ?: t.javaClass.simpleName, t))
                    },
                )
            }
        } catch (ce: CancellationException) {
            transitionLock.withLock {
                if (_state.value is ConnectionState.Connecting) {
                    _state.value = ConnectionState.Disconnected
                    _view.value = IdleConnectionView()
                    teardownGuard.set(null)
                }
            }
            throw ce
        } catch (t: Throwable) {
            transitionLock.withLock {
                if (epoch.get() == myEpoch && _state.value is ConnectionState.Connecting) {
                    handleConnectFailure(t)
                }
            }
            Result.failure(SshException(t.message ?: t.javaClass.simpleName, t))
        }
    }

    private fun abandonHandshake(result: Result<SshConnectResult>) {
        result.onSuccess { sshResult ->
            runCatching { sshResult.session.close(userInitiated = true) }
        }
        if (result.isSuccess) {
            // Mirror the teardownInternal order (Issue #17): clear the
            // registry before stopping the FGS so a tick that fires in
            // that <1 ms window sees null and returns false instead of
            // writing through a soon-to-close transport. The connector
            // disconnect on the line below is `SshClient.disconnect`, which
            // ALSO clears the registry + stops the FGS as a safety net for
            // its own onClose path — the double-stop is idempotent.
            runCatching { KeepAliveNudgeRegistry.set(null) }
            runCatching { connector.disconnect(userInitiated = true) }
            runCatching { SshKeepAliveService.stop(context) }
        }
    }

    private fun handleConnectSuccess(
        sshResult: SshConnectResult,
        username: String,
        host: String,
        port: Int,
    ) {
        val session = sshResult.session
        teardownBridgesOnly()

        val newBridge = BufferedPtyBridge()
        // Children run on Dispatchers.IO (real thread pool) inside
        // SshBridgeAdapter — this is required because
        // BufferedPtyBridge.Endpoint.read() uses Java's blocking
        // LinkedBlockingQueue.take(), which would deadlock a
        // virtual-time test dispatcher. See the memory note on
        // the deferred half of issue #15.
        val adapter = SshBridgeAdapter(session, newBridge, idleTimeoutMs)
        val newAdapterJob = adapter.start(ioScope)
        val newEndpoint = PtyBridgeEndpoint(newBridge)

        bridge = newBridge
        adapterJob = newAdapterJob
        liveSession = session
        _view.value = BridgedConnectionView(newBridge, newEndpoint, session)
        _state.value = ConnectionState.Connected("$username@$host:$port")
        teardownGuard.set(null)
        AppLog.i(
            tag,
            "connect success: $username@$host:$port",
            classification = LogClassification.ConnectionMetadata,
        )
        // Issue #17: bind the FGS keepalive capability into the process
        // registry BEFORE starting the FGS, so the service's first tick
        // (≤ FGS_SSH_KEEPALIVE_NUDGE_SECONDS = 3 s later) already sees a
        // live nudge. Clear is owned by [teardownInternal] and the
        // [SshClient.disconnect] safety net.
        runCatching { KeepAliveNudgeRegistry.set(sshResult.keepAliveNudge) }
            .onFailure { AppLog.w(tag, "KeepAliveNudgeRegistry.set failed", it) }
        runCatching {
            SshKeepAliveService.start(context, "$username@$host:$port")
        }.onFailure { AppLog.e(tag, "SshKeepAliveService.start failed", it) }
    }

    private fun handleConnectFailure(t: Throwable) {
        val msg = t.message ?: t.javaClass.simpleName
        teardownBridgesOnly()
        liveSession = null
        _view.value = IdleConnectionView()
        _state.value = ConnectionState.Error(msg)
        teardownGuard.set(null)
        AppLog.e(tag, "connect failure: $msg", t)
    }

    /**
     * Tear down the live session, if any. Idempotent under [teardownGuard].
     *
     * Issue #15 — deferred half (off-the-Main-thread sshj close): the
     * synchronous prologue (CAS guard, epoch bump, [TeardownState.TearingDown]
     * stamp, and the `userInitiated` session close that load-bears the
     * [com.taosun.hanterm.ssh.SessionCloseReason] race-fix) runs on the
     * caller's thread. The actual teardown body — [teardownInternal], which
     * includes the blocking `connector.disconnect(...)` (sshj's
     * `SSHClient.close()`) — runs inside `withContext(ioDispatcher)` so
     * the UI thread never sits on a socket close.
     *
     * Originally attempted to make this a plain `fun` (non-suspend) that
     * launched the teardown onto `ioScope`, but that conflicted with
     * `BufferedPtyBridge.Endpoint.read()` using Java's blocking
     * `LinkedBlockingQueue.take()` — the bridge's outbound child is
     * pinned to a real thread pool (a virtual-time test dispatcher would
     * deadlock the caller the moment `take()` blocks). With the teardown
     * on `ioScope` (testDispatcher in tests), `cancelAndJoin` waits for
     * the children to complete in real time, but the caller's
     * `advanceUntilIdle()` returns before that. `withContext` keeps the
     * teardown observable on the caller's coroutine without giving up
     * the off-Main-thread property.
     *
     * Stamp semantics (unchanged):
     * - [TeardownState.TearingDown] — synchronous, on the caller thread.
     *   Observers see the intent was accepted even if the launched
     *   teardown's step 7 idle publish is blocked by socket I/O.
     * - [TeardownState.Complete] — inside the `try`'s `finally`, **after**
     *   [teardownInternal] publishes the idle `_view` / `_state` pair.
     *   This ordering is what [TeardownState.Complete] documents; stamp
     *   it earlier and observers see `Complete` before the idle pair,
     *   breaking `teardownState_stampsTearingDownAndComplete`.
     *
     * The guard is left "Unit" after teardown — same contract as before
     * — so a second `disconnect` is a no-op (CAS-fail); the
     * [teardownState] observer continues to see [TeardownState.Complete]
     * from the first intent.
     *
     * @param userInitiated stamps UserInitiated before socket teardown.
     * @param finalState published after teardown (Disconnected or Error).
     */
    suspend fun disconnect(
        userInitiated: Boolean = true,
        finalState: ConnectionState = ConnectionState.Disconnected,
    ) {
        if (!teardownGuard.compareAndSet(null, Unit)) {
            AppLog.i(tag, "disconnect: another caller is already tearing down")
            return
        }
        // Invalidate any in-flight connect before tearing resources.
        epoch.incrementAndGet()
        _teardownState.value = TeardownState.TearingDown
        if (userInitiated) {
            // Stay synchronous on the caller thread — load-bearing for the
            // SessionCloseReason race-fix: close(userInitiated=true)
            // synchronously writes lastCloseReason=UserInitiated before
            // enqueueing the async transport.close(). Deferring this into
            // a launched coroutine opens a window where TerminalPane's
            // finally reads an unstamped reason and paints the "Connection
            // Closed" overlay on a user-initiated Disconnect tap.
            ( _view.value as? BridgedConnectionView)?.closeUserInitiated()
                ?: liveSession?.close(userInitiated = true)
        }
        try {
            // Hop to ioDispatcher so the blocking teardown (sshj's
            // SSHClient.close() in step 7, plus the FGS stop and the
            // registry clear) never runs on the caller's thread. The
            // caller suspends — releasing whatever thread it was on
            // (Main in production) — and resumes here after the hop.
            withContext(ioDispatcher) {
                transitionLock.withLock {
                    teardownInternal(finalState)
                }
            }
        } finally {
            // Stamp Complete on both the happy path and any step 6/7
            // failure so observers never get stuck on TearingDown even
            // when connector.disconnect throws — the [TeardownState]
            // machine must always settle. teardownInternal has already
            // published _state.value = finalState inside step 7 by this
            // point, so the existing state mirror and the new
            // teardownState mirror publish the same final value.
            _teardownState.value = TeardownState.Complete(finalState)
        }
    }

    private suspend fun teardownInternal(finalState: ConnectionState) {
        AppLog.i(tag, "teardown start")
        try {
            bridge?.close()
            adapterJob?.cancelAndJoin()
            bridge = null
            adapterJob = null
            liveSession = null

            // Issue #17 — clear the FGS keepalive registry BEFORE stopping
            // the FGS. The < 1 ms window between this line and the stop
            // is harmless because any tick that fires here sees null and
            // returns false; the FGS stop then runs while the registry is
            // already empty. Order preserved with the #15 7-step teardown
            // (FGS before sshj close) — the only new step is the registry
            // clear, which lands in front of step 5.
            runCatching {
                KeepAliveNudgeRegistry.set(null)
            }.onFailure {
                AppLog.w(tag, "KeepAliveNudgeRegistry.set(null) failed", it)
            }

            runCatching {
                SshKeepAliveService.stop(context)
            }.onFailure {
                AppLog.w(tag, "SshKeepAliveService.stop failed", it)
            }

            runCatching {
                connector.disconnect(userInitiated = true)
            }.onFailure {
                AppLog.w(tag, "connector.disconnect failed", it)
            }

            _view.value = IdleConnectionView()
            _state.value = finalState
            AppLog.i(tag, "teardown done")
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AppLog.e(tag, "teardownInternal failed", t)
        }
    }

    private fun teardownBridgesOnly() {
        bridge?.close()
        adapterJob?.cancel()
        bridge = null
        adapterJob = null
    }

    /**
     * Cancel the IO scope. Process-scoped production runtimes are disposed
     * from Application teardown / tests only — never from ViewModel dispose.
     */
    fun dispose() {
        ioScope.coroutineContext[Job]?.cancel()
    }
}
