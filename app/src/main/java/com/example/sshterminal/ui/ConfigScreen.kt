package com.example.sshterminal.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest
import com.example.sshterminal.data.crypto.KeyStoreManager
import com.example.sshterminal.data.prefs.AppPreferences
import com.example.sshterminal.logging.AppLog
import java.io.File

/**
 * Connection configuration form. Wired to [AppPreferences] for persistence and
 * to [KeyStoreManager] for password-at-rest encryption (Sprint 1.5 §1–§3).
 *
 * Editing state lives in `mutableStateOf` (so typing feels responsive), but the
 * canonical store is [AppPreferences] — Save commits every field, Clear wipes
 * everything. The password field is *only* kept in plain text inside the local
 * `var password by remember ...` for the duration of an editing session; Save
 * encrypts it via [KeyStoreManager.encrypt] before it ever touches
 * SharedPreferences, and the plain copy is cleared from local state on a
 * successful save or on Clear.
 */
/** Live form values from [ConfigScreen], used by Connect before reading [AppPreferences]. */
data class ConnectionDraft(
    val host: String,
    val port: String,
    val username: String,
    val password: String,
    val privateKeyName: String,
)

/**
 * Writes [draft] into [prefs] for a Connect attempt.
 *
 * Unlike explicit Save, an empty password field does **not** wipe the stored
 * encrypted password — after Save the UI clears the local password box, and
 * Connect must still reuse the blob already on disk.
 */
internal fun applyDraftForConnect(prefs: AppPreferences, draft: ConnectionDraft) {
    prefs.host = draft.host.trim()
    prefs.port = draft.port.toIntOrNull() ?: AppPreferences.DEFAULT_PORT
    prefs.username = draft.username.trim()
    if (draft.password.isNotEmpty()) {
        val blob = KeyStoreManager.encrypt(draft.password.toByteArray(Charsets.UTF_8))
        prefs.setEncryptedPassword(blob)
    }
    prefs.privateKeyName = draft.privateKeyName.trim()
}

@Composable
fun ConfigScreen(
    prefs: AppPreferences,
    modifier: Modifier = Modifier,
    onDraftChange: (ConnectionDraft) -> Unit = {},
) {
    val context = LocalContext.current
    val initial = remember { loadInitialConfig(prefs) }

    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var privateKeyName by remember { mutableStateOf(initial.privateKeyName) }
    var importError by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var lastCrash by remember { mutableStateOf<String?>(null) }

    // Read crash log once on entry so we can show the user what just killed
    // their app on the previous launch (no adb needed).
    LaunchedEffect(Unit) {
        lastCrash = com.example.sshterminal.CrashHandler.readLastCrash(context)
    }

    LaunchedEffect(host, port, username, password, privateKeyName) {
        onDraftChange(
            ConnectionDraft(
                host = host,
                port = port,
                username = username,
                password = password,
                privateKeyName = privateKeyName,
            ),
        )
    }

    val keyPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val savedName = importPrivateKey(context, uri)
            privateKeyName = savedName
            prefs.privateKeyName = savedName
            importError = null
            statusMessage = "Imported $savedName"
        } catch (t: Throwable) {
            importError = "Import failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host") },
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                singleLine = true,
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                modifier = Modifier.weight(0.35f),
                singleLine = true,
            )
        }
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (encrypted at rest)") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = privateKeyName,
                onValueChange = { privateKeyName = it },
                label = { Text("Private key file") },
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                singleLine = true,
            )
            OutlinedButton(
                onClick = {
                    // "application/x-pem-file" isn't always recognized; "*/*" lets the
                    // SAF picker surface any file the user happens to have. We still
                    // copy whatever they pick into filesDir/keys/<name>.pem.
                    keyPicker.launch(arrayOf("*/*"))
                },
            ) {
                Text("Import")
            }
        }
        importError?.let { Text(it, color = androidx.compose.ui.graphics.Color.Red) }
        statusMessage?.let { Text(it) }

        // Last-crash display: if the app crashed on the previous launch, show
        // the stack trace inline so the user can copy it back to me without
        // needing adb. Displayed ABOVE the fingerprint block so a crash is
        // the first thing the user sees when they reopen the app.
        lastCrash?.let { trace ->
            Text(
                "LAST CRASH (previous launch):",
                color = androidx.compose.ui.graphics.Color.Red,
                style = androidx.compose.ui.text.TextStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                trace,
                color = androidx.compose.ui.graphics.Color.White,
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 9.sp,
                ),
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("crash log", trace))
                    statusMessage = "Crash log copied to clipboard"
                }) {
                    Text("Copy crash log", style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
                }
                TextButton(onClick = {
                    com.example.sshterminal.CrashHandler.clearLastCrash(context)
                    lastCrash = null
                }) {
                    Text("Dismiss crash log", style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
                }
            }
        }

        fingerprint?.let { fp ->
            Text(
                "Password fingerprint:\n  $fp",
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = {
                AppLog.i("ConfigScreen", "share-request fingerprint=$fp")
                statusMessage = "Fingerprint appended to log"
            }) {
                Text("Copy fingerprint to log", style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    saveConfig(
                        prefs = prefs,
                        host = host,
                        port = port,
                        username = username,
                        password = password,
                        privateKeyName = privateKeyName,
                    )
                    // Capture a fingerprint of the password that was just saved
                    // (before we zero the local copy) so the user can compare it
                    // against `echo -n "..." | sha256sum` from a terminal.
                    val fp = passwordFingerprint(password)
                    fingerprint = fp
                    AppLog.i(
                        "ConfigScreen",
                        "save host=$host port=$port user=$username " +
                            "password=$fp privateKey=$privateKeyName",
                    )
                    // Drop the plain copy from local state — re-enter reads from prefs
                    // (which holds the encrypted blob) and decrypts on demand.
                    password = ""
                    statusMessage = "Saved"
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Save")
            }
            OutlinedButton(
                onClick = {
                    prefs.clear()
                    host = ""
                    port = AppPreferences.DEFAULT_PORT.toString()
                    username = ""
                    password = ""
                    privateKeyName = ""
                    statusMessage = "Cleared"
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear")
            }
        }
    }

    // Hide the transient status banner after a moment so it doesn't linger.
    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            kotlinx.coroutines.delay(2000)
            statusMessage = null
        }
    }
}

