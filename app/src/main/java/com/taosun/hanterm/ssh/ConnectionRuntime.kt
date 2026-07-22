package com.taosun.hanterm.ssh

import android.content.Context
import com.taosun.hanterm.logging.AppLog
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
 *  5. `SshKeepAliveService.stop` — FGS before sshj.
 *  6. `connector.disconnect`.
 *  7. Publish idle [ConnectionView] + final [ConnectionState].
 *
 * See `docs/superpowers/specs/2026-07-22-connection-runtime-design.md`.
 */
class ConnectionRuntime(
    private val context: Context,
    private val connector: SshConnector,
    private val idleTimeoutMs: Long = SshConfig.SO_TIMEOUT_MS.toLong() * 2,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val tag = "ConnectionRuntime"

    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _view = MutableStateFlow<ConnectionView>(IdleConnectionView())
    val view: StateFlow<ConnectionView> = _view.asStateFlow()

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
        val adapter = SshBridgeAdapter(session, newBridge, idleTimeoutMs)
        val newAdapterJob = adapter.start(ioScope)
        val newEndpoint = PtyBridgeEndpoint(newBridge)

        bridge = newBridge
        adapterJob = newAdapterJob
        liveSession = session
        _view.value = BridgedConnectionView(newBridge, newEndpoint, session)
        _state.value = ConnectionState.Connected("$username@$host:$port")
        teardownGuard.set(null)
        AppLog.i(tag, "connect success: $username@$host:$port")
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
        if (userInitiated) {
            ( _view.value as? BridgedConnectionView)?.closeUserInitiated()
                ?: liveSession?.close(userInitiated = true)
        }
        transitionLock.withLock {
            teardownInternal(finalState)
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
