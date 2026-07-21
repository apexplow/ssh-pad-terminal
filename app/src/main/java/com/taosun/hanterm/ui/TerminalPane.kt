package com.taosun.hanterm.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.taosun.hanterm.ssh.SessionCloseReason
import com.taosun.hanterm.ssh.SshSession
import com.taosun.hanterm.terminal.FontSizeController
import com.taosun.hanterm.terminal.PtyBridge
import com.taosun.hanterm.terminal.TerminalEndpoint
import com.taosun.hanterm.terminal.TerminalView
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
 * Wraps the platform [TerminalView] and runs the IO loop while a session
 * is active.
 *
 * Two paths, decided by [bridge]:
 *
 * - **Bridge path (preferred, `bridge != null`)** — the read loop drains
 *   bytes from [PtyBridge.view].read (which carries remote output that
 *   the [com.taosun.hanterm.ssh.SshBridgeAdapter] has pushed), and
 *   resizes go through [PtyBridge.resize] (which the adapter's listener
 *   forwards to the underlying session). This is the production path.
 *
 * - **Legacy path (`bridge == null`)** — falls back to calling
 *   [SshSession.readInto] directly. Retained so unit tests that don't
 *   care about the bridge can pass `bridge = null` and exercise the
 *   older wiring.
 *
 * Data flow (bridge path):
 * ```
 *   SshBridgeAdapter.inbound: session.readInto { bytes ─► bridge.transport.write }
 *                                                                  │
 *   TerminalPane:                                                ▼
 *        bridge.view.read() ──► ZmodemFilter.onInbound ──► emulator.append
 *                                  │                         │
 *                                  ├─ reply → endpoint.write │
 *                                  └─ Done/Failed → Snackbar └─ refreshSignal
 * ```
 *
 * Lifecycle: when [bridge] (or [sshSession]) flips back to null, the
 * [LaunchedEffect] cancels its coroutines, the bridge view's
 * [PtyBridge.view].read returns null, the loop breaks, and the
 * `finally` clause runs the disconnect bookkeeping (resize detached,
 * ZMODEM abort, refresh channel closed, [onSessionClosed] called if
 * appropriate).
 */
