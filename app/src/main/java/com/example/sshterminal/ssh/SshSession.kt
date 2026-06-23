package com.example.sshterminal.ssh

import com.example.sshterminal.terminal.TerminalEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Two-way bridge between the local [TerminalEndpoint] (the `TerminalView`
 * input pipeline) and a remote SSH shell.
 *
 * Implements [TerminalEndpoint.write] so the existing IME pipeline can target
 * an [SshSession] exactly the way it targets a [com.example.sshterminal.terminal.MockEchoSession] —
 * no changes needed in [com.example.sshterminal.terminal.TerminalInputConnection]
 * or [com.example.sshterminal.terminal.TerminalView]. The Sprint 1.5 IME chain
 * (Gboard race fix, Ctrl+Space swallow) keeps working.
 *
 * The reverse direction is [readInto], driven from the UI scope. Per
 * `implementation_plan.md` §"终端数据流", the IO loop pulls bytes from the
 * SSH channel and feeds them into the Termux emulator via [sink]; we don't
 * own a coroutine here so cancellation flows from the UI scope down through
 * the dispatcher.
 *
 * Lifecycle:
 *  - [close] is idempotent. Call it from a `finally` block (and from the
 *    UI's Disconnect handler) so the underlying channel and transport are
 *    released whether the IO loop ended cleanly, threw, or was cancelled.
 *
 * Threading:
 *  - [write] is safe to call from any thread. The underlying transport does
 *    its own locking.
 *  - [readInto] MUST run in a coroutine. It hops to [Dispatchers.IO] for the
 *    blocking read and yields back to the caller's context to invoke [sink].
 */
class SshSession internal constructor(
    private val transport: SshTransport,
    private val onClose: () -> Unit = {},
) : TerminalEndpoint {

    override fun write(bytes: ByteArray) {
        transport.write(bytes)
    }

    /**
     * Drains [transport] into [sink] until EOF or coroutine cancellation.
     *
     * Each batch of bytes is delivered to [sink] on the caller's coroutine
     * context (typically the main dispatcher). The blocking read itself
     * happens on [Dispatchers.IO] so the caller's coroutine isn't pinned.
     *
     * [sink] is the seam where the UI hooks the Termux emulator. The
     * canonical caller does:
     *
     * ```
     * session.readInto { bytes ->
     *     emulatorSession.write(bytes, 0, bytes.size)
     *     refreshSignal.trySend(Unit)
     * }
     * ```
     */
    suspend fun readInto(sink: (ByteArray) -> Unit) {
        try {
            while (currentCoroutineContext().isActive) {
                val bytes = withContext(Dispatchers.IO) { transport.readBytes() } ?: break
                sink(bytes)
            }
        } finally {
            close()
        }
    }

    /**
     * Forward a terminal-window resize to the remote. Called from
     * [com.example.sshterminal.terminal.TerminalView]'s layout listener when
     * the visible grid dimensions change.
     *
     * Pixel dimensions default to 0 (i.e. "unspecified"); SSHJ tolerates
     * that and only updates the cols/rows the remote cares about for SIGWINCH.
     */
    fun resizePty(cols: Int, rows: Int, widthPx: Int = 0, heightPx: Int = 0) {
        transport.resizePty(cols, rows, widthPx, heightPx)
    }

    /**
     * Closes the SSH channel and invokes the [onClose] hook that the
     * [SshClient] registered. The hook is responsible for tearing down the
     * parent `SSHClient` so the underlying TCP socket is released.
     */
    fun close() {
        transport.close()
        onClose()
    }
}