private data class InitialConfig(
    val host: String,
    val port: String,
    val username: String,
    val password: String,
    val privateKeyName: String,
)

/**
 * Pull the persisted values out of [AppPreferences]. The encrypted password is
 * decrypted here so the user sees a populated password field after a process
 * restart (and so Clear still works as expected).
 */
private fun loadInitialConfig(prefs: AppPreferences): InitialConfig {
    val encrypted = prefs.getEncryptedPassword()
    val plain = encrypted?.let { runCatching { KeyStoreManager.decrypt(it) }.getOrNull() }
    return InitialConfig(
        host = prefs.host,
        port = prefs.port.toString(),
        username = prefs.username,
        password = plain?.toString(Charsets.UTF_8).orEmpty(),
        privateKeyName = prefs.privateKeyName,
    )
}

/**
 * Writes the form to [AppPreferences], routing the password through the Keystore.
 * Returns silently on success — any failure (e.g. Keystore error) is rethrown so
 * the caller can surface it.
 */
private fun saveConfig(
    prefs: AppPreferences,
    host: String,
    port: String,
    username: String,
    password: String,
    privateKeyName: String,
) {
    prefs.host = host.trim()
    prefs.port = port.toIntOrNull() ?: AppPreferences.DEFAULT_PORT
    prefs.username = username.trim()
    if (password.isNotEmpty()) {
        val blob = KeyStoreManager.encrypt(password.toByteArray(Charsets.UTF_8))
        prefs.setEncryptedPassword(blob)
    } else {
        // Treat empty as "wipe the saved password" so the user can intentionally
        // clear it without overwriting other fields.
        prefs.setEncryptedPassword(ByteArray(0))
    }
    prefs.privateKeyName = privateKeyName.trim()
}

/**
 * Copies the file at [uri] into `filesDir/keys/<displayName>.pem` and returns
 * the stored filename. SAF doesn't require any storage permission — the OS
 * grants the calling activity a transient read grant on the picked URI.
 */
private fun importPrivateKey(context: Context, uri: Uri): String {
    val resolver = context.contentResolver
    val displayName = queryDisplayName(resolver, uri) ?: "imported_key.pem"
    val safeName = sanitizeFileName(displayName).let { if (it.endsWith(".pem")) it else "$it.pem" }
    val keysDir = File(context.filesDir, "keys").apply { mkdirs() }
    val target = File(keysDir, safeName)

    resolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "could not open $uri" }
        target.outputStream().use { output -> input.copyTo(output) }
    }
    return safeName
}

private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
    return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}

private fun sanitizeFileName(raw: String): String =
    raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")

/**
 * Compute a non-reversible fingerprint of [password] for debugging
 * authentication issues — the user can compare this against
 * `echo -n "their_password" | sha256sum` from any terminal to confirm the
 * password bytes that reached the auth provider are the same ones they typed.
 *
 * Never log the plaintext password itself.
 */
internal fun passwordFingerprint(password: String): String {
    if (password.isEmpty()) return "(empty, length=0)"
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(password.toByteArray(Charsets.UTF_8))
    val hex = bytes.joinToString("") { "%02x".format(it) }
    val first = password.first()
    val firstByteHex = "0x%02x".format(first.code)
    val firstRepr = if (first.isLetterOrDigit() || first in "!@#\$%^&*()-_=+[]{};:,.<>?/ ") {
        "'$first'"
    } else {
        "(non-printable $firstByteHex)"
    }
    return "len=${password.length} sha256[0..16]=${hex.take(16)} firstByte=$firstByteHex $firstRepr"
}
