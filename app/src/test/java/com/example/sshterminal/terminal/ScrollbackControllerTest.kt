package com.example.sshterminal.terminal

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.termux.terminal.TerminalEmulator
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun onTouchEvent_singleFingerActionDown_returnsPassThrough() {
        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = mockk(relaxed = true),
            fontLineSpacing = { 16f },
        )

        // pointerCount=1 with ACTION_DOWN — single-finger entry. Must NOT
        // hijack the gesture (the alt-buffer guard inside Termux and
        // long-press selection are the single-finger path).
        val ev = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        try {
            val decision = controller.onTouchEvent(ev)
            assertEquals(ScrollbackController.TouchDecision.PassThrough, decision)
        } finally {
            ev.recycle()
        }
    }

    @Test
    fun onTouchEvent_twoFingerActionPointerDown_setsIsInScrollbackTrue() {
        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = mockk(relaxed = true),
            fontLineSpacing = { 16f },
        )

        // Build a real MotionEvent with pointerCount=2. The constructor
        // is fiddly; this is the shape `dispatchTouchEvent` produces when
        // a second finger lands.
        val downTime = SystemClock.uptimeMillis()
        val eventTime = downTime
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 10f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 10f; pressure = 1f; size = 1f },
        )
        val ev = MotionEvent.obtain(
            downTime, eventTime,
            MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            val decision = controller.onTouchEvent(ev)
            assertEquals(ScrollbackController.TouchDecision.Consumed, decision)
            assertTrue(controller.state.value.isInScrollback)
        } finally {
            ev.recycle()
        }
    }
}
