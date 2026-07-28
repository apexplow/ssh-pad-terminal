package com.apexplow.hanterm.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [UiMessageBridge] (Issue #41). The bridge is a
 * process-singleton `object`; these tests pin the public contract:
 *
 *  1. `showMessage` delivers each message to an active collector in order.
 *  2. A burst of `showMessage` calls delivers only the most recent pending
 *     value to a consumer that is not actively draining (the SharedFlow's
 *     `extraBufferCapacity=1, DROP_OLDEST` semantics — same as
 *     [com.apexplow.hanterm.terminal.FontSizeController]).
 *
 * Each test starts a fresh collector on a fresh `MutableSharedFlow` and
 * bridges from the singleton's `messageEvents` into the test flow; this
 * isolates the test from the singleton's state across runs. The bridging
 * job and the test collector are both cancelled before the test returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UiMessageBridgeTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun showMessage_deliversToActiveCollector() = runTest(dispatcher) {
        val (sink, teardown) = startBridgedCollector(this)
        advanceUntilIdle()

        try {
            UiMessageBridge.showMessage("hello")
            advanceUntilIdle()
            assertEquals(listOf("hello"), sink)

            UiMessageBridge.showMessage("world")
            advanceUntilIdle()
            assertEquals(listOf("hello", "world"), sink)
        } finally {
            teardown()
        }
    }

    @Test
    fun showMessage_latestWinsOnBurst() = runTest(dispatcher) {
        val (sink, teardown) = startBridgedCollector(this)
        advanceUntilIdle()

        try {
            // With a single-slot buffer + DROP_OLDEST, the most recent
            // value always wins.
            repeat(10) { UiMessageBridge.showMessage("m$it") }
            advanceUntilIdle()

            assertEquals("m9", sink.last())
            assertTrue("at least one message delivered", sink.isNotEmpty())
        } finally {
            teardown()
        }
    }

    /**
     * Returns a sink list that receives all messages emitted by the
     * singleton, plus a `teardown` lambda that cancels both bridging
     * coroutines. The bridge is implemented as a private `MutableSharedFlow`
     * so that the test is fully isolated from prior test runs and from
     * production observers.
     */
    private fun startBridgedCollector(
        scope: CoroutineScope,
    ): Pair<MutableList<String>, () -> Unit> {
        val sink = mutableListOf<String>()
        val testFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
        val consumer = scope.launch { testFlow.collect { sink.add(it) } }
        val bridge = scope.launch {
            UiMessageBridge.messageEvents.collect { testFlow.tryEmit(it) }
        }
        return sink to {
            consumer.cancel()
            bridge.cancel()
        }
    }
}
