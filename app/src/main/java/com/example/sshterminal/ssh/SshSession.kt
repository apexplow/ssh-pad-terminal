package com.example.sshterminal.ssh

import com.example.sshterminal.terminal.TerminalEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.common.SSHException
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
 *  - [write] and [resizePty] are non-blocking on the caller's thread. Work is
 *    queued on a single-thread executor so keystrokes stay in order while the
 *    main thread never touches the socket (StrictMode / ANR safety).
 *  - [readInto] MUST run in a coroutine. It hops to [Dispatchers.IO] for the
 *    blocking read and yields back to the caller's context to invoke [sink].
 */
class SshSession internal constructor(
    private val transport: SshTransport,
    private val onClose: () -> Unit = {},
) : TerminalEndpoint {

    /**
     * Tracks whether [close] has already run. SSHJ's underlying channel close
     * is idempotent at the wire level (a second close is a no-op on most SSH
     * servers), but our [onClose] hook tears down the parent [SshClient] —
     * firing it twice would null out the client while a coroutine might still
     * be reading from it.
     */
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Serialises outbound channel I/O (writes + SIGWINCH) off the main thread. */
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SshSession-write").apply { isDaemon = true }
    }

    override fun write(bytes: ByteArray) {
        // Empty write is a no-op. Spec doesn't require this, but it's the
        // polite thing to do — SSHJ's underlying OutputStream would write a
        // zero-length chunk which can confuse some servers' framing, and the
        // TerminalEndpoint contract doesn't promise that empty writes will
        // reach the wire.
        if (bytes.isEmpty()) return
        if (closed.get()) return
        val payload = bytes.copyOf()
        writeExecutor.execute {
            if (closed.get()) return@execute
            transport.write(payload)
        }
    }

    /**
     * Drains [transport] into [sink] until EOF or coroutine cancellation.
     *
     * Each batch of bytes is delivered to [sink] on the caller's coroutine
     * context (typically the main dispatcher). The blocking read itself
     * happens on [Dispatchers.IO] so the caller's coroutine isn't pinned.
     *
     * Returns [Result.failure] if the read fails because the underlying
     * connection died ([SocketException] from an aborted TCP socket,
     * [SocketTimeoutException] from SO_TIMEOUT firing on a quiet socket, or
     * [SSHException] from sshj's transport layer surfacing the same event).
     * The transport is closed on every natural end of the loop (EOF,
     * transport error, sink exception) — the caller doesn't need to
     * call [close] itself.
     *
     * Cancellation is the *one* path that does NOT close the session.
     * The session is a longer-lived resource than any one read loop:
     * the same [SshSession] may be driven by a sequence of UI
     * lifecycles (an Activity recreation can re-attach to the existing
     * session via [ActiveSshSessionStore]). A cancellation is a "stop
     * this reader" signal, not a "kill the session" signal. The
     * caller owns session lifetime — when the user actually wants to
     * disconnect, they go through [SshClient.disconnect] (which calls
     * [close] via the `onClose` hook). Cancellation propagates as
     * [CancellationException] and is NOT wrapped in [Result] so
     * structured concurrency continues to work normally.
     *
     * The returned [Throwable] is always wrapped in [SshException] with a
     * [SshErrorMessages]-translated message, so the UI's
     * `t.message ?: t.javaClass.simpleName` reads the same one-line
     * English hint for both the connect path and the read-loop path. The
     * original throwable is preserved as the `cause` so `Log.e(TAG, ...,
     * cause)` still prints the full stack for engineers reading logs.
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
    suspend fun readInto(sink: (ByteArray) -> Unit): Result<Unit> {
        // Cancellation is a UI signal, not a stream end. The finally
        // block consults this flag to decide whether to close the
        // transport — see the kdoc for the full reasoning.
        var cancelled = false
        return try {
            while (currentCoroutineContext().isActive) {
                val bytes = withContext(Dispatchers.IO) { transport.readBytes() } ?: break
                sink(bytes)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            // Don't wrap cancellation in Result — let structured
            // concurrency unwind normally. Mark before rethrowing so
            // the finally block knows not to close the transport.
            cancelled = true
            throw e
        } catch (e: SocketException) {
            // OS-level abort (TCP RST, broken pipe) on the underlying socket.
            Result.failure(SshException(SshErrorMessages.friendly(e), e))
        } catch (e: java.net.SocketTimeoutException) {
            // SO_TIMEOUT fired during the post-connect read loop. This is not
            // a SocketException (SocketTimeoutException extends
            // InterruptedIOException, not SocketException), so it would
            // otherwise escape and crash the coroutine instead of becoming
            // a clean connection-lost result.
            Result.failure(SshException(SshErrorMessages.friendly(e), e))
        } catch (e: SSHException) {
            // sshj transport-layer wrapper around the same socket event, or
            // any other protocol-level error. Either way, the connection is
            // unusable — surface it as a failure so the UI can show a
            // meaningful reason rather than the old hard-coded
            // "Connection closed by remote" string.
            Result.failure(SshException(SshErrorMessages.friendly(e), e))
        } finally {
            // Close on every natural end (EOF, transport error, sink
            // exception). Skip on cancellation so the next reader can
            // re-attach to the same live session — see ActiveSshSessionStore.
            if (!cancelled) {
                close()
            }
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
        if (closed.get()) return
        writeExecutor.execute {
            if (closed.get()) return@execute
            transport.resizePty(cols, rows, widthPx, heightPx)
        }
    }

    /**
     * Closes the SSH channel and invokes the [onClose] hook that the
     * [SshClient] registered. The hook is responsible for tearing down the
     * parent `SSHClient` so the underlying TCP socket is released.
     */
    fun close() {
        // AtomicBoolean.flipToTrue makes this idempotent: only the first close
        // call reaches the transport and the onClose hook. Subsequent calls
        // are silent no-ops — important because callers wrap close() in
        // `finally` blocks plus the Disconnect button.
        if (!closed.compareAndSet(false, true)) return
        writeExecutor.execute {
            transport.close()
            onClose()
        }
        writeExecutor.shutdown()
    }

    /**
     * Blocks until all writes/resizes queued before this call have finished.
     * Used by unit tests; production callers must not rely on this.
     */
    internal fun awaitWriteQueueDrained(timeoutMs: Long = 5000) {
        if (writeExecutor.isShutdown) {
            check(writeExecutor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                "Timed out waiting for SSH write executor to terminate"
            }
            return
        }
        val done = CountDownLatch(1)
        writeExecutor.execute { done.countDown() }
        check(done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            "Timed out waiting for SSH write queue to drain"
        }
    }
}
