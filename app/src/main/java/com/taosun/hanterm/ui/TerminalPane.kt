package com.taosun.hanterm.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.taosun.hanterm.ssh.ConnectionView
import com.taosun.hanterm.ssh.SessionCloseReason
import com.taosun.hanterm.terminal.InboundTransferRouter
import com.taosun.hanterm.terminal.TerminalView
import com.taosun.hanterm.terminal.trzsz.TrzszFilter
import com.taosun.hanterm.terminal.zmodem.MediaStoreDownloadSink
import com.taosun.hanterm.terminal.zmodem.TransferEvent
import com.taosun.hanterm.terminal.zmodem.ZmodemFilter
import com.termux.terminal.TerminalEmulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wraps the platform [TerminalView] and runs the IO loop while a live
 * [ConnectionView] is present.
 *
 * Data flow:
 * ```
 *   ConnectionView.read() ──► InboundTransferRouter ──► emulator.append
 *                                  │ (trzsz | zmodem)       │
 *                                  ├─ reply → view.write    │
 *                                  └─ Done/Failed → UiMessageBridge (Snackbar) └─ refreshSignal
 * ```
 *
 * Resize goes through [ConnectionView.resize]. Close-reason checks use
 * [ConnectionView.lastCloseReason] — the UI never sees `SshSession` /
 * `PtyBridge`.
 */
@Composable
fun TerminalPane(
    view: ConnectionView,
    onComposingHint: (String?) -> Unit,
    onSessionClosed: (reason: String, closeReason: SessionCloseReason) -> Unit = { _, _ -> },
    onTerminalViewChanged: (TerminalView?) -> Unit = {},
    fontSize: Int,
    modifier: Modifier = Modifier,
) {
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    val lastBoundView = remember { Ref<ConnectionView?>() }

    val context = LocalContext.current
    val transfers = remember(view) {
        InboundTransferRouter(
            trzsz = TrzszFilter(MediaStoreDownloadSink(context.applicationContext)),
            zmodem = ZmodemFilter(MediaStoreDownloadSink(context.applicationContext)),
        )
    }

    LaunchedEffect(view, terminalView) {
        if (!view.isLive) return@LaunchedEffect
        val termView = terminalView ?: return@LaunchedEffect
        val emulator = withContext(Dispatchers.Main) { termView.termuxView.mEmulator }
            ?: return@LaunchedEffect

        termView.setPtyResizeListener { cols, rows, _, _ ->
            view.resize(cols, rows)
        }

        val refreshSignal = Channel<Unit>(Channel.CONFLATED)
        launch(Dispatchers.Main) {
            for (signal in refreshSignal) {
                termView.termuxView.invalidate()
            }
        }

        var failureReason: String? = null
        try {
            while (currentCoroutineContext().isActive) {
                val bytes = withContext(Dispatchers.IO) {
                    view.read()
                } ?: break
                applyInbound(bytes, transfers, view, emulator, refreshSignal)
                // Rising-edge alt-buffer → IME refresh (Gboard stale-IC after TUI).
                termView.onDisplayUpdated()
            }
        } finally {
            for (ev in transfers.abort()) {
                if (ev is TransferEvent.Failed) {
                    UiMessageBridge.showMessage("Transfer failed: ${ev.reason}")
                }
            }
            termView.setPtyResizeListener(null)
            refreshSignal.close()

            // SCR-TP-01..02: skip overlay when user-initiated disconnect stamped
            // UserInitiated before socket teardown.
            if (isActive && view.lastCloseReason !is SessionCloseReason.UserInitiated) {
                onSessionClosed(
                    failureReason ?: "Connection closed by remote",
                    view.lastCloseReason,
                )
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TerminalView(ctx).also { terminal ->
                    terminal.bindEndpoint(view)
                    lastBoundView.value = view
                    terminal.setComposingHintListener(onComposingHint)
                    terminal.setTextSize(fontSize)
                }
            },
            onRelease = { released ->
                if (terminalView === released) {
                    terminalView = null
                }
                onTerminalViewChanged(null)
            },
            update = { terminal ->
                if (terminalView !== terminal) {
                    terminalView = terminal
                    onTerminalViewChanged(terminal)
                }
                if (lastBoundView.value !== view) {
                    terminal.bindEndpoint(view)
                    lastBoundView.value = view
                }
                terminal.setComposingHintListener(onComposingHint)
                terminal.setTextSize(fontSize)
            },
        )

        val terminal = terminalView
        if (terminal != null) {
            val state by terminal.scrollbackState.collectAsState()
            ScrollbackBanner(
                state = state,
                onBackToBottom = { terminal.scrollToBottom() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
            )
        }
    }
}

/**
 * Route one inbound PTY chunk through [InboundTransferRouter]: replies go
 * to SSH, display bytes go to the emulator, transfer events become Snackbars.
 */
private fun applyInbound(
    bytes: ByteArray,
    transfers: InboundTransferRouter,
    endpoint: ConnectionView,
    emulator: TerminalEmulator,
    refreshSignal: Channel<Unit>,
) {
    val result = transfers.onInbound(bytes)
    result.reply?.let { endpoint.write(it) }
    if (result.display.isNotEmpty()) {
        emulator.append(result.display, result.display.size)
        refreshSignal.trySend(Unit)
    }
    when (val event = result.event) {
        is TransferEvent.Done ->
            UiMessageBridge.showMessage("Saved to Downloads: ${event.fileName}")
        is TransferEvent.Failed ->
            UiMessageBridge.showMessage("Transfer failed: ${event.reason}")
        null -> Unit
    }
}

/**
 * Mutable single-cell reference without snapshot observation. Used to skip
 * redundant [TerminalView.bindEndpoint] calls across recompositions.
 */
private class Ref<T>(var value: T? = null)
