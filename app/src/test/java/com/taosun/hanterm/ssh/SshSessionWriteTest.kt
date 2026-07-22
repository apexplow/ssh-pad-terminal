package com.taosun.hanterm.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Contract tests for [SshSession] using a hand-rolled fake [SshTransport].
 *
 * Per the Sprint 2 task list: "mock SSHJ 的 Channel 子类,验证 write 出去的
 * bytes 是对的 + close 流程". We sidestep Channel (a 700-line abstract class)
 * by going through [SshTransport], the same seam [SshClient] uses to wire
 * to ChannelTransport in production. The fake records every call so we can
 * assert against it.
 *
 * What's NOT covered here (Sprint 2.5 work):
 *  - actual TCP / kex / auth — those need a real sshd.
 *  - emulator handoff — that's a UI-level integration test.
 */
class SshSessionWriteTest {

    private lateinit var transport: FakeTransport
    private lateinit var session: SshSession
    private lateinit var onCloseCalls: IntArray
    private lateinit var scope: CoroutineScope
    private lateinit var ioJob: Job

    @Before
    fun setUp() {
        transport = FakeTransport()
        onCloseCalls = IntArray(1)
        session = SshSession(transport) { _ ->
            // onClose runs when session.close() is called; the array gives us
            // a single-slot counter we can assert on without resorting to
            // AtomicInteger. Sprint 3 / Module 17: onClose now also takes the
            // `userInitiated` flag (SshSession close → onClose hook
            // propagation chain); we ignore it here because the test only
            // asserts on call COUNT, not the reason.
            onCloseCalls[0]++
        }
        scope = CoroutineScope(Dispatchers.IO)
    }

    @After
    fun tearDown() {
        // ioJob is only started by the readInto* tests — for write/resizePty/
        // close tests it stays uninitialized. Guard with ::isInitialized so
        // tearDown works uniformly for all cases (without it, those tests
        // blow up with UninitializedPropertyAccessException before they can
        // even report a clean pass/fail).
        if (::ioJob.isInitialized) {
            runBlocking { ioJob.cancelAndJoin() }
        }
        scope.coroutineContext[Job]?.cancel()
    }

    // ---------------------------------------------------------------------
    // TerminalEndpoint.write: forwards bytes verbatim to the transport.
    // ---------------------------------------------------------------------

    @Test
    fun test_write_forwardsBytesVerbatimToTransport() {
        val payload = "ls -la\n".toByteArray(Charsets.UTF_8)

        session.write(payload)
        session.awaitWriteQueueDrained()

        assertArrayEquals(
            "every byte written to SshSession must reach the transport in order",
            payload,
            transport.recordedWrites.flattenToByteArray(),
        )
        assertEquals("exactly one write call expected", 1, transport.writeCallCount)
    }

    @Test
    fun test_write_multipleCallsAccumulateInOrder() {
        session.write("a".toByteArray())
        session.write("你".toByteArray(Charsets.UTF_8))  // 3-byte UTF-8
        session.write("\n".toByteArray())
        session.awaitWriteQueueDrained()

        val recorded = transport.recordedWrites.flattenToByteArray()
        // 1 + 3 + 1 = 5 bytes, matching the concatenation order.
        assertEquals(5, recorded.size)
        assertArrayEquals(
            "byte sequence must be a b 你 c concatenated in call order",
            byteArrayOf('a'.code.toByte()) + "你".toByteArray(Charsets.UTF_8) + "\n".toByteArray(),
            recorded,
        )
    }

    @Test
    fun test_write_emptyArray_isNoOp() {
        session.write(ByteArray(0))

        assertEquals(
            "empty write must not appear in transport history",
            0,
            transport.writeCallCount,
        )
    }

    @Test
    fun test_write_transportThrows_closeSessionAndFireOnClose() {
        // BG-WF-01: write failures must not be swallowed by the
        // ExecutorService.execute default uncaught handler. The fix
        // wraps transport.write() in try/catch and calls close() on
        // any throw, so the UI's existing disconnect overlay takes
        // over instead of silently losing the user's input.
        transport.throwOnWrite = SocketException("Broken pipe (test)")

        session.write("a".toByteArray())
        // Drain the executor so the failing task's catch block has
        // had a chance to call close() and enqueue transport.close()
        // + onClose() on the same executor.
        session.awaitWriteQueueDrained()

        assertEquals(
            "the failing write still committed before throwing (one-shot seam records first)",
            1,
            transport.writeCallCount,
        )
        assertTrue(
            "transport.close must be invoked when write throws — " +
                "without this, the user has no UI signal that input is being dropped",
            transport.closeCalled,
        )
        assertEquals(
            "onClose hook must fire exactly once on the close()-from-write path",
            1,
            onCloseCalls[0],
        )
    }

