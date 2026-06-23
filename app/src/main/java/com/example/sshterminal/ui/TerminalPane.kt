package com.example.sshterminal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.sshterminal.ssh.SshSession
import com.example.sshterminal.terminal.TerminalEndpoint
import com.example.sshterminal.terminal.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    modifier: Modifier = Modifier,
) {
    // A simple holder so we can stash the View reference from AndroidView's
    // factory without dragging in MutableState (which would trigger extra
    // recompositions). The LaunchedEffect below polls this holder once per
    // session change.
    val viewHolder = remember { ViewHolder() }
    val scope = rememberCoroutineScope()

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

        // IO loop: read bytes from the SSH channel and feed them directly
        // into the emulator via append(). TerminalEmulator.append() is the
        // same entry point TerminalSession uses internally; it processes ANSI
        // escape sequences and updates the transcript buffer.
        session.readInto { bytes ->
            emulator.append(bytes, bytes.size)
            refreshSignal.trySend(Unit)
        }

        // Detach the resize listener so a subsequent reconnect gets a fresh
        // registration; otherwise we'd be holding a stale session reference.
        view.setPtyResizeListener(null)
        refreshSignal.close()
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TerminalView(context).also { terminal ->
                terminal.bindEndpoint(endpoint)
                terminal.setComposingHintListener(onComposingHint)
                viewHolder.view = terminal
            }
        },
        update = { terminal ->
            terminal.bindEndpoint(endpoint)
            terminal.setComposingHintListener(onComposingHint)
        },
    )
}

private class ViewHolder {
    var view: TerminalView? = null
}
