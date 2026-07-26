package com.taosun.hanterm.ui

import android.app.Application
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taosun.hanterm.data.prefs.AppPreferences
import com.taosun.hanterm.data.profile.ConnectionDraft
import com.taosun.hanterm.data.profile.ConnectionProfile
import com.taosun.hanterm.logging.AppLog
import com.taosun.hanterm.logging.LogClassification
import com.taosun.hanterm.net.NetworkAvailability
import com.taosun.hanterm.ssh.ConnectionRuntime
import com.taosun.hanterm.ssh.ConnectionState
import com.taosun.hanterm.ssh.ConnectionView
import com.taosun.hanterm.ssh.SshConnectResult
import com.taosun.hanterm.terminal.FontSizeController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * State-holder for the top-level SSH terminal UI.
 *
 * Connection resources live in [ConnectionRuntime]. Credentials live in
 * [ConnectionProfile]. This class owns UI-adjacent concerns (composing hint,
 * snackbar, log panel, font size) and proxies runtime flows into Compose
 * [State].
 *
 * ## Lifecycle ownership (Issue #41)
 *
 * Extends `androidx.lifecycle.ViewModel` so the instance survives across
 * configuration changes / `BackPressed` / `finish()` cycles via the
 * `ViewModelStore` provided by `ComponentActivity`. The constructor takes
 * [Application] — never `Activity` — so a `LocalContext.current` leak is
 * impossible by construction.
 *
 * `ConnectionRuntime` remains process-scoped on `HanTermApplication`; this
 * VM is a passive observer + UI mirror of that runtime. `onCleared()` does
 * **not** dispose the runtime — that ownership is carved in stone at
 * `ConnectionRuntime`/`HanTermApplication`.
 *
 * ## `SavedStateHandle` — only `showTerminal`
 *
 * [showTerminal] is restored from `SavedStateHandle[KEY_SHOW_TERMINAL]` so a
 * process-death + restore still prefers the terminal pane when the user was
 * last viewing it. Crucially the restoration is gated on runtime liveness:
 * if the runtime was disposed between sessions, the value is forced to
 * `false` so we never paint a stale terminal over an idle runtime.
 *
 * `connectionState` is **not** saved — the runtime is the source of truth
 * and the mirror re-syncs on `init`. A stale `Connected` in `SavedStateHandle`
 * would lie about the live process state.
 *
 * ## Font size
 *
 * Authoritative state lives in [fontSize] (Compose `State<Int>`). The
 * initial value is read from [AppPreferences.fontSize] (which already
 * clamps to `[MIN_FONT_SIZE, MAX_FONT_SIZE]`). Mutations come from the
 * [FontSizeController] bridge — `MainActivity.onKeyDown` posts absolute
 * values via `FontSizeController.requestSizeChange(...)` and we collect
 * here in `viewModelScope`. The VM also persists every accepted change
 * through `prefs.fontSize = ...` so the choice survives process death.
 */
