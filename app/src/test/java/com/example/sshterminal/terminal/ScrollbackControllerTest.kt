package com.example.sshterminal.terminal

import android.view.View
import com.termux.terminal.TerminalEmulator
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-logic tests for [ScrollbackController]. Uses mockk for the
 * [TerminalEmulator] (final class with JNI) so the state machine can be
 * driven without touching the AAR. Robolectric is required for `View`
 * (used by mockk's relaxed mocks); no real MotionEvents are constructed
 * here — see [ScrollbackControllerRobolectricTest] for the
 * MotionEvent.obtain path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScrollbackControllerTest {

    @Test
    fun state_isInScrollbackFalseByDefault() {
        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = mockk(relaxed = true),
            fontLineSpacing = { 16f },
        )

        assertFalse(controller.state.value.isInScrollback)
        assertEquals(0, controller.state.value.pendingOutputCount)
    }
}
