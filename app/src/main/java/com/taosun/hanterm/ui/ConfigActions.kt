package com.taosun.hanterm.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taosun.hanterm.BuildConfig
import com.taosun.hanterm.data.crypto.KeyStoreManager
import com.taosun.hanterm.data.prefs.AppPreferences
import com.taosun.hanterm.logging.AppLog
import java.io.File
import java.security.MessageDigest

internal data class InitialConfig(
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
internal fun loadInitialConfig(prefs: AppPreferences): InitialConfig {
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
internal fun saveConfig(
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
 * Sprint 2.5 / S3 (CS-PF-01 + CS-PF-02): gated by [BuildConfig.DEBUG].
 */
internal fun passwordFingerprint(
    password: String,
    isDebug: Boolean = BuildConfig.DEBUG,
): String {
    if (!isDebug) return ""
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

/** Sprint 2.5 / S3 (CS-DL-01..04): file sink gated by [BuildConfig.DEBUG]. */
internal fun appendDebugLog(
    context: Context,
    message: String,
    isDebug: Boolean = BuildConfig.DEBUG,
) {
    Log.d("ConfigScreen", message)
    AppLog.i("ConfigScreen", message)
    if (!isDebug) return
    val debugFile = File(context.filesDir, "debug.log")
    runCatching {
        debugFile.appendText(message + "\n", Charsets.UTF_8)
    }
}

/**
 * Save / Clear / forget-enrolled-host action row.
 *
 * The host Composable owns the form mutable state; this section just emits the
 * buttons and invokes the supplied callbacks.
 */
@Composable
internal fun ConfigActions(
    onSave: () -> Unit,
    onClear: () -> Unit,
    canForgetHost: Boolean,
    onForgetHost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                Text("Save")
            }
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                Text("Clear")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (canForgetHost) {
                OutlinedButton(onClick = onForgetHost) {
                    Text("Forget enrolled host", style = TextStyle(fontSize = 11.sp))
                }
            }
        }
    }
}
