package com.taosun.hanterm.ui

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taosun.hanterm.data.prefs.AppPreferences
import com.taosun.hanterm.data.profile.ConnectionDraft
import com.taosun.hanterm.data.profile.ConnectionProfile
import com.taosun.hanterm.logging.AppLog
import com.taosun.hanterm.net.NetworkAvailability
import com.taosun.hanterm.ssh.ConnectionRuntime
import com.taosun.hanterm.ssh.ConnectionState
import com.taosun.hanterm.ssh.ConnectionView
import com.taosun.hanterm.ssh.SshConnectResult
import com.taosun.hanterm.ssh.SshSession
import com.taosun.hanterm.terminal.MockEchoSession
import com.taosun.hanterm.terminal.PtyBridge
import com.taosun.hanterm.terminal.TerminalEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * State-holder for the top-level SSH terminal UI.
 *
 * Connection resources live in [ConnectionRuntime]. Credentials and connection
 * fields live in [ConnectionProfile]. This class owns UI-adjacent concerns
 * (composing hint, snackbar, log panel) and proxies runtime StateFlows into
 * Compose [State].
 */
class HanTermAppViewModel(
    private val context: Context,
    val prefs: AppPreferences,
    private val profile: ConnectionProfile,
    private val runtime: ConnectionRuntime,
    private val uiScope: CoroutineScope,
    val connectionState: MutableState<ConnectionState>,
    val showTerminal: MutableState<Boolean>,
    private val isNetworkAvailable: () -> Boolean = { NetworkAvailability.isOnline(context) },
) {

    private val _endpoint = mutableStateOf<TerminalEndpoint>(
        runtime.view.value?.endpoint ?: MockEchoSession(),
    )
    val endpoint: State<TerminalEndpoint> = _endpoint

    private val _activeSession = mutableStateOf<SshSession?>(runtime.activeSession.value)
    val activeSession: State<SshSession?> = _activeSession

    private val _bridge = mutableStateOf<PtyBridge?>(runtime.view.value?.bridge)
    val bridge: State<PtyBridge?> = _bridge

    private val _connectionView = mutableStateOf<ConnectionView?>(runtime.view.value)
    val connectionView: State<ConnectionView?> = _connectionView

    private val _composingHint = mutableStateOf<String?>(null)
    val composingHint: State<String?> = _composingHint

    private val _showLogs = mutableStateOf(false)
    val showLogs: State<Boolean> = _showLogs

    private val _logRefreshTick = mutableStateOf(0)
    val logRefreshTick: State<Int> = _logRefreshTick

    val snackbarHostState = SnackbarHostState()

    var hasRequestedNotificationPermission by mutableStateOf(false)
        private set

    var hasRequestedBatteryOptExemption by mutableStateOf(false)
        private set

    private val mirrorJob: Job

    init {
        when (val seeded = runtime.state.value) {
            is ConnectionState.Connected,
            is ConnectionState.Error,
            is ConnectionState.Connecting,
            -> connectionState.value = seeded
            ConnectionState.Disconnected -> Unit
        }
        if (runtime.activeSession.value != null) {
            AppLog.i(
                "HanTermAppViewModel",
                "reattached to existing session ${prefs.username}@${prefs.host}:${prefs.port}",
            )
        }
        mirrorJob = uiScope.launch {
            launch {
                runtime.state.collect { connectionState.value = it }
            }
            launch {
                runtime.view.collect { view ->
                    _connectionView.value = view
                    _endpoint.value = view?.endpoint ?: MockEchoSession()
                    _bridge.value = view?.bridge
                }
            }
            launch {
                runtime.activeSession.collect { _activeSession.value = it }
            }
        }
    }

    fun markNotificationPermissionRequested() {
        hasRequestedNotificationPermission = true
    }

    fun markBatteryOptExemptionRequested() {
        hasRequestedBatteryOptExemption = true
    }

    fun startConnect(draft: ConnectionDraft? = null, onSuccessExtra: () -> Unit = {}) {
        if (connectionState.value is ConnectionState.Connecting) return
        _logRefreshTick.value++
        uiScope.launch {
            val outcome = runConnect(draft)
            handleConnectOutcome(outcome, onSuccessExtra)
        }
    }

    fun disconnect() {
        uiScope.launch {
            runtime.disconnect(userInitiated = true)
        }
    }

    fun onSessionClosed(reason: String, closeReason: com.taosun.hanterm.ssh.SessionCloseReason) {
        uiScope.launch {
            runtime.disconnect(
                userInitiated = false,
                finalState = ConnectionState.Error(formatCloseMessage(closeReason, reason)),
            )
        }
    }

    fun onComposingHint(hint: String?) {
        _composingHint.value = hint
    }

    fun toggleLogs() {
        _showLogs.value = !_showLogs.value
        if (_showLogs.value) _logRefreshTick.value++
    }

    fun dispose() {
        mirrorJob.cancel()
        runtime.dispose()
    }

    /**
     * Resolves credentials via [ConnectionProfile.prepareConnect] and calls
     * [ConnectionRuntime.connect]. Pre-flight failures (no network) publish
     * Error on [connectionState] directly.
     */
    private suspend fun runConnect(draft: ConnectionDraft?): Result<SshConnectResult> {
        if (!isNetworkAvailable()) {
            val msg = "No network connection. Check Wi‑Fi or mobile data."
            AppLog.e("HanTermAppViewModel", "connect aborted: $msg", null)
            connectionState.value = ConnectionState.Error(msg)
            return Result.failure(IllegalStateException(msg))
        }
        val source = draft ?: profile.load().draft
        val prepared = profile.prepareConnect(source).getOrElse { t ->
            val msg = t.message ?: "Missing host, username, or password/key. Fill in the form and tap Connect."
            AppLog.e("HanTermAppViewModel", "connect aborted: $msg", t)
            connectionState.value = ConnectionState.Error(msg)
            return Result.failure(t)
        }
        AppLog.i(
            "HanTermAppViewModel",
            "connect started host=${prepared.host} port=${prepared.port} user=${prepared.username} " +
                "auth=${prepared.auth::class.java.simpleName}",
        )
        return runtime.connect(prepared.host, prepared.port, prepared.username, prepared.auth)
    }

    private fun handleConnectOutcome(
        outcome: Result<SshConnectResult>,
        onSuccessExtra: () -> Unit = {},
    ) {
        outcome.fold(
            onSuccess = { result ->
                result.enrollmentNotice?.let { notice ->
                    uiScope.launch {
                        snackbarHostState.showSnackbar(
                            message = notice,
                            duration = SnackbarDuration.Long,
                        )
                    }
                }
                onSuccessExtra()
            },
            onFailure = { t ->
                _showLogs.value = true
                _logRefreshTick.value++
                AppLog.e(
                    "HanTermAppViewModel",
                    "connect outcome failure: ${t.message ?: t.javaClass.simpleName}",
                    t,
                )
            },
        )
    }
}

/**
 * Maps a structured [com.taosun.hanterm.ssh.SessionCloseReason] to a one-line
 * user-facing message for the "Connection Closed" overlay.
 */
private fun formatCloseMessage(
    closeReason: com.taosun.hanterm.ssh.SessionCloseReason,
    fallback: String,
): String = when (closeReason) {
    is com.taosun.hanterm.ssh.SessionCloseReason.TransportError ->
        "Network error: ${closeReason.message}"
    is com.taosun.hanterm.ssh.SessionCloseReason.SinkError ->
        "Internal error: ${closeReason.message}"
    com.taosun.hanterm.ssh.SessionCloseReason.IdleTimeout ->
        "Session ended due to inactivity."
    com.taosun.hanterm.ssh.SessionCloseReason.RemoteEof ->
        "Remote host closed the connection."
    com.taosun.hanterm.ssh.SessionCloseReason.UserInitiated -> fallback
}
