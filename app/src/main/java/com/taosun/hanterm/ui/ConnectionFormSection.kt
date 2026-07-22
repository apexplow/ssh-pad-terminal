package com.taosun.hanterm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taosun.hanterm.data.prefs.AppPreferences
import com.taosun.hanterm.theme.WarpAccent
import com.taosun.hanterm.theme.WarpMuted
import com.taosun.hanterm.theme.WarpPanel
import com.taosun.hanterm.theme.WarpSurface
import com.taosun.hanterm.theme.WarpText

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
 * Modern Host / port / username / password / private-key configuration card section.
 *
 * Receives the canonical [ConnectionDraft] and a single [onDraftChange]
 * callback. Features a modern dark card layout with clear visual hierarchy,
 * password visibility toggling, leading icons, and badge labels.
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
    var passwordVisible by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = WarpAccent,
        unfocusedBorderColor = Color(0xFF2E353D),
        focusedLabelColor = WarpAccent,
        unfocusedLabelColor = WarpMuted,
        focusedLeadingIconColor = WarpAccent,
        unfocusedLeadingIconColor = WarpMuted,
        focusedTrailingIconColor = WarpAccent,
        unfocusedTrailingIconColor = WarpMuted,
        cursorColor = WarpAccent,
        focusedTextColor = WarpText,
        unfocusedTextColor = WarpText,
        focusedContainerColor = WarpPanel.copy(alpha = 0.4f),
        unfocusedContainerColor = WarpPanel.copy(alpha = 0.2f),
    )

    val fieldShape = RoundedCornerShape(10.dp)

    Card(
        colors = CardDefaults.cardColors(containerColor = WarpSurface),
        border = BorderStroke(1.dp, Color(0xFF2E353D)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Card Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = AppIcons.Server,
                        contentDescription = null,
                        tint = WarpAccent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "主机与凭据配置",
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = WarpText,
                        ),
                    )
                }

                // Single Host Badge Indicator
                Box(
                    modifier = Modifier
                        .background(
                            color = WarpAccent.copy(alpha = 0.15f),
                            shape = CircleShape,
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(WarpAccent, CircleShape),
                        )
                        Text(
                            text = "当前主机 (Single Host)",
                            style = TextStyle(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = WarpAccent,
                            ),
                        )
                    }
                }
            }

            // Host & Port Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft.host,
                    onValueChange = { onDraftChange(draft.copy(host = it)) },
                    label = { Text("Host") },
                    leadingIcon = {
                        Icon(AppIcons.Server, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.weight(0.72f),
                    singleLine = true,
                    shape = fieldShape,
                    colors = fieldColors,
                )
                OutlinedTextField(
                    value = draft.port,
                    onValueChange = {
                        onDraftChange(draft.copy(port = it.filter(Char::isDigit).take(5)))
                    },
                    label = { Text("Port") },
                    leadingIcon = {
                        Icon(AppIcons.Port, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.weight(0.28f),
                    singleLine = true,
                    shape = fieldShape,
                    colors = fieldColors,
                )
            }

            // Username
            OutlinedTextField(
                value = draft.username,
                onValueChange = { onDraftChange(draft.copy(username = it)) },
                label = { Text("Username") },
                leadingIcon = {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = fieldShape,
                colors = fieldColors,
            )

            // Password with Toggle
            OutlinedTextField(
                value = draft.password,
                onValueChange = { onDraftChange(draft.copy(password = it)) },
                label = { Text("Password (encrypted at rest)") },
                leadingIcon = {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) AppIcons.EyeOff else AppIcons.Eye,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = fieldShape,
                colors = fieldColors,
            )

            // Private Key File with Embedded Import Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft.privateKeyName,
                    onValueChange = { onDraftChange(draft.copy(privateKeyName = it)) },
                    label = { Text("Private key file") },
                    leadingIcon = {
                        Icon(AppIcons.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = fieldShape,
                    colors = fieldColors,
                )
                OutlinedButton(
                    onClick = onImportClick,
                    shape = fieldShape,
                    border = BorderStroke(1.dp, WarpAccent.copy(alpha = 0.6f)),
                    modifier = Modifier.height(52.dp).padding(top = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Folder,
                            contentDescription = null,
                            tint = WarpAccent,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("Import", color = WarpAccent, fontSize = 13.sp)
                    }
                }
            }

            // Status & Error Banners
            importError?.let { err ->
                Text(
                    text = err,
                    color = Color(0xFFFF6B6B),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF3D1E1E), shape = RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            statusMessage?.let { msg ->
                Text(
                    text = msg,
                    color = WarpAccent,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E332A), shape = RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}
