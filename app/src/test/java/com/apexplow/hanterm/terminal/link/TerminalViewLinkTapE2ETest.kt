package com.apexplow.hanterm.terminal.link

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.apexplow.hanterm.logging.AppLog
import com.apexplow.hanterm.logging.LogDestination
import com.apexplow.hanterm.logging.LogEntry
import com.apexplow.hanterm.logging.LogLevel
import com.apexplow.hanterm.logging.LogPolicy
import com.apexplow.hanterm.terminal.TerminalEndpoint
import com.apexplow.hanterm.terminal.TerminalView
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
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
 * End-to-end regression for the 2026-08-02 URL UX redesign.
 *
 * **Long-press → single-tap → Ctrl+tap.** Long-press on a URL still
 * falls through to Termux's selection toolbar (with Share / Search
 * web in the overflow). Bare tap now goes to normal terminal
 * character input. The URL-opens-in-browser path is now a Ctrl+tap
 * on a URL cell — browser convention, matches the keyboard-only
 * shell where a hardware Ctrl is always in reach.
 *
 * **Why this test exists**
 *
 * The Sprint 4 unit suite (`LinkGestureTest`, `LinkOverlayTest`)
 * drives [LinkGesture.onTouchEvent] directly with hand-built
 * MotionEvents. That seam is too shallow to catch wiring regressions
 * in [TerminalView.dispatchTouchEvent] — the path that actually runs
 * on a tablet:
 *
 *   TerminalView.dispatchTouchEvent(ev)
 *     -> ScrollbackController.onTouchEvent(ev)   // PassThrough for single-finger DOWN
 *     -> LinkGesture.onTouchEvent(ev)           // arms its GestureDetector
 *     -> super.dispatchTouchEvent(ev)           // Termux's inner view
 *     -> GestureDetector fires onSingleTapUp on UP within tap timeout,
 *        gated by isCtrlPressed() on the UP event
 *
 * This test exercises that whole chain with a real [TerminalView],
 * real emulator (write URL line), real [LinkOverlay] (refresh), and a
 * stubbed [com.termux.view.TerminalRenderer] for font metrics. The
 * assertion is the user-visible symptom: after dispatching Ctrl+DOWN
 * + Ctrl+UP at a URL cell within the tap timeout, the registered
 * [TerminalView.setLinkTapListener] callback MUST have received the
 * URL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TerminalViewLinkTapE2ETest {

    private lateinit var context: Context
    private lateinit var view: TerminalView
    private var capturedUrl: String? = null

    private val fontWidthPx = 10f
    private val fontLineSpacingPx = 20

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
        view.setLinkTapListener { url -> capturedUrl = url }

        // Robolectric shadows Termux's TerminalRenderer font metrics to 0
        // by default; LinkGesture bails out on fontWidth<=0 || lineSpacing<=0.
        // Inject realistic metrics matching the existing LinkGestureTest.
        val renderer = mockk<com.termux.view.TerminalRenderer>()
        every { renderer.getFontWidth() } returns fontWidthPx
        every { renderer.getFontLineSpacing() } returns fontLineSpacingPx
        val mRendererField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mRenderer")
            .apply { isAccessible = true }
        mRendererField.set(view.termuxView, renderer)
    }

    @After
    fun tearDown() {
        // Restore release-default policy so a test ordering bug doesn't
        // leak the recording policy into the next suite.
        AppLog.resetPolicyForTests()
    }

    /**
     * PRIMARY ASSERTION: with a URL on screen and the overlay refreshed,
     * dispatching Ctrl+DOWN+Ctrl+UP at a URL cell within the tap
     * timeout fires the link-tap listener with the URL.
     */
    @Test
    fun ctrlTap_onUrlCell_viaFullDispatchChain_firesListener() {
        val url = "https://example.com"
        writeLine(row = 0, text = "see $url here")
        val overlay = view.linkOverlayForView
        overlay.refresh()

        // Sanity: overlay recorded the span at (0, 4..23) and the URL
        // substring lives in cols [4, 23).
        val spans = overlay.snapshot()
        assertEquals("overlay should have exactly one span", 1, spans.size)
        val span = spans[0]
        assertEquals(0, span.row)
        assertEquals("see ".length, span.startCol)
        assertEquals("see ".length + url.length, span.endCol)

        // CTRL+TAP at (col=10, row=0) → inside the URL substring.
        val urlCol = 10
        val x = urlCol * fontWidthPx + fontWidthPx / 2f
        val y = 0f * fontLineSpacingPx + fontLineSpacingPx / 2f

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            x, y, KeyEvent.META_CTRL_ON,
        )
        val up = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP, x, y, KeyEvent.META_CTRL_ON,
        )
        try {
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }

        // Real device: UP fires within tap timeout (default ~100 ms).
        ShadowLooper.idleMainLooper(
            ViewConfiguration.getTapTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )

        assertEquals(
            "Ctrl+tap on URL cell must deliver URL to listener (user symptom)",
            url,
            capturedUrl,
        )
    }

    /**
     * REGRESSION: bare tap (no Ctrl) on a URL cell must NOT fire.
     * Pin the contract that keeps terminal character input untouched.
     */
    @Test
    fun bareTap_onUrlCell_doesNotFireListener() {
        val url = "https://example.com"
        writeLine(row = 0, text = "see $url here")
        view.linkOverlayForView.refresh()

        val x = 10f * fontWidthPx + fontWidthPx / 2f
        val y = 0f * fontLineSpacingPx + fontLineSpacingPx / 2f

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0,
        )
        val up = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP, x, y, 0,
        )
        try {
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }

        ShadowLooper.idleMainLooper(
            ViewConfiguration.getTapTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )

        assertEquals(
            "bare tap on URL cell must NOT fire — keep terminal input clean",
            null, capturedUrl,
        )
    }

    /**
     * NEGATIVE control: Ctrl+tap on a NON-URL cell must NOT fire the
     * listener. Pins the contract that LinkDialog only opens for spans
     * the overlay flagged as URLs.
     */
    @Test
    fun ctrlTap_onNonUrlCell_viaFullDispatchChain_doesNotFireListener() {
        // Row 0 has "see <url> here" → col 0..3 is "see ", outside any span.
        writeLine(row = 0, text = "see https://example.com here")
        view.linkOverlayForView.refresh()

        val x = 1f * fontWidthPx + fontWidthPx / 2f // col 1 → "e" in "see"
        val y = 0f * fontLineSpacingPx + fontLineSpacingPx / 2f

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            x, y, KeyEvent.META_CTRL_ON,
        )
        val up = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP, x, y, KeyEvent.META_CTRL_ON,
        )
        try {
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }

        ShadowLooper.idleMainLooper(
            ViewConfiguration.getTapTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )

        assertEquals(null, capturedUrl)
    }

    /**
     * REGRESSION TEST (kept from the prior long-press iteration):
     * production code must NOT silently drop the URL when
     * `linkTapListener` is null at tap time. Instead it must emit an
     * `AppLog.w` so:
     *  - on the next user bug report we can grep `app.log` and confirm
     *    "listener not wired" instead of guessing; and
     *  - a future refactor that re-introduces the silent drop fails
     *    this test loudly.
     */
    @Test
    fun ctrlTap_onUrlCell_withListenerNull_logsWarning() {
        val recording = RecordingLogPolicy()
        AppLog.init(context, recording)
        AppLog.clear()

        // Drop the listener installed in setUp — mimics a Compose
        // wiring race where onTerminalViewChanged didn't run on the
        // TerminalView the user is touching.
        val listenerField = TerminalView::class.java
            .getDeclaredField("linkTapListener")
            .apply { isAccessible = true }
        listenerField.set(view, null)

        val url = "https://example.com"
        writeLine(row = 0, text = "see $url here")
        view.linkOverlayForView.refresh()

        val x = 10f * fontWidthPx + fontWidthPx / 2f
        val y = 0f * fontLineSpacingPx + fontLineSpacingPx / 2f

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            x, y, KeyEvent.META_CTRL_ON,
        )
        val up = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP, x, y, KeyEvent.META_CTRL_ON,
        )
        try {
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }

        ShadowLooper.idleMainLooper(
            ViewConfiguration.getTapTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )

        // Find the warning the fix is supposed to emit. Tolerant of
        // exact phrasing — assert the SEMANTIC contract (warning +
        // "linkTapListener" + "null") instead of grepping for a
        // specific logcat line.
        val warning = recording.entries.firstOrNull {
            it.level == LogLevel.W && it.message.contains("linkTapListener")
        }
        assertTrue(
            "expected AppLog.w mentioning linkTapListener when listener is null; " +
                "saw entries=${recording.entries.map { it.message }}",
            warning != null,
        )
    }

    /**
     * IME composing short-circuit (T1 from Sprint 4): while the IME has
     * an active composing region, a Ctrl+tap on a URL cell must NOT
     * fire the link-tap listener. The IME owns the touch mid-拼音.
     */
    @Test
    fun ctrlTap_onUrlCell_duringImeComposition_doesNotFireListener() {
        // Force the InputConnection into a composing state by calling
        // setComposingText. setLinkTapListener is installed (from
        // setUp), so a non-composing Ctrl+tap WOULD fire it.
        val ic = view.onCreateInputConnection(EditorInfo())
        ic.setComposingText("拼", 1)
        assertEquals(true, view.isComposing())

        val url = "https://example.com"
        writeLine(row = 0, text = "see $url here")
        view.linkOverlayForView.refresh()

        val x = 10f * fontWidthPx + fontWidthPx / 2f
        val y = 0f * fontLineSpacingPx + fontLineSpacingPx / 2f

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            x, y, KeyEvent.META_CTRL_ON,
        )
        val up = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP, x, y, KeyEvent.META_CTRL_ON,
        )
        try {
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }

        ShadowLooper.idleMainLooper(
            ViewConfiguration.getTapTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )

        // isComposing() true → LinkGesture PassThrough → listener NOT
        // fired. Matches the contract from LinkGesture kdoc "T1:
        // while IME is composing, this gesture is dormant".
        assertNull(capturedUrl)
    }

    // --- helpers --------------------------------------------------------

    private fun writeLine(row: Int, text: String) {
        val cursorRow = row + 1
        val sb = StringBuilder()
        // "[" = ESC + `[` (CSI introducer). The non-printable
        // ESC byte is invisible in source editors — without the
        // explicit Kotlin unicode escape, the resulting source file
        // would just contain the literal string "[1;1H" and the
        // emulator would render those characters instead of moving
        // the cursor.
        sb.append("[").append(cursorRow).append(";1H") // CUP row;col
        sb.append(text)
        sb.append("[K") // clear to end of line
        view.termuxView.mEmulator?.append(sb.toString().toByteArray(), sb.length)
    }

    /**
     * Captures every [LogEntry] the AppLog pipeline sees. Mirrors the
     * shape of `AppLogTest.RecordingLogPolicy`; duplicated here
     * because the production test seam is `internal` to the logging
     * module and pulling it across would leak test-only types into
     * the main sourceset.
     */
    private class RecordingLogPolicy : LogPolicy {
        val entries: MutableList<LogEntry> = mutableListOf()
        override fun classify(entry: LogEntry): LogDestination {
            entries.add(entry)
            // Mirror the production release policy so a test asserting
            // "the file sink would have written this" stays truthful.
            return com.apexplow.hanterm.logging.BuildConfigAwareLogPolicy(false)
                .classify(entry)
        }
    }
}