@Composable
fun TerminalPane(
    endpoint: TerminalEndpoint,
    bridge: PtyBridge?,
    sshSession: SshSession?,
    onComposingHint: (String?) -> Unit,
    onPtyResize: (SshSession, Int, Int, Int, Int) -> Unit,
    onSessionClosed: (reason: String, closeReason: SessionCloseReason) -> Unit = { _, _ -> },
    /**
     * Receives the latest emulator reference whenever the view is created
     * or replaced (Activity recreation, font-size swap, etc.). The callback
     * is invoked with `null` after [bridge]/[sshSession] flip back to null
     * so consumers like [TmuxSessionSource] can short-circuit [refresh]
     * instead of waiting for the polling loop to time out.
     *
     * Called on Dispatchers.Main.
     */
    onEmulatorChanged: (TerminalEmulator?) -> Unit = {},
    fontSize: Int,
    modifier: Modifier = Modifier,
) {
    // A simple holder so we can stash the View reference from AndroidView's
    // factory without dragging in MutableState (which would trigger extra
    // recompositions). The LaunchedEffect below polls this holder once per
    // session change.
    val viewHolder = remember { ViewHolder() }

    // Tracks the endpoint last bound onto the underlying TerminalView, so the
    // AndroidView `update` block can skip the rebind (and its side effect of
    // nulling `inputConnection`) when the endpoint hasn't actually changed.
    val lastBoundEndpoint = remember { Ref<TerminalEndpoint?>() }

    val context = LocalContext.current
    // One filter per live session: MediaStore sink + ZMODEM state machine.
    // Keyed on the bridge/session so reconnect gets a fresh receiver.
    val zmodem = remember(bridge ?: sshSession) {
        ZmodemFilter(MediaStoreDownloadSink(context.applicationContext))
    }

    // The LaunchedEffect keys on the bridge-or-session pair: when the
    // user reconnects, the bridge reference changes, the previous
    // effect's coroutine is cancelled, and a fresh effect starts.
    // The View stays the same across reconnects (it's set inside the
    // AndroidView factory, which is keyed on the Composable's identity
    // in the tree), so `viewHolder.view` is non-null for the full
    // connected lifetime.
    LaunchedEffect(bridge ?: sshSession, viewHolder.view) {
        val activeBridge = bridge
        val activeSession = sshSession
        if (activeBridge == null && activeSession == null) return@LaunchedEffect
        val view = viewHolder.view ?: return@LaunchedEffect
        // We bypass TerminalSession entirely (it would try to fork a local
        // shell). Instead we grab the TerminalEmulator directly, which was
        // assigned to mEmulator in TerminalView's constructor.
        val emulator = withContext(Dispatchers.Main) { view.termuxView.mEmulator }
            ?: return@LaunchedEffect

        // Forward PTY resizes. When a bridge is present, the bridge's
        // resize listener (registered by SshBridgeAdapter) forwards
        // to the underlying session's resizePty — so we just call
        // bridge.resize here. When no bridge is present, fall back
        // to the legacy onPtyResize lambda that takes a session.
        //
        // Registered before the IO loop starts so the very first
        // layout pass (which can race the launch) still reaches the
        // resize listener.
        view.setPtyResizeListener { cols, rows, _, _ ->
            if (activeBridge != null) {
                activeBridge.resize(cols, rows)
            } else if (activeSession != null) {
                onPtyResize(activeSession, cols, rows, 0, 0)
            }
        }

        val refreshSignal = Channel<Unit>(Channel.CONFLATED)

        // UI-side refresh: drains the conflated channel and calls invalidate
        // on the underlying Termux view. Runs on Main.
        launch(Dispatchers.Main) {
            for (signal in refreshSignal) {
                view.termuxView.invalidate()
            }
        }

        // `failureReason` is read by the `finally` block below to thread the
        // real abort message into onSessionClosed; declaring it before the
        // try block keeps the finally block from needing to peek into try
        // scope (and ensures we don't see a stale value if the coroutine is
        // cancelled mid-loop).
        var failureReason: String? = null

        try {
            if (activeBridge != null) {
                // Bridge path: drain the bridge's view side. EOF
                // arrives when the adapter's inbound coroutine has
                // exited (because session.readInto returned, threw,
                // or was cancelled) AND the bridge was closed —
                // either by the inbound's `finally` block (clean
                // EOF or transport error) or by the user clicking
                // Disconnect (which calls bridge.close()).
                while (currentCoroutineContext().isActive) {
                    val bytes = withContext(Dispatchers.IO) {
                        activeBridge.view.read()
                    } ?: break
                    applyInbound(bytes, zmodem, endpoint, emulator, refreshSignal)
                }
            } else if (activeSession != null) {
                // Legacy path — unchanged from Sprint 2. Kept for
                // tests that don't construct a bridge.
                val outcome = activeSession.readInto { bytes ->
                    applyInbound(bytes, zmodem, endpoint, emulator, refreshSignal)
                }
                outcome.exceptionOrNull()?.let {
                    failureReason = it.message ?: it.javaClass.simpleName
                }
            }
        } finally {
            // Abort any in-flight ZMODEM receive so a partial MediaStore
            // entry is deleted and the next session starts clean.
            zmodem.abort()?.let { ev ->
                if (ev is TransferEvent.Failed) {
                    FontSizeController.showMessage(ev.reason)
                }
            }
            // Detach the resize listener so a subsequent reconnect gets a fresh
            // registration; otherwise we'd be holding a stale bridge/session
            // reference.
            view.setPtyResizeListener(null)
            refreshSignal.close()

            // If the coroutine is still active when the read loop finished,
            // it means the remote server disconnected or closed, rather than
            // the user clicking Disconnect (which would cancel this
            // coroutine). In the bridge path, the bridge's close
            // (whether triggered by the inbound coroutine or by the UI)
            // caused view.read to return null; either way the session is
            // no longer usable.
            //
            // SCR-TP-01..02: check session.lastCloseReason. The Disconnect
            // button sets lastCloseReason = UserInitiated synchronously
            // before the socket teardown, so by the time we reach this
            // finally block — even if the async socket close raced the
            // coroutine cancellation and readInto observed a
            // SocketException — the field tells us "user asked first" and
            // we must skip the "Connection Closed" overlay.
            val session = activeSession
            if (session != null &&
                isActive &&
                session.lastCloseReason !is SessionCloseReason.UserInitiated
            ) {
                onSessionClosed(
                    failureReason ?: "Connection closed by remote",
                    session.lastCloseReason,
                )
            }
            onEmulatorChanged(null)
        }
    }

    // Publish the emulator reference every time it's available (first frame
    // after view construction) and on every recomposition where the view
    // is replaced (e.g. Activity recreation). Cheap: it's a function call.
    val publishedEmulator = viewHolder.view?.currentEmulator()
    LaunchedEffect(publishedEmulator) {
        onEmulatorChanged(publishedEmulator)
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                TerminalView(context).also { terminal ->
                    terminal.bindEndpoint(endpoint)
                    lastBoundEndpoint.value = endpoint
                    terminal.setComposingHintListener(onComposingHint)
                    // Apply the persisted font size on first construction so the
                    // user never sees the default 14 then a jump to their saved
                    // value. TerminalView's constructor already calls setTextSize(14)
                    // to initialise the renderer; this overrides it before the first
                    // frame.
                    terminal.setTextSize(fontSize)
                    viewHolder.view = terminal
                }
            },
            update = { terminal ->
                // bindEndpoint() has a side effect of nulling inputConnection;
                // calling it on every recomposition would detach the IME's
                // active InputConnection on every volume-button press. Skip the
                // rebind when the endpoint reference hasn't changed.
                if (lastBoundEndpoint.value !== endpoint) {
                    terminal.bindEndpoint(endpoint)
                    lastBoundEndpoint.value = endpoint
                }
                terminal.setComposingHintListener(onComposingHint)
                // TerminalView.setTextSize is idempotent — repeated calls with
                // the same value are a no-op, so we don't need an extra guard
                // here. The PTY resize fires only when the underlying font
                // metrics actually change, which is the only behaviour that
                // would queue a SIGWINCH on the SSH write executor.
                terminal.setTextSize(fontSize)
            },
        )

        // Scrollback banner: collects the view's StateFlow and floats
        // above the terminal surface. The LaunchedEffect owns the
        // collection coroutine; when the view is replaced (e.g. rotation)
        // the old coroutine is cancelled automatically. Banner click
        // jumps back to the live view via TerminalView.scrollToBottom().
        val terminal = viewHolder.view
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

