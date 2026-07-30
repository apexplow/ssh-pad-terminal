package com.apexplow.hanterm.ssh

import com.apexplow.hanterm.terminal.BufferedPtyBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Feedback loop for the device report:
 *   physical keyboard → pause → typed chars appear in one burst
 *   (especially under cursor-agent / alt-buffer TUIs).
 *
 * HanTerm has no local echo: screen update = remote echo through
 * [BufferedPtyBridge] → TerminalPane drain → emulator.append → invalidate.
 * These cases pin two local mechanisms that turn "steady typing" into
 * "silence then a burst" without needing a real sshd or Gboard.
 */
class InputDisplayBurstLatencyTest {

    private lateinit var adapterScope: CoroutineScope
    private lateinit var transport: FakeTransport
    private lateinit var session: SshSession
    private lateinit var bridge: BufferedPtyBridge

    @Before
    fun setUp() {
        adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        transport = FakeTransport()
        session = SshSession(transport = transport, onClose = {})
        bridge = BufferedPtyBridge()
        SshBridgeAdapter(session, bridge).start(adapterScope)
    }

    @After
    fun tearDown() {
        adapterScope.cancel()
        bridge.close()
        session.close(userInitiated = true)
    }

    /**
     * Pre-fix pathology probe (not the production pump): if append cost
     * runs on the same thread that drains the bridge, echo chunks sit behind
     * the flood (HOL) and then surface in one burst — the device symptom.
     * Post-fix behaviour is pinned by [TerminalInboundLoopTest].
     */
    @Test(timeout = 15_000)
    fun inbound_flood_delays_echo_until_backlog_drains_then_burst() = runBlocking {
        val floodChunks = 8
        val slowAppendMs = 40L
        val echoMarker = byteArrayOf(0x45, 0x43, 0x48, 0x4F) // "ECHO"

        repeat(floodChunks) { i ->
            // ~2 KiB fake TUI redraw per chunk (cursor-agent scale, not full frame).
            val redraw = ByteArray(2048) { ((i + it) % 26 + 'a'.code).toByte() }
            bridge.transport.write(redraw)
        }
        bridge.transport.write(echoMarker)

        val echoObservedAt = AtomicLong(-1L)
        val t0 = System.nanoTime()
        val invalidateTimes = mutableListOf<Long>()
        val refreshSignal = Channel<Unit>(Channel.CONFLATED)

        val painter = launch(Dispatchers.Default) {
            for (signal in refreshSignal) {
                invalidateTimes += (System.nanoTime() - t0) / 1_000_000L
            }
        }

        // Pre-fix model: append cost on the drain thread (HOL).
        var remaining = floodChunks + 1
        while (remaining > 0) {
            val bytes = withContext(Dispatchers.IO) { bridge.view.read() } ?: break
            delay(slowAppendMs)
            if (bytes.contentEquals(echoMarker)) {
                echoObservedAt.set((System.nanoTime() - t0) / 1_000_000L)
            }
            refreshSignal.trySend(Unit)
            remaining--
        }
        refreshSignal.close()
        painter.join()

        val echoMs = echoObservedAt.get()
        val minExpectedMs = floodChunks * slowAppendMs
        assertTrue(
            "echo observed at ${echoMs}ms; must sit behind $floodChunks slow " +
                "chunks (≥${minExpectedMs}ms) — inbound HOL blocking",
            echoMs >= minExpectedMs - 20,
        )
        // After the flood, paints collapse into a short window (burst), not
        // one-per-keystroke spacing across seconds.
        assertTrue(
            "expected at least one invalidate after drain, got ${invalidateTimes.size}",
            invalidateTimes.isNotEmpty(),
        )
    }

