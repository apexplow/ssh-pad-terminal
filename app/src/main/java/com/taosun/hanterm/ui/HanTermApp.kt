package com.taosun.hanterm.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import com.taosun.hanterm.theme.WarpAccent
import com.taosun.hanterm.theme.WarpMuted
import com.taosun.hanterm.theme.WarpPanel
import com.taosun.hanterm.theme.WarpSurface
import com.taosun.hanterm.theme.WarpText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taosun.hanterm.data.prefs.AppPreferences
import com.taosun.hanterm.logging.AppLog
import com.taosun.hanterm.net.NetworkAvailability
import com.taosun.hanterm.ssh.SshClient
import com.taosun.hanterm.ssh.SshConnector
import com.taosun.hanterm.ssh.SshSession
import com.taosun.hanterm.terminal.FontSizeController
import com.taosun.hanterm.terminal.PtyBridge
import com.taosun.hanterm.terminal.TerminalEndpoint
import com.taosun.hanterm.terminal.TerminalView
import com.taosun.hanterm.theme.HanTermTheme
import com.taosun.hanterm.theme.WarpBackground
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Top-level shell for the SSH terminal app.
 *
 * Sprint 2 wiring:
 *  - The form fields live in [ConfigScreen] (unchanged from Sprint 1.5).
 *  - This composable owns the [SshClient] lifecycle and the [ConnectionState]
 *    state machine.
 *  - On Connect: resolve credentials from [AppPreferences] (decrypting the
 *    password via [com.taosun.hanterm.data.crypto.KeyStoreManager]), call
 *    [SshClient.connect], and on success rebind the endpoint to the resulting
 *    [SshSession]. Errors are surfaced through [ConnectionState.Error] and the
 *    endpoint falls back to
 *    [com.taosun.hanterm.terminal.MockEchoSession] so the user can keep typing
 *    into something visible.
 *  - On Disconnect: tear down the session, the client, and the IO coroutine;
 *    rebind back to MockEchoSession.
 *
 * IO loop lives inside [TerminalPane] (keyed on the active [SshSession]) —
 * keeping the loop close to the emulator means the byte → emulator handoff
 * is one short call site instead of plumbing across components.
 *
 * Sprint 3 refactor: connection state and lifecycle are now owned by
 * [HanTermAppViewModel]; this Composable is responsible for rendering,
 * permission launchers, and host-key dialog wiring only.
 */
