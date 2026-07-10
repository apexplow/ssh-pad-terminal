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

    private fun newController(
        sentToRemote: MutableList<ByteArray> = mutableListOf(),
    ): Triple<TerminalView, TerminalEmulator, ScrollbackController> {
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
            sendToRemote = { sentToRemote.add(it) },
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
        // dy = 0 - 200 = -200. Threshold = 16 * 24 / 4 = 96. dy=-200 < -96 → page up.
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
                "page-up must scroll mTopRow back by one page minus one row of overlap (Termux stores scrollback as a non-positive offset)",
                initialTopRow - (pageSize - 1), topRowField.getInt(view.termuxView),
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
                "single-finger page-up must scroll mTopRow back one page minus one row of overlap",
                initialTopRow - (pageSize - 1), topRowField.getInt(view.termuxView),
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
                "滑动距离不够（需超过 1/4 屏）",
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

        // Swipe DOWN: y=200 to y=400 (dy=+200, threshold=96 → triggers).
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
                "page-down must advance mTopRow one page minus one row of overlap toward 0 (less negative)",
                before + (pageSize - 1), topRowField.getInt(view.termuxView),
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

        // Swipe dy=10px — well under the spatial threshold (16 * 24 / 4 = 96).
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
        // Populate scrollback so pageDown from mTopRow=-(mRows-1) can land on 0
        // exactly (the controller's one-row overlap convention means a page
        // request advances by (mRows-1), so we pre-seat one row short).
        val scrollbackFiller = "\r\n".repeat(emulator.mRows * 4).toByteArray()
        emulator.append(scrollbackFiller, scrollbackFiller.size)
        // Termux convention: mTopRow is non-positive. One page up: -(mRows-1).
        val pageSize = view.termuxView.mEmulator!!.mRows
        topRowField.setInt(view.termuxView, -(pageSize - 1))

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
            // Page-down by one page minus one row of overlap: -(mRows-1) + (mRows-1) = 0.
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
    fun onTouchEvent_inAltBufferMode_sendsCursorKeysToRemote() {
        // vim/less/man/tmux-TUI (alt buffer, no mouse tracking): a page swipe
        // must reach the remote as a screenful of cursor-key presses (the
        // xterm alternateScroll behaviour) so scrolling works without any
        // PageUp/PageDown key or tmux config.
        val sent = mutableListOf<ByteArray>()
        val (view, emulator, controller) = newController(sent)
        emulator.doDecSetOrReset(true, 1049) // enter alt buffer

        val topRowField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mTopRow")
            .apply { isAccessible = true }
        val initialTopRow = topRowField.getInt(view.termuxView)

        // Big upward swipe (y=200 → y=0, dy=-200 < -threshold=192) → page up.
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

            // Still no local scrollback mutation — the remote owns the screen.
            assertEquals(
                "alt-buffer mode must not call doScroll (avoids the NPE in branch 2)",
                initialTopRow, topRowField.getInt(view.termuxView),
            )
            // One write, carrying mRows-1 copies of the DPAD_UP escape sequence
            // (one line of overlap kept as context) for the current cursor mode.
            val unit = com.termux.terminal.KeyHandler.getCode(
                android.view.KeyEvent.KEYCODE_DPAD_UP, 0,
                emulator.isCursorKeysApplicationMode,
                emulator.isKeypadApplicationMode,
            )!!
            assertEquals("exactly one batched write to the remote", 1, sent.size)
            assertEquals(
                "must send one screenful (minus a line of overlap) of up-arrows to the remote TUI",
                unit.repeat((emulator.mRows - 1).coerceAtLeast(1)),
                String(sent[0], Charsets.UTF_8),
            )
            // Local scrollback banner state cleared (remote owns the view).
            assertFalse(controller.state.value.isInScrollback)
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_inAltBufferModeWithSgrMouse_sendsSgrWheelUp() {
        // alt-buffer + DECSET 1000 + 1006 (SGR encoding): the swipe must
        // turn into a single SGR mouse-wheel event at the centre of the
        // view, NOT (mRows-1) cursor keys. The TUI (tmux `set -g mouse on`,
        // vim `:set mouse=a`) scrolls its own history on receipt.
        val sent = mutableListOf<ByteArray>()
        val (_, emulator, controller) = newController(sent)
        emulator.doDecSetOrReset(true, 1049) // alt buffer
        emulator.doDecSetOrReset(true, 1000) // press/release tracking
        emulator.doDecSetOrReset(true, 1006) // SGR encoding
        assertTrue(
            "DECSET 1000/1006 must report mouse tracking active",
            emulator.isMouseTrackingActive,
        )

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
            assertEquals("exactly one SGR wheel event", 1, sent.size)
            val expected = "\u001b[<64;${emulator.mColumns / 2};${emulator.mRows / 2}M"
            assertEquals(
                "must send SGR wheel-up (button=64) at view centre, NOT batched cursor keys",
                expected,
                String(sent[0], Charsets.UTF_8),
            )
            assertEquals("↑ 已向上滚动", controller.state.value.gestureHint)
            assertFalse(controller.state.value.isInScrollback)
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_inAltBufferModeWithLegacyMouse_sendsLegacyWheelUp() {
        // alt-buffer + DECSET 1000 only (no 1006 SGR): the swipe must
        // fall back to the legacy xterm mouse encoding — ESC [ M <button+32>
        // <col+32> <row+32> — three bytes after the ESC prefix. This is
        // the path used by very old TUIs or when mouse tracking is enabled
        // before SGR is negotiated.
        val sent = mutableListOf<ByteArray>()
        val (_, emulator, controller) = newController(sent)
        emulator.doDecSetOrReset(true, 1049) // alt buffer
        emulator.doDecSetOrReset(true, 1000) // press/release tracking, NO SGR
        assertTrue(emulator.isMouseTrackingActive)

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
            assertEquals("exactly one legacy wheel event", 1, sent.size)
            // Expected bytes: ESC [ M (button+32) (col+32) (row+32)
            val bytes = sent[0]
            assertEquals("legacy wheel event is 6 bytes", 6, bytes.size)
            assertEquals(0x1B.toByte(), bytes[0])
            assertEquals('['.code.toByte(), bytes[1])
            assertEquals('M'.code.toByte(), bytes[2])
            assertEquals(
                "button byte = MOUSE_WHEELUP_BUTTON(64) + 32 = 96",
                (TerminalEmulator.MOUSE_WHEELUP_BUTTON + 32).toByte(),
                bytes[3],
            )
            assertEquals(
                "col byte = mColumns/2 + 32 (centre)",
                (emulator.mColumns / 2 + 32).toByte(),
                bytes[4],
            )
            assertEquals(
                "row byte = mRows/2 + 32 (centre)",
                (emulator.mRows / 2 + 32).toByte(),
                bytes[5],
            )
            assertEquals("↑ 已向上滚动", controller.state.value.gestureHint)
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_inAltBufferModeWithSgrMouse_swipeDown_sendsWheelDown() {
        // Mirror of the up case — verify the wheel-down button (65) and
        // the down-banner hint.
        val sent = mutableListOf<ByteArray>()
        val (_, emulator, controller) = newController(sent)
        emulator.doDecSetOrReset(true, 1049)
        emulator.doDecSetOrReset(true, 1000)
        emulator.doDecSetOrReset(true, 1006)

        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 50f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 50f; pressure = 1f; size = 1f },
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
            assertEquals(1, sent.size)
            val expected = "\u001b[<65;${emulator.mColumns / 2};${emulator.mRows / 2}M"
            assertEquals(expected, String(sent[0], Charsets.UTF_8))
            assertEquals("↓ 已向下滚动", controller.state.value.gestureHint)
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_inAltBufferModeWithMouseTrackingOff_bannerMentionsFallback() {
        // Regression for the bug report: with mouse tracking OFF, the
        // cursor-key fallback fires AND the banner explicitly tells the
        // user that the keys went to the foreground program — they might
        // see shell history navigate in a tmux pane and not realise why.
        val sent = mutableListOf<ByteArray>()
        val (_, emulator, controller) = newController(sent)
        emulator.doDecSetOrReset(true, 1049)
        // No DECSET 1000/1002/1003.

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
            // Cursor-key fallback path is unchanged.
            assertEquals(1, sent.size)
            assertTrue(
                "banner must mention 远端未启用鼠标模式 so the user understands why a shell pane " +
                    "navigates history instead of scrolling tmux",
                controller.state.value.gestureHint!!.contains("未启用鼠标模式"),
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

    @Test
    fun onTouchEvent_peakDisplacement_callsDoScroll() {
        // User swipes up 200 px, then retreats down to net dy=-50 before
        // lifting. The legacy "final - initial" formula would have refused
        // (50 < threshold). The new peak-displacement path fires the page
        // flip the user clearly intended.
        val (view, emulator, controller) = newController()
        val topRowField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mTopRow").apply { isAccessible = true }
        val scrollbackFiller = "\r\n".repeat(emulator.mRows * 4).toByteArray()
        emulator.append(scrollbackFiller, scrollbackFiller.size)
        val initialTopRow = topRowField.getInt(view.termuxView)
        val pageSize = view.termuxView.mEmulator!!.mRows

        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val coordsPeak = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 0f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 0f; pressure = 1f; size = 1f },
        )
        val coordsRetreat = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 150f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 150f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evMovePeak = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE,
            2, props, coordsPeak,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evMoveRetreat = MotionEvent.obtain(
            downTime, downTime + 32L, MotionEvent.ACTION_MOVE,
            2, props, coordsRetreat,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evUp = MotionEvent.obtain(
            downTime, downTime + 48L, MotionEvent.ACTION_UP, 10f, 150f, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evMovePeak)
            controller.onTouchEvent(evMoveRetreat)
            controller.onTouchEvent(evUp)
            assertEquals(
                "peak displacement must fire page-up even when net dy < threshold",
                initialTopRow - (pageSize - 1),
                topRowField.getInt(view.termuxView),
            )
            assertEquals("↑ 已向上翻一页", controller.state.value.gestureHint)
        } finally {
            evDown.recycle()
            evMovePeak.recycle()
            evMoveRetreat.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_pageUpClampedToRemainingScrollback_clampsAmountAndReportsActualRows() {
        // Sitting N rows from the top of the scrollback leaves only N rows
        // of headroom. The page request must clamp to that residual and
        // the banner must reflect the actual clamped amount (not the
        // pre-fix "一页" lie). We probe active transcript rows via the same
        // reflective handle the controller uses — so the test stays in sync
        // with whatever the bundled Termux AAR actually returns, even if the
        // exact count drifts across Termux versions.
        val (view, emulator, controller) = newController()
        val topRowField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mTopRow").apply { isAccessible = true }
        val scrollbackFiller = "\r\n".repeat(emulator.mRows * 4).toByteArray()
        emulator.append(scrollbackFiller, scrollbackFiller.size)
        val totalRows = readActiveTranscriptRows(emulator)
        assertTrue(
            "reflection on TerminalBuffer.getActiveTranscriptRows should report nonzero rows; got $totalRows",
            totalRows > 0,
        )

        val pageSize = emulator.mRows
        val scrollbackCapacity = (totalRows - pageSize).coerceAtLeast(0)
        // Pick a headroom smaller than mRows-1 so the clamp must fire.
        val desiredHeadroom = 5
        val startTopRow = -(scrollbackCapacity - desiredHeadroom)
        topRowField.setInt(view.termuxView, startTopRow)

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
            val newTopRow = topRowField.getInt(view.termuxView)
            val actualDelta = startTopRow - newTopRow // positive = upward
            assertTrue(
                "page-up should move mTopRow negatively (start=$startTopRow new=$newTopRow)",
                actualDelta > 0,
            )
            assertTrue(
                "clamp should fire when headroom($desiredHeadroom) < mRows-1(${pageSize - 1}); " +
                    "actual delta = $actualDelta",
                actualDelta <= desiredHeadroom,
            )
            assertTrue(
                "actual delta $actualDelta must be < mRows-1 so the banner shows N rows, not '一页'",
                actualDelta < pageSize - 1,
            )
            assertEquals(
                "banner must report the actual clamped amount, not '一页'",
                "↑ 已向上翻 $actualDelta 行",
                controller.state.value.gestureHint,
            )
            assertTrue(
                "we did not auto-exit — the user is still scrolled back",
                controller.state.value.isInScrollback,
            )
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_pageUpAtTopOfScrollback_publishesTopHint() {
        // When the user is already at the top of the scrollback, a page-up
        // gesture must NOT move anything and must publish the "已到顶部"
        // hint so the user understands why nothing happened. We probe
        // the actual buffer size so the preset mTopRow matches reality.
        val (view, emulator, controller) = newController()
        val topRowField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mTopRow").apply { isAccessible = true }
        val scrollbackFiller = "\r\n".repeat(emulator.mRows * 4).toByteArray()
        emulator.append(scrollbackFiller, scrollbackFiller.size)
        val totalRows = readActiveTranscriptRows(emulator)
        assertTrue(
            "reflection on TerminalBuffer.getActiveTranscriptRows should report nonzero rows; got $totalRows",
            totalRows > 0,
        )
        val pageSize = emulator.mRows
        val scrollbackCapacity = (totalRows - pageSize).coerceAtLeast(0)
        // Seat mTopRow at the maximum (top of scrollback).
        val initialTopRow = -scrollbackCapacity
        topRowField.setInt(view.termuxView, initialTopRow)

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
                "page-up at top of scrollback must not move mTopRow",
                initialTopRow, topRowField.getInt(view.termuxView),
            )
            assertEquals("已到顶部", controller.state.value.gestureHint)
            assertTrue(controller.state.value.isInScrollback)
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    /**
     * Mirror of [ScrollbackController]'s private reflection helper so the
     * tests can pre-seat [mTopRow] accurately. Returns the row count from
     * `TerminalBuffer.getActiveTranscriptRows()`; falls back to a large
     * sentinel if the reflection can't find the field/method (so the
     * dependent tests assert with a clear failure rather than being
     * silently downgraded to the legacy behaviour).
     */
    private fun readActiveTranscriptRows(emulator: TerminalEmulator): Int {
        return runCatching {
            val bufferField = TerminalEmulator::class.java
                .getDeclaredField("mMainBuffer").apply { isAccessible = true }
            val method = Class.forName("com.termux.terminal.TerminalBuffer")
                .getDeclaredMethod("getActiveTranscriptRows").apply { isAccessible = true }
            (method.invoke(bufferField.get(emulator)) as? Int) ?: 0
        }.getOrDefault(0)
    }
}