    @Test
    fun test_write_transportThrows_doesNotOverwriteUserInitiatedReason() {
        // SCR-CL-04 guard: if the user tapped Disconnect moments
        // before a write failed, the failure-handler must not
        // clobber the UserInitiated signal in lastCloseReason. The
        // catch calls close() with the default userInitiated=false,
        // and close()'s CAS keeps the original reason.
        session.close(userInitiated = true)
        assertEquals(SessionCloseReason.UserInitiated, session.lastCloseReason)

        transport.throwOnWrite = SocketException("Broken pipe (test)")
        session.write("a".toByteArray())
        session.awaitWriteQueueDrained()

        assertEquals(
            "UserInitiated must survive a subsequent write-failure close",
            SessionCloseReason.UserInitiated,
            session.lastCloseReason,
        )
    }

    // ---------------------------------------------------------------------
    // resizePty: forwards the SIGWINCH-equivalent call to the transport.
    // ---------------------------------------------------------------------

    @Test
    fun test_resizePty_forwardsColsRowsAndPixelsToTransport() {
        session.resizePty(cols = 120, rows = 40, widthPx = 1920, heightPx = 1080)
        session.awaitWriteQueueDrained()

        assertEquals(1, transport.resizeCalls.size)
        val (cols, rows, widthPx, heightPx) = transport.resizeCalls.single()
        assertEquals(120, cols)
        assertEquals(40, rows)
        assertEquals(1920, widthPx)
        assertEquals(1080, heightPx)
    }

    @Test
    fun test_resizePty_defaultsPixelDimsToZero() {
        // The TerminalView layout listener can pass 0 for pixels when the
        // view hasn't laid out yet — the contract is "pass through whatever
        // you got"; SSHJ's setTerminalWidth/Height tolerates 0.
        session.resizePty(cols = 80, rows = 24)
        session.awaitWriteQueueDrained()

        val (cols, rows, widthPx, heightPx) = transport.resizeCalls.single()
        assertEquals(80, cols)
        assertEquals(24, rows)
        assertEquals(0, widthPx)
        assertEquals(0, heightPx)
    }

    // ---------------------------------------------------------------------
    // close: idempotent and triggers onClose hook.
    // ---------------------------------------------------------------------

    @Test
    fun test_close_invokesTransportCloseAndOnCloseHook() {
        session.close()
        session.awaitWriteQueueDrained()

        assertTrue("transport.close() must be called", transport.closeCalled)
        assertEquals(
            "onClose hook must run exactly once when close() is called",
            1,
            onCloseCalls[0],
        )
    }

    @Test
    fun test_close_isIdempotent() {
        session.close()
        session.close()
        session.close()
        session.awaitWriteQueueDrained()

        assertEquals(
            "multiple close() calls must invoke onClose exactly once (idempotency contract)",
            1,
            onCloseCalls[0],
        )
        // We don't pin transport.close() call count: FakeTransport just flips a
        // boolean, so the production ChannelTransport's idempotency is
        // delegated. What matters for the contract is that the user-visible
        // hook (which is what tears down the SshClient parent) doesn't
        // double-fire.
    }

    // ---------------------------------------------------------------------
    // readInto: the IO loop drains the transport and invokes the sink.
    // ---------------------------------------------------------------------

    @Test
    fun test_readInto_invokesSinkForEachBatch() = runBlocking {
        transport.enqueueRead("hello ".toByteArray(Charsets.UTF_8))
        transport.enqueueRead("world".toByteArray(Charsets.UTF_8))
        transport.enqueueEof()

        val received = mutableListOf<ByteArray>()
        ioJob = scope.launch {
            session.readInto { bytes -> received += bytes }
        }
        ioJob.join()
        // readInto's finally block posts transport.close() asynchronously on
        // the write executor; drain it before asserting (same pattern as the
        // active socket-timeout test). Without this, the assertion can race
        // the executor's close() call and report a false-negative on slow CI.
        session.awaitWriteQueueDrained()

        assertEquals(2, received.size)
        assertArrayEquals("hello ".toByteArray(), received[0])
        assertArrayEquals("world".toByteArray(), received[1])
    }

