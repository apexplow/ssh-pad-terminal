package com.taosun.hanterm.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.taosun.hanterm.data.profile.ConnectionDraft
import com.taosun.hanterm.data.profile.ConnectionProfile
import com.taosun.hanterm.data.profile.StoredProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Connection configuration form. Wired to [ConnectionProfile] for persistence
 * and credential lifecycle (encrypt / wipe / import / forget).
 *
 * Editing state lives in a local [ConnectionDraft]; [ConnectionProfile.load]
 * never returns plaintext password — use [hasStoredPassword] for UI status.
 */
@Composable
fun ConfigScreen(
    profile: ConnectionProfile,
    modifier: Modifier = Modifier,
    onDraftChange: (ConnectionDraft) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { profile.load() }

    var draft by remember { mutableStateOf(initial.draft) }
    var hasStoredPassword by remember { mutableStateOf(initial.hasStoredPassword) }
    var importError by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var lastCrash by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        lastCrash = com.taosun.hanterm.CrashHandler.readLastCrash(context)
    }

    LaunchedEffect(draft) {
        onDraftChange(draft)
    }

    val keyPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val (displayName, bytes) = readPrivateKeyFromUri(context, uri)
            profile.importKey(displayName, bytes)
                .onSuccess { savedName ->
                    draft = draft.copy(privateKeyName = savedName)
                    importError = null
                    statusMessage = "Imported $savedName"
                }
                .onFailure { t ->
                    importError = "Import failed: ${t.message ?: t.javaClass.simpleName}"
                }
        } catch (t: Throwable) {
            importError = "Import failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectionFormSection(
            draft = draft,
            onDraftChange = { draft = it },
            onImportClick = {
                keyPicker.launch(arrayOf("*/*"))
            },
            importError = importError,
            statusMessage = statusMessage,
            hasStoredPassword = hasStoredPassword,
        )

        lastCrash?.let { trace ->
            CrashLogCard(
                trace = trace,
                onCopy = {
                    copyCrashLogToClipboard(context, trace)
                    statusMessage = "Crash log copied to clipboard"
                },
                onDismiss = {
                    com.taosun.hanterm.CrashHandler.clearLastCrash(context)
                    lastCrash = null
                },
            )
        }

        fingerprint?.let { fp ->
            FingerprintSection(
                fingerprint = fp,
                onStatusMessageChange = { statusMessage = it },
            )
        }

        ConfigActions(
            onSave = {
                val typedPassword = draft.password
                val outcome = profile.save(draft)
                fingerprint = passwordFingerprint(typedPassword)
                appendDebugLog(
                    context,
                    "save host=${draft.host} port=${draft.port} user=${draft.username}",
                    privateKeyName = draft.privateKeyName,
                )
                draft = outcome.draftForUi
                hasStoredPassword = outcome.hasStoredPassword
                statusMessage = "Saved"
            },
            onClear = {
                draft = profile.clearAll()
                hasStoredPassword = false
                statusMessage = "Cleared"
            },
            canRemoveSavedPassword = hasStoredPassword,
            onRemoveSavedPassword = {
                profile.clearStoredPassword()
                hasStoredPassword = false
                statusMessage = "Saved password removed"
            },
            canForgetHost = draft.host.isNotBlank(),
            onForgetHost = {
                val port = draft.port.toIntOrNull() ?: StoredProfile.DEFAULT_PORT
                scope.launch {
                    runCatching { profile.forgetHost(draft.host, port) }
                    statusMessage = "Host enrollment forgotten for ${draft.host}"
                    appendDebugLog(
                        context,
                        "forget host=${draft.host} port=$port",
                    )
                }
            },
        )
    }

    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(2000)
            statusMessage = null
        }
    }
}
