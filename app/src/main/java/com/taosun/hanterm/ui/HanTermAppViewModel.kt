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
import com.taosun.hanterm.ssh.ConnectionRuntime
import com.taosun.hanterm.ssh.ConnectionState
import com.taosun.hanterm.ssh.ConnectionView
import com.taosun.hanterm.ssh.SshConnectResult
import com.taosun.hanterm.ssh.SshSession
import com.taosun.hanterm.ssh.auth.Auth
import com.taosun.hanterm.terminal.MockEchoSession
import com.taosun.hanterm.terminal.PtyBridge
import com.taosun.hanterm.terminal.TerminalEndpoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Connection resources (session / bridge / adapter / FGS / teardown order)
 * live in [ConnectionRuntime]. This class owns UI-adjacent concerns
 * (composing hint, snackbar, log panel, credential resolution) and proxies
 * the runtime's StateFlows into Compose [State] so existing call sites that
 * read `.value` during composition keep recomposing.
 *
 * Compose responsibilities (permission launchers, BackHandler, Scaffold) stay
 * in [HanTermApp].
 */
class HanTermAppViewModel(
    private val context: Context,
    val prefs: AppPreferences,
    private val runtime: ConnectionRuntime,
    private val uiScope: CoroutineScope,
    val connectionState: MutableState<ConnectionState>,
    val showTerminal: MutableState<Boolean>,
    private val isNetworkAvailable: () -> Boolean = { NetworkAvailability.isOnline(context) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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

    /** One-shot guard for the POST_NOTIFICATIONS permission request. */
    var hasRequestedNotificationPermission by mutableStateOf(false)
        private set

    /** One-shot guard for the ignore-battery-optimization prompt. */
    var hasRequestedBatteryOptExemption by mutableStateOf(false)
        private set

    /**
     * Parent job for the three StateFlow → Compose State mirrors. Cancelled
     * in [dispose] so unit tests' `runTest` scopes don't hang on never-
     * completing `collect` children.
     */
    private val mirrorJob: Job

    init {
        // Seed connectionState from the runtime (covers Activity-recreation
        // re-attach where runtime.init already published Connected).
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
        // Mirror runtime StateFlows into Compose State so reading `.value`
        // during composition invalidates and recomposes.
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

    /**
     * Called when the inbound IO loop ends for a non-cancellation reason.
     * Tears down the client and surfaces the close reason in the UI.
     */
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

    /**
     * Cancels the StateFlow mirrors and the runtime's IO scope. Call from the
     * Composable's `DisposableEffect` so adapter coroutines are cleaned up
     * when the host leaves composition.
     */
    fun dispose() {
        mirrorJob.cancel()
        runtime.dispose()
    }

    /**
     * Resolves credentials from [prefs] and calls [ConnectionRuntime.connect].
     * Pre-flight failures (no network / missing credentials) never touch the
     * runtime — they publish Error on [connectionState] directly so a
     * half-started connect doesn't leave the runtime in Connecting.
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
            connectionState.value = ConnectionState.Error(msg)
            return Result.failure(IllegalStateException(msg))
        }
        if (!prefs.hasUsableCredentials()) {
            val msg = "Missing host, username, or password/key. Fill in the form and tap Connect."
            AppLog.e("HanTermAppViewModel", "connect aborted: $msg", null)
            connectionState.value = ConnectionState.Error(msg)
            return Result.failure(IllegalStateException(msg))
        }
        val auth = resolveAuth(context, prefs)
        return runtime.connect(prefs.host, prefs.port, prefs.username, auth)
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
        val decoded = Charsets.UTF_8.decode(ByteBuffer.wrap(plainBytes))
        val plainChars = try {
            CharArray(decoded.remaining()).also { decoded.get(it) }
        } finally {
            // Best-effort wipe of the intermediate CharBuffer copy before it is
            // GC'd. CharsetDecoder allocates a buffer that holds the decoded
            // plaintext; without this step the password lingers in memory as a
            // second copy until collection.
            try {
                decoded.clear()
                while (decoded.hasRemaining()) {
                    decoded.put('\u0000')
                }
            } catch (_: Throwable) {
                // Buffer may be read-only or otherwise non-writable; nothing
                // more we can do portably.
            }
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
                // Runtime already published Connected + ConnectionView.
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
                // Runtime already published Error + MockEchoSession view for
                // transport failures. Pre-flight failures set Error above and
                // never touched the runtime — still open the log panel.
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
