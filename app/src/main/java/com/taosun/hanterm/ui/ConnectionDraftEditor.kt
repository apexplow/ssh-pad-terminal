package com.taosun.hanterm.ui

import com.taosun.hanterm.data.profile.ConnectionDraft
import com.taosun.hanterm.data.profile.ConnectionProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Single owner of the connection-form editing intent. Issue #18
 * consolidation: every user action on [ConfigScreen] flows through
 * [onIntent] and produces observable [draft] / [status] /
 * [hasStoredPassword] / [lastSavedFingerprint] state.
 *
 * ## Why a class, not a ViewModel
 *
 * The editor borrows a [CoroutineScope] from the caller (so the lifetime is
 * tied to the configuration screen composition, not the process) and holds
 * no [androidx.lifecycle.ViewModel] / `SavedStateHandle` dependencies. That
 * keeps it testable in pure JUnit via [kotlinx.coroutines.test.runTest] —
 * see [ConnectionDraftEditorTest]. Reusable for a future multi-host list
 * (Issue #18 US10) because nothing here is `mutableStateOf`-bound.
 *
 * **Scope of the lifecycle decision (Issue #41)**: lifecycle ownership is
 * reserved for the top-level app state holder. `HanTermAppViewModel` is the
 * only `androidx.lifecycle.ViewModel` in the project. All connection-form
 * state stays composition-scoped on purpose — promoting the editor to a
 * `ViewModel` would not gain anything here (no Activity leak, no
 * process-death survival beyond what `rememberSaveable` already provides),
 * and would force every `ConnectionDraftEditorTest` case into Robolectric
 * to drive `Dispatchers.setMain`. Don't migrate this without a separate
 * spec.
 *
 * ## Persistence boundary
 *
 * The editor is the **only** UI-side caller of [ConnectionProfile.save] /
 * [ConnectionProfile.clearAll] / [ConnectionProfile.clearStoredPassword] /
 * [ConnectionProfile.importKey] / [ConnectionProfile.forgetHost]. It is
 * NOT the caller of [ConnectionProfile.prepareConnect] — that path is owned
 * by [com.taosun.hanterm.ssh.ConnectionRuntime] via
 * [com.taosun.hanterm.ui.HanTermAppViewModel.runConnect] and persists the
 * draft as a side effect of starting a connect. Don't add a `PrepareConnect`
 * intent here; the editor's lifecycle ends when the user submits to connect.
 *
 * ## Logging policy
 *
 * All credential-derived log lines go through [DebugLogSink] so the editor
 * never references [android.util.Log], [com.taosun.hanterm.logging.AppLog],
 * or [android.content.Context] directly. Production wires
 * [AndroidDebugLogSink]; tests wire a `RecordingDebugLogSink`. This is the
 * primary reason the editor's test runs in pure JUnit instead of Robolectric.
 */
internal class ConnectionDraftEditor(
    private val profile: ConnectionProfile,
    private val scope: CoroutineScope,
    private val debugLog: DebugLogSink = NoOpDebugLogSink,
    private val autoClearDelay: Duration = 2.seconds,
    private val maxKeyBytes: Int = DEFAULT_MAX_KEY_BYTES,
) {

    /** Live draft. Reads are always synchronous via [StateFlow.value]. */
    val draft: StateFlow<ConnectionDraft>

    /** Current editing status. `Success` is auto-cleared after [autoClearDelay]; `Error` is sticky. */
    val status: StateFlow<DraftStatus>

    /** True if the profile holds a stored password blob. Updated by Save / Clear / RemoveSavedPassword. */
    val hasStoredPassword: StateFlow<Boolean>

    /**
     * Debug-build password fingerprint of the most recent Save. Empty in
     * release (gated by [DebugLogSink.fingerprint]). Cleared on the next
     * Save. Separate from [status] to keep credential-derived strings off
     * the status channel — see the design decision pinned in
     * `precious-baking-garden.md` §"Key contract decisions".
     */
    val lastSavedFingerprint: StateFlow<String?>

    private val _draft: MutableStateFlow<ConnectionDraft>
    private val _status: MutableStateFlow<DraftStatus> = MutableStateFlow(DraftStatus.Idle)
    private val _hasStoredPassword: MutableStateFlow<Boolean>
    private val _lastSavedFingerprint: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Auto-clear job for the most recent `Success` status. Cancelled when a new Success arrives. */
    private var autoClearJob: Job? = null

    init {
        val snapshot = profile.load()
        _draft = MutableStateFlow(snapshot.draft)
        _hasStoredPassword = MutableStateFlow(snapshot.hasStoredPassword)
        draft = _draft.asStateFlow()
        status = _status.asStateFlow()
        hasStoredPassword = _hasStoredPassword.asStateFlow()
        lastSavedFingerprint = _lastSavedFingerprint.asStateFlow()
    }

    /**
     * Apply a user intent. Sync intents modify state on the calling thread
     * (typically Compose Main). Only [DraftIntent.ForgetHost] is async — it
     * launches on [scope] because [ConnectionProfile.forgetHost] is `suspend`.
     */
    fun onIntent(intent: DraftIntent) {
        when (intent) {
            is DraftIntent.UpdateHost -> _draft.update { it.copy(host = intent.host) }
            is DraftIntent.UpdatePort -> _draft.update { it.copy(port = intent.port) }
            is DraftIntent.UpdateUsername -> _draft.update { it.copy(username = intent.username) }
            is DraftIntent.UpdatePassword -> _draft.update { it.copy(password = intent.password) }
            is DraftIntent.UpdatePrivateKeyName -> _draft.update { it.copy(privateKeyName = intent.name) }

            is DraftIntent.Save -> handleSave()
            is DraftIntent.Clear -> handleClear()
            is DraftIntent.RemoveSavedPassword -> handleRemoveSavedPassword()
            is DraftIntent.ForgetHost -> handleForgetHost(intent)
            is DraftIntent.ImportKey -> handleImportKey(intent)
            is DraftIntent.LogFingerprint -> handleLogFingerprint(intent)
            is DraftIntent.DismissStatus -> _status.value = DraftStatus.Idle
        }
    }

    // ── handlers ─────────────────────────────────────────────────────────

    private fun handleSave() {
        val typedPassword = _draft.value.password
        val outcome = profile.save(_draft.value)
        val fp = debugLog.fingerprint(typedPassword)
        debugLog.append(
            message = "save host=${_draft.value.host} " +
                "port=${_draft.value.port} " +
                "user=${_draft.value.username}",
            privateKeyName = _draft.value.privateKeyName,
        )
        _draft.update { outcome.draftForUi }
        _hasStoredPassword.value = outcome.hasStoredPassword
        _lastSavedFingerprint.value = fp
        emitTransientSuccess("Saved")
    }

    private fun handleClear() {
        val blank = profile.clearAll()
        _draft.update { blank }
        _hasStoredPassword.value = false
        emitTransientSuccess("Cleared")
    }

    private fun handleRemoveSavedPassword() {
        profile.clearStoredPassword()
        _hasStoredPassword.value = false
        emitTransientSuccess("Saved password removed")
    }

    private fun handleForgetHost(intent: DraftIntent.ForgetHost) {
        scope.launch {
            runCatching { profile.forgetHost(intent.host, intent.port) }
            debugLog.append(
                message = "forget host=${intent.host} port=${intent.port}",
                privateKeyName = "",
            )
            emitTransientSuccess("Host enrollment forgotten for ${intent.host}")
        }
    }

    private fun handleImportKey(intent: DraftIntent.ImportKey) {
        if (intent.bytes.size > maxKeyBytes) {
            _status.value = DraftStatus.Error(
                "Key file too large: ${intent.bytes.size} bytes (max $maxKeyBytes)",
            )
            return
        }
        // profile.importKey already returns Result<String>; wrap with
        // runCatching only to convert unexpected exceptions into a clean Error.
        val result = runCatching { profile.importKey(intent.displayName, intent.bytes) }
            .getOrElse { Result.failure(it) }
        result.onSuccess { safeName ->
            _draft.update { it.copy(privateKeyName = safeName) }
            emitTransientSuccess("Imported $safeName")
        }.onFailure { t ->
            _status.value = DraftStatus.Error(
                "Import failed: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    private fun handleLogFingerprint(intent: DraftIntent.LogFingerprint) {
        debugLog.logCredential("share-request fingerprint=${intent.fingerprint}")
        emitTransientSuccess("Fingerprint appended to log")
    }

    // ── status helpers ──────────────────────────────────────────────────

    private fun emitTransientSuccess(message: String) {
        // Cancel any in-flight auto-clear so a back-to-back Save → Clear
        // doesn't get its older timer firing after the newer status is set.
        autoClearJob?.cancel()
        _status.value = DraftStatus.Success(message)
        autoClearJob = scope.launch {
            delay(autoClearDelay)
            // Only clear if the status is still the one we emitted. If a
            // newer intent has overwritten us (Success or Error), leave the
            // newer state alone — the editor's "sticky Error" + "newest
            // Success wins" contract depends on this guard.
            if (_status.value == DraftStatus.Success(message)) {
                _status.value = DraftStatus.Idle
            }
        }
    }

    companion object {
        /** Default cap on [DraftIntent.ImportKey.bytes]. 2 MB is generous for any PEM SSH key. */
        const val DEFAULT_MAX_KEY_BYTES: Int = 2 * 1024 * 1024
    }
}

/**
 * All user intents on the connection form. Sealed so the editor's
 * `when (intent)` is exhaustive at compile time.
 */
internal sealed class DraftIntent {
    data class UpdateHost(val host: String) : DraftIntent()
    data class UpdatePort(val port: String) : DraftIntent()
    data class UpdateUsername(val username: String) : DraftIntent()
    data class UpdatePassword(val password: String) : DraftIntent()
    data class UpdatePrivateKeyName(val name: String) : DraftIntent()

    /** SAF/UI has already resolved the URI to bytes; the editor never sees a Uri. */
    data class ImportKey(val displayName: String, val bytes: ByteArray) : DraftIntent()

    data class ForgetHost(val host: String, val port: Int) : DraftIntent()

    data object Save : DraftIntent()
    data object Clear : DraftIntent()
    data object RemoveSavedPassword : DraftIntent()

    /** Sent by `FingerprintSection`'s "Copy to log" button. Logs under CredentialMetadata. */
    data class LogFingerprint(val fingerprint: String) : DraftIntent()

    /** Explicit user dismissal of a sticky status (e.g. closing an error banner). */
    data object DismissStatus : DraftIntent()
}

/**
 * Status surfaced to the UI for rendering. `Success` auto-clears to [Idle]
 * after the editor's `autoClearDelay`; `Error` is sticky and only resets via
 * [DraftIntent.DismissStatus] or an overwrite by a subsequent intent.
 *
 * Carries only a `message` — credential-derived content (the saved-password
 * fingerprint) lives on `editor.lastSavedFingerprint` instead.
 */
internal sealed class DraftStatus {
    data object Idle : DraftStatus()
    data class Success(val message: String) : DraftStatus()
    data class Error(val message: String) : DraftStatus()
}

/**
 * Default no-op [DebugLogSink] used when the editor is constructed without
 * an explicit sink (e.g. in tests that don't exercise log propagation). All
 * methods are no-ops; [fingerprint] returns the empty string (release-build
 * convention).
 */
internal object NoOpDebugLogSink : DebugLogSink {
    override fun append(message: String, privateKeyName: String) = Unit
    override fun logCredential(message: String) = Unit
    override fun fingerprint(password: String): String = ""
}