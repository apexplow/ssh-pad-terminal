package com.example.sshterminal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.sshterminal.ssh.SshSession
import com.example.sshterminal.terminal.ScrollbackController
import com.example.sshterminal.terminal.TerminalEndpoint
import com.example.sshterminal.terminal.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive

/**
 * Wraps the platform [TerminalView] and runs the Sprint 2 IO loop while an
 * [SshSession] is active.
 *
 * Data flow while connected (per `implementation_plan.md` §"终端数据流"):
 *
 * ```
 *   SSH channel  ─►  session.readInto { bytes ─► emulator.session.write }
 *                                            │
 *                                            └─► refreshSignal.trySend(Unit)
 *                                                  │
 *                                                  ▼
 *                                     refreshLoop on Main: postInvalidate
 * ```
 *
 * The refresh loop is keyed on the `Channel<Unit>(CONFLATED)` so multiple
 * rapid SSH packets collapse into a single invalidate — we don't need to
 * redraw the screen N times for N packets.
 *
 * Lifecycle: when [sshSession] flips back to null (user clicked Disconnect)
 * the [LaunchedEffect] cancels its coroutines, draining the read loop's
 * `finally` clause which closes the channel. No manual cleanup needed.
 */
@Composable
fun TerminalPane(
    endpoint: TerminalEndpoint,
    sshSession: SshSession?,
    onComposingHint: (String?) -> Unit,
    onPtyResize: (SshSession, Int, Int, Int, Int) -> Unit,
    onSessionClosed: (reason: String) -> Unit = { _ -> },
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
    // The volume-button font-size path recomposes SshTermApp many times per
    // second; without this guard bindEndpoint() ran on every recomposition
    // and detached the InputConnection the IME was actively using.
    val lastBoundEndpoint = remember { Ref<TerminalEndpoint?>() }

    LaunchedEffect(sshSession, viewHolder.view) {
        val session = sshSession ?: return@LaunchedEffect
        val view = viewHolder.view ?: return@LaunchedEffect
        // We bypass TerminalSession entirely (it would try to fork a local
        // shell). Instead we grab the TerminalEmulator directly, which was
        // assigned to mEmulator in TerminalView's constructor.
        val emulator = withContext(Dispatchers.Main) { view.termuxView.mEmulator }
            ?: return@LaunchedEffect

        // Forward PTY resizes from the View into the SSH session. Registered
        // before the IO loop starts so the very first layout pass (which can
        // race the launch) still reaches the remote.
        view.setPtyResizeListener { cols, rows, widthPx, heightPx ->
            onPtyResize(session, cols, rows, widthPx, heightPx)
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
            // IO loop: read bytes from the SSH channel and feed them directly
            // into the emulator via append(). TerminalEmulator.append() is the
            // same entry point TerminalSession uses internally; it processes ANSI
            // escape sequences and updates the transcript buffer.
            //
            // readInto returns Result<Unit>: success on clean EOF, failure
            // on a socket abort (the case this LaunchedEffect can't see on
            // its own — the abort happens inside sshj's internal Reader
            // thread). We stash the failure so the finally block can pass
            // a real reason to onSessionClosed() instead of the old
            // "Connection closed by remote" string.
            val outcome = session.readInto { bytes ->
                emulator.append(bytes, bytes.size)
                refreshSignal.trySend(Unit)
            }
            outcome.exceptionOrNull()?.let { failureReason = it.message ?: it.javaClass.simpleName }
        } finally {
            // Detach the resize listener so a subsequent reconnect gets a fresh
            // registration; otherwise we'd be holding a stale session reference.
            view.setPtyResizeListener(null)
            refreshSignal.close()

            // If the coroutine is still active when the readInto loop finished,
            // it means the remote server disconnected or closed, rather than
            // the user clicking Disconnect (which would cancel this coroutine).
            if (isActive) {
                onSessionClosed(failureReason ?: "Connection closed by remote")
            }
        }
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

        // Scrollback banner: subscribes to the view's StateFlow and
        // floats above the terminal surface. Banner click jumps back
        // to the live view via TerminalView.scrollToBottom().
        val scrollbackState = remember { mutableStateOf(ScrollbackController.ScrollbackState()) }
        val terminal = viewHolder.view
        LaunchedEffect(terminal) {
            terminal?.setScrollbackListener { state -> scrollbackState.value = state }
        }
        if (terminal != null) {
            ScrollbackBanner(
                state = scrollbackState.value,
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
 * Mutable single-cell reference, like a one-element [androidx.compose.runtime.MutableState]
 * without the snapshot/observation plumbing. We need it only to compare the
 * previous endpoint against the current one in the AndroidView `update`
 * block; reading it does NOT need to invalidate any composable.
 */
private class Ref<T>(var value: T? = null)
