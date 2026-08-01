package com.apexplow.hanterm.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apexplow.hanterm.data.prefs.AppPreferences
import com.apexplow.hanterm.logging.AppLog
import com.apexplow.hanterm.net.NetworkAvailability
import com.apexplow.hanterm.ssh.ConnectionState
import com.apexplow.hanterm.ssh.SshConnector
import com.apexplow.hanterm.theme.HanTermTheme
import com.apexplow.hanterm.theme.WarpBackground
import com.apexplow.hanterm.ui.components.TerminalScreen
import com.apexplow.hanterm.ui.config.ConfigScreenLayout
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
 *    password via [com.apexplow.hanterm.data.crypto.KeyStoreManager]), call
 *    [SshClient.connect], and on success rebind the endpoint to the resulting
 *    [SshSession]. Errors are surfaced through [ConnectionState.Error] and the
 *    endpoint falls back to
 *    [com.apexplow.hanterm.terminal.MockEchoSession] so the user can keep typing
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
 *
 * Issue #41: the ViewModel is now a real `androidx.lifecycle.ViewModel`
 * obtained via `viewModel(factory = ...)`, so its lifetime is anchored to
 * the `ViewModelStore` (not Compose `remember`). The factory accepts only
 * `Application` context — no Activity leak.
 *
 * Issue #65: this file used to be a 785-LOC god-file with 6 composables.
 * The 5 private composables (form-shell chrome + terminal wrapper +
 * config-screen layout) have been moved to `ui/components/` and
 * `ui/config/`. This file is now the shell only — theme, VM wiring,
 * permission launchers, BackHandler, Scaffold, and the host-key dialog.
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
        // Sprint 4 Link-Open wiring: the `LinkDialogState` is the bridge
        // between [TerminalView.setLinkTapListener] (pushed from the
        // single-tap gesture) and the `LinkDialog` ModalBottomSheet
        // (mounted at the bottom of this composable). Stays remembered
        // across recompositions so taps survived a config-screen
        // → terminal-screen cross-fade don't lose a pending URL.
        // 2026-08-01: long-press → single-tap UX, no ActionMode-deny
        // latch to drop on dismiss.
        val linkDialogState = remember { LinkDialogState() }
        // Holds the latest TerminalView so the [setLinkTapListener]
        // block below always installs the callback on the same view
        // the user is touching (no ActionMode-deny latch to manage —
        // 2026-08-01 single-tap redesign).
        var linkTerminalView by remember {
            mutableStateOf<com.apexplow.hanterm.terminal.TerminalView?>(null)
        }
        val app = context.applicationContext as? com.apexplow.hanterm.HanTermApplication
        // ConnectionProfile + ConnectionRuntime are process-scoped on
        // HanTermApplication. Tests inject [connector] and get an ephemeral
        // runtime that is not stashed on Application.
        val connectionProfile = remember(prefs) {
            app?.connectionProfile(prefs)
                ?: com.apexplow.hanterm.data.profile.ConnectionProfiles.create(
                    context = context.applicationContext,
                    prefs = prefs,
                )
        }
        val runtime = remember {
            if (connector != null) {
                com.apexplow.hanterm.ssh.ConnectionRuntime(
                    context = context.applicationContext,
                    connector = connector,
                    ioDispatcher = ioDispatcher,
                )
            } else {
                checkNotNull(app) {
                    "HanTermApp requires HanTermApplication when no test connector is supplied"
                }.connectionRuntime(hostKeyPrompt, ioDispatcher)
            }
        }
        val scope = rememberCoroutineScope()

        // Issue #41: ViewModel is lifecycle-scoped, not composition-scoped.
        // Build the factory in a `remember` block keyed on every
        // collaborator identity, so the same factory instance is
        // presented to `viewModel(...)` across recompositions. An
        // unstable factory would defeat the `ViewModelStore` cache and
        // could trigger a recomposition loop if the factory's hash
        // participates in any equality check downstream.
        val application = context.applicationContext as Application
        val viewModelFactory = remember(
            application,
            prefs,
            connectionProfile,
            runtime,
        ) {
            hanTermAppViewModelFactory(
                application = application,
                prefs = prefs,
                profile = connectionProfile,
                runtime = runtime,
                isNetworkAvailable = { isNetworkAvailable(context) },
            )
        }
        val viewModel: HanTermAppViewModel = viewModel(factory = viewModelFactory)

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
        // response to volume up/down. Issue #41: the authoritative state lives
        // in [HanTermAppViewModel.fontSize]; MainActivity publishes via the
        // [com.apexplow.hanterm.terminal.FontSizeController] bridge. The VM
        // initial value is read from `AppPreferences.fontSize` (already
        // clamped) so the first frame already shows the user's last choice.
        val fontSize by viewModel.fontSize

        // Drain transient status messages (font-size confirmation from
        // MainActivity, trzsz / zmodem transfer status from TerminalPane).
        // The SnackbarHostState is mounted once in the Scaffold below.
        // SharedFlow with extraBufferCapacity=1 + DROP_OLDEST on the
        // producer side means a held volume key never queues more than
        // one in-flight message.
        LaunchedEffect(Unit) {
            UiMessageBridge.messageEvents.collect { message ->
                viewModel.snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            }
        }

        // Request POST_NOTIFICATIONS the first time we reach the Connected
        // state. minSdk is 36 (Issue #19), so the permission is always
        // runtime-gated — no TIRAMISU version check. The one-shot guard
        // prevents a reconnect cycle from re-prompting; the user can change
        // their mind from system Settings.
        // BG-KA-06: without this exemption, OEM battery savers freeze the
        // FGS nudge thread for ~40 s and Tailscale RSTs the SSH socket.
        LaunchedEffect(viewModel.connectionState.value) {
            if (viewModel.connectionState.value !is ConnectionState.Connected) return@LaunchedEffect
            if (!viewModel.hasRequestedNotificationPermission) {
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

        BackHandler(enabled = viewModel.showTerminal.value) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000) {
                viewModel.disconnect()
                viewModel.setShowTerminal(false)
            } else {
                lastBackPressTime = currentTime
                // Single press: send ESC to terminal and show warning
                viewModel.connectionView.value.write(byteArrayOf(0x1B)) // 0x1B is ESC
                scope.launch {
                    viewModel.snackbarHostState.showSnackbar(
                        message = "当前会话正在运行。再次返回以断开连接",
                        actionLabel = "断开",
                        duration = SnackbarDuration.Short
                    ).let { result ->
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.disconnect()
                            viewModel.setShowTerminal(false)
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
                if (viewModel.showTerminal.value) {
                    TerminalScreen(
                        viewModel = viewModel,
                        onTerminalViewChanged = { view ->
                            // Sprint 4 Link-Open (Step 11): wire the
                            // long-press → URL callback. The `LinkGesture`
                            // inside `TerminalView` reads the latest
                            // listener via a backing field, so calling
                            // this once per view mount is sufficient —
                            // a recomposition that re-fires this closure
                            // just re-installs the same lambda.
                            linkTerminalView = view
                            view?.setLinkTapListener { url ->
                                linkDialogState.show(url)
                            }
                        },
                        fontSize = fontSize,
                    )
                } else {
                    ConfigScreenLayout(
                        viewModel = viewModel,
                        profile = connectionProfile,
                        fontSize = fontSize,
                        autoShowTerminalOnConnect = autoShowTerminalOnConnect,
                    )
                }
            }
        }

        hostKeyPrompt.Dialog()
        // Sprint 4 Link-Open: mount the URL `LinkDialog` so a long-press
        // on a URL cell from `TerminalView` → `LinkGesture` shows the
        // Open / Copy / Share sheet. Renders nothing when `pendingUrl`
        // is null (see `LinkDialog`'s `url = current ?: return` guard).
        LinkDialog(state = linkDialogState)
    }
}