    @Test
    fun test_readInto_closesTransportOnEof() = runBlocking {
        transport.enqueueEof()

        ioJob = scope.launch { session.readInto { /* discard */ } }
        ioJob.join()
        session.awaitWriteQueueDrained()

        assertTrue(
            "readInto must close the transport in its finally block when the remote EOFs",
            transport.closeCalled,
        )
    }

    @Test
    fun test_readInto_doesNotCloseTransportOnCancellation() = runBlocking {
        // Don't enqueue real bytes: readBytes() blocks on readQueue.take()
        // until we deliver a [CANCEL_SENTINEL] (or EOF) through the queue.
        // The sentinel simulates what would happen if a coroutine was
        // cancelled mid-blocking-read in production: the read loop receives
        // a CancellationException, the catch arm sets `cancelled = true`,
        // and the finally block skips close() — that's the contract under
        // test (SS-RI-02).
        //
        // Deterministic-blocking pattern: instead of delay(50) (timing flake
        // under Gradle's test executor), install a beforeRead hook that fires
        // inside FakeTransport.readBytes() — synchronously, BEFORE the queue
        // take — and counts down a latch. Awaiting the latch proves the
        // read executor has entered the blocking call.
        //
        // Why not rely on Thread.interrupt() + Job.cancel()?
        // kotlinx-coroutines' Job.cancel() does NOT interrupt a thread
        // blocked in a plain Java blocking call like LinkedBlockingQueue
        // .take() — it only sets the cancellation flag, which is observed at
        // coroutine suspension points. take() has no such point. A test
        // that depended on Thread.interrupt + withContext cancellation
        // conversion would be racy with the coroutine machinery's handling
        // of InterruptedException → CancellationException (the IOException
        // path was observed to escape the readInto catch arm and hit the
        // generic Throwable arm, which runs the close() path). The sentinel
        // path is fully deterministic: the same CancellationException class
        // is thrown by readBytes that SshSession.readInto is built to handle.
        //
        // The session is a longer-lived resource than any one read loop:
        // the same SshSession may be driven by a sequence of UI
        // lifecycles (an Activity recreation can re-attach to the existing
        // session via the process-scoped ConnectionRuntime). A cancellation is a "stop
        // this reader" signal, NOT a "kill the session" signal. The
        // caller owns session lifetime — when the user actually wants to
        // disconnect, they go through SshClient.disconnect() (which calls
        // close() via the onClose hook).
        val reachedBlockingRead = CountDownLatch(1)
        transport.beforeRead = { reachedBlockingRead.countDown() }

        ioJob = scope.launch { session.readInto { /* discard */ } }
        val arrived = reachedBlockingRead.await(2, TimeUnit.SECONDS)
        assertTrue(
            "ioJob must reach readBytes() within 2 s — the latch counts down " +
                "before the queue take, so a count-down is a proof of having " +
                "entered readBytes() (not of having returned from it).",
            arrived,
        )
        // Deliver the cancellation sentinel to wake the blocking take();
        // FakeTransport.readBytes() converts it to a CancellationException
        // that the readInto catch arm recognises and converts to the "no
        // close" path.
        transport.enqueueCancellation()
        ioJob.join()

        assertEquals(
            "cancellation of readInto must NOT close the transport — " +
                "session lifetime is owned by the caller (SshClient.disconnect), " +
                "not by the read coroutine (so an Activity recreation can " +
                "re-attach to the still-live session via ConnectionRuntime).",
            false,
            transport.closeCalled,
        )
        assertEquals(
            "onClose hook must not fire on cancellation either — it is " +
                "invoked exclusively by SshClient.disconnect.",
            0,
            onCloseCalls[0],
        )
    }

    @Test
    fun test_readInto_closesTransportOnSinkException() = runBlocking {
        // If the sink throws (e.g. emulator backing is null), the loop's
        // finally block must still release the transport — otherwise a
        // thrown RuntimeException would leak the SSH socket.
        transport.enqueueRead("bad".toByteArray(Charsets.UTF_8))

        ioJob = scope.launch {
            session.readInto { _ -> error("sink failure simulation") }
        }
        ioJob.join()
        session.awaitWriteQueueDrained()

        assertTrue(
            "transport must close even when the sink throws",
            transport.closeCalled,
        )
    }

