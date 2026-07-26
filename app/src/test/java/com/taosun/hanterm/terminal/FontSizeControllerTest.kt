package com.taosun.hanterm.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FontSizeController] (Issue #41). The controller is a
 * process-singleton `object`; these tests pin the public contract:
 *
 *  1. `requestSizeChange(absolute)` ultimately delivers the **latest** value
 *     to a fresh collector.
 *  2. The producer never blocks — a burst of `tryEmit` calls does not stall
 *     the calling thread (verified by the burst size vs. received count:
 *     the contract is that the most recent value is delivered, not every
 *     intermediate one).
 *
 * The SharedFlow is configured `replay=0, extraBufferCapacity=1, DROP_OLDEST`
 * for absolute-value + latest-wins semantics. A single collector picks up
 * the most recent pending value; older values are coalesced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FontSizeControllerTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun requestSizeChange_latestAbsoluteSizeIsDelivered() = runTest(dispatcher) {
        val received = mutableListOf<Int>()
        val job = startCollector(this, received)
        advanceUntilIdle()

        FontSizeController.requestSizeChange(16)
        advanceUntilIdle()
        assertEquals(listOf(16), received)

        FontSizeController.requestSizeChange(18)
        advanceUntilIdle()
        assertEquals(listOf(16, 18), received)

        job.cancel()
    }

    @Test
    fun rapidBurst_producerNeverBlocksAndLatestWins() = runTest(dispatcher) {
        val received = mutableListOf<Int>()
        val job = startCollector(this, received)
        advanceUntilIdle()

        // 200 back-to-back tryEmits. With DROP_OLDEST and a 1-slot buffer,
        // most intermediate values are coalesced; the contract is that the
        // producer never blocks and the latest value (199) is delivered.
        repeat(200) { FontSizeController.requestSizeChange(it) }
        advanceUntilIdle()

        job.cancel()
        // The last emitted value must be 199 (proves latest-wins).
        assertEquals(199, received.last())
        // The producer never blocked. We do not pin the exact received
        // count — only that the burst completed without throwing and the
        // collector saw at least one value.
        assertTrue("collector should see at least one emission", received.isNotEmpty())
    }

    private fun startCollector(scope: CoroutineScope, sink: MutableList<Int>) = scope.launch {
        FontSizeController.sizeRequests.collect { sink.add(it) }
    }
}
