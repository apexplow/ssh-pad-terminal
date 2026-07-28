package com.apexplow.hanterm.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Contract tests for [PtyBridge], exercised against [BufferedPtyBridge].
 *
 * These tests are deliberately plain JUnit — no Robolectric, no
 * Android framework — because the impl is pure Java. If a future
 * NDK-backed or `LocalServerSocket`-backed impl cannot pass these
 * same cases, the contract is wrong; the cases are not coupled to
 * the v1 implementation.
 *
 * The byte-stream tests are written twice — once for each
 * direction of the bridge — because the symmetric API means
 * "view reads remote output" and "transport reads user
 * keystrokes" go through different queues and have to satisfy
 * the same guarantees independently.
 *
 * The cases pin:
 *   - transport→view round-trip and FIFO ordering (cases 1, 2)
 *   - view→transport round-trip and FIFO ordering (cases 3, 4)
 *   - EOF on close propagates to both sides (cases 5, 6, 7)
 *   - idempotent close (case 8)
 *   - post-close writes are silent no-ops, both sides (9, 10)
 *   - empty writes are silent no-ops, both sides (11)
 *   - read() blocks until data is available, both sides (12, 13)
 *   - close() unblocks a pending read() promptly, both sides (14, 15)
 *   - concurrent writers, both directions (16, 17)
 *   - bytes enqueued before close are still readable after
 *     close (case 18)
 *   - unidirectional isolation: a byte written on one end does
 *     NOT appear at that same end's read (case 19)
 *   - resize listener fires on every resize and on registration,
 *     and detaches cleanly when set to null (cases 20, 21, 22)
 *
 * The `@Test(timeout = ...)` annotations are last-resort guards:
 * a failure in any synchronization case manifests as a hang, and
 * a hang would otherwise freeze the test suite.
 */
class PtyBridgeTest {

    // ─── transport → view (remote output to emulator) ──────────────

