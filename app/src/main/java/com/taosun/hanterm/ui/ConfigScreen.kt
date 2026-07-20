package com.taosun.hanterm.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.taosun.hanterm.data.prefs.AppPreferences
import kotlinx.coroutines.delay

/**
 * Connection configuration form. Wired to [AppPreferences] for persistence and
 * to [com.taosun.hanterm.data.crypto.KeyStoreManager] for password-at-rest
 * encryption (Sprint 1.5 §1–§3).
 *
 * Editing state lives in a single [ConnectionDraft] `mutableStateOf` (so typing
 * feels responsive), but the canonical store is [AppPreferences] — Save commits
 * every field, Clear wipes everything. The password field is *only* kept in
 * plain text inside the local draft for the duration of an editing session;
 * Save encrypts it before it ever touches SharedPreferences, and the plain copy
 * is cleared from local state on a successful save or on Clear.
 *
 * The screen is intentionally thin: form rendering lives in
 * [ConnectionFormSection], crash display in [CrashLogCard], fingerprint in
 * [FingerprintSection], and action buttons in [ConfigActions]. This Composable
 * only owns the mutable form state and the SAF launcher.
 */
@Composable
fun ConfigScreen(
    prefs: AppPreferences,
    modifier: Modifier = Modifier,
    onDraftChange: (ConnectionDraft) -> Unit = {},
) {
    val context = LocalContext.current
    val initial = remember { loadInitialConfig(prefs) }

    var draft by remember { mutableStateOf(initial) }
    var importError by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var lastCrash by remember { mutableStateOf<String?>(null) }

    // Read crash log once on entry so we can show the user what just killed
    // their app on the previous launch (no adb needed).
    LaunchedEffect(Unit) {
        lastCrash = com.taosun.hanterm.CrashHandler.readLastCrash(context)
    }

    // Propagate every draft change (typing, import, save, clear) to the parent
    // so Connect can read the current form without re-parsing prefs.
    LaunchedEffect(draft) {
        onDraftChange(draft)
    }

    val keyPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val savedName = importPrivateKey(context, uri, prefs)
            draft = draft.copy(privateKeyName = savedName)
            prefs.privateKeyName = savedName
            importError = null
            statusMessage = "Imported $savedName"
        } catch (t: Throwable) {
            importError = "Import failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        ConnectionFormSection(
            draft = draft,
            onDraftChange = { draft = it },
            onImportClick = {
                // "application/x-pem-file" isn't always recognized; "*/*" lets the
                // SAF picker surface any file the user happens to have. We still
                // copy whatever they pick into filesDir/keys/<name>.pem.
                keyPicker.launch(arrayOf("*/*"))
            },
            importError = importError,
            statusMessage = statusMessage,
        )

        // Last-crash display: if the app crashed on the previous launch, show
        // the stack trace inline so the user can copy it back to me without
        // needing adb. Displayed ABOVE the fingerprint block so a crash is
        // the first thing the user sees when they reopen the app.
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
                saveConfig(
                    prefs = prefs,
                    host = draft.host,
                    port = draft.port,
                    username = draft.username,
                    password = draft.password,
                    privateKeyName = draft.privateKeyName,
                )
                // Capture a fingerprint of the password that was just saved
                // (before we zero the local copy) so the user can compare it
                // against `echo -n "..." | sha256sum` from a terminal.
                fingerprint = passwordFingerprint(draft.password)
                appendDebugLog(
                    context,
                    "save host=${draft.host} port=${draft.port} user=${draft.username} privateKey=${draft.privateKeyName}",
                )
                // Drop the plain copy from local state — re-enter reads from prefs
                // (which holds the encrypted blob) and decrypts on demand.
                draft = draft.copy(password = "")
                statusMessage = "Saved"
            },
            onClear = {
                prefs.clear()
                draft = ConnectionDraft(
                    host = "",
                    port = AppPreferences.DEFAULT_PORT.toString(),
                    username = "",
                    password = "",
                    privateKeyName = "",
                )
                statusMessage = "Cleared"
            },
            canForgetHost = draft.host.isNotBlank(),
            onForgetHost = {
                runCatching {
                    com.taosun.hanterm.ssh.security
                        .KnownHostsStore(context)
                        .let { store ->
                            kotlinx.coroutines.runBlocking {
                                store.delete(draft.host, AppPreferences.DEFAULT_PORT)
                            }
                        }
                }
                statusMessage = "Host enrollment forgotten for ${draft.host}"
                appendDebugLog(
                    context,
                    "forget host=${draft.host} port=${AppPreferences.DEFAULT_PORT}",
                )
            },
        )
    }

    // Hide the transient status banner after a moment so it doesn't linger.
    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(2000)
            statusMessage = null
        }
    }
}
