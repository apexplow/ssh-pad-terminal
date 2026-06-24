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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

import com.example.sshterminal.data.crypto.KeyStoreManager
import com.example.sshterminal.data.prefs.AppPreferences
import com.example.sshterminal.logging.AppLog
import com.example.sshterminal.net.NetworkAvailability
import com.example.sshterminal.ssh.SshClient
import com.example.sshterminal.ssh.SshSession
import com.example.sshterminal.ssh.auth.Auth
import com.example.sshterminal.terminal.FontSizeController
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
        var showTerminal by remember { mutableStateOf(false) }
        val snackbarHostState = remember { SnackbarHostState() }
        var lastBackPressTime by remember { mutableStateOf(0L) }
        // Toggle for the in-app log viewer shown in the error overlay.
        // Lives at the top level so the value persists across recompositions
        // even when the user closes and reopens the overlay.
        var showLogs by remember { mutableStateOf(false) }
        // Tick counter: bumped on Reconnect to force the log Text to re-read
        // the file. Read-tail is intentionally not reactive (AppLog is a
        // singleton holding a file handle, not a Compose State).
        var logRefreshTick by remember { mutableStateOf(0) }
        var connectionDraft by remember { mutableStateOf<ConnectionDraft?>(null) }

        fun handleConnectOutcome(outcome: Result<SshSession>, onSuccessExtra: () -> Unit = {}) {
            outcome.fold(
                onSuccess = { session ->
                    activeSession = session
                    endpoint = session
                    connectionState = ConnectionState.Connected(
                        "${prefs.username}@${prefs.host}:${prefs.port}",
                    )
                    onSuccessExtra()
                },
                onFailure = { t ->
                    endpoint = MockEchoSession()
                    activeSession = null
                    connectionState = ConnectionState.Error(
                        t.message ?: t.javaClass.simpleName,
                    )
                    showLogs = true
                    logRefreshTick++
                },
            )
        }

        fun startConnect(onSuccessExtra: () -> Unit = {}) {
            if (connectionState is ConnectionState.Connecting) return
            connectionState = ConnectionState.Connecting
            logRefreshTick++
            scope.launch {
                val outcome = runConnect(context, prefs, sshClient, connectionDraft)
                handleConnectOutcome(outcome, onSuccessExtra)
            }
        }

        // User-controlled font size, mutated by MainActivity.onKeyDown in
        // response to volume up/down. Reading via `by` makes Compose recompose
        // SshTermApp on every change so both TerminalPane call sites (preview
        // and fullscreen) pick up the new value through their AndroidView update
        // blocks. The initial value is seeded in MainActivity.onCreate from
        // AppPreferences.fontSize before this composable ever runs.
        val fontSize by FontSizeController.state

        // Drain transient status messages pushed by MainActivity (e.g. "Font
        // size: 16" on volume-button presses). Mirrors the back-press snackbar
        // below — the SnackbarHostState is mounted once in the Scaffold and
        // reused for every kind of transient confirmation. CONFLATED on the
        // producer side, so a held volume key never queues more than one
        // in-flight snackbar.
        LaunchedEffect(Unit) {
            for (message in FontSizeController.snackbarMessages) {
                snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            }
        }

        BackHandler(enabled = showTerminal) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000) {
                // Double press: disconnect and go back
                activeSession = null
                sshClient.disconnect()
                endpoint = MockEchoSession()
                connectionState = ConnectionState.Disconnected
                showTerminal = false
            } else {
                lastBackPressTime = currentTime
                // Single press: send ESC to terminal and show warning
                endpoint.write(byteArrayOf(0x1B)) // 0x1B is ESC
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "当前会话正在运行。再次返回以断开连接",
                        actionLabel = "断开",
                        duration = SnackbarDuration.Short
                    ).let { result ->
                        if (result == SnackbarResult.ActionPerformed) {
                            activeSession = null
                            sshClient.disconnect()
                            endpoint = MockEchoSession()
                            connectionState = ConnectionState.Disconnected
                            showTerminal = false
                        }
                    }
                }
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            modifier = Modifier.fillMaxSize()
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(WarpBackground)
            ) {
                if (showTerminal) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TerminalPane(
                            endpoint = endpoint,
                            sshSession = activeSession,
                            onComposingHint = { composingHint.value = it },
                            onPtyResize = { session, cols, rows, widthPx, heightPx ->
                                session.resizePty(cols, rows, widthPx, heightPx)
                            },
                            onSessionClosed = { reason ->
                                activeSession = null
                                sshClient.disconnect()
                                endpoint = MockEchoSession()
                                connectionState = ConnectionState.Error(reason)
                            },
                            fontSize = fontSize,
                            modifier = Modifier.fillMaxSize(),
                        )
                        composingHint.value?.let {
                            Text(
                                text = it,
                                color = Color.White,
                                modifier = Modifier
                                    .align(androidx.compose.ui.Alignment.BottomStart)
                                    .padding(12.dp)
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .padding(4.dp)
                            )
                        }

                        // Disconnected / connection error overlay
                        if (activeSession == null && (connectionState is ConnectionState.Error || connectionState is ConnectionState.Disconnected)) {
                            val focusRequester = remember { FocusRequester() }
                            LaunchedEffect(connectionState) {
                                focusRequester.requestFocus()
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .focusRequester(focusRequester)
                                    .focusable()
                                    .onKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter) {
                                            // Trigger Reconnect
                                            startConnect()
                                            true
                                        } else {
                                            false
                                        }
                                    },
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFF21262D).copy(alpha = 0.9f),
                                        contentColor = Color.White
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .padding(24.dp)
                                        .width(360.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Connection Closed",
                                            style = androidx.compose.ui.text.TextStyle(
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFF85149)
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        
                                        val errMsg = when (val state = connectionState) {
                                            is ConnectionState.Error -> state.message
                                            else -> "The connection to the remote host was closed."
                                        }
                                        
                                        Text(
                                            text = errMsg,
                                            color = Color(0xFFC9D1D9),
                                            fontSize = 14.sp,
                                            textAlign = TextAlign.Center
                                        )

                                        Spacer(modifier = Modifier.height(12.dp))
                                        ConnectionLogPanel(
                                            context = context,
                                            logRefreshTick = logRefreshTick,
                                            errorMessage = null,
                                            showLogs = showLogs,
                                            onToggleShowLogs = {
                                                showLogs = !showLogs
                                                if (showLogs) logRefreshTick++
                                            },
                                            maxHeightDp = 200,
                                        )

                                        Spacer(modifier = Modifier.height(24.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    showTerminal = false
                                                    connectionState = ConnectionState.Disconnected
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Back to Config", color = Color.White)
                                            }
                                            Button(
                                                onClick = { startConnect() },
                                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF238636)
                                                ),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Reconnect", color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(WarpBackground),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            val isBusy = connectionState is ConnectionState.Connecting
                            Button(
                                onClick = { startConnect { showTerminal = true } },
                                enabled = !isBusy,
                            ) { Text("Connect") }
                            OutlinedButton(
                                onClick = {
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
                            onDraftChange = { connectionDraft = it },
                        )
                        if (connectionState is ConnectionState.Error) {
                            val errMsg = (connectionState as ConnectionState.Error).message
                            ConnectionLogPanel(
                                context = context,
                                logRefreshTick = logRefreshTick,
                                errorMessage = errMsg,
                                showLogs = showLogs,
                                onToggleShowLogs = {
                                    showLogs = !showLogs
                                    if (showLogs) logRefreshTick++
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                        TerminalPane(
                            endpoint = endpoint,
                            sshSession = activeSession,
                            onComposingHint = { composingHint.value = it },
                            onPtyResize = { session, cols, rows, widthPx, heightPx ->
                                session.resizePty(cols, rows, widthPx, heightPx)
                            },
                            onSessionClosed = { reason ->
                                activeSession = null
                                sshClient.disconnect()
                                endpoint = MockEchoSession()
                                connectionState = ConnectionState.Error(reason)
                            },
                            fontSize = fontSize,
                            modifier = Modifier.weight(1f),
                        )
                        composingHint.value?.let {
                            Text(text = it, color = Color.White, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
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
    draft: ConnectionDraft? = null,
): Result<SshSession> {
    draft?.let { applyDraftForConnect(prefs, it) }
    val authKind = when {
        prefs.privateKeyName.isNotBlank() -> "PublicKeyAuth(${prefs.privateKeyName})"
        prefs.getEncryptedPassword() != null -> "PasswordAuth"
        else -> "none"
    }
    AppLog.i(
        "SshTermApp",
        "connect started host=${prefs.host} port=${prefs.port} user=${prefs.username} auth=$authKind",
    )
    if (!NetworkAvailability.isOnline(context)) {
        val msg = "No network connection. Check Wi‑Fi or mobile data."
        AppLog.e("SshTermApp", "connect aborted: $msg", null)
        return Result.failure(IllegalStateException(msg))
    }
    if (!prefs.hasUsableCredentials()) {
        val msg = "Missing host, username, or password/key. Fill in the form and tap Connect."
        AppLog.e("SshTermApp", "connect aborted: $msg", null)
        return Result.failure(IllegalStateException(msg))
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
