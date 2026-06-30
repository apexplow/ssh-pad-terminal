package com.example.sshterminal.terminal

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.termux.terminal.TerminalEmulator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-logic tests for [ScrollbackController]. Most cases mockk the
 * [TerminalEmulator] (final class with JNI) so the state machine can be
 * driven without touching the AAR. The scroll-math cases (Task 3) use a
 * real [TerminalView] + real [com.termux.terminal.TerminalEmulator]
 * because mockk cannot easily intercept the public-Java-field setter on
 * the emulator — using the real emulator lets us read `mTopRow` directly.
 * Companion [ScrollbackControllerRobolectricTest] covers the end-to-end
 * MotionEvent path on a real TerminalView.
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

    @Test
    fun onTouchEvent_twoFingerMoveUp_increasesTopRow() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
        view.onCreateInputConnection(android.view.inputmethod.EditorInfo())
        val emulator = view.termuxView.mEmulator!!
        // Pin the relevant mTotalRows/mRows for the test (the real emulator
        // has them set by its constructor; we just need to know what they are).
        emulator.mTopRow = 0

        val controller = ScrollbackController(
            view = view,
            emulator = emulator,
            fontLineSpacing = { 16f }, // 16 px per row
        )

        // Frame 1: enter scrollback at y=100
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 100f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 100f; pressure = 1f; size = 1f },
        )
        val ev0 = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )

        // Frame 2: move UP 32px (y went from 100 to 68 → deltaY = -32 → 2 rows up)
        val coords1 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 68f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 68f; pressure = 1f; size = 1f },
        )
        val ev1 = MotionEvent.obtain(
            0L, 16L, MotionEvent.ACTION_MOVE,
            2, props, coords1,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            controller.onTouchEvent(ev0)
            controller.onTouchEvent(ev1)

            // Initial mTopRow=0; moved up 32px (2 rows); expect mTopRow=2.
            assertEquals(2, emulator.mTopRow)
        } finally {
            ev0.recycle()
            ev1.recycle()
        }
    }

    @Test
    fun onTouchEvent_twoFingerMoveDown_clampsAtZero() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
        view.onCreateInputConnection(android.view.inputmethod.EditorInfo())
        val emulator = view.termuxView.mEmulator!!
        emulator.mTopRow = 0

        val controller = ScrollbackController(
            view = view,
            emulator = emulator,
            fontLineSpacing = { 16f },
        )

        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 100f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 100f; pressure = 1f; size = 1f },
        )
        val coords1 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val ev0 = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val ev1 = MotionEvent.obtain(
            0L, 16L, MotionEvent.ACTION_MOVE,
            2, props, coords1,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            controller.onTouchEvent(ev0)
            controller.onTouchEvent(ev1)

            // deltaY = +100 (down); -deltaY/fontLineSpacing = -100/16 = -6.25 → -6 rows.
            // Toprow was 0, minus 6 clamps to 0. Verify the final write was 0.
            assertEquals(0, emulator.mTopRow)
        } finally {
            ev0.recycle()
            ev1.recycle()
        }
    }

    @Test
    fun onTouchEvent_twoFingerMoveUp_clampsAtMaxScroll() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
        view.onCreateInputConnection(android.view.inputmethod.EditorInfo())
        val emulator = view.termuxView.mEmulator!!
        emulator.mTopRow = 0
        // For a strict clamp test we need mTotalRows - mRows to be a known
        // small value. The real emulator's transcriptRows default is too
        // large; bump the scrollback buffer via resize so the clamp is
        // tight enough to test against.
        // (80 cols, 24 rows visible, transcript = 1000, total = 1024.)
        // 1024 - 24 = 1000. Move up WAY more than 1000 rows → expect 1000.

        val controller = ScrollbackController(
            view = view,
            emulator = emulator,
            fontLineSpacing = { 16f },
        )

        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 1000f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 1000f; pressure = 1f; size = 1f },
        )
        // deltaY = -11000 → 687 rows. Real max scrollback is huge, so just
        // assert we wrote SOMETHING ≥ what the no-clamp math would produce.
        val coordsBigUp = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = -10000f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = -10000f; pressure = 1f; size = 1f },
        )
        val ev0 = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val ev1 = MotionEvent.obtain(
            0L, 16L, MotionEvent.ACTION_MOVE,
            2, props, coordsBigUp,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            controller.onTouchEvent(ev0)
            controller.onTouchEvent(ev1)

            // mTopRow must equal mTotalRows - mRows (the clamped max). The
            // exact value depends on the real emulator's transcriptRows
            // default, so derive it the same way the controller does.
            val maxScroll = (emulator.mTotalRows - emulator.mRows).coerceAtLeast(0)
            assertEquals(
                "scrollback must clamp at mTotalRows - mRows",
                maxScroll, emulator.mTopRow,
            )
        } finally {
            ev0.recycle()
            ev1.recycle()
        }
    }

    @Test
    fun onTouchEvent_fontLineSpacingZero_doesNotTouchTopRow() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
        view.onCreateInputConnection(android.view.inputmethod.EditorInfo())
        val emulator = view.termuxView.mEmulator!!
        emulator.mTopRow = 5

        val controller = ScrollbackController(
            view = view,
            emulator = emulator,
            fontLineSpacing = { 0f }, // pathological — Robolectric renderer w/o font
        )

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
        val ev0 = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val ev1 = MotionEvent.obtain(
            0L, 16L, MotionEvent.ACTION_MOVE,
            2, props, coords1,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            controller.onTouchEvent(ev0)
            controller.onTouchEvent(ev1)

            // No mTopRow write should have happened — fontLineSpacing=0 is
            // the "renderer not ready" path. Same guard as
            // TerminalView.reportPtyResize:583.
            assertEquals(
                "fontLineSpacing=0 must not write mTopRow",
                5, emulator.mTopRow,
            )
        } finally {
            ev0.recycle()
            ev1.recycle()
        }
    }
}
