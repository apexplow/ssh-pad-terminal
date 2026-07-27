package com.taosun.hanterm

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JUnit tests for [TransportAbortSignal] — the marker that replaces
 * CrashHandler's brittle `t.name.startsWith("Reader")` prefix match
 * (Issue #62 / P2).
 *
 * The signal is process-scoped and time-windowed; the tests just exercise
 * the mark / isRecent contract and the [resetForTests] seam so each case
 * starts from a known state.
 */
class TransportAbortSignalTest {

    @After
    fun tearDown() {
        TransportAbortSignal.resetForTests()
    }

    @Test
    fun freshSignal_isNotRecent() {
        TransportAbortSignal.resetForTests()
        assertFalse("fresh signal must not be recent", TransportAbortSignal.isRecent())
    }

    @Test
    fun markMakesSignalRecent() {
        TransportAbortSignal.mark()
        assertTrue("mark() must make isRecent() return true within the window",
            TransportAbortSignal.isRecent())
    }

    @Test
    fun resetClearsSignal() {
        TransportAbortSignal.mark()
        assertTrue(TransportAbortSignal.isRecent())
        TransportAbortSignal.resetForTests()
        assertFalse("resetForTests must clear the marker",
            TransportAbortSignal.isRecent())
    }

    @Test
    fun multipleMarks_idempotent() {
        // Calling mark() twice in rapid succession should keep the
        // signal recent (the second call resets the timestamp).
        TransportAbortSignal.mark()
        TransportAbortSignal.mark()
        assertTrue(TransportAbortSignal.isRecent())
    }
}