class HanTermAppViewModel(
    application: Application,
    val prefs: AppPreferences,
    private val profile: ConnectionProfile,
    private val runtime: ConnectionRuntime,
    private val savedStateHandle: SavedStateHandle,
    private val fontSizeRequests: Flow<Int> = FontSizeController.sizeRequests,
    private val isNetworkAvailable: () -> Boolean =
        { NetworkAvailability.isOnline(application) },
) : ViewModel() {

    /** Seeds from the process-scoped runtime so a Bundle-restored `Connected`
     *  cannot outlive a fresh `Disconnected` runtime after process death. */
    val connectionState = mutableStateOf(runtime.state.value)

    private val _connectionView = mutableStateOf<ConnectionView>(runtime.view.value)
    val connectionView: State<ConnectionView> = _connectionView

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

    /**
     * Live font size. Read-only at the call site — only [setShowTerminal] and
     * the [fontSizeRequests] collector mutate this object's peer state.
     * `AppPreferences.fontSize` getter already clamps, so the initial value
     * is always in `[MIN_FONT_SIZE, MAX_FONT_SIZE]`.
     */
    val fontSize = mutableStateOf(prefs.fontSize)

    /**
     * Whether the terminal pane is the visible top-level surface.
     *
     * Restored from `SavedStateHandle[KEY_SHOW_TERMINAL]` when present, but
     * always gated on runtime liveness — a dead runtime cannot paint a
     * live terminal. Mutate only via [setShowTerminal] so the saved copy
     * stays in sync.
     */
    val showTerminal = mutableStateOf(
        (savedStateHandle.get<Boolean>(KEY_SHOW_TERMINAL) ?: false) &&
            runtime.view.value.isLive,
    )

    /**
     * One-shot restore flag: when `true`, the next runtime-live event (i.e.
     * a handshake that flips `runtime.view.isLive` from false → true) will
     * re-apply the saved `showTerminal=true` intent. Used for the case
     * where the saved state says "show the terminal" but the runtime was
     * not yet live at construction time (fresh process + a later connect
     * via the process-scoped runtime).
     *
     * Cleared after the first restore so a user-initiated `setShowTerminal(false)`
     * is not undone by a subsequent runtime transition.
     */
    private var pendingShowTerminalRestore: Boolean =
        savedStateHandle.get<Boolean>(KEY_SHOW_TERMINAL) ?: false

    init {
        if (runtime.view.value.isLive) {
            AppLog.i(
                "HanTermAppViewModel",
                "reattached to process-scoped runtime ${prefs.username}@${prefs.host}:${prefs.port}",
                classification = LogClassification.ConnectionMetadata,
            )
        }

        // Mirror runtime.state → connectionState. Done synchronously in init
        // so the very first read of `connectionState.value` reflects the
        // live runtime (any cross-composition observer dep sees the right
        // value on the first frame). The launched collector handles the
        // rest of the lifetime.
        viewModelScope.launch {
            launch {
                runtime.state.collect { connectionState.value = it }
            }
            launch {
                runtime.view.collect { view ->
                    _connectionView.value = view
                    if (!view.isLive && showTerminal.value) {
                        // Runtime just died while we were showing the
                        // terminal — collapse the pane and clear the saved
                        // intent so a future restore does not auto-open an
                        // empty terminal over a still-idle runtime.
                        setShowTerminal(false)
                    } else if (view.isLive &&
                        pendingShowTerminalRestore &&
                        !showTerminal.value
                    ) {
                        // Runtime just went live; re-apply the saved intent.
                        // One-shot: pendingShowTerminalRestore is cleared so
                        // a later user-initiated `setShowTerminal(false)`
                        // is not undone by a re-connect cycle.
                        setShowTerminal(true)
                        pendingShowTerminalRestore = false
                    }
                }
            }
        }

        // Bridge: imperative font-size writers (MainActivity.onKeyDown) →
        // authoritative Compose state. Defensive clamp on receive so a
        // bad caller cannot push the renderer out of range.
        viewModelScope.launch {
            fontSizeRequests.collect { requested ->
                val clamped = requested.coerceIn(
                    AppPreferences.MIN_FONT_SIZE,
                    AppPreferences.MAX_FONT_SIZE,
                )
                if (clamped != fontSize.value) {
                    fontSize.value = clamped
                    prefs.fontSize = clamped
                }
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
        viewModelScope.launch {
            val outcome = runConnect(draft)
            handleConnectOutcome(outcome, onSuccessExtra)
        }
    }

    fun disconnect() {
        // Issue #15 deferred half: runtime.disconnect hops to ioDispatcher
        // for the blocking sshj.SSHClient.close() (see
        // ConnectionRuntime.disconnect kdoc). viewModelScope runs on Main,
        // `withContext(ioDispatcher)` inside disconnect releases Main while
        // the teardown completes, then resumes back on Main.
        viewModelScope.launch {
            runtime.disconnect(userInitiated = true)
        }
    }

    fun onSessionClosed(reason: String, closeReason: com.taosun.hanterm.ssh.SessionCloseReason) {
        viewModelScope.launch {
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
     * Single mutation path for [showTerminal]. Keeps the Compose state and
     * `SavedStateHandle` copy in sync — direct `viewModel.showTerminal.value = X`
     * writes from the UI are forbidden by convention.
     */
    fun setShowTerminal(value: Boolean) {
        showTerminal.value = value
        savedStateHandle[KEY_SHOW_TERMINAL] = value
    }

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
            "connect started host=${prepared.host} port=${prepared.port} user=${prepared.username}",
            classification = LogClassification.ConnectionMetadata,
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
                    viewModelScope.launch {
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

    private companion object {
        /**
         * `SavedStateHandle` key for [showTerminal]. Kept private to the
         * file so no external class can accidentally read the wrong shape
         * — the only writer is [setShowTerminal], the only reader is the
         * `init` block.
         */
        const val KEY_SHOW_TERMINAL = "showTerminal"
    }
}

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
