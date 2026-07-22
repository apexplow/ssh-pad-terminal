package com.taosun.hanterm.ssh

import android.content.Context
import com.taosun.hanterm.logging.AppLog
import com.taosun.hanterm.ssh.auth.Auth
import com.taosun.hanterm.terminal.BufferedPtyBridge
import com.taosun.hanterm.terminal.MockEchoSession
import com.taosun.hanterm.terminal.PtyBridgeEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * Connection runtime — owns every live resource required to ferry bytes
 * between the IME-driven terminal view and a remote SSH shell, and the
 * canonical teardown order between them. Replaces the six-way fan-out the
 * [com.taosun.hanterm.ui.HanTermAppViewModel] previously had to manage
 * (ssh client / session / bridge / adapter job / bridgeScope / process-scoped
 * holder).
 *
 * ## Lifetime
 *
 * Process-scoped. Constructed once in `HanTermApp.kt` alongside the
 * [SshConnector] (typically an [SshClient]). The companion-object
 * [ActiveSshSessionStore] still handles the Activity-recreation case
 * (sshj's keepalive service keeps the process alive long enough to recover
 * the socket); the runtime reads / clears the store but does not own it.
 *
 * ## Threading
 *
 * All work happens on a single internal
 * `CoroutineScope(SupervisorJob() + Dispatchers.IO)` named "ConnectionRuntime-io".
 * The legacy `bridgeScope` field on `HanTermAppViewModel` is gone — the
 * runtime takes over. `SshBridgeAdapter.start(scope)` accepts this scope
 * directly.
 *
 * ## Concurrent teardown safety
 *
 * [disconnect] uses a single-winner guard (atomic ref compare-and-set on
 * [teardownGuard]). Three concurrent callers — the Disconnect button, the
 * inbound coroutine's `finally`, and the BackHandler double-tap — race for
 * the single slot; the loser is a true no-op. The pattern is the same one
 * `SshClient.disconnect` already uses (see
 * `SshClientKeepAliveTest.disconnect_concurrentCallers_closeTheUnderlyingClientExactlyOnce`).
 *
 * ## Canonical teardown order
 *
 * Encoded in [teardownInternal]:
 *
 *  1. `bridge.close()` — puts EOF on both queues so the outbound coroutine
 *     exits via `bridge.transport.read() == null` instead of being torn out
 *     mid-take.
 *  2. `adapterJob.cancelAndJoin()` — cancels outbound + inbound + watchdog.
 *     Must come AFTER step 1; ordering the other way races the bridge.
 *  3. null out internal refs.
 *  4. `SshKeepAliveService.stop(context)` — FGS nudge callback references
 *     `SshClient.sshRef`; stopping FGS BEFORE sshj teardown avoids the
 *     callback observing a half-torn-down state. (CLAUDE.md "ordering matters".)
 *  5. `connector.disconnect(userInitiated = true)` — synchronous sshj teardown.
 *  6. `ActiveSshSessionStore.clear()` — process-scoped holder.
 *  7. Publish a new [ConnectionView] falling back to [MockEchoSession] — the
 *     view side keeps a working endpoint so the Compose UI doesn't lose its
 *     `AndroidView.update` block.
 *  8. Publish `state = Disconnected`.
 *
 * Steps 1-3 are sequential on the calling coroutine; steps 4-6 may run in
 * parallel with each other but must follow steps 1-3.
 *
 * See `docs/superpowers/specs/2026-07-22-connection-runtime-design.md` for
 * the full design rationale.
 */
