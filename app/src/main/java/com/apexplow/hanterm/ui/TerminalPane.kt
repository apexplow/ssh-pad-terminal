package com.apexplow.hanterm.ui

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
import com.apexplow.hanterm.ssh.ConnectionView
import com.apexplow.hanterm.ssh.SessionCloseReason
import com.apexplow.hanterm.terminal.InboundTransferRouter
import com.apexplow.hanterm.terminal.TerminalView
import com.apexplow.hanterm.terminal.trzsz.TrzszFilter
import com.apexplow.hanterm.terminal.zmodem.MediaStoreDownloadSink
import com.apexplow.hanterm.terminal.zmodem.TransferEvent
import com.apexplow.hanterm.terminal.zmodem.ZmodemFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wraps the platform [TerminalView] and runs the IO loop while a live
 * [ConnectionView] is present.
 *
 * Data flow:
 * ```
 *   ConnectionView.read() ──► InboundTransferRouter ──► emulator.append  (Dispatchers.IO)
 *                                  │ (trzsz | zmodem)       │
 *                                  ├─ reply → view.write    │
 *                                  └─ Done/Failed → UiMessageBridge
 *                                                       ├─ refreshSignal → Main postInvalidateOnAnimation
 *                                                       └─ per-chunk Main onDisplayUpdated (TV-IME-04)
 * ```
 *
 * ## Why append runs off Main
 *
 * Printable physical keys intentionally fall through to the IME
 * (`KeyMapper` → `Ignore` → `onKeyDown` returns false). IME / KeyEvent
 * delivery shares the Main thread with Compose. Under a TUI redraw storm
 * (cursor-agent, vim, htop) a Main-thread `emulator.append` blocked that
 * queue for hundreds of ms — typed keys piled up, then flushed as one
 * burst when the flood eased. Termux itself appends from the session
 * reader thread and only posts invalidate to the UI thread; we mirror
 * that split so Main stays free to drain KeyEvents during redraws.
 *
 * Paint stays on a CONFLATED [Channel] (`implementation_plan` rendering
 * constraint). [TerminalView.onDisplayUpdated] is a **per-chunk** Main hop
 * so GEARS TV-IME-04 rising-edge alt-buffer refresh cannot be coalesced
 * away; TV-IME-05 still holds because the View only `restartInput`s on the
 * rising edge, not on every call.
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
                // VSync-aligned redraw (implementation_plan rendering constraint).
                termView.termuxView.postInvalidateOnAnimation()
            }
        }

        var failureReason: String? = null
        try {
            TerminalInboundLoop.run(
                read = { view.read() },
                applyChunk = { bytes ->
                    applyInboundChunk(bytes, transfers, view, emulator)
                },
                onDisplayUpdated = { termView.onDisplayUpdated() },
                refreshSignal = refreshSignal,
            )
        } finally {
            for (ev in transfers.abort()) {
                if (ev is TransferEvent.Failed) {
                    UiMessageBridge.showMessage("Transfer failed: ${ev.reason}")
                }
            }
            withContext(Dispatchers.Main) {
                termView.setPtyResizeListener(null)
            }
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
 * Mutable single-cell reference without snapshot observation. Used to skip
 * redundant [TerminalView.bindEndpoint] calls across recompositions.
 */
private class Ref<T>(var value: T? = null)
