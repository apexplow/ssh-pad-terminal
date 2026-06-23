package com.example.sshterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.sshterminal.data.crypto.KeyStoreManager
import com.example.sshterminal.data.prefs.AppPreferences
import com.example.sshterminal.ssh.SshClient
import com.example.sshterminal.ssh.SshSession
import com.example.sshterminal.ssh.auth.Auth
import com.example.sshterminal.terminal.MockEchoSession
import com.example.sshterminal.terminal.TerminalEndpoint
import com.example.sshterminal.theme.SshTermTheme
import com.example.sshterminal.theme.WarpBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Top-level shell for the SSH terminal app.
 *
 * Sprint 2 wiring:
 *  - The form fields live in [ConfigScreen] (unchanged from Sprint 1.5).
 *  - This composable owns the [SshClient] lifecycle and the [ConnectionState]
 *    state machine.
 *  - On Connect: resolve credentials from [AppPreferences] (decrypting the
 *    password via [KeyStoreManager]), call [SshClient.connect], and on
 *    success rebind the endpoint to the resulting [SshSession]. Errors are
 *    surfaced through [ConnectionState.Error] and the endpoint falls back to
 *    [MockEchoSession] so the user can keep typing into something visible.
 *  - On Disconnect: tear down the session, the client, and the IO coroutine;
 *    rebind back to [MockEchoSession].
 *
 * IO loop lives inside [TerminalPane] (keyed on the active [SshSession]) —
 * keeping the loop close to the emulator means the byte → emulator handoff
 * is one short call site instead of plumbing across components.
 */
@Composable
fun SshTermApp() {
    SshTermTheme {
        val context = LocalContext.current
        val prefs = remember(context) { AppPreferences(context) }
        val sshClient = remember { SshClient() }
        val scope = rememberCoroutineScope()

        var connectionState by remember { mutableStateOf<ConnectionState>(ConnectionState.Disconnected) }
        var endpoint by remember { mutableStateOf<TerminalEndpoint>(MockEchoSession()) }
        var activeSession by remember { mutableStateOf<SshSession?>(null) }
        val composingHint = remember { mutableStateOf<String?>(null) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WarpBackground),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                val isBusy = connectionState is ConnectionState.Connecting
                Button(
                    onClick = {
                        if (isBusy) return@Button
                        connectionState = ConnectionState.Connecting
                        scope.launch {
                            val outcome = runConnect(context, prefs, sshClient)
                            outcome.fold(
                                onSuccess = { session ->
                                    activeSession = session
                                    endpoint = session
                                    connectionState = ConnectionState.Connected(
                                        "${prefs.username}@${prefs.host}:${prefs.port}",
                                    )
                                },
                                onFailure = { t ->
                                    endpoint = MockEchoSession()
                                    activeSession = null
                                    connectionState = ConnectionState.Error(
                                        t.message ?: t.javaClass.simpleName,
                                    )
                                },
                            )
                        }
                    },
                    enabled = !isBusy,
                ) { Text("Connect") }
                OutlinedButton(
                    onClick = {
                        // Cancel the IO coroutine FIRST (by nulling the
                        // session) so the readInto() loop drains its `finally`
                        // and closes the transport before we tear the parent
                        // SshClient down. Otherwise the close races the loop's
                        // last read.
                        activeSession = null
                        sshClient.disconnect()
                        endpoint = MockEchoSession()
                        connectionState = ConnectionState.Disconnected
                    },
                    enabled = activeSession != null,
                ) { Text("Disconnect") }
                ConnectionStatusLabel(connectionState)
            }
            ConfigScreen(
                prefs = prefs,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            TerminalPane(
                endpoint = endpoint,
                sshSession = activeSession,
                onComposingHint = { composingHint.value = it },
                onPtyResize = { session, cols, rows, widthPx, heightPx ->
                    session.resizePty(cols, rows, widthPx, heightPx)
                },
                modifier = Modifier.weight(1f),
            )
            composingHint.value?.let {
                Text(text = it, color = Color.White, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

/**
 * Resolves credentials from [prefs] (using [context] for filesystem access)
 * and calls [SshClient.connect]. All throwables are returned through
 * [Result] so the UI can render them in the status label without crashing
 * the activity.
 *
 * Password resolution path:
 *   prefs.getEncryptedPassword() → KeyStoreManager.decrypt(blob) → UTF-8.
 * Any of those steps can fail (user wiped the keystore, etc.); we surface
 * the original throwable so the UI can show "decrypt failed: …" rather than
 * a generic "auth failed".
 */
private suspend fun runConnect(
    context: android.content.Context,
    prefs: AppPreferences,
    client: SshClient,
): Result<SshSession> {
    if (!prefs.hasUsableCredentials()) {
        return Result.failure(IllegalStateException("missing host/username/credential"))
    }
    val auth = resolveAuth(context, prefs)
    return client.connect(prefs.host, prefs.port, prefs.username, auth)
}

private suspend fun resolveAuth(
    context: android.content.Context,
    prefs: AppPreferences,
): Auth = withContext(Dispatchers.IO) {
    if (prefs.privateKeyName.isNotBlank()) {
        // Resolve filesDir/keys/<privateKeyName> into an absolute path. SAF
        // already copied the imported PEM here in Sprint 1.5.
        val keyPath = File(File(context.filesDir, "keys"), prefs.privateKeyName).absolutePath
        require(File(keyPath).isFile) { "private key not found at $keyPath" }
        return@withContext Auth.PublicKeyAuth(keyPath)
    }
    val blob = prefs.getEncryptedPassword()
        ?: error("password slot empty but no private key configured")
    val plain = String(KeyStoreManager.decrypt(blob), Charsets.UTF_8)
    Auth.PasswordAuth(plain)
}

/** UI-facing connection state machine. */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val summary: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

@Composable
private fun ConnectionStatusLabel(state: ConnectionState) {
    val (text, color) = when (state) {
        ConnectionState.Disconnected -> "Disconnected" to Color(0xFF9AA0A6)
        ConnectionState.Connecting -> "Connecting…" to Color(0xFFFFC107)
        is ConnectionState.Connected -> "Connected to ${state.summary}" to Color(0xFF66BB6A)
        is ConnectionState.Error -> "Error: ${state.message}" to Color(0xFFEF5350)
    }
    Text(text, color = color)
}
