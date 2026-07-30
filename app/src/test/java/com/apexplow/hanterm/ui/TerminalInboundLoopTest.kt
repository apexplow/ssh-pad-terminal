package com.apexplow.hanterm.ui

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Direct acceptance of the TerminalPane IO/Main split (review follow-up):
 * [TerminalInboundLoop] must append on the IO dispatcher, call
 * [TerminalInboundLoop.run]'s `onDisplayUpdated` once per chunk on Main
 * (TV-IME-04 must not ride CONFLATED paint), and only signal paint via the
 * CONFLATED channel.
 */
class TerminalInboundLoopTest {

    private lateinit var ioDispatcher: ExecutorCoroutineDispatcher
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private lateinit var ioThread: Thread
    private lateinit var mainThread: Thread

    @Before
    fun setUp() {
        ioDispatcher = Executors.newSingleThreadExecutor { Thread(it, "til-io") }
            .asCoroutineDispatcher()
        mainDispatcher = Executors.newSingleThreadExecutor { Thread(it, "til-main") }
            .asCoroutineDispatcher()
        runBlocking {
            withContext(ioDispatcher) {
                ioThread = Thread.currentThread()
            }
            withContext(mainDispatcher) {
                mainThread = Thread.currentThread()
            }
        }
    }

    @After
    fun tearDown() {
        ioDispatcher.close()
        mainDispatcher.close()
    }

    @Test(timeout = 10_000)
    fun append_runs_on_io_displayUpdated_per_chunk_on_main_paint_conflated() = runBlocking {
        val chunkCount = 6
        val eof = Any()
        val inbound = LinkedBlockingQueue<Any>()
        repeat(chunkCount) { i ->
            inbound.put(byteArrayOf(i.toByte()))
        }
        inbound.put(eof)

        val appendThreads = ConcurrentLinkedQueue<Thread>()
        val displayUpdatedThreads = ConcurrentLinkedQueue<Thread>()
        val displayUpdatedCount = AtomicInteger(0)
        val paintCount = AtomicInteger(0)

        val refreshSignal = Channel<Unit>(Channel.CONFLATED)
        val painter = launch(mainDispatcher) {
            for (signal in refreshSignal) {
                paintCount.incrementAndGet()
            }
        }

        TerminalInboundLoop.run(
            read = {
                when (val item = inbound.take()) {
                    eof -> null
                    else -> item as ByteArray
                }
            },
            applyChunk = { bytes ->
                appendThreads.add(Thread.currentThread())
                // Simulate non-trivial emulator.append cost on IO.
                Thread.sleep(15)
                bytes.isNotEmpty()
            },
            onDisplayUpdated = {
                displayUpdatedThreads.add(Thread.currentThread())
                displayUpdatedCount.incrementAndGet()
            },
            refreshSignal = refreshSignal,
            ioDispatcher = ioDispatcher,
            mainDispatcher = mainDispatcher,
        )
        refreshSignal.close()
        painter.join()

        assertEquals(
            "TV-IME-04: onDisplayUpdated must run once per inbound chunk",
            chunkCount,
            displayUpdatedCount.get(),
        )
        assertTrue(
            "every append must run on the IO dispatcher thread",
            appendThreads.isNotEmpty() && appendThreads.all { it === ioThread },
        )
        assertTrue(
            "every onDisplayUpdated must run on the Main stand-in thread",
            displayUpdatedThreads.isNotEmpty() && displayUpdatedThreads.all { it === mainThread },
        )
        // CONFLATED paint: strictly fewer (or equal) paints than chunks under load.
        assertTrue(
            "paint signals are CONFLATED — got ${paintCount.get()} paints for $chunkCount chunks",
            paintCount.get() in 1..chunkCount,
        )
    }

    @Test(timeout = 10_000)
    fun io_append_cost_does_not_stall_main_key_delivery() = runBlocking {
        val chunkCount = 8
        val eof = Any()
        val inbound = LinkedBlockingQueue<Any>()
        repeat(chunkCount) { inbound.put(byteArrayOf(1)) }
        inbound.put(eof)

        val mainTicks = AtomicInteger(0)
        val mainTarget = 30
        val mainJob = launch(mainDispatcher) {
            while (mainTicks.get() < mainTarget && isActive) {
                mainTicks.incrementAndGet()
                delay(5)
            }
        }

        val refreshSignal = Channel<Unit>(Channel.CONFLATED)
        val painter = launch(mainDispatcher) {
            for (signal in refreshSignal) { /* paint */ }
        }

        TerminalInboundLoop.run(
            read = {
                when (val item = inbound.take()) {
                    eof -> null
                    else -> item as ByteArray
                }
            },
            applyChunk = {
                Thread.sleep(40) // append on IO
                true
            },
            onDisplayUpdated = { /* cheap Main hop */ },
            refreshSignal = refreshSignal,
            ioDispatcher = ioDispatcher,
            mainDispatcher = mainDispatcher,
        )
        refreshSignal.close()
        painter.join()

        val ticks = mainTicks.get()
        mainJob.cancel()
        assertTrue(
            "Main stand-in reached $ticks/$mainTarget during IO append flood",
            ticks >= mainTarget,
        )
    }
}