    // ---------------------------------------------------------------------
    // readInto failure translation: SocketTimeoutException in the read loop
    // (e.g. SO_TIMEOUT firing on a quiet socket) must reach the UI as the
    // SshErrorMessages.friendly() text, NOT the raw JDK "Read timed out"
    // string. Regression test for the read-loop-vs-connect-path inconsistency
    // where a connect-time timeout was translated but a read-loop timeout
    // still leaked the raw message. The transport throws synchronously, so
    // no coroutine-timing race — the @Ignore'd tests above can't run this
    // shape because they rely on readQueue.take() returning.
    // ---------------------------------------------------------------------

    @Test
    fun test_readInto_socketTimeout_isTranslatedToFriendlyMessage() = runBlocking {
        transport.throwOnRead = SocketTimeoutException("Read timed out")

        val outcome = session.readInto { /* discard */ }

        val failure = outcome.exceptionOrNull()
        assertNotNull("readInto must surface the exception as Result.failure", failure)
        // The wrapping is what changed: SshException carries the friendly
        // translation as its message, with the raw SocketTimeoutException
        // preserved as cause for adb logcat post-mortem.
        assertTrue(
            "failure must be wrapped in SshException: ${failure!!::class.java.simpleName}",
            failure is SshException,
        )
        assertEquals(
            "message must be the SshErrorMessages.friendly() translation, not the raw JDK string",
            "Connection timed out. Check your network and the server's address.",
            failure.message,
        )
        assertTrue(
            "original exception must be preserved as cause for log analysis",
            failure.cause is SocketTimeoutException,
        )
        // close() posts transport.close() to the write executor, which runs
        // asynchronously — drain it before asserting so the queue's work
        // has actually completed.
        session.awaitWriteQueueDrained()
        assertTrue(
            "transport must close in the finally block on read failure",
            transport.closeCalled,
        )
    }

    // ---------------------------------------------------------------------
    // Sprint 3 / Module 17 / SCR-TS-01..02: lastCloseReason disambiguation.
    //
    // The race fix relies on SshSession.close(userInitiated=true) writing
    // SessionCloseReason.UserInitiated SYNCHRONOUSLY (before enqueueing the
    // async transport teardown), so a concurrent readInto catch block sees
    // UserInitiated even if a SocketException races us to the field. These
    // tests pin that invariant and the readInto exit-branch classifications.
    //
    // SCR-TS-01: UserInitiated is preserved across a subsequent SocketException.
    // SCR-TS-02: readInto's EOF path sets RemoteEof, SocketException path sets
    //             TransportError, both when no prior UserInitiated close ran.
    // ---------------------------------------------------------------------

    @Test
    fun scr_ts_01_closeUserInitiated_thenSocketException_keepsUserInitiated() = runBlocking {
        // User taps Disconnect — close runs synchronously, setting the
        // lastCloseReason BEFORE transport.close() is enqueued.
        session.close(userInitiated = true)
        assertEquals(
            "UserInitiated must be set synchronously by close(userInitiated=true)",
            SessionCloseReason.UserInitiated,
            session.lastCloseReason,
        )

        // The async socket teardown eventually causes the reader thread to
        // see a SocketException on the next readBytes(). With the fix, this
        // must NOT overwrite the UserInitiated signal — that's the whole
        // point of the SCR-CL-02 invariant.
        transport.throwOnRead = SocketException("Connection reset")
        val outcome = session.readInto { /* discard */ }
        assertTrue(
            "readInto should still surface the SocketException as Result.failure",
            outcome.isFailure,
        )
        assertEquals(
            "lastCloseReason must remain UserInitiated even after a racing " +
                "SocketException — this is the SCR-CL-02 invariant",
            SessionCloseReason.UserInitiated,
            session.lastCloseReason,
        )
        session.awaitWriteQueueDrained()
    }

    @Test
    fun scr_ts_02_readInto_cleanEof_setsRemoteEof() = runBlocking {
        // No prior close — the default lastCloseReason is RemoteEof, but
        // the explicit readInto EOF path should also write it (covering
        // the case where the field was somehow reset between sessions,
        // e.g. a future reuse scenario).
        transport.enqueueEof()

        val outcome = session.readInto { /* discard */ }

        assertTrue(
            "clean EOF must return Result.success",
            outcome.isSuccess,
        )
        assertEquals(
            "clean EOF must classify as RemoteEof",
            SessionCloseReason.RemoteEof,
            session.lastCloseReason,
        )
        session.awaitWriteQueueDrained()
        assertTrue(
            "transport must close in the finally block on EOF",
            transport.closeCalled,
        )
    }

