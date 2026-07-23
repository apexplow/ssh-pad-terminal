package com.taosun.hanterm.ssh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Primary seam for the [KeepAliveNudgeRegistry] binding (Issue #17).
 *
 * Pure JUnit — no Robolectric, no Android, no mockk. The registry is a
 * process-wide singleton (the FGS process and the runtime are co-located,
 * and there is at most one live transport at a time), so the unit tests
 * are responsible for clearing it in `@After` to prevent leakage across
 * test classes — mirrors the `AppLogTest` pattern for `AppLog.policy`.
 */
class KeepAliveNudgeRegistryTest {

    @After
    fun tearDown() {
        // Defensive clear: any test that leaves a bound nudge behind would
        // poison the next test class. The order in which JUnit runs
        // test classes is unspecified.
        KeepAliveNudgeRegistry.set(null)
    }

    @Test
    fun get_emptyRegistry_returnsNull() {
        // Hand-clear before the assertion because an earlier test class
        // (running in the same JVM) may have left a binding.
        KeepAliveNudgeRegistry.set(null)
        assertNull(KeepAliveNudgeRegistry.get())
    }

    @Test
    fun set_thenGet_returnsSameInstance() {
        val nudge = KeepAliveNudge { true }
        KeepAliveNudgeRegistry.set(nudge)
        assertSame(nudge, KeepAliveNudgeRegistry.get())
    }

    @Test
    fun set_clear_thenGet_returnsNull() {
        KeepAliveNudgeRegistry.set(KeepAliveNudge { true })
        KeepAliveNudgeRegistry.set(null)
        assertNull(KeepAliveNudgeRegistry.get())
    }

    @Test
    fun set_null_isEquivalentToClear() {
        KeepAliveNudgeRegistry.set(KeepAliveNudge { true })
        // Explicit `set(null)` and a subsequent `set(null)` must both
        // end in the empty state; the second call is a safe no-op.
        KeepAliveNudgeRegistry.set(null)
        KeepAliveNudgeRegistry.set(null)
        assertNull(KeepAliveNudgeRegistry.get())
    }

    @Test
    fun set_replacesPreviousBinding_lastWriterWins() {
        val first = KeepAliveNudge { true }
        val second = KeepAliveNudge { false }
        KeepAliveNudgeRegistry.set(first)
        KeepAliveNudgeRegistry.set(second)
        assertSame(
            "A second `set` overwrites the first binding (no merging)",
            second,
            KeepAliveNudgeRegistry.get(),
        )
    }

    @Test
    fun concurrent_setClear_isThreadSafe() {
        // N threads hammer set/clear; assert no exception + the final
        // state is one of the two valid slots (null or a non-null
        // nudge). The internal AtomicReference gives us happens-before;
        // we just confirm there is no torn read or thrown exception.
        val threadCount = 16
        val iterations = 1_000
        val ready = CountDownLatch(threadCount)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val fake = KeepAliveNudge { true }
        repeat(threadCount) { i ->
            Thread {
                ready.countDown()
                go.await()
                repeat(iterations) { j ->
                    if ((i + j) % 2 == 0) {
                        KeepAliveNudgeRegistry.set(fake)
                    } else {
                        KeepAliveNudgeRegistry.set(null)
                    }
                }
                done.countDown()
            }.start()
        }
        assertEquals(true, ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        assertEquals(true, done.await(15, TimeUnit.SECONDS))

        val finalState = KeepAliveNudgeRegistry.get()
        // Either the real one (we just set it) or null — both are valid.
        // The only failure mode would be a torn read or thrown exception,
        // which the latch-timeout + class-level test pass would catch.
        if (finalState != null) {
            assertSame(fake, finalState)
        }
    }
}
