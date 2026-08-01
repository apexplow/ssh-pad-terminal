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
import org.junit.Assert.assertNull
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
 * **2026-08-01 redesign — long-press → single-tap.** The contracts
 * are now:
 *
 *  1. **isComposing short-circuit (T1)** — while the IME has an active
 *     composing region, the gesture is dormant (`PassThrough` for every
 *     event). The IME owns the touch mid-拼音.
 *  2. **Single tap on a URL span fires `onSingleTap(url)`** —
 *     `GestureDetector.onSingleTapUp` runs from the Main handler on
 *     ACTION_UP, no waiting for double-tap confirmation. The URL is
 *     delivered to the registered callback; the touch itself stays
 *     PassThrough because the dialog is a Compose ModalBottomSheet on
 *     top of the terminal, not a touch-dispatch consumer.
 *  3. **No-URL tap is `PassThrough`** — the consumer doesn't claim
 *     touches that aren't on a URL cell. The wrapper falls through
 *     to `super.dispatchTouchEvent` and Termux's text-selection /
 *     cursor-placement paths take over as before.
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
            onSingleTap = { url -> capturedUrl = url },
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
    fun isComposing_doesNotFireUrl() {
        // Even if there's a URL somewhere in the overlay, the IME-composing
        // short-circuit must NOT deliver it. The outer behaviour:
        // onTouchEvent returns PassThrough and the callback is never
        // invoked.
        val overlay = overlayWithSpan(
            LinkOverlay.UrlSpan(2, 0, 20, "https://example.com"),
        )
        val gesture = buildGesture(overlay)
        composing = true

        val downTime = android.os.SystemClock.uptimeMillis()
        gesture.onTouchEvent(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 50f, 40f, 0),
        )
        val up = MotionEvent.obtain(
            downTime, android.os.SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP, 50f, 40f, 0,
        )
        assertEquals(TouchDecision.PassThrough, gesture.onTouchEvent(up))
        assertNull(capturedUrl)
    }

    @Test
    fun tap_noOverlayMatch_doesNotFire() {
        val gesture = buildGesture(emptyOverlay())
        val down = makeEvent(MotionEvent.ACTION_DOWN, 0f, 0f)
        val verdict = gesture.onTouchEvent(down)
        assertEquals(TouchDecision.PassThrough, verdict)

        val up = makeEvent(MotionEvent.ACTION_UP, 0f, 0f)
        assertEquals(TouchDecision.PassThrough, gesture.onTouchEvent(up))
        assertNull(capturedUrl)
    }

    @Test
    fun cancelEvent_doesNotFire() {
        val overlay = overlayWithSpan(
            LinkOverlay.UrlSpan(0, 0, 10, "https://a.com"),
        )
        val gesture = buildGesture(overlay)
        val down = makeEvent(MotionEvent.ACTION_DOWN, 5f, 5f)
        gesture.onTouchEvent(down)
        val cancel = makeEvent(MotionEvent.ACTION_CANCEL, 5f, 5f)
        assertEquals(TouchDecision.PassThrough, gesture.onTouchEvent(cancel))
        assertNull(capturedUrl)
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
    fun tap_onUrlCell_firesCallback() {
        // Single-tap UX: DOWN at (col=5, row=2) → y=40 (2*20), x=50 (5*10)
        // with the mocked renderer. UP within the tap timeout delivers
        // the URL.
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

        // No long-press wait — fire UP, the GestureDetector runs
        // onSingleTapUp from the Main handler.
        val up = MotionEvent.obtain(
            downTime, android.os.SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP, 50f, 40f, 0,
        )
        assertEquals(TouchDecision.PassThrough, gesture.onTouchEvent(up))

        ShadowLooper.idleMainLooper(
            ViewConfiguration.getTapTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )
        assertEquals("https://example.com", capturedUrl)
    }

    @Test
    fun tap_onNonUrlCell_doesNotFire() {
        val overlay = overlayWithSpan(
            LinkOverlay.UrlSpan(row = 5, startCol = 0, endCol = 10, url = "https://x.com"),
        )
        val gesture = buildGesture(overlay)
        // Tap on row 0, col 0 — outside the span at row 5.
        val downTime = android.os.SystemClock.uptimeMillis()
        gesture.onTouchEvent(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 5f, 5f, 0),
        )
        val up = MotionEvent.obtain(
            downTime, android.os.SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP, 5f, 5f, 0,
        )
        gesture.onTouchEvent(up)
        ShadowLooper.idleMainLooper(
            ViewConfiguration.getTapTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )
        assertNull(capturedUrl)
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