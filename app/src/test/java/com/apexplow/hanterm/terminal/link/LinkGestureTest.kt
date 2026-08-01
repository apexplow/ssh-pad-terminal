package com.apexplow.hanterm.terminal.link

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import com.apexplow.hanterm.terminal.TerminalEndpoint
import com.apexplow.hanterm.terminal.TerminalView
import com.apexplow.hanterm.terminal.TouchDecision
import com.termux.view.TerminalRenderer
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Sprint 4 T13 — pins the [LinkGesture] consumer behaviour.
 *
 * Three contracts the wrapper relies on:
 *  1. **isComposing short-circuit (T1)** — while the IME has an active
 *     composing region, the gesture is dormant (`PassThrough` for every
 *     event). The IME owns the touch mid-拼音.
 *  2. **Long-press fires `onLongPress(url)`** — when the gesture fires
 *     on a cell the [LinkOverlay] flagged as a URL, the consumer
 *     returns [TouchDecision.Consumed] and the URL is delivered to the
 *     registered callback.
 *  3. **No-URL long-press is `PassThrough`** — the consumer doesn't
 *     claim touches that aren't on a URL cell. The wrapper falls through
 *     to `super.dispatchTouchEvent` and Termux's text-selection
 *     GestureDetector takes over as before.
 *
 * Uses a real [TerminalView] + reflection-injected [TerminalRenderer]
 * (matches the pattern in
 * [com.apexplow.hanterm.terminal.TerminalViewLayoutTest]). The real
 * [com.termux.view.TerminalView] is used so the inner renderer + bridge
 * are wired exactly as in production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LinkGestureTest {

    private lateinit var context: Context
    private lateinit var terminalView: TerminalView
    private lateinit var bridge: com.apexplow.hanterm.terminal.TermuxViewBridge
    private lateinit var wrapper: View
    private var composing: Boolean = false
    private var capturedUrl: String? = null

    private val fontWidthPx = 10f
    private val fontLineSpacingPx = 20

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        terminalView = TerminalView(context)
        terminalView.bindEndpoint(TerminalEndpoint {})

        // Replace the inner view's mRenderer with a mockk — the
        // LinkGesture reads `bridge.view.mRenderer` directly to compute
        // row/col from touch (x, y).
        val renderer = mockk<TerminalRenderer>()
        every { renderer.getFontWidth() } returns fontWidthPx
        every { renderer.getFontLineSpacing() } returns fontLineSpacingPx
        val mRendererField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mRenderer")
            .apply { isAccessible = true }
        mRendererField.set(terminalView.termuxView, renderer)

        // Reach the bridge through reflection.
        val bridgeField = TerminalView::class.java.getDeclaredField("termuxViewBridge")
        bridgeField.isAccessible = true
        bridge = bridgeField.get(terminalView) as com.apexplow.hanterm.terminal.TermuxViewBridge

        wrapper = View(context)

        composing = false
        capturedUrl = null
    }

    /** Build a LinkGesture with a (possibly empty) overlay. */
    private fun buildGesture(overlay: LinkOverlay): LinkGesture =
        LinkGesture(
            context = context,
            view = wrapper,
            overlay = overlay,
            bridge = bridge,
            isComposingProvider = { composing },
            onLongPress = { url -> capturedUrl = url },
        )

    private fun emptyOverlay(): LinkOverlay =
        LinkOverlay(
            emulatorSource = { null }, // never matches
            topRowSource = { 0 },
            lastWriteUptimeMsSource = { 0L },
        )

    @Test
    fun isComposing_passesThroughAllEvents() {
        val gesture = buildGesture(emptyOverlay())
        composing = true

        val down = makeEvent(MotionEvent.ACTION_DOWN, 50f, 50f)
        assertEquals(TouchDecision.PassThrough, gesture.onTouchEvent(down))

        val move = makeEvent(MotionEvent.ACTION_MOVE, 50f, 50f)
        assertEquals(TouchDecision.PassThrough, gesture.onTouchEvent(move))
    }

    @Test
    fun isComposing_doesNotQueueUrl() {
        // Even if there's a URL somewhere in the overlay, the IME-composing
        // short-circuit must NOT populate pendingUrl. The outer
        // behaviour: onTouchEvent returns PassThrough and the
        // callback is never invoked.
        val gesture = buildGesture(emptyOverlay())
        composing = true

        val down = makeEvent(MotionEvent.ACTION_DOWN, 50f, 50f)
        val verdict = gesture.onTouchEvent(down)
        assertEquals(TouchDecision.PassThrough, verdict)
        assertNull(capturedUrl)
    }

    @Test
    fun onLongPress_noOverlayMatch_doesNotConsume() {
        val gesture = buildGesture(emptyOverlay())
        // Overlay has no spans, so the long-press detector (eventually)
        // fires but pendingUrl stays null.
        val down = makeEvent(MotionEvent.ACTION_DOWN, 0f, 0f)
        val verdict = gesture.onTouchEvent(down)
        assertEquals(TouchDecision.PassThrough, verdict)
    }

    @Test
    fun upBeforeLongPress_clearsPending() {
        val gesture = buildGesture(emptyOverlay())
        val down = makeEvent(MotionEvent.ACTION_DOWN, 50f, 50f)
        gesture.onTouchEvent(down)
        val up = makeEvent(MotionEvent.ACTION_UP, 50f, 50f)
        val verdict = gesture.onTouchEvent(up)
        // UP returns PassThrough (no URL pending) and resets state.
        assertEquals(TouchDecision.PassThrough, verdict)
        assertNull(capturedUrl)
    }

    @Test
    fun cancelEvent_clearsState() {
        val gesture = buildGesture(emptyOverlay())
        val down = makeEvent(MotionEvent.ACTION_DOWN, 50f, 50f)
        gesture.onTouchEvent(down)
        val cancel = makeEvent(MotionEvent.ACTION_CANCEL, 50f, 50f)
        val verdict = gesture.onTouchEvent(cancel)
        assertEquals(TouchDecision.PassThrough, verdict)
    }

    @Test
    fun moveEvent_doesNotConsume() {
        // Multi-touch scrollback should still work — pure MOVE events
        // (without DOWN) must not be claimed by LinkGesture.
        val gesture = buildGesture(emptyOverlay())
        val move = makeEvent(MotionEvent.ACTION_MOVE, 100f, 100f)
        val verdict = gesture.onTouchEvent(move)
        assertEquals(TouchDecision.PassThrough, verdict)
    }

    @Test
    fun urlLongPress_deliversImmediatelyEvenWithoutMoveBeforeUp() {
        // Real-device failure mode: GestureDetector.onLongPress fires on the
        // Main handler between DOWN and UP with no intervening MOVE. The
        // previous implementation only drained `pendingUrl` inside
        // onTouchEvent, so UP cleared the URL and LinkDialog never opened
        // — Termux's Copy/More was all the user saw.
        val overlay = overlayWithSpan(
            LinkOverlay.UrlSpan(2, 0, 20, "https://example.com"),
        )
        val gesture = buildGesture(overlay)

        val downTime = android.os.SystemClock.uptimeMillis()
        gesture.onTouchEvent(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 50f, 40f, 0),
        )
        ShadowLooper.idleMainLooper(
            ViewConfiguration.getLongPressTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )

        // No MOVE — finger lifts. URL must already have been delivered.
        assertEquals("https://example.com", capturedUrl)
        assertTrue(gesture.isLinkLongPressActive)

        val up = MotionEvent.obtain(
            downTime, android.os.SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP, 50f, 40f, 0,
        )
        assertEquals(TouchDecision.Consumed, gesture.onTouchEvent(up))
        assertEquals("https://example.com", capturedUrl)
        assertTrue(gesture.isLinkLongPressActive)
    }

    @Test
    fun urlLongPress_setsActiveFlagConsumesAndDeliversUrl() {
        // row=2 col=5 → y=40 (2*20), x=50 (5*10) with the mocked renderer.
        val overlay = overlayWithSpan(
            LinkOverlay.UrlSpan(
                row = 2,
                startCol = 0,
                endCol = 20,
                url = "https://example.com",
            ),
        )
        val gesture = buildGesture(overlay)

        val downTime = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, 50f, 40f, 0,
        )
        assertEquals(TouchDecision.PassThrough, gesture.onTouchEvent(down))
        assertFalse(gesture.isLinkLongPressActive)

        // Fire GestureDetector's delayed long-press runnable — URL is
        // delivered inside the detector callback (no MOVE required).
        ShadowLooper.idleMainLooper(
            ViewConfiguration.getLongPressTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )
        assertTrue(gesture.isLinkLongPressActive)
        assertEquals("https://example.com", capturedUrl)

        // Subsequent MOVE is claimed for the rest of the pointer sequence.
        val move = MotionEvent.obtain(
            downTime, android.os.SystemClock.uptimeMillis(),
            MotionEvent.ACTION_MOVE, 50f, 40f, 0,
        )
        assertEquals(TouchDecision.Consumed, gesture.onTouchEvent(move))
        assertTrue(gesture.isLinkLongPressActive)
    }

    @Test
    fun actionDown_clearsActiveFlag() {
        val overlay = overlayWithSpan(
            LinkOverlay.UrlSpan(0, 0, 10, "https://a.com"),
        )
        val gesture = buildGesture(overlay)
        val downTime = android.os.SystemClock.uptimeMillis()
        gesture.onTouchEvent(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 5f, 5f, 0),
        )
        ShadowLooper.idleMainLooper(
            ViewConfiguration.getLongPressTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )
        assertTrue(gesture.isLinkLongPressActive)
        assertEquals("https://a.com", capturedUrl)

        capturedUrl = null
        val nextDown = MotionEvent.obtain(
            downTime + 1_000,
            downTime + 1_000,
            MotionEvent.ACTION_DOWN,
            5f,
            5f,
            0,
        )
        gesture.onTouchEvent(nextDown)
        assertFalse(gesture.isLinkLongPressActive)
        assertNull(capturedUrl) // fresh gesture; long-press not fired yet
    }

    @Test
    fun clearLinkLongPressActive_dropsLatch() {
        val overlay = overlayWithSpan(
            LinkOverlay.UrlSpan(0, 0, 10, "https://a.com"),
        )
        val gesture = buildGesture(overlay)
        val downTime = android.os.SystemClock.uptimeMillis()
        gesture.onTouchEvent(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 5f, 5f, 0),
        )
        ShadowLooper.idleMainLooper(
            ViewConfiguration.getLongPressTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )
        assertTrue(gesture.isLinkLongPressActive)
        gesture.clearLinkLongPressActive()
        assertFalse(gesture.isLinkLongPressActive)
    }

    /** Inject a single span into an otherwise empty overlay via reflection. */
    private fun overlayWithSpan(span: LinkOverlay.UrlSpan): LinkOverlay {
        val overlay = emptyOverlay()
        val spansField = LinkOverlay::class.java.getDeclaredField("spans").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val spans = spansField.get(overlay) as HashMap<Int, List<LinkOverlay.UrlSpan>>
        spans[span.row] = listOf(span)
        return overlay
    }

    private fun makeEvent(action: Int, x: Float, y: Float): MotionEvent {
        val now = android.os.SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, x, y, 0)
    }
}