@Composable
fun HanTermApp(
    connector: SshConnector? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    autoShowTerminalOnConnect: Boolean = true,
    isNetworkAvailable: (Context) -> Boolean = { NetworkAvailability.isOnline(it) },
) {
    HanTermTheme {
        val context = LocalContext.current
        val prefs = remember(context) { AppPreferences(context) }
        // Interactive TOFU trust prompt (KHV-UX-02): remembered alongside
        // sshClient so the Yes/No dialog it renders (mounted unconditionally
        // below) is wired to the same instance the SshClient calls into
        // during connect.
        val hostKeyPrompt = remember { ComposeHostKeyPrompt() }
        val sshClient = remember {
            connector ?: SshClient(context = context.applicationContext, hostKeyPrompt = hostKeyPrompt)
        }
        val scope = rememberCoroutineScope()

        // On Activity recreation (config change we don't handle in the
        // manifest, process death + restore, …) the SSH session may still
        // be alive in ActiveSshSessionStore — re-attach to it instead of
        // dropping the user back on the login page. The store outlives the
        // Activity but shares the process lifetime; the keepalive service
        // keeps the process in the "perceptible" priority bucket, so this
        // is reliable in practice.
        val storeInitialSession = remember { com.taosun.hanterm.ssh.ActiveSshSessionStore.get() }
        val initialConnectionState: ConnectionState = if (storeInitialSession != null) {
            // Re-derive the summary from prefs (which we just read from
            // SharedPreferences) so the status line shows the same
            // "user@host:port" as before the recreation. If the user
            // changed prefs in a parallel process (impossible in
            // practice — the prefs screen isn't part of the app), this
            // is at worst a one-line label mismatch.
            ConnectionState.Connected("${prefs.username}@${prefs.host}:${prefs.port}")
        } else {
            ConnectionState.Disconnected
        }

        val connectionState = rememberSaveable(stateSaver = ConnectionStateSaver) {
            mutableStateOf<ConnectionState>(initialConnectionState)
        }
        // rememberSaveable so a process-death + restore still routes the
        // user back to the terminal pane (not the login page) when the
        // store still holds a live session. For config changes, the
        // manifest's `configChanges` already keeps the Activity alive,
        // so this saveable is the second line of defence for the rare
        // process-kill case.
        val showTerminal = rememberSaveable { mutableStateOf(storeInitialSession != null) }

        val viewModel = remember {
            HanTermAppViewModel(
                context = context,
                prefs = prefs,
                connector = sshClient,
                uiScope = scope,
                connectionState = connectionState,
                showTerminal = showTerminal,
                initialSession = storeInitialSession,
                isNetworkAvailable = { isNetworkAvailable(context) },
                ioDispatcher = ioDispatcher,
            )
        }

        DisposableEffect(viewModel) {
            onDispose { viewModel.dispose() }
        }

        var lastBackPressTime by remember { mutableStateOf(0L) }
        // Toggle for the in-app log viewer shown in the error overlay.
        // Lives at the top level so the value persists across recompositions
        // even when the user closes and reopens the overlay.
        // One-shot guard for the POST_NOTIFICATIONS permission request. We
        // ask the user at most once per process — the system dialog is
        // intentionally non-modal so a denied result doesn't trap us in a
        // loop, and the user can re-grant later from system settings if
        // they change their mind. rememberSaveable so a configuration change
        // (rotation, dark-mode toggle) does not re-fire the prompt after
        // the user has already responded.
        // Permission launcher for POST_NOTIFICATIONS (API 33+). The result
        // callback intentionally ignores the granted/denied bit — the
        // service still runs without the permission, the user just doesn't
        // see the persistent notification. Degrade gracefully.
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { /* result ignored — degrade gracefully */ }
        val batteryOptLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            AppLog.i(
                "HanTermApp",
                "battery-opt dialog closed: ignoring=" +
                    pm.isIgnoringBatteryOptimizations(context.packageName),
            )
        }

        // User-controlled font size, mutated by MainActivity.onKeyDown in
        // response to volume up/down. Reading via `by` makes Compose recompose
        // HanTermApp on every change so both TerminalPane call sites (preview
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
                viewModel.snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            }
        }

        // Request POST_NOTIFICATIONS the first time we reach the Connected
        // state, on API 33+ only. Earlier API levels grant it implicitly.
        // The one-shot guard prevents a reconnect cycle from re-prompting;
        // the user can change their mind from system Settings.
        // BG-KA-06: without this exemption, OEM battery savers freeze the
        // FGS nudge thread for ~40 s and Tailscale RSTs the SSH socket.
        LaunchedEffect(viewModel.connectionState.value) {
            if (viewModel.connectionState.value !is ConnectionState.Connected) return@LaunchedEffect
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !viewModel.hasRequestedNotificationPermission
            ) {
                viewModel.markNotificationPermissionRequested()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!viewModel.hasRequestedBatteryOptExemption) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
                AppLog.i("HanTermApp", "battery-opt check: ignoring=$ignoring")
                if (!ignoring) {
                    viewModel.markBatteryOptExemptionRequested()
                    scope.launch {
                        viewModel.snackbarHostState.showSnackbar(
                            message = "请允许「忽略电池优化」，否则切到后台 SSH 会被系统冻结断开",
                            duration = SnackbarDuration.Long,
                        )
                    }
                    runCatching {
                        batteryOptLauncher.launch(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }.onFailure {
                        AppLog.w("HanTermApp", "battery-opt request failed; open settings manually", it)
                        runCatching {
                            batteryOptLauncher.launch(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            )
                        }
                    }
                } else {
                    viewModel.markBatteryOptExemptionRequested()
                }
            }
        }

        BackHandler(enabled = showTerminal.value) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000) {
                viewModel.disconnect()
                showTerminal.value = false
            } else {
                lastBackPressTime = currentTime
                // Single press: send ESC to terminal and show warning
                viewModel.endpoint.value.write(byteArrayOf(0x1B)) // 0x1B is ESC
                scope.launch {
                    viewModel.snackbarHostState.showSnackbar(
                        message = "当前会话正在运行。再次返回以断开连接",
                        actionLabel = "断开",
                        duration = SnackbarDuration.Short
                    ).let { result ->
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.disconnect()
                            showTerminal.value = false
                        }
                    }
                }
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(viewModel.snackbarHostState) },
            modifier = Modifier.fillMaxSize()
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(WarpBackground)
            ) {
                if (showTerminal.value) {
                    TerminalScreen(
                        viewModel = viewModel,
                        onTerminalViewChanged = {},
                        fontSize = fontSize,
                    )
                } else {
                    ConfigScreenLayout(
                        viewModel = viewModel,
                        prefs = prefs,
                        fontSize = fontSize,
                        autoShowTerminalOnConnect = autoShowTerminalOnConnect,
                    )
                }
            }
        }

        hostKeyPrompt.Dialog()
    }
}