    @Test
    fun viewRead_afterTransportWrite_returnsBytes() {
        val bridge = BufferedPtyBridge()
        bridge.transport.write(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), bridge.view.read())
        bridge.close()
    }

    @Test
    fun viewRead_preservesOrderAcrossTransportWrites() {
        val bridge = BufferedPtyBridge()
        bridge.transport.write(byteArrayOf(1))
        bridge.transport.write(byteArrayOf(2, 3))
        bridge.transport.write(byteArrayOf(4, 5, 6))
        assertArrayEquals(byteArrayOf(1), bridge.view.read())
        assertArrayEquals(byteArrayOf(2, 3), bridge.view.read())
        assertArrayEquals(byteArrayOf(4, 5, 6), bridge.view.read())
        bridge.close()
    }

    // ─── view → transport (keystrokes to remote) ───────────────────

    @Test
    fun transportRead_afterViewWrite_returnsBytes() {
        val bridge = BufferedPtyBridge()
        bridge.view.write(byteArrayOf(7, 8, 9))
        assertArrayEquals(byteArrayOf(7, 8, 9), bridge.transport.read())
        bridge.close()
    }

    @Test
    fun transportRead_preservesOrderAcrossViewWrites() {
        val bridge = BufferedPtyBridge()
        bridge.view.write(byteArrayOf(9))
        bridge.view.write(byteArrayOf(8, 7))
        bridge.view.write(byteArrayOf(6, 5, 4))
        assertArrayEquals(byteArrayOf(9), bridge.transport.read())
        assertArrayEquals(byteArrayOf(8, 7), bridge.transport.read())
        assertArrayEquals(byteArrayOf(6, 5, 4), bridge.transport.read())
        bridge.close()
    }

    // ─── close/EOF (both sides) ────────────────────────────────────

    @Test
    fun viewRead_returnsNull_afterClose() {
        val bridge = BufferedPtyBridge()
        bridge.transport.write(byteArrayOf(0x42))
        bridge.close()
        assertArrayEquals(byteArrayOf(0x42), bridge.view.read())
        assertNull(bridge.view.read())
        // null-once-null-forever: the bridge does not reopen.
        assertNull(bridge.view.read())
        assertNull(bridge.view.read())
    }

    @Test
    fun transportRead_returnsNull_afterClose() {
        val bridge = BufferedPtyBridge()
        bridge.view.write(byteArrayOf(0x42))
        bridge.close()
        assertArrayEquals(byteArrayOf(0x42), bridge.transport.read())
        assertNull(bridge.transport.read())
        assertNull(bridge.transport.read())
    }

    @Test
    fun close_signalsEofOnBothSides() {
        // A single close() must EOF both streams — neither side
        // can hang in take() forever after the bridge is gone.
        val bridge = BufferedPtyBridge()
        bridge.close()
        assertNull("view.read must EOF after close", bridge.view.read())
        assertNull("transport.read must EOF after close", bridge.transport.read())
    }

    @Test
    fun close_isIdempotent() {
        val bridge = BufferedPtyBridge()
        bridge.close()
        bridge.close()
        bridge.close()
        assertNull(bridge.view.read())
        assertNull(bridge.transport.read())
        // Endpoint close() also funnels to bridge.close(): a
        // caller that only knows about PtyEndpoint can still
        // tear down the bridge through either end.
        bridge.view.close()
        bridge.transport.close()
        assertNull(bridge.view.read())
        assertNull(bridge.transport.read())
    }

    // ─── write after close ─────────────────────────────────────────

    @Test
    fun transportWrite_afterClose_isNoOp() {
        val bridge = BufferedPtyBridge()
        bridge.close()
        bridge.transport.write(byteArrayOf(1, 2, 3))
        bridge.transport.write(byteArrayOf(4, 5, 6))
        assertNull(bridge.view.read())
    }

    @Test
    fun viewWrite_afterClose_isNoOp() {
        val bridge = BufferedPtyBridge()
        bridge.close()
        bridge.view.write(byteArrayOf(1, 2, 3))
        bridge.view.write(byteArrayOf(4, 5, 6))
        assertNull(bridge.transport.read())
    }

    // ─── empty writes ──────────────────────────────────────────────

    @Test
    fun emptyWrite_isNoOp_onBothEnds() {
        val bridge = BufferedPtyBridge()
        bridge.transport.write(ByteArray(0))
        bridge.view.write(ByteArray(0))
        // If either empty write had been enqueued, the close
        // below would put EOF after it and read would return
        // the empty chunk (matching `bytes ?: break` shape)
        // before the EOF. We assert both reads are null on a
        // fresh bridge, which only holds if the empty writes
        // were dropped at both ends.
        bridge.close()
        assertNull(bridge.view.read())
        assertNull(bridge.transport.read())
    }

    // ─── blocking read until data ──────────────────────────────────

    @Test(timeout = 5_000)
    fun viewRead_blocksUntilTransportWrite() {
        val bridge = BufferedPtyBridge()
        val started = CountDownLatch(1)
        val result = AtomicReference<ByteArray?>(null)
        val reader = Thread {
            started.countDown()
            result.set(bridge.view.read())
        }
        reader.start()
        assertTrue("reader thread must start", started.await(1, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertTrue("reader should still be blocked", reader.isAlive)
        bridge.transport.write(byteArrayOf(1, 2, 3))
        val unblockStart = System.nanoTime()
        reader.join()
        val unblockElapsedMs = (System.nanoTime() - unblockStart) / 1_000_000
        assertTrue(
            "reader must unblock promptly; got ${unblockElapsedMs}ms",
            unblockElapsedMs < 1_000,
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), result.get())
        bridge.close()
    }

    @Test(timeout = 5_000)
    fun transportRead_blocksUntilViewWrite() {
        val bridge = BufferedPtyBridge()
        val started = CountDownLatch(1)
        val result = AtomicReference<ByteArray?>(null)
        val reader = Thread {
            started.countDown()
            result.set(bridge.transport.read())
        }
        reader.start()
        assertTrue("reader thread must start", started.await(1, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertTrue("reader should still be blocked", reader.isAlive)
        bridge.view.write(byteArrayOf(1, 2, 3))
        val unblockStart = System.nanoTime()
        reader.join()
        val unblockElapsedMs = (System.nanoTime() - unblockStart) / 1_000_000
        assertTrue(
            "reader must unblock promptly; got ${unblockElapsedMs}ms",
            unblockElapsedMs < 1_000,
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), result.get())
        bridge.close()
    }

    // ─── close unblocks a pending read ─────────────────────────────

    @Test(timeout = 5_000)
    fun viewRead_blocksUntilClose() {
        val bridge = BufferedPtyBridge()
        val reader = Thread { bridge.view.read() }
        reader.start()
        Thread.sleep(50)
        assertTrue(reader.isAlive)
        val startNanos = System.nanoTime()
        bridge.close()
        reader.join()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        assertTrue(
            "view reader must unblock within 500ms of close(); got ${elapsedMs}ms",
            elapsedMs < 500,
        )
    }

    @Test(timeout = 5_000)
    fun transportRead_blocksUntilClose() {
        val bridge = BufferedPtyBridge()
        val reader = Thread { bridge.transport.read() }
        reader.start()
        Thread.sleep(50)
        assertTrue(reader.isAlive)
        val startNanos = System.nanoTime()
        bridge.close()
        reader.join()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        assertTrue(
            "transport reader must unblock within 500ms of close(); got ${elapsedMs}ms",
            elapsedMs < 500,
        )
    }

    // ─── concurrent writers, both directions ───────────────────────

    @Test(timeout = 10_000)
    fun concurrentTransportWrites_doNotCorruptViewReads() {
        val bridge = BufferedPtyBridge()
        val numWriters = 8
        val writesPerThread = 1_000
        val chunkSize = 64
        val expectedTotal = numWriters * writesPerThread * chunkSize

        val writersGo = CountDownLatch(1)
        val writersDone = CountDownLatch(numWriters)

        repeat(numWriters) { threadId ->
            Thread {
                writersGo.await()
                repeat(writesPerThread) {
                    val chunk = ByteArray(chunkSize) { i ->
                        ((i + threadId) and 0xFF).toByte()
                    }
                    bridge.transport.write(chunk)
                }
                writersDone.countDown()
            }.start()
        }

        val closer = Thread {
            writersDone.await()
            bridge.close()
        }
        closer.start()

        writersGo.countDown()

        val received = ByteArrayOutputStream()
        while (true) {
            val chunk = bridge.view.read() ?: break
            received.write(chunk)
        }
        val closerStart = System.nanoTime()
        closer.join()
        val closerElapsed = (System.nanoTime() - closerStart) / 1_000_000
        assertTrue(
            "closer must finish promptly; got ${closerElapsed}ms",
            closerElapsed < 2_000,
        )
        assertEquals(
            "concurrent transport writers must not lose or duplicate bytes",
            expectedTotal,
            received.size(),
        )
    }

    @Test(timeout = 10_000)
    fun concurrentViewWrites_doNotCorruptTransportReads() {
        val bridge = BufferedPtyBridge()
        val numWriters = 8
        val writesPerThread = 1_000
        val chunkSize = 64
        val expectedTotal = numWriters * writesPerThread * chunkSize

        val writersGo = CountDownLatch(1)
        val writersDone = CountDownLatch(numWriters)

        repeat(numWriters) { threadId ->
            Thread {
                writersGo.await()
                repeat(writesPerThread) {
                    val chunk = ByteArray(chunkSize) { i ->
                        ((i + threadId) and 0xFF).toByte()
                    }
                    bridge.view.write(chunk)
                }
                writersDone.countDown()
            }.start()
        }

        val closer = Thread {
            writersDone.await()
            bridge.close()
        }
        closer.start()

        writersGo.countDown()

        val received = ByteArrayOutputStream()
        while (true) {
            val chunk = bridge.transport.read() ?: break
            received.write(chunk)
        }
        closer.join()
        assertEquals(
            "concurrent view writers must not lose or duplicate bytes",
            expectedTotal,
            received.size(),
        )
    }

    // ─── drain semantics ───────────────────────────────────────────

    @Test
    fun writeThenClose_drainAllQueuedBytes_onBothSides() {
        val bridge = BufferedPtyBridge()
        bridge.transport.write(byteArrayOf(1))
        bridge.transport.write(byteArrayOf(2, 3))
        bridge.view.write(byteArrayOf(0xAA.toByte()))
        bridge.view.write(byteArrayOf(0xBB.toByte(), 0xCC.toByte()))
        bridge.close()
        assertArrayEquals(byteArrayOf(1), bridge.view.read())
        assertArrayEquals(byteArrayOf(2, 3), bridge.view.read())
        assertArrayEquals(byteArrayOf(0xAA.toByte()), bridge.transport.read())
        assertArrayEquals(byteArrayOf(0xBB.toByte(), 0xCC.toByte()), bridge.transport.read())
        // Only after every queued byte is drained does each
        // read return null.
        assertNull(bridge.view.read())
        assertNull(bridge.transport.read())
    }

    // ─── cross-stream isolation ────────────────────────────────────

    @Test
    fun writesDoNotLeakToOwnSideRead() {
        // The two endpoints are inverse views of the same two
        // queues; a write on one end NEVER appears at that same
        // end's read. This pins the symmetric semantics —
        // without it the bridge would degenerate to loopback.
        //
        // We can't simply "read on the same side" to verify
        // non-leakage: a read on an empty, open queue blocks
        // until either data arrives or close() puts EOF. So
        // instead we (a) confirm the correct direction
        // round-trips exact bytes, then (b) close and verify
        // BOTH sides see EOF with no extra data — which would
        // only happen if a write had looped onto its own side.
        val bridge = BufferedPtyBridge()

        // Direction A: transport writes → view reads.
        bridge.transport.write(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), bridge.view.read())

        // Direction B: view writes → transport reads.
        bridge.view.write(byteArrayOf(4, 5, 6))
        assertArrayEquals(byteArrayOf(4, 5, 6), bridge.transport.read())

        // Both queues should now be drained. Closing must
        // yield EOF on both sides with no spurious data — a
        // loopback impl would have queued the writer's bytes on
        // both directions.
        bridge.close()
        assertNull("view.read must EOF without leakage", bridge.view.read())
        assertNull(
            "transport.read must EOF without leakage from view's write",
            bridge.transport.read(),
        )
    }

    // ─── resize ────────────────────────────────────────────────────

    @Test
    fun resize_storesLastValue_andFiresListener() {
        val bridge = BufferedPtyBridge()
        val received = mutableListOf<Pair<Int, Int>>()
        bridge.setResizeListener { c, r -> received.add(c to r) }
        bridge.resize(80, 24)
        bridge.resize(132, 50)
        bridge.resize(200, 60)
        assertEquals(
            "listener should fire for every resize, in order",
            listOf(80 to 24, 132 to 50, 200 to 60),
            received,
        )
        // Late-registration fire-once: a freshly-bound listener
        // should immediately see the most recent size.
        val late = mutableListOf<Pair<Int, Int>>()
        bridge.setResizeListener { c, r -> late.add(c to r) }
        assertEquals(listOf(200 to 60), late)
    }

    @Test
    fun setResizeListener_nullDisablesCallbacks() {
        val bridge = BufferedPtyBridge()
        var count = 0
        bridge.setResizeListener { _, _ -> count++ }
        bridge.resize(80, 24)
        // First resize fires exactly once (the registration did
        // NOT fire on its own because lastCols/lastRows were 0).
        assertEquals(1, count)
        bridge.setResizeListener(null)
        bridge.resize(100, 30)
        bridge.resize(120, 40)
        assertEquals(
            "detached listener must not fire",
            1,
            count,
        )
    }

    @Test
    fun resize_afterClose_isNoOp() {
        val bridge = BufferedPtyBridge()
        var count = 0
        bridge.setResizeListener { _, _ -> count++ }
        bridge.close()
        bridge.resize(80, 24)
        assertEquals("post-close resize must not fire the listener", 0, count)
    }

    @Test
    fun transportWrite_thenResize_doesNotInterleave() {
        // Sanity check that the resize path doesn't accidentally
        // consume or reorder a queued byte on the transport→view
        // stream.
        val bridge = BufferedPtyBridge()
        bridge.transport.write(byteArrayOf(0x42))
        bridge.setResizeListener { _, _ -> /* drop */ }
        bridge.resize(80, 24)
        assertArrayEquals(byteArrayOf(0x42), bridge.view.read())
        bridge.close()
        assertNull(bridge.view.read())
    }

    private fun drain(bridge: PtyBridge) {
        var i = 0
        while (bridge.view.read() != null || bridge.transport.read() != null) {
            if (++i > 1_000) fail("drain did not reach EOF in 1_000 reads")
        }
    }
}