class ConnectionRuntime(
    private val context: Context,
    private val connector: SshConnector,
    // Default matches SshBridgeAdapter's own default (SshConfig.SO_TIMEOUT_MS * 2)
    // — the watchdog fires after 2× the socket read timeout, which is what
    // gives a healthy remote enough time to reply before we declare it idle.
    private val idleTimeoutMs: Long = SshConfig.SO_TIMEOUT_MS.toLong() * 2,
) {

    private val tag = "ConnectionRuntime"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _view = MutableStateFlow<ConnectionView?>(null)
    val view: StateFlow<ConnectionView?> = _view.asStateFlow()

    private val _activeSession = MutableStateFlow<SshSession?>(null)
    val activeSession: StateFlow<SshSession?> = _activeSession.asStateFlow()

    /**
     * Single-winner guard for [disconnect]. The CAS ensures only the first
     * concurrent caller runs the teardown sequence; every other caller
     * returns immediately. Mirrors `SshClient.sshRef: AtomicReference`'s
     * `getAndSet(null)` pattern.
     */
    private val teardownGuard = AtomicReference<Unit?>(null)

    /** Mutex around connect/disconnect state transitions. */
    private val transitionLock = Mutex()

    /** Internal resource refs (set on connect, cleared on teardown). */
    private var bridge: BufferedPtyBridge? = null
    private var adapterJob: Job? = null

    /**
     * Re-attach to a session that survived Activity recreation. The
     * [ActiveSshSessionStore] outlives the Activity but shares the process
     * lifetime; the FGS keeps the process in the "perceptible" priority
     * bucket, so this is reliable in practice.
     *
     * The re-attached session exposes itself via the [view] StateFlow but
     * does NOT rebuild the bridge or restart the adapter — the existing
     * FGS-driven connection is already running. Callers that want to
     * actively drive IO should check [activeSession] and skip this path if
     * they want a fresh connection.
     */
    init {
        val stored = ActiveSshSessionStore.get()
        if (stored != null) {
            AppLog.i(
                tag,
                "reattached to existing session in ActiveSshSessionStore",
            )
            // Surface the session + a degraded view (no new bridge — the
            // existing one is still owned by the FGS-side adapter). This
            // lets the UI re-render with the right "Connected" status banner
            // before deciding whether to drive a fresh connect.
            _activeSession.value = stored
            _view.value = ConnectionView(
                endpoint = MockEchoSession(),
                bridge = null,
                session = stored,
            )
            _state.value = ConnectionState.Connected(
                "Reconnected to existing session",
            )
        }
    }

    /**
     * Connect to a remote SSH host. Idempotent — bails if already connecting.
     * On success, [state] becomes `Connected(summary)` and [view] is rebuilt
     * atomically. On failure, [state] becomes `Error(message)` and [view]
     * reverts to a `MockEchoSession`-backed snapshot so the Compose UI keeps
     * a valid endpoint.
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
                return Result.failure(
                    IllegalStateException("connect already in progress"),
                )
            }
            _state.value = ConnectionState.Connecting
            return try {
                val result = connector.connect(host, port, username, auth)
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
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                handleConnectFailure(t)
                Result.failure(SshException(t.message ?: t.javaClass.simpleName, t))
            }
        }
    }

    private fun handleConnectSuccess(
        sshResult: SshConnectResult,
        username: String,
        host: String,
        port: Int,
    ) {
        val session = sshResult.session
        // Tear down any existing bridge before building the new one. This
        // matches the existing handleConnectOutcome behavior and protects
        // against back-to-back connect attempts without an intervening
        // disconnect (e.g. user double-taps Connect).
        teardownBridgesOnly()

        val newBridge = BufferedPtyBridge()
        val adapter = SshBridgeAdapter(session, newBridge)
        val newAdapterJob = adapter.start(ioScope)
        val newEndpoint = PtyBridgeEndpoint(newBridge)

        bridge = newBridge
        adapterJob = newAdapterJob
        ActiveSshSessionStore.set(session)
        _activeSession.value = session
        _view.value = ConnectionView(newEndpoint, newBridge, session)
        _state.value = ConnectionState.Connected("$username@$host:$port")
        // Re-arm the teardown guard so a subsequent disconnect can run.
        teardownGuard.set(null)
        AppLog.i(tag, "connect success: $username@$host:$port")
    }

    private fun handleConnectFailure(t: Throwable) {
        val msg = t.message ?: t.javaClass.simpleName
        teardownBridgesOnly()
        ActiveSshSessionStore.clear()
        _view.value = ConnectionView(
            endpoint = MockEchoSession(),
            bridge = null,
            session = null,
        )
        _activeSession.value = null
        _state.value = ConnectionState.Error(msg)
        teardownGuard.set(null)
        AppLog.e(tag, "connect failure: $msg", t)
    }

    /**
     * Tear down the live session, if any. Idempotent — safe to call from
     * the Disconnect button, BackHandler double-tap, the inbound coroutine's
     * `finally`, and the ViewModel's `onSessionClosed` concurrently. The
     * first caller wins the [teardownGuard] race and runs the canonical
     * 8-step teardown; every other caller returns immediately.
     *
     * Never throws. Cancellation propagates so structured concurrency can
     * unwind.
     */
    suspend fun disconnect() {
        // Single-winner CAS. The first concurrent caller sees `null` and
        // wins; every subsequent caller sees `Unit` and returns. The actual
        // teardown work runs in `ioScope.launch { ... }` so the calling
        // coroutine (typically the UI) doesn't block on `cancelAndJoin`.
        if (!teardownGuard.compareAndSet(null, Unit)) {
            AppLog.i(tag, "disconnect: another caller is already tearing down")
            return
        }
        ioScope.launch { teardownInternal() }
    }

    /**
     * The canonical teardown order. Steps are documented on the class kdoc.
     * Runs on `ioScope`. Never throws — every step that touches external
     * resources is wrapped in `runCatching`.
     */
    private suspend fun teardownInternal() {
        AppLog.i(tag, "teardown start")
        try {
            // 1. close bridge (puts EOF on both queues)
            bridge?.close()

            // 2. cancel adapter job — must be AFTER bridge.close so the
            // outbound coroutine sees EOF and exits via read()==null
            // instead of being torn out mid-take.
            adapterJob?.cancelAndJoin()

            // 3. null out internal refs
            bridge = null
            adapterJob = null
            _activeSession.value = null

            // 4. stop FGS BEFORE sshj teardown. FGS nudge callback references
            // SshClient.sshRef; stopping FGS first avoids the callback
            // observing a half-torn-down state.
            runCatching {
                SshKeepAliveService.stop(context)
            }.onFailure {
                AppLog.w(tag, "SshKeepAliveService.stop failed", it)
            }

            // 5. close sshj (synchronous; releases socket)
            runCatching {
                connector.disconnect(userInitiated = true)
            }.onFailure {
                AppLog.w(tag, "connector.disconnect failed", it)
            }

            // 6. clear process-scoped holder
            ActiveSshSessionStore.clear()

            // 7. publish the new view (endpoint falls back to MockEchoSession)
            _view.value = ConnectionView(
                endpoint = MockEchoSession(),
                bridge = null,
                session = null,
            )

            // 8. publish state
            _state.value = ConnectionState.Disconnected
            AppLog.i(tag, "teardown done")
        } catch (ce: CancellationException) {
            // If the ioScope itself was cancelled mid-teardown, propagate
            // so structured concurrency unwinds. The state has been left in
            // a half-torn-down shape — the next disconnect() call will retry
            // from step 1 once the scope is healthy.
            throw ce
        } catch (t: Throwable) {
            AppLog.e(tag, "teardownInternal failed", t)
        }
    }

    /**
     * Tear down just the bridge / adapter pair (used when re-attaching on
     * connect-success or connect-failure). Does NOT touch sshj, the FGS, or
     * the process-scoped holder. Steps 1-2 of the canonical teardown only.
     */
    private fun teardownBridgesOnly() {
        bridge?.close()
        adapterJob?.cancel()
        bridge = null
        adapterJob = null
    }

    /**
     * Detach from the process. Called from `HanTermApplication.onTerminate`
     * or in tests' `@After`. Cancels the internal scope so any in-flight
     * adapter coroutines are stopped; safe to call multiple times.
     */
    fun dispose() {
        ioScope.coroutineContext[Job]?.cancel()
    }
}