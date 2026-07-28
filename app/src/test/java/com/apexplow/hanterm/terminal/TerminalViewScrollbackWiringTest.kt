package com.apexplow.hanterm.terminal

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wires the ScrollbackController into TerminalView. Asserts:
 *   1. The scrollbackController field is lazily constructed when
 *      first accessed (proves the lazy + non-null init works).
 *   2. isInScrollback reads the controller's state.
 *   3. scrollToBottom() resets the inner view's mTopRow to 0.
 *   4. setScrollbackListener() fires the initial state on registration.
 *   5. dispatchTouchEvent routes single-finger vs two-finger gestures
 *      end-to-end (the path Robolectric unit tests on the controller
 *      alone do not cover).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
class TerminalViewScrollbackWiringTest {

    private lateinit var context: Context
    private lateinit var view: TerminalView

    private val innerTopRowField: java.lang.reflect.Field =
        com.termux.view.TerminalView::class.java
            .getDeclaredField("mTopRow")
            .apply { isAccessible = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
        view.onCreateInputConnection(EditorInfo())
    }

    @Test
    fun isInScrollback_readsControllerState() {
        // Default: not in scrollback.
        assertFalse(view.isInScrollback)
    }

    @Test
    fun scrollToBottom_resetsInnerTopRow() {
        val emulator = view.termuxView.mEmulator!!
        // Populate scrollback and jump up two pages.
        val scrollbackFiller = "\r\n".repeat(emulator.mRows * 4).toByteArray()
        emulator.append(scrollbackFiller, scrollbackFiller.size)
        innerTopRowField.setInt(view.termuxView, emulator.mRows * 2)

        view.scrollToBottom()

        assertEquals(0, innerTopRowField.getInt(view.termuxView))
        assertFalse(view.isInScrollback)
    }

    @Test
    fun setScrollbackListener_firesInitialState() {
        var seen: ScrollbackController.ScrollbackState? = null
        view.setScrollbackListener { state -> seen = state }
        // Initial state fires once on registration (mirrors the
        // setPtyResizeListener pattern).
        assertNotNull(seen)
        assertFalse(seen!!.isInScrollback)
    }

    @Test
    fun dispatchTouchEvent_singleFingerDown_doesNotEnterScrollback() {
        val ev = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        try {
            view.dispatchTouchEvent(ev)
            assertFalse(view.isInScrollback)
        } finally {
            ev.recycle()
        }
    }

    @Test
    fun dispatchTouchEvent_twoFingerPointerDown_consumesAndEntersScrollback() {
        val downTime = SystemClock.uptimeMillis()
        val finger1Down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, 10f, 200f, 0,
        )
        val finger2Down = twoFingerPointerDown(downTime, downTime + 8L, y = 200f)
        try {
            view.dispatchTouchEvent(finger1Down)
            assertTrue(
                "second finger must be consumed at the wrapper",
                view.dispatchTouchEvent(finger2Down),
            )
            assertTrue(view.isInScrollback)
        } finally {
            finger1Down.recycle()
            finger2Down.recycle()
        }
    }

    @Test
    fun dispatchTouchEvent_singleFingerPageUp_changesInnerTopRow() {
        stubRendererFontMetrics(lineSpacing = 16)

        val emulator = view.termuxView.mEmulator!!
        val scrollbackFiller = "\r\n".repeat(emulator.mRows * 4).toByteArray()
        emulator.append(scrollbackFiller, scrollbackFiller.size)
        val initialTopRow = innerTopRowField.getInt(view.termuxView)
        val pageSize = emulator.mRows

        val downTime = SystemClock.uptimeMillis()
        val fingerDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, 10f, 200f, 0,
        )
        val fingerMove = MotionEvent.obtain(
            downTime, downTime + 24L, MotionEvent.ACTION_MOVE, 10f, 0f, 0,
        )
        val fingerUp = MotionEvent.obtain(
            downTime, downTime + 32L, MotionEvent.ACTION_UP, 10f, 0f, 0,
        )
        try {
            view.dispatchTouchEvent(fingerDown)
            view.dispatchTouchEvent(fingerMove)
            view.dispatchTouchEvent(fingerUp)
            assertEquals(
                "single-finger page-up through dispatchTouchEvent must scroll mTopRow back one page minus one row of overlap",
                initialTopRow - (pageSize - 1),
                innerTopRowField.getInt(view.termuxView),
            )
        } finally {
            fingerDown.recycle()
            fingerMove.recycle()
            fingerUp.recycle()
        }
    }

    @Test
    fun dispatchTouchEvent_twoFingerPageUp_changesInnerTopRow() {
        // Robolectric shadows Termux's TerminalRenderer font metrics to 0,
        // which makes commitGesture bail on lineSpacing<=0. Stub realistic
        // metrics so this test exercises the same path a real device sees.
        stubRendererFontMetrics(lineSpacing = 16)

        val emulator = view.termuxView.mEmulator!!
        val scrollbackFiller = "\r\n".repeat(emulator.mRows * 4).toByteArray()
        emulator.append(scrollbackFiller, scrollbackFiller.size)
        val initialTopRow = innerTopRowField.getInt(view.termuxView)
        val pageSize = emulator.mRows

        val downTime = SystemClock.uptimeMillis()
        val finger1Down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, 10f, 200f, 0,
        )
        val finger2Down = twoFingerPointerDown(downTime, downTime + 8L, y = 200f)
        val twoFingerMove = twoFingerMove(downTime, downTime + 24L, y = 0f)
        val fingerUp = MotionEvent.obtain(
            downTime, downTime + 32L, MotionEvent.ACTION_UP, 10f, 0f, 0,
        )
        try {
            view.dispatchTouchEvent(finger1Down)
            view.dispatchTouchEvent(finger2Down)
            view.dispatchTouchEvent(twoFingerMove)
            view.dispatchTouchEvent(fingerUp)
            assertEquals(
                "page-up through dispatchTouchEvent must scroll mTopRow back one page minus one row of overlap",
                initialTopRow - (pageSize - 1),
                innerTopRowField.getInt(view.termuxView),
            )
        } finally {
            finger1Down.recycle()
            finger2Down.recycle()
            twoFingerMove.recycle()
            fingerUp.recycle()
        }
    }

    /**
     * Termux AAR's renderer returns 0 font metrics under Robolectric; the
     * scrollback threshold needs a positive lineSpacing. Do NOT call
     * setTextSize after this — it would replace mRenderer.
     */
    private fun stubRendererFontMetrics(lineSpacing: Int) {
        val renderer = mockk<com.termux.view.TerminalRenderer>()
        every { renderer.getFontWidth() } returns 8f
        every { renderer.getFontLineSpacing() } returns lineSpacing
        val mRendererField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mRenderer")
            .apply { isAccessible = true }
        mRendererField.set(view.termuxView, renderer)
    }

    private fun twoFingerPointerDown(downTime: Long, eventTime: Long, y: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; this.y = y; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; this.y = y; pressure = 1f; size = 1f },
        )
        return MotionEvent.obtain(
            downTime, eventTime,
            MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun twoFingerMove(downTime: Long, eventTime: Long, y: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; this.y = y; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; this.y = y; pressure = 1f; size = 1f },
        )
        return MotionEvent.obtain(
            downTime, eventTime,
            MotionEvent.ACTION_MOVE,
            2, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }
}
