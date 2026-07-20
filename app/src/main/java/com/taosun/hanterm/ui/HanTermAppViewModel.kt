package com.taosun.hanterm.ui

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taosun.hanterm.data.crypto.KeyStoreManager
import com.taosun.hanterm.data.prefs.AppPreferences
import com.taosun.hanterm.logging.AppLog
import com.taosun.hanterm.net.NetworkAvailability
import com.taosun.hanterm.ssh.ActiveSshSessionStore
import com.taosun.hanterm.ssh.SshBridgeAdapter
import com.taosun.hanterm.ssh.SshConnectResult
import com.taosun.hanterm.ssh.SshConnector
import com.taosun.hanterm.ssh.SshSession
import com.taosun.hanterm.ssh.auth.Auth
import com.taosun.hanterm.terminal.BufferedPtyBridge
import com.taosun.hanterm.terminal.MockEchoSession
import com.taosun.hanterm.terminal.PtyBridge
import com.taosun.hanterm.terminal.PtyBridgeEndpoint
import com.taosun.hanterm.terminal.TerminalEndpoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * State-holder for the top-level SSH terminal UI.
 *
 * This is intentionally **not** an androidx `ViewModel`; it is a plain class
 * that receives the two [MutableState] objects managed by Compose's
 * `rememberSaveable` (`connectionState`, `showTerminal`). That keeps process-
 * death restoration working without adding a new lifecycle dependency, while
 * still making the connection state machine testable outside of Compose.
 *
 * Compose responsibilities (permission launchers, BackHandler, Scaffold) stay
 * in [HanTermApp]; this class owns the connection lifecycle, endpoint wiring,
 * and adapter management.
 */
