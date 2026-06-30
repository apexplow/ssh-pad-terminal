package com.example.sshterminal.terminal

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView as TermuxTerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [ScrollbackController]. Uses a real [com.example.sshterminal.terminal.TerminalView]
 * (which builds a real [TerminalEmulator] and a real [TermuxTerminalView] inner view) so we can
 * read `termuxView.mTopRow` after a gesture and observe the doScroll side-effect end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScrollbackControllerTest {

    private fun newController(): Triple<TerminalView, TerminalEmulator, ScrollbackController> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
        view.onCreateInputConnection(EditorInfo())
        val emulator = view.termuxView.mEmulator!!
        val controller = ScrollbackController(
            view = view,
            innerView = view.termuxView,
            emulator = emulator,
            fontLineSpacing = { 16f },
        )
        return Triple(view, emulator, controller)
    }

    @Test
    fun state_isInScrollbackFalseByDefault() {
        val (_, _, controller) = newController()
        assertFalse(controller.state.value.isInScrollback)
        assertEquals(0, controller.state.value.pendingOutputCount)
    }

    @Test
    fun onTouchEvent_singleFingerActionDown_returnsPassThrough() {
        val (_, _, controller) = newController()
        val ev = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        try {
            assertEquals(
                ScrollbackController.TouchDecision.PassThrough,
                controller.onTouchEvent(ev),
            )
            assertFalse(controller.state.value.isInScrollback)
        } finally {
            ev.recycle()
        }
    }

    @Test
    fun onTouchEvent_twoFingerActionPointerDown_setsIsInScrollbackTrue() {
        val (_, _, controller) = newController()
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 10f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 10f; pressure = 1f; size = 1f },
        )
        val ev = MotionEvent.obtain(
            downTime, downTime,
            MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            assertEquals(
                ScrollbackController.TouchDecision.Consumed,
                controller.onTouchEvent(ev),
            )
            assertTrue(controller.state.value.isInScrollback)
        } finally {
            ev.recycle()
        }
    }

    @Test
    fun onTouchEvent_twoFingerActionMove_doesNotChangeTopRowYet() {
        // Page-by-page model: the controller does NOT call doScroll on
        // MOVE; it just remembers the final centroid. The actual scroll
        // happens on ACTION_UP. So a sequence of MOVEs without UP leaves
        // mTopRow untouched.
        val (view, _, controller) = newController()
        val topRowField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mTopRow")
            .apply { isAccessible = true }
        val initialTopRow = topRowField.getInt(view.termuxView)

        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 100f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 100f; pressure = 1f; size = 1f },
        )
        val coords1 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 50f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 50f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evMove = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE,
            2, props, coords1,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evMove)
            assertEquals(
                "MOVE alone must not yet call doScroll — page scroll happens on ACTION_UP",
                initialTopRow, topRowField.getInt(view.termuxView),
            )
        } finally {
            evDown.recycle()
            evMove.recycle()
        }
    }
}
