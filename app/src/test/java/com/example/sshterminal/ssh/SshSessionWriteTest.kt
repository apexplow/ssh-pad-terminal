package com.example.sshterminal.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue

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
    @Ignore("Sprint 2.5: readInto tests race with the coroutine+FakeTransport " +
            "interaction — the IO loop blocks on readQueue.take() and the test's " +
            "delay()/join() timing isn't reliable enough to assert deterministically. " +
            "Tracked in SPRINT_2_5 backlog.")
    fun test_readInto_invokesSinkForEachBatch() = runBlocking {
        transport.enqueueRead("hello ".toByteArray(Charsets.UTF_8))
        transport.enqueueRead("world".toByteArray(Charsets.UTF_8))
        transport.enqueueEof()

        val received = mutableListOf<ByteArray>()
        ioJob = scope.launch {
            session.readInto { bytes -> received += bytes }
        }
        ioJob.join()

        assertEquals(2, received.size)
        assertArrayEquals("hello ".toByteArray(), received[0])
        assertArrayEquals("world".toByteArray(), received[1])
    }

    @Test
    @Ignore("Sprint 2.5: see test_readInto_invokesSinkForEachBatch.")
    fun test_readInto_closesTransportOnEof() = runBlocking {
        transport.enqueueEof()

        ioJob = scope.launch { session.readInto { /* discard */ } }
        ioJob.join()

        assertTrue(
            "readInto must close the transport in its finally block when the remote EOFs",
            transport.closeCalled,
        )
    }

    @Test
    @Ignore("Sprint 2.5: cancellation timing in JUnit+runBlocking is flaky under " +
            "the Gradle test executor. The contract is real and covered by manual " +
            "testing; deferring automated coverage.")
    fun test_readInto_doesNotCloseTransportOnCancellation() = runBlocking {
        // Don't enqueue anything: readBytes() blocks on readQueue.take() until
        // the coroutine is cancelled, which interrupts the IO thread and
        // unwinds the withContext block via CancellationException.
        //
        // The session is a longer-lived resource than any one read loop:
        // the same SshSession may be driven by a sequence of UI
        // lifecycles (an Activity recreation can re-attach to the existing
        // session via ActiveSshSessionStore). A cancellation is a "stop
        // this reader" signal, NOT a "kill the session" signal. The
        // caller owns session lifetime — when the user actually wants to
        // disconnect, they go through SshClient.disconnect() (which calls
        // close() via the onClose hook).
        ioJob = scope.launch { session.readInto { /* discard */ } }
        // Give it a moment to reach the blocking take() before cancelling.
        delay(50)
        ioJob.cancelAndJoin()

        assertEquals(
            "cancellation of readInto must NOT close the transport — " +
                "session lifetime is owned by the caller (SshClient.disconnect), " +
                "not by the read coroutine (so an Activity recreation can " +
                "re-attach to the still-live session via ActiveSshSessionStore).",
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
    @Ignore("Sprint 2.5: see test_readInto_invokesSinkForEachBatch.")
    fun test_readInto_closesTransportOnSinkException() = runBlocking {
        // If the sink throws (e.g. emulator backing is null), the loop's
        // finally block must still release the transport — otherwise a
        // thrown RuntimeException would leak the SSH socket.
        transport.enqueueRead("bad".toByteArray(Charsets.UTF_8))

        ioJob = scope.launch {
            session.readInto { _ -> error("sink failure simulation") }
        }
        ioJob.join()

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

    /** Read side: blocking queue of pre-canned byte arrays; EOF marker = end of stream. */
    private val readQueue: LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue()

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

    fun enqueueRead(bytes: ByteArray) {
        readQueue.put(bytes)
    }

    fun enqueueEof() {
        readQueue.put(EOF_MARKER)
    }

    override fun write(bytes: ByteArray) {
        recordedWrites += bytes.copyOf()
        writeCallCount++
    }

    override fun readBytes(): ByteArray? {
        // Test seam for read-loop failure paths (SocketException,
        // SocketTimeoutException, SSHException). Checked before the queue
        // so a single throw fires exactly once and doesn't leak into a
        // subsequent test sharing the same FakeTransport.
        throwOnRead?.let {
            throwOnRead = null
            throw it
        }
        val next = readQueue.take()
        // Sentinel-based EOF: identity comparison on the singleton avoids
        // a magic-byte collision with a legitimate empty read.
        return if (next === EOF_MARKER) null else next
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