class HanTermAppViewModel(
    private val context: Context,
    val prefs: AppPreferences,
    private val connector: SshConnector,
    private val uiScope: CoroutineScope,
    val connectionState: MutableState<ConnectionState>,
    val showTerminal: MutableState<Boolean>,
    initialSession: SshSession? = null,
    private val isNetworkAvailable: () -> Boolean = { NetworkAvailability.isOnline(context) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _endpoint = mutableStateOf<TerminalEndpoint>(initialSession ?: MockEchoSession())
    val endpoint: State<TerminalEndpoint> = _endpoint

    private val _activeSession = mutableStateOf<SshSession?>(initialSession)
    val activeSession: State<SshSession?> = _activeSession

    private val _bridge = mutableStateOf<PtyBridge?>(null)
    val bridge: State<PtyBridge?> = _bridge

    private var _adapterJob: Job? = null

    /** Dedicated scope for adapter coroutines, separate from the UI scope. */
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _composingHint = mutableStateOf<String?>(null)
    val composingHint: State<String?> = _composingHint

    private val _showLogs = mutableStateOf(false)
    val showLogs: State<Boolean> = _showLogs

    private val _logRefreshTick = mutableStateOf(0)
    val logRefreshTick: State<Int> = _logRefreshTick

    val snackbarHostState = SnackbarHostState()

    /** One-shot guard for the POST_NOTIFICATIONS permission request. */
    var hasRequestedNotificationPermission by mutableStateOf(false)
        private set

    /** One-shot guard for the ignore-battery-optimization prompt. */
    var hasRequestedBatteryOptExemption by mutableStateOf(false)
        private set

    init {
        if (initialSession != null) {
            AppLog.i(
                "HanTermAppViewModel",
                "reattached to existing session ${prefs.username}@${prefs.host}:${prefs.port}",
            )
        }
    }

    /**
     * Records that the POST_NOTIFICATIONS prompt has been shown.
     * The actual launcher lives in the Composable; this just prevents re-prompts.
     */
    fun markNotificationPermissionRequested() {
        hasRequestedNotificationPermission = true
    }

    /**
     * Records that the battery-optimization exemption prompt has been handled.
     * The actual launcher lives in the Composable; this just prevents re-prompts.
     */
    fun markBatteryOptExemptionRequested() {
        hasRequestedBatteryOptExemption = true
    }

    fun startConnect(draft: ConnectionDraft? = null, onSuccessExtra: () -> Unit = {}) {
        if (connectionState.value is ConnectionState.Connecting) return
        connectionState.value = ConnectionState.Connecting
        _logRefreshTick.value++
        uiScope.launch {
            val outcome = runConnect(draft)
            handleConnectOutcome(outcome, onSuccessExtra)
        }
    }

    fun disconnect() {
        val session = _activeSession.value
        if (session != null) {
            session.close(userInitiated = true)
        } else {
            connector.disconnect()
        }
        teardownConnection()
    }

    /**
     * Called when the inbound IO loop ends for a non-cancellation reason.
     * Tears down the client and surfaces the close reason in the UI.
     */
    fun onSessionClosed(reason: String, closeReason: com.taosun.hanterm.ssh.SessionCloseReason) {
        teardownConnection()
        connectionState.value = ConnectionState.Error(formatCloseMessage(closeReason, reason))
    }

    fun onComposingHint(hint: String?) {
        _composingHint.value = hint
    }

    fun toggleLogs() {
        _showLogs.value = !_showLogs.value
        if (_showLogs.value) _logRefreshTick.value++
    }

    /**
     * Cancels the bridge scope. Call from the Composable's `DisposableEffect`
     * so the ViewModel can outlive individual recompositions but still be
     * cleaned up when the host leaves composition (e.g. process death is not
     * covered here, but Activity recreation is).
     */
    fun dispose() {
        bridgeScope.cancel("HanTermAppViewModel disposed")
    }

    /**
     * Resolves credentials from [prefs] and calls [connector.connect].
     * All throwables are returned through [Result] so the UI can render them
     * in the status label without crashing the activity.
     */
    private suspend fun runConnect(draft: ConnectionDraft?): Result<SshConnectResult> {
        draft?.let { applyDraftForConnect(prefs, it) }
        val authKind = when {
            prefs.privateKeyName.isNotBlank() -> "PublicKeyAuth(${prefs.privateKeyName})"
            prefs.getEncryptedPassword() != null -> "PasswordAuth"
            else -> "none"
        }
        AppLog.i(
            "HanTermAppViewModel",
            "connect started host=${prefs.host} port=${prefs.port} user=${prefs.username} auth=$authKind",
        )
        if (!isNetworkAvailable()) {
            val msg = "No network connection. Check Wi‑Fi or mobile data."
            AppLog.e("HanTermAppViewModel", "connect aborted: $msg", null)
            return Result.failure(IllegalStateException(msg))
        }
        if (!prefs.hasUsableCredentials()) {
            val msg = "Missing host, username, or password/key. Fill in the form and tap Connect."
            AppLog.e("HanTermAppViewModel", "connect aborted: $msg", null)
            return Result.failure(IllegalStateException(msg))
        }
        val auth = resolveAuth(context, prefs)
        return connector.connect(prefs.host, prefs.port, prefs.username, auth)
    }

    private suspend fun resolveAuth(
        context: Context,
        prefs: AppPreferences,
    ): Auth = withContext(ioDispatcher) {
        if (prefs.privateKeyName.isNotBlank()) {
            val keyFile = com.taosun.hanterm.data.crypto.EncryptedPrivateKeyStore(context)
                .resolveKeyFile(prefs.privateKeyName)
                ?: error("private key not found for ${prefs.privateKeyName}")
            return@withContext Auth.PublicKeyAuth(keyFile.absolutePath)
        }
        val blob = prefs.getEncryptedPassword()
            ?: error("password slot empty but no private key configured")
        val plainBytes = KeyStoreManager.decrypt(blob)
        val plainChars = try {
            val decoded = Charsets.UTF_8.decode(ByteBuffer.wrap(plainBytes))
            CharArray(decoded.remaining()).also { decoded.get(it) }
        } finally {
            plainBytes.fill(0)
        }
        Auth.PasswordAuth(plainChars)
    }

    private fun handleConnectOutcome(
        outcome: Result<SshConnectResult>,
        onSuccessExtra: () -> Unit = {},
    ) {
        outcome.fold(
            onSuccess = { result ->
                val session = result.session
                _bridge.value?.close()
                _adapterJob?.cancel()
                val newBridge = BufferedPtyBridge()
                val adapter = SshBridgeAdapter(session, newBridge)
                val newAdapterJob = adapter.start(bridgeScope)
                val newEndpoint = PtyBridgeEndpoint(newBridge)

                ActiveSshSessionStore.set(session)
                _activeSession.value = session
                _bridge.value = newBridge
                _adapterJob = newAdapterJob
                _endpoint.value = newEndpoint
                connectionState.value = ConnectionState.Connected(
                    "${prefs.username}@${prefs.host}:${prefs.port}",
                )
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
                ActiveSshSessionStore.clear()
                _bridge.value?.close()
                _adapterJob?.cancel()
                _bridge.value = null
                _adapterJob = null
                _endpoint.value = MockEchoSession()
                _activeSession.value = null
                connectionState.value = ConnectionState.Error(
                    t.message ?: t.javaClass.simpleName,
                )
                _showLogs.value = true
                _logRefreshTick.value++
            },
        )
    }

    private fun teardownConnection() {
        _bridge.value?.close()
        _adapterJob?.cancel()
        _bridge.value = null
        _adapterJob = null
        _activeSession.value = null
        connector.disconnect()
        ActiveSshSessionStore.clear()
        _endpoint.value = MockEchoSession()
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
