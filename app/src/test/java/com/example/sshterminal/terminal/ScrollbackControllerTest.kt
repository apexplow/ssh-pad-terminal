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
            assertEquals(
                ScrollbackController.SCROLL_GESTURE_HINT,
                controller.state.value.gestureHint,
            )
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

    @Test
    fun onTouchEvent_pageUp_callsDoScrollWithNegativeRows() {
        val (view, emulator, controller) = newController()
        val topRowField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mTopRow")
            .apply { isAccessible = true }
        // Populate scrollback so doScroll's branch-3 clamp at
        // -getActiveTranscriptRows() doesn't pin mTopRow at 0.
        // 4 pages of CRLFs into the emulator builds enough rows.
        val scrollbackFiller = "\r\n".repeat(emulator.mRows * 4).toByteArray()
        emulator.append(scrollbackFiller, scrollbackFiller.size)
        val initialTopRow = topRowField.getInt(view.termuxView)
        val pageSize = view.termuxView.mEmulator!!.mRows

        // Two-finger POINTER_DOWN at y=200, MOVE to y=0 (huge upward swipe).
        // dy = 0 - 200 = -200. Threshold = 16 * 24 / 2 = 192. dy=-200 < -192 → page up.
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val coordsUp = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 0f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 0f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evMove = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE,
            2, props, coordsUp,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evUp = MotionEvent.obtain(
            downTime, downTime + 32L, MotionEvent.ACTION_UP, 10f, 0f, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evMove)
            controller.onTouchEvent(evUp)
            assertEquals(
                "page-up must scroll mTopRow back by one page (Termux stores scrollback as a non-positive offset)",
                initialTopRow - pageSize, topRowField.getInt(view.termuxView),
            )
            assertEquals("↑ 已向上翻一页", controller.state.value.gestureHint)
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_singleFingerPageUp_callsDoScrollWithNegativeRows() {
        val (view, emulator, controller) = newController()
        val topRowField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mTopRow")
            .apply { isAccessible = true }
        val scrollbackFiller = "\r\n".repeat(emulator.mRows * 4).toByteArray()
        emulator.append(scrollbackFiller, scrollbackFiller.size)
        val initialTopRow = topRowField.getInt(view.termuxView)
        val pageSize = emulator.mRows

        val downTime = SystemClock.uptimeMillis()
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, 10f, 200f, 0,
        )
        val evMove = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE, 10f, 0f, 0,
        )
        val evUp = MotionEvent.obtain(
            downTime, downTime + 32L, MotionEvent.ACTION_UP, 10f, 0f, 0,
        )
        try {
            assertEquals(
                ScrollbackController.TouchDecision.PassThrough,
                controller.onTouchEvent(evDown),
            )
            assertEquals(
                ScrollbackController.TouchDecision.Consumed,
                controller.onTouchEvent(evMove),
            )
            controller.onTouchEvent(evUp)
            assertEquals(
                "single-finger page-up must scroll mTopRow back one page",
                initialTopRow - pageSize, topRowField.getInt(view.termuxView),
            )
            assertEquals("↑ 已向上翻一页", controller.state.value.gestureHint)
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_singleFingerMoveBeyondSlop_consumesWithoutLongPressPath() {
        val (_, _, controller) = newController()
        val downTime = SystemClock.uptimeMillis()
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, 10f, 200f, 0,
        )
        val evMove = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE, 10f, 150f, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            assertEquals(
                ScrollbackController.TouchDecision.Consumed,
                controller.onTouchEvent(evMove),
            )
            assertTrue(controller.state.value.isInScrollback)
        } finally {
            evDown.recycle()
            evMove.recycle()
        }
    }

    @Test
    fun onTouchEvent_upWithoutMove_setsIncompleteGestureHint() {
        val (_, _, controller) = newController()
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evUp = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_UP, 10f, 200f, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evUp)
            assertEquals(
                "需滑动后再抬起（不能只点按）",
                controller.state.value.gestureHint,
            )
        } finally {
            evDown.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_subThresholdSwipe_setsGestureHint() {
        val (view, emulator, controller) = newController()
        val topRowField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mTopRow")
            .apply { isAccessible = true }
        val scrollbackFiller = "\r\n".repeat(emulator.mRows * 4).toByteArray()
        emulator.append(scrollbackFiller, scrollbackFiller.size)
        val initialTopRow = topRowField.getInt(view.termuxView)

        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val coordsSmall = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 190f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 190f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evMove = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE,
            2, props, coordsSmall,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evUp = MotionEvent.obtain(
            downTime, downTime + 32L, MotionEvent.ACTION_UP, 10f, 190f, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evMove)
            controller.onTouchEvent(evUp)
            assertEquals(initialTopRow, topRowField.getInt(view.termuxView))
            assertEquals(
                "滑动距离不够（需超过半屏）",
                controller.state.value.gestureHint,
            )
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_pageDown_callsDoScrollWithPositiveRows() {
        val (view, emulator, controller) = newController()
        val topRowField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mTopRow")
            .apply { isAccessible = true }
        // Populate scrollback so the inner view's clamp allows mTopRow
        // to step toward 0 (live view).
        val scrollbackFiller = "\r\n".repeat(emulator.mRows * 4).toByteArray()
        emulator.append(scrollbackFiller, scrollbackFiller.size)
        // Termux: mTopRow is non-positive — negative means scrolled back.
        // Two pages up: mTopRow = -mRows*2.
        val pageSize = view.termuxView.mEmulator!!.mRows
        topRowField.setInt(view.termuxView, -pageSize * 2)
        val before = topRowField.getInt(view.termuxView)

        // Swipe DOWN: y=200 to y=400 (dy=+200, threshold=192 → triggers).
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val coordsDown = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 400f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 400f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evMove = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE,
            2, props, coordsDown,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evUp = MotionEvent.obtain(
            downTime, downTime + 32L, MotionEvent.ACTION_UP, 10f, 400f, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evMove)
            controller.onTouchEvent(evUp)
            assertEquals(
                "page-down must advance mTopRow one page toward 0 (less negative)",
                before + pageSize, topRowField.getInt(view.termuxView),
            )
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_shortSwipe_isNoOp() {
        val (view, _, controller) = newController()
        val topRowField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mTopRow")
            .apply { isAccessible = true }
        val initialTopRow = topRowField.getInt(view.termuxView)

        // Swipe dy=10px — well under the threshold (192).
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val coordsSlight = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 190f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 190f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evMove = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE,
            2, props, coordsSlight,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evUp = MotionEvent.obtain(
            downTime, downTime + 32L, MotionEvent.ACTION_UP, 10f, 190f, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evMove)
            controller.onTouchEvent(evUp)
            assertEquals(
                "swipe below threshold must not change mTopRow",
                initialTopRow, topRowField.getInt(view.termuxView),
            )
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_pageDownToZero_autoExitsScrollback() {
        val (view, emulator, controller) = newController()
        val topRowField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mTopRow")
            .apply { isAccessible = true }
        // Populate scrollback so pageDown from mTopRow=-mRows can land on 0.
        val scrollbackFiller = "\r\n".repeat(emulator.mRows * 4).toByteArray()
        emulator.append(scrollbackFiller, scrollbackFiller.size)
        // Termux convention: mTopRow is non-positive. One page up: -mRows.
        val pageSize = view.termuxView.mEmulator!!.mRows
        topRowField.setInt(view.termuxView, -pageSize)

        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val coordsDown = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 400f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 400f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evMove = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE,
            2, props, coordsDown,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evUp = MotionEvent.obtain(
            downTime, downTime + 32L, MotionEvent.ACTION_UP, 10f, 400f, 0,
        )
        try {
            // POINTER_DOWN arms the gesture (sets isInScrollback=true).
            controller.onTouchEvent(evDown)
            // Page-down by one page: -mRows + mRows = 0.
            controller.onTouchEvent(evMove)
            controller.onTouchEvent(evUp)
            assertEquals(0, topRowField.getInt(view.termuxView))
            assertFalse(
                "page-down to mTopRow=0 must auto-exit scrollback",
                controller.state.value.isInScrollback,
            )
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_inAltBufferMode_swallowsGestureWithoutDoScroll() {
        // vim/less/htop are in the alt buffer. doScroll would NPE because
        // the inner view's mTermSession is null. The controller must
        // consume the gesture (to prevent the inner view's GestureDetector
        // from NPEing) but NOT call doScroll — the remote TUI owns
        // scrolling in this mode.
        val (view, emulator, controller) = newController()
        emulator.doDecSetOrReset(true, 1049) // enter alt buffer

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
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val coordsUp = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 0f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 0f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evMove = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE,
            2, props, coordsUp,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evUp = MotionEvent.obtain(
            downTime, downTime + 32L, MotionEvent.ACTION_UP, 10f, 0f, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evMove)
            controller.onTouchEvent(evUp)
            assertEquals(
                "alt-buffer mode must not call doScroll (avoids the NPE in branch 2)",
                initialTopRow, topRowField.getInt(view.termuxView),
            )
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun scrollToBottom_resetsInnerTopRowAndState() {
        val (view, emulator, controller) = newController()
        // Populate scrollback and page up so there's real distance to jump back.
        val scrollbackFiller = "\r\n".repeat(emulator.mRows * 4).toByteArray()
        emulator.append(scrollbackFiller, scrollbackFiller.size)
        val topRowField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mTopRow")
            .apply { isAccessible = true }
        topRowField.setInt(view.termuxView, emulator.mRows * 2)
        // Simulate that the controller is "in scrollback" by setting the
        // state via a two-finger gesture.
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 10f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 10f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            controller.onTouchEvent(evDown)
        } finally {
            evDown.recycle()
        }
        assertTrue(controller.state.value.isInScrollback)

        controller.scrollToBottom()

        assertEquals(0, topRowField.getInt(view.termuxView))
        assertFalse(controller.state.value.isInScrollback)
        assertEquals(0, controller.state.value.pendingOutputCount)
    }

    @Test
    fun scrollToBottom_whenAlreadyAtZero_isNoOp() {
        val (view, _, controller) = newController()
        val topRowField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mTopRow")
            .apply { isAccessible = true }
        val initialTopRow = topRowField.getInt(view.termuxView)

        controller.scrollToBottom()

        assertEquals(initialTopRow, topRowField.getInt(view.termuxView))
        assertFalse(controller.state.value.isInScrollback)
    }

    @Test
    fun onTranscriptWrite_eightyBytes_addsOneLine() {
        val (_, _, controller) = newController()
        controller.onTranscriptWrite(byteCount = 80, columns = 80)
        assertEquals(1, controller.state.value.pendingOutputCount)
    }

    @Test
    fun onTranscriptWrite_hundredSixtyBytes_addsTwoLines() {
        val (_, _, controller) = newController()
        controller.onTranscriptWrite(byteCount = 160, columns = 80)
        assertEquals(2, controller.state.value.pendingOutputCount)
    }

    @Test
    fun onTranscriptWrite_partialLine_floorsToOne() {
        val (_, _, controller) = newController()
        controller.onTranscriptWrite(byteCount = 40, columns = 80)
        assertEquals(1, controller.state.value.pendingOutputCount)
    }

    @Test
    fun onTranscriptWrite_accumulatesAcrossCalls() {
        val (_, _, controller) = newController()
        controller.onTranscriptWrite(80, 80)
        controller.onTranscriptWrite(80, 80)
        controller.onTranscriptWrite(40, 80)
        assertEquals(3, controller.state.value.pendingOutputCount)
    }

    @Test
    fun scrollToBottom_resetsPendingCount() {
        val (_, _, controller) = newController()
        controller.onTranscriptWrite(240, 80)
        assertEquals(3, controller.state.value.pendingOutputCount)
        controller.scrollToBottom()
        assertEquals(0, controller.state.value.pendingOutputCount)
    }
}