    /**
     * While writeExecutor is stalled (TCP window / sshj lock under a busy
     * TUI), physical keystrokes enqueue locally. When the stall lifts they
     * hit the wire in a tight burst — remote echoes them together → screen
     * burst. Pins the write-side half of the same user-visible symptom.
     */
    @Test(timeout = 10_000)
    fun write_stall_then_release_delivers_keystrokes_in_a_burst() = runBlocking {
        val stallMs = 200L
        val keyCount = 5
        val gate = CountDownLatch(1)
        val firstEntered = AtomicBoolean(false)
        val arrivalGapsMs = mutableListOf<Long>()
        var lastArrivalNs = 0L

        transport.beforeWrite = {
            if (firstEntered.compareAndSet(false, true)) {
                gate.await(3, TimeUnit.SECONDS)
            }
            val now = System.nanoTime()
            if (lastArrivalNs != 0L) {
                arrivalGapsMs += (now - lastArrivalNs) / 1_000_000L
            }
            lastArrivalNs = now
        }

        // First write enters and blocks; subsequent keys pile on writeExecutor.
        bridge.view.write(byteArrayOf('0'.code.toByte()))
        awaitTrue("first write entered") { firstEntered.get() }

        repeat(keyCount) { i ->
            bridge.view.write(byteArrayOf(('1'.code + i).toByte()))
        }

        delay(stallMs)
        gate.countDown()

        awaitTrue("all keystrokes reached transport") {
            transport.writeCallCount >= keyCount + 1
        }
        transport.beforeWrite = null
        session.awaitWriteQueueDrained()

        // After release, arrivals are clustered (each gap ≪ stall), i.e. a burst.
        assertTrue("need gaps between ${keyCount + 1} writes", arrivalGapsMs.size >= keyCount)
        val maxGapAfterRelease = arrivalGapsMs.maxOrNull() ?: error("no gaps")
        assertTrue(
            "post-release gaps=$arrivalGapsMs — must be a burst (max gap ≪ stall $stallMs)",
            maxGapAfterRelease < stallMs / 2,
        )
    }

    /**
     * Delegates to [com.apexplow.hanterm.ui.TerminalInboundLoop] semantics via
     * dedicated threads — kept here as a cross-check; primary pin is
     * [com.apexplow.hanterm.ui.TerminalInboundLoopTest].
     */
    @Test(timeout = 15_000)
    fun io_append_flood_leaves_main_dispatcher_free_for_key_delivery() = runBlocking {
        val floodChunks = 8
        val slowAppendMs = 40L
        val mainTicks = AtomicInteger(0)
        val mainTarget = 40

        val mainDispatcher: ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { Thread(it, "burst-fake-main") }
                .asCoroutineDispatcher()
        val ioDispatcher: ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { Thread(it, "burst-fake-io") }
                .asCoroutineDispatcher()
        try {
            val mainJob = launch(mainDispatcher) {
                while (mainTicks.get() < mainTarget && isActive) {
                    mainTicks.incrementAndGet()
                    delay(5)
                }
            }

            val refreshSignal = Channel<Unit>(Channel.CONFLATED)
            val painter = launch(mainDispatcher) {
                for (signal in refreshSignal) { /* paint only */ }
            }

            val eof = Any()
            val inbound = java.util.concurrent.LinkedBlockingQueue<Any>()
            repeat(floodChunks) { inbound.put(byteArrayOf(1)) }
            inbound.put(eof)

            com.apexplow.hanterm.ui.TerminalInboundLoop.run(
                read = {
                    when (val item = inbound.take()) {
                        eof -> null
                        else -> item as ByteArray
                    }
                },
                applyChunk = {
                    Thread.sleep(slowAppendMs)
                    true
                },
                onDisplayUpdated = { /* per-chunk Main hop */ },
                refreshSignal = refreshSignal,
                ioDispatcher = ioDispatcher,
                mainDispatcher = mainDispatcher,
            )
            refreshSignal.close()
            painter.join()

            val ticksAtFloodEnd = mainTicks.get()
            mainJob.cancel()
            assertTrue(
                "Main stand-in only reached $ticksAtFloodEnd/$mainTarget ticks during " +
                    "IO append flood — KeyEvent delivery would still be stalled",
                ticksAtFloodEnd >= mainTarget,
            )
        } finally {
            mainDispatcher.close()
            ioDispatcher.close()
        }
    }

    private suspend fun awaitTrue(
        description: String,
        timeoutMs: Long = 5_000,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(25)
        }
        assertTrue(description, predicate())
    }
}
