package com.apexplow.hanterm.ssh

import com.apexplow.hanterm.TransportAbortSignal
import com.apexplow.hanterm.logging.AppLog
import com.apexplow.hanterm.terminal.TerminalEndpoint
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
 * an [SshSession] exactly the way it targets a [com.apexplow.hanterm.terminal.MockEchoSession] —
 * no changes needed in [com.apexplow.hanterm.terminal.TerminalInputConnection]
 * or [com.apexplow.hanterm.terminal.TerminalView]. The Sprint 1.5 IME chain
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
    private val onClose: (userInitiated: Boolean) -> Unit = {},
) : TerminalEndpoint {

    /**
     * Tracks whether [close] has already run. SSHJ's underlying channel close
     * is idempotent at the wire level (a second close is a no-op on most SSH
     * servers), but our [onClose] hook tears down the parent [SshClient] —
     * firing it twice would null out the client while a coroutine might still
     * be reading from it.
     */
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Why this session is no longer usable, captured at the moment of close.
     *
     * Sprint 3 / Module 17 / SCR-RS-01..04 + SCR-CL-02: written by
     * [close] (when `userInitiated = true`) and by `readInto`'s exit
     * branches (EOF, SocketException, SocketTimeoutException, SSHException,
     * sink throw). The invariant is **once [SessionCloseReason.UserInitiated]
     * is written, no subsequent exit branch may overwrite the field** — the
     * race fix depends on the UI being able to read this field after the
     * socket teardown and still see the user-initiated signal.
     *
     * Default is [SessionCloseReason.RemoteEof]: the most common reason a
     * session ends, and a safe placeholder if [readInto] somehow observes a
     * state we didn't model. Callers should not rely on the default — it
     * exists so the field is always non-null when read.
     */
    @Volatile
    var lastCloseReason: SessionCloseReason = SessionCloseReason.RemoteEof
        private set

    /**
     * Sets [lastCloseReason] to [reason] only if it is not already
     * [SessionCloseReason.UserInitiated] or [SessionCloseReason.IdleTimeout].
     * This is the single enforcement point for the SCR-CL-02 invariant;
     * every `readInto` exit branch routes through here so a future
     * maintainer can't accidentally regress the race fix by adding a new
     * catch that bypasses the check.
     *
     * IdleTimeout is protected for the same reason as UserInitiated: the
     * watchdog in [com.apexplow.hanterm.ssh.SshBridgeAdapter] sets the
     * reason explicitly via [setCloseReasonFromWatchdog] before calling
     * [close]. If we allowed the subsequent read-loop catch to overwrite
     * it with [SessionCloseReason.TransportError], the UI would mislabel a
     * silent remote as a network error.
     */
    private fun setCloseReasonUnlessUserInitiated(reason: SessionCloseReason) {
        val current = lastCloseReason
        if (current is SessionCloseReason.UserInitiated) return
        if (current is SessionCloseReason.IdleTimeout) return
        lastCloseReason = reason
    }

    /**
     * Watchdog-only setter: writes [SessionCloseReason.IdleTimeout] to
     * [lastCloseReason] unconditionally. Called by
     * [com.apexplow.hanterm.ssh.SshBridgeAdapter] just before it invokes
     * [close], so the UI's "Connection Closed" overlay can attribute the
     * drop to the watchdog rather than a generic transport error.
     *
     * `internal` (not `private`) because the adapter lives in the same
     * module but a different file; `private` would keep it inaccessible.
     */
    internal fun setCloseReasonFromWatchdog() {
        lastCloseReason = SessionCloseReason.IdleTimeout
    }

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
            try {
                transport.write(payload)
            } catch (t: Throwable) {
                // Write failed — typically `SocketException` ("Broken pipe"
                // / "Software caused connection abort") from a dead socket
                // that the read loop hasn't yet noticed. `ExecutorService.
                // execute` does NOT propagate the throwable back to the IME
                // chain, so without this catch the user's keystroke
                // vanishes into stderr and the UI keeps showing a live
                // session that no longer accepts input. Force-close so the
                // existing disconnect UX (SshBridgeAdapter → TerminalPane
                // → "Connection Closed" overlay) takes over instead.
                //
                // Re-entry safety: `close()` is CAS-guarded, so calling it
                // again from the inbound coroutine's `finally` block (when
                // the transport finally gives up too) is a silent no-op.
                // The default `userInitiated = false` keeps the field
                // consistent with the previous behaviour — we never claim
                // the user asked for this.
                AppLog.e(TAG, "writeExecutor task failed; closing session", t)
                close()
            }
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
     * session via the process-scoped [ConnectionRuntime]). A cancellation is a "stop
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
            // Clean EOF: transport.readBytes() returned null without
            // throwing. The remote end closed the connection politely.
            // SCR-RS-02 + SCR-CL-02: skip if the user already asked to
            // disconnect — that signal wins.
            setCloseReasonUnlessUserInitiated(SessionCloseReason.RemoteEof)
            Result.success(Unit)
        } catch (e: CancellationException) {
            // Don't wrap cancellation in Result — let structured
            // concurrency unwind normally. Mark before rethrowing so
            // the finally block knows not to close the transport.
            cancelled = true
            throw e
        } catch (e: SocketException) {
            // OS-level abort (TCP RST, broken pipe) on the underlying socket.
            // BG-DIAG: log the raw throwable so logcat shows the original
            // stack trace — SshErrorMessages.friendly() reduces it to a
            // one-line user-facing string and the cause chain is otherwise
            // invisible. Tag the failure mode so log filters can isolate it.
            // Issue #62: raise the TransportAbortSignal so CrashHandler
            // suppresses the duplicate sshj Reader-thread re-throw that
            // arrives shortly after this catch (avoids the brittle
            // thread-name prefix match).
            TransportAbortSignal.mark()
            AppLog.e(TAG, "readInto: SocketException (transport abort)", e)
            setCloseReasonUnlessUserInitiated(
                SessionCloseReason.TransportError(SshErrorMessages.friendly(e)),
            )
            Result.failure(SshException(SshErrorMessages.friendly(e), e))
        } catch (e: java.net.SocketTimeoutException) {
            // SO_TIMEOUT fired during the post-connect read loop. This is not
            // a SocketException (SocketTimeoutException extends
            // InterruptedIOException, not SocketException), so it would
            // otherwise escape and crash the coroutine instead of becoming
            // a clean connection-lost result.
            // BG-DIAG: distinguish SO_TIMEOUT from a generic socket abort —
            // different root causes (idle socket vs. network drop) need
            // different fixes.
            // Issue #62: also raise the abort signal — same rationale.
            TransportAbortSignal.mark()
            AppLog.e(TAG, "readInto: SocketTimeoutException (SO_TIMEOUT fired)", e)
            setCloseReasonUnlessUserInitiated(
                SessionCloseReason.TransportError(SshErrorMessages.friendly(e)),
            )
            Result.failure(SshException(SshErrorMessages.friendly(e), e))
        } catch (e: SSHException) {
            // sshj transport-layer wrapper around the same socket event, or
            // any other protocol-level error. Either way, the connection is
            // unusable — surface it as a failure so the UI can show a
            // meaningful reason rather than the old hard-coded
            // "Connection closed by remote" string.
            // BG-DIAG: sshj's KeepAliveRunner raises SSHException with
            // CONNECTION_LOST after maxAliveCount unanswered probes — that
            // stack trace is what distinguishes "server killed us" from
            // "transport went silent" from "keepalive thread tripped".
            // Issue #62: also raise the abort signal — same rationale.
            TransportAbortSignal.mark()
            AppLog.e(TAG, "readInto: SSHException (sshj protocol/transport error)", e)
            setCloseReasonUnlessUserInitiated(
                SessionCloseReason.TransportError(SshErrorMessages.friendly(e)),
            )
            Result.failure(SshException(SshErrorMessages.friendly(e), e))
        } catch (e: Throwable) {
            // Sink callback threw (e.g. emulator backing null), OR a
            // transport exception type we didn't enumerate above. Either
            // way the read loop can't continue — wrap as a failure so the
            // caller can distinguish "stream ended" from "stream failed".
            // SCR-RS-04: a sink error is its own category, not a transport
            // error — a future debugging surface may want to tell them
            // apart.
            // BG-DIAG: this catch-all is the last-chance bucket; logging
            // the class name + stack trace here is the only way to spot
            // an unanticipated sshj throwable family.
            AppLog.e(TAG, "readInto: unhandled Throwable (sink error or unknown transport)", e)
            val msg = e.message ?: e.javaClass.simpleName
            setCloseReasonUnlessUserInitiated(SessionCloseReason.SinkError(msg))
            Result.failure(SshException(msg, e))
        } finally {
            // Close on every natural end (EOF, transport error, sink
            // exception). Skip on cancellation so the next reader can
            // re-attach to the same live session — see ConnectionRuntime on HanTermApplication.
            // close(userInitiated=false) preserves the existing SCR-CL-03
            // contract for the default caller — no reason field change
            // happens here unless the caller already set UserInitiated.
            if (!cancelled) {
                close()
            }
        }
    }

    /**
     * Forward a terminal-window resize to the remote. Called from
     * [com.apexplow.hanterm.terminal.TerminalView]'s layout listener when
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
     *
     * Sprint 3 / SCR-CL-01..04: when [userInitiated] is `true`, this method
     * writes [SessionCloseReason.UserInitiated] to [lastCloseReason]
     * synchronously, *before* enqueueing the asynchronous transport
     * teardown. This closes the race window described in `GEARS_SPEC.md`
     * Module 17 §Problem — the flag is visible to any concurrently-running
     * `readInto` catch block before the socket is actually torn down, so
     * the UI can reliably tell "user asked first" apart from "the
     * transport actually failed".
     *
     * The default `userInitiated = false` keeps the existing call sites
     * (the `readInto` `finally` block, plus any legacy callers) unchanged
     * — no reason field is touched on the default path.
     */
    fun close(userInitiated: Boolean = false) {
        // AtomicBoolean.flipToTrue makes this idempotent: only the first close
        // call reaches the transport and the onClose hook. Subsequent calls
        // are silent no-ops — important because callers wrap close() in
        // `finally` blocks plus the Disconnect button. SCR-CL-04: a second
        // call (with any userInitiated value) shall not change
        // lastCloseReason — if a reason was already written, it's preserved.
        if (!closed.compareAndSet(false, true)) return
        // Set UserInitiated FIRST, before the async transport.close() runs,
        // so a concurrent readInto catch sees the user signal even if the
        // socket-close-induced SocketException races us to the field.
        if (userInitiated) {
            lastCloseReason = SessionCloseReason.UserInitiated
        }
        writeExecutor.execute {
            transport.close()
            onClose(userInitiated)
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

    companion object {
        // BG-DIAG: logcat tag for the diagnostic AppLog.e calls in readInto.
        // Kept distinct from "SshClient" so a filter on either tag isolates
        // the layer that observed the failure (SshClient sees the
        // disconnect path; SshSession sees the read loop that triggered it).
        private const val TAG = "SshSession"
    }
}
