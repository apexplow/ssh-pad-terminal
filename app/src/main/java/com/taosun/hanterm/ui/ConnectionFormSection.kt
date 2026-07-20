package com.taosun.hanterm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.taosun.hanterm.data.prefs.AppPreferences

/**
 * Live form values from [ConfigScreen], used by Connect before reading [AppPreferences].
 */
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
        val blob = com.taosun.hanterm.data.crypto.KeyStoreManager.encrypt(
            draft.password.toByteArray(Charsets.UTF_8),
        )
        prefs.setEncryptedPassword(blob)
    }
    prefs.privateKeyName = draft.privateKeyName.trim()
}

/**
 * Host / port / username / password / private-key form.
 *
 * Receives the canonical [ConnectionDraft] and a single [onDraftChange]
 * callback so the caller does not have to thread one callback per field.
 * The actual file picker launcher must live in the host Composable (it needs
 * the Activity result registry); this section just receives a callback that
 * fires the launcher.
 */
@Composable
internal fun ConnectionFormSection(
    draft: ConnectionDraft,
    onDraftChange: (ConnectionDraft) -> Unit,
    onImportClick: () -> Unit,
    importError: String?,
    statusMessage: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = draft.host,
                onValueChange = { onDraftChange(draft.copy(host = it)) },
                label = { Text("Host") },
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.port,
                onValueChange = {
                    onDraftChange(draft.copy(port = it.filter(Char::isDigit).take(5)))
                },
                label = { Text("Port") },
                modifier = Modifier.weight(0.35f),
                singleLine = true,
            )
        }
        OutlinedTextField(
            value = draft.username,
            onValueChange = { onDraftChange(draft.copy(username = it)) },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = draft.password,
            onValueChange = { onDraftChange(draft.copy(password = it)) },
            label = { Text("Password (encrypted at rest)") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft.privateKeyName,
                onValueChange = { onDraftChange(draft.copy(privateKeyName = it)) },
                label = { Text("Private key file") },
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                singleLine = true,
            )
            OutlinedButton(onClick = onImportClick) {
                Text("Import")
            }
        }
        importError?.let { Text(it, color = Color.Red) }
        statusMessage?.let { Text(it) }
    }
}