@Composable
private fun TerminalScreen(
    viewModel: HanTermAppViewModel,
    onTerminalViewChanged: (TerminalView?) -> Unit,
    fontSize: Int,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        TerminalPane(
            endpoint = viewModel.endpoint.value,
            bridge = viewModel.bridge.value,
            sshSession = viewModel.activeSession.value,
            onComposingHint = { viewModel.onComposingHint(it) },
            onPtyResize = { session, cols, rows, widthPx, heightPx ->
                session.resizePty(cols, rows, widthPx, heightPx)
            },
            onSessionClosed = { reason, closeReason ->
                viewModel.onSessionClosed(reason, closeReason)
            },
            onTerminalViewChanged = onTerminalViewChanged,
            fontSize = fontSize,
            modifier = Modifier.fillMaxSize(),
        )
        viewModel.composingHint.value?.let {
            Text(
                text = it,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(4.dp)
            )
        }

        // Disconnected / connection error overlay
        if (viewModel.activeSession.value == null &&
            (viewModel.connectionState.value is ConnectionState.Error ||
                viewModel.connectionState.value is ConnectionState.Disconnected)
        ) {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(viewModel.connectionState.value) {
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
                            viewModel.startConnect()
                            true
                        } else {
                            false
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF21262D).copy(alpha = 0.9f),
                        contentColor = Color.White
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .padding(24.dp)
                        .width(360.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Connection Closed",
                            style = TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF85149)
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val errMsg = when (val state = viewModel.connectionState.value) {
                            is ConnectionState.Error -> state.message
                            else -> "The connection to the remote host was closed."
                        }
                        val parts = errMsg.split(": ", limit = 2)
                        val category = if (parts.size == 2) parts[0] else null
                        val detail = if (parts.size == 2) parts[1] else errMsg

                        category?.let {
                            Text(
                                text = it,
                                color = Color(0xFFFFC107),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text(
                            text = detail,
                            color = Color(0xFFC9D1D9),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        ConnectionLogPanel(
                            context = LocalContext.current,
                            logRefreshTick = viewModel.logRefreshTick.value,
                            errorMessage = null,
                            showLogs = viewModel.showLogs.value,
                            onToggleShowLogs = { viewModel.toggleLogs() },
                            maxHeightDp = 200,
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    // "Back to Config" doesn't disconnect — the
                                    // session is already gone via onSessionClosed
                                    // (which cleared the store and tore down the
                                    // client). We just collapse the overlay.
                                    viewModel.showTerminal.value = false
                                    viewModel.connectionState.value = ConnectionState.Disconnected
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Back to Config", color = Color.White)
                            }
                            Button(
                                onClick = { viewModel.startConnect() },
                                colors = ButtonDefaults.buttonColors(
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
}

@Composable
private fun HanTermTopHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(WarpAccent.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                    .border(1.dp, WarpAccent.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Terminal,
                    contentDescription = null,
                    tint = WarpAccent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column {
                Text(
                    text = "HanTerm",
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarpText,
                    ),
                )
                Text(
                    text = "Decoupled IME SSH Terminal",
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = WarpMuted,
                    ),
                )
            }
        }

        Box(
            modifier = Modifier
                .background(WarpSurface, RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFF2E353D), RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(WarpAccent, CircleShape),
                )
                Text(
                    text = "当前主机 (Single Host)",
                    style = TextStyle(fontSize = 11.sp, color = WarpMuted, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

@Composable
private fun HanTermConnectionBar(
    viewModel: HanTermAppViewModel,
    onConnect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = WarpSurface),
        border = BorderStroke(1.dp, Color(0xFF2E353D)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isBusy = viewModel.connectionState.value is ConnectionState.Connecting
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onConnect,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WarpAccent,
                        contentColor = Color(0xFF0F1419),
                        disabledContainerColor = WarpAccent.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier
                        .height(40.dp)
                        .semantics(mergeDescendants = true) {},
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("Connect", style = TextStyle(fontWeight = FontWeight.Bold))
                    }
                }

                OutlinedButton(
                    onClick = {
                        viewModel.disconnect()
                        viewModel.connectionState.value = ConnectionState.Disconnected
                    },
                    enabled = viewModel.activeSession.value != null,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF3D2626)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFF7B7B),
                    ),
                    modifier = Modifier
                        .height(40.dp)
                        .semantics(mergeDescendants = true) {},
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Power,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("Disconnect")
                    }
                }
            }

            ConnectionStatusLabel(viewModel.connectionState.value)
        }
    }
}