private class ViewHolder {
    var view: TerminalView? = null
}

/**
 * Route one inbound PTY chunk through [ZmodemFilter]: replies go to SSH,
 * display bytes go to the emulator, transfer events become Snackbars.
 */
private fun applyInbound(
    bytes: ByteArray,
    zmodem: ZmodemFilter,
    endpoint: TerminalEndpoint,
    emulator: TerminalEmulator,
    refreshSignal: Channel<Unit>,
) {
    val result = zmodem.onInbound(bytes)
    result.reply?.let { endpoint.write(it) }
    if (result.display.isNotEmpty()) {
        emulator.append(result.display, result.display.size)
        refreshSignal.trySend(Unit)
    }
    when (val event = result.event) {
        is TransferEvent.Done ->
            FontSizeController.showMessage("Saved: ${event.fileName}")
        is TransferEvent.Failed ->
            FontSizeController.showMessage("Transfer failed: ${event.reason}")
        null -> Unit
    }
}

/**
 * Mutable single-cell reference, like a one-element [androidx.compose.runtime.MutableState]
 * without the snapshot/observation plumbing. We need it only to compare the
 * previous endpoint against the current one in the AndroidView `update`
 * block; reading it does NOT need to invalidate any composable.
 */
private class Ref<T>(var value: T? = null)