    @Test
    fun scr_ts_02_readInto_socketException_setsTransportError() = runBlocking {
        transport.throwOnRead = SocketException("Connection reset")

        val outcome = session.readInto { /* discard */ }

        assertTrue(
            "SocketException must be surfaced as Result.failure",
            outcome.isFailure,
        )
        val reason = session.lastCloseReason
        assertTrue(
            "SocketException must classify as TransportError, was: $reason",
            reason is SessionCloseReason.TransportError,
        )
        // The friendly translation is what the TerminalPane would forward to
        // onSessionClosed — pin it so a future SshErrorMessages refactor
        // doesn't silently change the user-visible string for this path.
        assertEquals(
            "TransportError message must be the SshErrorMessages.friendly " +
                "translation of SocketException (per SCR-TP-03 + SCR-RS-03)",
            "Connection lost. The server may have closed the connection.",
            (reason as SessionCloseReason.TransportError).message,
        )
        session.awaitWriteQueueDrained()
        assertTrue(
            "transport must close in the finally block on read failure",
            transport.closeCalled,
        )
    }

    @Test
    fun scr_ts_02_close_userInitiatedDefault_isFalseAndDoesNotSetReason() = runBlocking {
        // SCR-CL-03: existing no-arg close() call sites must behave exactly
        // as close(userInitiated=false). lastCloseReason must NOT become
        // UserInitiated on the default path.
        session.close()
        assertEquals(
            "default close() must NOT set lastCloseReason to UserInitiated — " +
                "that signal is opt-in only (SCR-CL-03)",
            SessionCloseReason.RemoteEof,
            session.lastCloseReason,
        )
        session.awaitWriteQueueDrained()
        assertEquals(
            "onClose hook must still fire on default close",
            1,
            onCloseCalls[0],
        )
    }
}

/**
 * Test-only [SshTransport] that records every call and lets the test
 * enqueue canned byte arrays for the IO loop to drain.
 *
 * Why a hand-rolled fake instead of mocking [com.hierynomus.sshj.channel.Channel]:
 *   Channel is abstract, has 30+ abstract methods, and touches several
 *   internal state machines. A Mockito mock would compile only against a
 *   specific SSHJ version and break every time sshj bumps its internals.
 *   The narrow [SshTransport] surface lets the contract tests ride out
 *   SSHJ version changes for free.
 */
internal class FakeTransport : SshTransport {
    val recordedWrites: MutableList<ByteArray> = mutableListOf()
    var writeCallCount: Int = 0
        private set
    var closeCalled: Boolean = false
        private set

    data class ResizeCall(val cols: Int, val rows: Int, val widthPx: Int, val heightPx: Int)
    val resizeCalls: MutableList<ResizeCall> = mutableListOf()

    /** Read side: blocking queue of pre-canned byte arrays + EOF + cancellation sentinels. */
    private val readQueue: LinkedBlockingQueue<Any> = LinkedBlockingQueue()

    /**
     * Singleton sentinel for "EOF" in the read queue. Used to be `null` but
     * `LinkedBlockingQueue.put` rejects null (NPE) — the previous
     * implementation never tripped because every test that called
     * `enqueueEof()` was @Ignore'd. The non-null sentinel lets the queue
     * type stay non-nullable while still signalling end-of-stream.
     */
    private val EOF_MARKER: ByteArray = ByteArray(0)

    /**
     * If set, the next [readBytes] call throws this and clears the field.
     * Lets readInto-failure tests exercise the catch blocks without having
     * to coordinate with the blocking queue or coroutine cancellation.
     */
    var throwOnRead: Throwable? = null

    /**
     * If set, the next [write] call throws this and clears the field.
     * Lets write-failure tests exercise the `SshSession.write` catch
     * block — proves that a write throw triggers `SshSession.close()` and
     * the existing disconnect UX, instead of being swallowed by the
     * `ExecutorService.execute` default uncaught handler.
     *
     * Mirrors [throwOnRead]'s one-shot semantics: recorded bytes are
     * committed to [recordedWrites] BEFORE the throw so the test can
     * still assert what the caller attempted.
     */
    var throwOnWrite: Throwable? = null

