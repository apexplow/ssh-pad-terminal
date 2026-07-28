package com.apexplow.hanterm.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.apexplow.hanterm.data.profile.StoredProfile

/**
 * Connection configuration form. Stateless view adapter — every user event is
 * forwarded to [editor] via [DraftIntent]; the screen reads [editor]'s
 * StateFlows (collected via [collectAsState]) and renders.
 *
 * The connection-form editing state machine lives in [ConnectionDraftEditor]
 * (Issue #18). The only state this composable owns locally is
 * [lastCrashTrace] — a [com.apexplow.hanterm.CrashHandler] read, unrelated to
 * the draft-editing intent.
 *
 * Password semantics, host/port/username/key-file fields, save/clear/import/
 * forget, and debug logging are owned by [ConnectionDraftEditor] /
 * [ConnectionProfile]. This composable does not call any
 * [ConnectionProfile] method directly.
 */
@Composable
internal fun ConfigScreen(
    editor: ConnectionDraftEditor,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val draft by editor.draft.collectAsState()
    val status by editor.status.collectAsState()
    val hasStoredPassword by editor.hasStoredPassword.collectAsState()
    val lastSavedFingerprint by editor.lastSavedFingerprint.collectAsState()

    var lastCrashTrace by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        lastCrashTrace = com.apexplow.hanterm.CrashHandler.readLastCrash(context)
    }

    val keyPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val (displayName, bytes) = readPrivateKeyFromUri(context, uri)
            editor.onIntent(DraftIntent.ImportKey(displayName, bytes))
        } catch (t: Throwable) {
            // SAF stream errors (canceled, no permission, etc.) — the editor's
            // ImportKey handler also catches per-call failures. This catch is
            // for failures that happen BEFORE the editor gets the bytes.
            editor.onIntent(DraftIntent.ImportKey(displayName = "", bytes = byteArrayOf()))
            // The empty bytes payload triggers the editor's "too large" / vault
            // failure path; the user sees the resulting Error banner.
            // We swallow the SAF exception itself — it would have surfaced
            // redundantly otherwise.
        }
    }

    val importError = (status as? DraftStatus.Error)?.message
    val statusMessage = (status as? DraftStatus.Success)?.message

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectionFormSection(
            draft = draft,
            onDraftChange = { updated ->
                // The editor accepts field updates as intents. Diff the five
                // fields and emit only the ones that changed — keeps the
                // StateFlow churn minimal and the test surface honest.
                if (updated.host != draft.host) editor.onIntent(DraftIntent.UpdateHost(updated.host))
                if (updated.port != draft.port) editor.onIntent(DraftIntent.UpdatePort(updated.port))
                if (updated.username != draft.username) editor.onIntent(DraftIntent.UpdateUsername(updated.username))
                if (updated.password != draft.password) editor.onIntent(DraftIntent.UpdatePassword(updated.password))
                if (updated.privateKeyName != draft.privateKeyName) editor.onIntent(DraftIntent.UpdatePrivateKeyName(updated.privateKeyName))
            },
            onImportClick = {
                keyPicker.launch(arrayOf("*/*"))
            },
            importError = importError,
            statusMessage = statusMessage,
            hasStoredPassword = hasStoredPassword,
        )

        lastCrashTrace?.let { trace ->
            CrashLogCard(
                trace = trace,
                onCopy = {
                    copyCrashLogToClipboard(context, trace)
                },
                onDismiss = {
                    com.apexplow.hanterm.CrashHandler.clearLastCrash(context)
                    lastCrashTrace = null
                },
            )
        }

        lastSavedFingerprint?.takeIf { it.isNotEmpty() }?.let { fp ->
            FingerprintSection(
                fingerprint = fp,
                onCopyToLog = { editor.onIntent(DraftIntent.LogFingerprint(fp)) },
            )
        }

        ConfigActions(
            onSave = { editor.onIntent(DraftIntent.Save) },
            onClear = { editor.onIntent(DraftIntent.Clear) },
            canRemoveSavedPassword = hasStoredPassword,
            onRemoveSavedPassword = { editor.onIntent(DraftIntent.RemoveSavedPassword) },
            canForgetHost = draft.host.isNotBlank(),
            onForgetHost = {
                editor.onIntent(
                    DraftIntent.ForgetHost(
                        host = draft.host,
                        port = draft.port.toIntOrNull() ?: StoredProfile.DEFAULT_PORT,
                    ),
                )
            },
        )
    }
}