@Composable
private fun ConfigScreenLayout(
    viewModel: HanTermAppViewModel,
    prefs: AppPreferences,
    fontSize: Int,
    autoShowTerminalOnConnect: Boolean,
) {
    val context = LocalContext.current
    var connectionDraft by remember { mutableStateOf<ConnectionDraft?>(null) }
    val orientation = LocalConfiguration.current.orientation
    val onConnect = {
        viewModel.startConnect(connectionDraft) {
            if (autoShowTerminalOnConnect) {
                viewModel.showTerminal.value = true
            }
        }
    }
    if (shouldUseSplitLayout(orientation, viewModel.showTerminal.value)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(WarpBackground),
        ) {
            // Leading pane: connect/disconnect + config form.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                HanTermTopHeader()
                HanTermConnectionBar(viewModel = viewModel, onConnect = onConnect)
                Spacer(modifier = Modifier.height(8.dp))
                ConfigScreen(
                    prefs = prefs,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    onDraftChange = { connectionDraft = it },
                )
            }
            // Trailing pane: error log (when in Error) + terminal preview.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            ) {
                if (viewModel.connectionState.value is ConnectionState.Error) {
                    val errMsg = (viewModel.connectionState.value as ConnectionState.Error).message
                    ConnectionLogPanel(
                        context = context,
                        logRefreshTick = viewModel.logRefreshTick.value,
                        errorMessage = errMsg,
                        showLogs = viewModel.showLogs.value,
                        onToggleShowLogs = { viewModel.toggleLogs() },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                TerminalPane(
                    endpoint = viewModel.endpoint.value,
                    bridge = viewModel.bridge.value,
                    sshSession = viewModel.activeSession.value,
                    onComposingHint = { viewModel.onComposingHint(it) },
                    onPtyResize = { session, cols, rows, widthPx, heightPx ->
                        session.resizePty(cols, rows, widthPx, heightPx)
                    },
                    onSessionClosed = { reason, closeReason ->
                        viewModel.onSessionClosed(reason, closeReason)
                    },
                    fontSize = fontSize,
                    modifier = Modifier.weight(1f),
                )
                viewModel.composingHint.value?.let {
                    Text(text = it, color = Color.White, modifier = Modifier.padding(12.dp))
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WarpBackground)
                .verticalScroll(rememberScrollState()),
        ) {
            HanTermTopHeader()
            HanTermConnectionBar(viewModel = viewModel, onConnect = onConnect)
            Spacer(modifier = Modifier.height(8.dp))
            ConfigScreen(
                prefs = prefs,
                modifier = Modifier.padding(horizontal = 16.dp),
                onDraftChange = { connectionDraft = it },
            )
            if (viewModel.connectionState.value is ConnectionState.Error) {
                val errMsg = (viewModel.connectionState.value as ConnectionState.Error).message
                ConnectionLogPanel(
                    context = context,
                    logRefreshTick = viewModel.logRefreshTick.value,
                    errorMessage = errMsg,
                    showLogs = viewModel.showLogs.value,
                    onToggleShowLogs = { viewModel.toggleLogs() },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            TerminalPane(
                endpoint = viewModel.endpoint.value,
                bridge = viewModel.bridge.value,
                sshSession = viewModel.activeSession.value,
                onComposingHint = { viewModel.onComposingHint(it) },
                onPtyResize = { session, cols, rows, widthPx, heightPx ->
                    session.resizePty(cols, rows, widthPx, heightPx)
                },
                onSessionClosed = { reason, closeReason ->
                    viewModel.onSessionClosed(reason, closeReason)
                },
                fontSize = fontSize,
                modifier = Modifier.weight(1f),
            )
            viewModel.composingHint.value?.let {
                Text(text = it, color = Color.White, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

/** UI-facing connection state machine. */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val summary: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * `rememberSaveable` saver for [ConnectionState]. Sealed classes are
 * not [android.os.Parcelable] by default, so we encode the four variants
 * as `(discriminator, payload?)` lists that [listSaver] can write into
 * a `Bundle`. The list shape is stable across restarts — adding a new
 * variant means adding a new discriminator tag here AND in [restore].
 *
 * [ConnectionState.Connecting] is intentionally *not* preserved across
 * a process restart: the connect coroutine that owned that state is
 * dead with the old process, and the in-flight socket is gone. Treating
 * it as `Disconnected` on restore is the safest fallback — the user
 * sees the login page and can tap Connect again.
 */
internal val ConnectionStateSaver: Saver<ConnectionState, Any> = listSaver(
    save = { state ->
        when (state) {
            ConnectionState.Disconnected -> listOf("disconnected")
            ConnectionState.Connecting -> listOf("connecting")
            is ConnectionState.Connected -> listOf("connected", state.summary)
            is ConnectionState.Error -> listOf("error", state.message)
        }
    },
    restore = { list ->
        // Defensive: if the Bundle is partially malformed (older
        // build, hand-edited prefs, …) fall back to Disconnected
        // rather than crashing. The user can always tap Connect.
        when (list.firstOrNull()) {
            "disconnected" -> ConnectionState.Disconnected
            "connecting" -> ConnectionState.Disconnected
            "connected" -> ConnectionState.Connected(list.getOrNull(1) ?: "")
            "error" -> ConnectionState.Error(list.getOrNull(1) ?: "Unknown error")
            else -> ConnectionState.Disconnected
        }
    },
)

@Composable
private fun ConnectionStatusLabel(state: ConnectionState) {
    val (text, color, bg) = when (state) {
        ConnectionState.Disconnected -> Triple("Disconnected", Color(0xFF9AA0A6), Color(0xFF20252B))
        ConnectionState.Connecting -> Triple("Connecting…", Color(0xFFFFC107), Color(0xFF332B1A))
        is ConnectionState.Connected -> Triple("Connected to ${state.summary}", Color(0xFF66BB6A), Color(0xFF1E3324))
        is ConnectionState.Error -> Triple("Error: ${state.message}", Color(0xFFEF5350), Color(0xFF331E1E))
    }
    Box(
        modifier = Modifier
            .background(bg, shape = RoundedCornerShape(20.dp))
            .border(1.dp, color.copy(alpha = 0.3f), shape = RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape),
            )
            Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