    /**
     * Synchronous hook fired at the top of [readBytes], BEFORE the
     * throwOnRead / queue take. Lets cancellation tests prove the IO loop
     * actually reached the blocking read, not just that the coroutine was
     * launched — the hook counts down a latch that the test thread awaits,
     * closing the timing window that `delay(50)` previously had to paper
     * over.
     *
     * One-shot semantics: the hook auto-clears after firing, matching
     * [throwOnRead]'s one-shot pattern. This avoids surprise coupling
     * between sequential reads in the same test.
     */
    @Volatile
    var beforeRead: (() -> Unit)? = null

    /**
     * Singleton sentinel for "test-triggered cancellation" in the read
     * queue. When [enqueueCancellation] is called, this object is put on
     * the queue; [readBytes] recognises it (identity compare) and throws
     * a [java.util.concurrent.CancellationException], simulating what
     * happens in production when the coroutine is cancelled while blocked
     * on a read.
     *
     * Why not just rely on [Thread.interrupt] + [Job.cancel]?
     * kotlinx-coroutines' Job.cancel() does NOT interrupt a thread blocked
     * in a plain Java blocking call like [LinkedBlockingQueue.take] — it
     * only sets the cancellation flag, observed at coroutine suspension
     * points. take() has no such point. So a cancelled coroutine in a
     * blocking take() would deadlock on cancelAndJoin(). The sentinel
     * approach: the test thread enqueues the sentinel, the take() wakes,
     * readBytes throws CancellationException, SshSession.readInto's catch
     * arm sets `cancelled = true` and the finally block skips close() —
     * exactly the contract under test (SS-RI-02), without depending on
     * kotlinx-coroutines' interrupt semantics for blocking Java IO.
     */
    private val CANCEL_SENTINEL: Any = Any()

    fun enqueueRead(bytes: ByteArray) {
        readQueue.put(bytes)
    }

    fun enqueueEof() {
        readQueue.put(EOF_MARKER)
    }

    fun enqueueCancellation() {
        readQueue.put(CANCEL_SENTINEL)
    }

    override fun write(bytes: ByteArray) {
        recordedWrites += bytes.copyOf()
        writeCallCount++
        // Test seam for write-failure paths (SocketException /
        // IOException on a half-closed socket). Mirrors throwOnRead's
        // "commit, then throw, then clear" pattern: the write attempt
        // is recorded for inspection even though the call ultimately
        // failed.
        throwOnWrite?.let {
            throwOnWrite = null
            throw it
        }
    }

    override fun readBytes(): ByteArray? {
        // Cancellation test seam: proves the IO loop reached readBytes() so
        // a subsequent enqueueCancellation + cancelAndJoin is a race-free
        // test of the cancellation contract (SS-RI-02). Fires exactly once
        // per assignment.
        beforeRead?.let { hook ->
            beforeRead = null
            hook()
        }
        // Test seam for read-loop failure paths (SocketException,
        // SocketTimeoutException, SSHException). Checked before the queue
        // so a single throw fires exactly once and doesn't leak into a
        // subsequent test sharing the same FakeTransport.
        throwOnRead?.let {
            throwOnRead = null
            throw it
        }
        val next = readQueue.take()
        // Sentinel dispatch: EOF → null, cancellation → CancellationException
        // (SshSession.readInto's catch arm turns this into the "no close"
        // path), bytes → return as-is. Identity comparison on the singletons
        // avoids magic-byte collisions with legitimate empty reads.
        return when (next) {
            EOF_MARKER -> null
            CANCEL_SENTINEL -> throw java.util.concurrent.CancellationException(
                "test-triggered cancellation via FakeTransport sentinel",
            )
            else -> next as ByteArray
        }
    }

    override fun resizePty(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        resizeCalls += ResizeCall(cols, rows, widthPx, heightPx)
    }

    override fun close() {
        closeCalled = true
    }
}

private fun List<ByteArray>.flattenToByteArray(): ByteArray {
    val out = ByteArray(sumOf { it.size })
    var destPos = 0
    for (chunk in this) {
        System.arraycopy(chunk, 0, out, destPos, chunk.size)
        destPos += chunk.size
    }
    return out
}
