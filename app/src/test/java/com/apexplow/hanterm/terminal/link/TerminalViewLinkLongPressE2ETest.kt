package com.apexplow.hanterm.terminal.link

import android.content.Context
import android.os.SystemClock
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * End-to-end regression for the real-device symptom reported on
 * 2026-08-01: "long-press on a URL still shows Termux's Copy/More
 * toolbar, no `LinkDialog`".
 *
 * **Why this test exists**
 *
 * The Sprint 4 unit suite (`LinkGestureTest`, `LinkOverlayTest`) drives
 * [LinkGesture.onTouchEvent] directly with hand-built MotionEvents.
 * That seam is too shallow to catch wiring regressions in
 * [TerminalView.dispatchTouchEvent] — the path that actually runs on a
 * tablet:
 *
 *   TerminalView.dispatchTouchEvent(ev)
 *     -> ScrollbackController.onTouchEvent(ev)   // PassThrough for single-finger DOWN
 *     -> LinkGesture.onTouchEvent(ev)           // arms its GestureDetector
 *     -> super.dispatchTouchEvent(ev)           // Termux's inner view ALSO arms
 *     -> 500 ms later both GestureDetectors fire onLongPress
 *
 * This test exercises that whole chain with a real [TerminalView],
 * real emulator (write URL line), real [LinkOverlay] (refresh), and a
 * stubbed [com.termux.view.TerminalRenderer] for font metrics. The
 * assertion is the user-visible symptom: after dispatching DOWN at a
 * URL cell and idling past the long-press timeout, the registered
 * [TerminalView.setLinkLongPressListener] callback MUST have received
 * the URL.
 *
 * On 2026-08-01 this test was written while the user was still seeing
 * the bug on device; the goal is to confirm the failure mode in
 * Robolectric before proposing a fix.
 *
 * If the test goes green immediately on the current `ee03084` HEAD,
 * the bug is either (a) Compose `onTerminalViewChanged` wiring race
 * (listener null when long-press fires), (b) APK-on-device is not this
 * branch, or (c) Robolectric shadows mask a real-device-only path —
 * none of which a unit test can disambiguate without a tighter probe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TerminalViewLinkLongPressE2ETest {

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
        view.setLinkLongPressListener { url -> capturedUrl = url }

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
     * dispatching DOWN at a URL cell + idling past the long-press
     * timeout fires the link long-press listener with the URL.
     *
     * Reproduces the user's exact symptom ("long-press → no LinkDialog")
     * by collapsing "dialog appears" to the load-bearing precondition:
     * the listener received the URL. Compose-side rendering of the
     * ModalBottomSheet is covered by `LinkDialogStateTest` separately.
     */
    @Test
    fun longPress_onUrlCell_viaFullDispatchChain_firesListener() {
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

        // DOWN at (col=10, row=0) → inside the URL substring.
        val urlCol = 10
        val x = urlCol * fontWidthPx + fontWidthPx / 2f
        val y = 0f * fontLineSpacingPx + fontLineSpacingPx / 2f

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0,
        )
        try {
            view.dispatchTouchEvent(down)
        } finally {
            down.recycle()
        }

        // Real device: 500 ms passes with no MOVE; GestureDetector fires.
        ShadowLooper.idleMainLooper(
            ViewConfiguration.getLongPressTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )

        assertEquals(
            "long-press on URL cell must deliver URL to listener (user symptom)",
            url,
            capturedUrl,
        )
    }

    /**
     * NEGATIVE control: long-press on a NON-URL cell must NOT fire the
     * listener. Pins the contract that LinkDialog only opens for spans
     * the overlay flagged as URLs.
     */
    @Test
    fun longPress_onNonUrlCell_viaFullDispatchChain_doesNotFireListener() {
        // Row 0 has "see <url> here" → col 0..3 is "see ", outside any span.
        writeLine(row = 0, text = "see https://example.com here")
        view.linkOverlayForView.refresh()

        val x = 1f * fontWidthPx + fontWidthPx / 2f // col 1 → "e" in "see"
        val y = 0f * fontLineSpacingPx + fontLineSpacingPx / 2f

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0,
        )
        try {
            view.dispatchTouchEvent(down)
        } finally {
            down.recycle()
        }

        ShadowLooper.idleMainLooper(
            ViewConfiguration.getLongPressTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )

        assertEquals(null, capturedUrl)
    }

    /**
     * HYPOTHESIS A: linkLongPressListener is null when long-press fires
     * (Compose wiring race / APK-not-this-branch / pre-attach
     * recomposition). The current TerminalView wraps `onLongPress` in
     * `linkLongPressListener?.invoke(url)` — a silent no-op when the
     * listener is unset. This test pins the SILENT-DROP contract so
     * a future refactor that adds logging or a default listener
     * breaks loudly here.
     *
     * Falsifiable prediction: if we remove the listener BEFORE
     * dispatch, the listener is never invoked. The fix for the device
     * bug would be either (i) surface this case (log + maybe a snackbar)
     * or (ii) ensure the listener is always set before any long-press
     * can fire.
     */
    @Test
    fun longPress_onUrlCell_withListenerNull_silentlyDropsUrl() {
        // Clear the listener installed in setUp.
        // We don't have a public clear API; use reflection to mirror
        // the LinkGesture.isLinkLongPressActive pattern in the existing
        // selection-mode test.
        val listenerField = TerminalView::class.java
            .getDeclaredField("linkLongPressListener")
            .apply { isAccessible = true }
        listenerField.set(view, null)

        val url = "https://example.com"
        writeLine(row = 0, text = "see $url here")
        view.linkOverlayForView.refresh()

        val x = 10f * fontWidthPx + fontWidthPx / 2f
        val y = 0f * fontLineSpacingPx + fontLineSpacingPx / 2f

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0,
        )
        try {
            view.dispatchTouchEvent(down)
        } finally {
            down.recycle()
        }

        ShadowLooper.idleMainLooper(
            ViewConfiguration.getLongPressTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )

        // Listener null → URL silently dropped. This is what the user
        // sees on device if Compose wiring failed.
        assertEquals(null, capturedUrl)
    }

    /**
     * REGRESSION TEST for hypothesis #1 (device symptom: long-press on
     * URL → no LinkDialog).
     *
     * Phase 5 fix: production code must NOT silently drop the URL when
     * `linkLongPressListener` is null at long-press time. Instead it
     * must emit an `AppLog.w` so:
     *  - on the next user bug report we can grep `app.log` and confirm
     *    "listener not wired" instead of guessing; and
     *  - a future refactor that re-introduces the silent drop fails
     *    this test loudly.
     *
     * Wiring a recording LogPolicy so the test sees every entry the
     * production `w(...)` call handed to `classify(...)`. Mirrors the
     * `RecordingLogPolicy` pattern in `AppLogTest`.
     */
    @Test
    fun longPress_onUrlCell_withListenerNull_logsWarning() {
        val recording = RecordingLogPolicy()
        AppLog.init(context, recording)
        AppLog.clear()

        // Drop the listener installed in setUp — mimics a Compose
        // wiring race where onTerminalViewChanged didn't run on the
        // TerminalView the user is touching.
        val listenerField = TerminalView::class.java
            .getDeclaredField("linkLongPressListener")
            .apply { isAccessible = true }
        listenerField.set(view, null)

        val url = "https://example.com"
        writeLine(row = 0, text = "see $url here")
        view.linkOverlayForView.refresh()

        val x = 10f * fontWidthPx + fontWidthPx / 2f
        val y = 0f * fontLineSpacingPx + fontLineSpacingPx / 2f

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0,
        )
        try {
            view.dispatchTouchEvent(down)
        } finally {
            down.recycle()
        }

        ShadowLooper.idleMainLooper(
            ViewConfiguration.getLongPressTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )

        // Find the warning the fix is supposed to emit. Tolerant of
        // exact phrasing — assert the SEMANTIC contract (warning +
        // "linkLongPressListener" + "null") instead of grepping for
        // a specific logcat line.
        val warning = recording.entries.firstOrNull {
            it.level == LogLevel.W && it.message.contains("linkLongPressListener")
        }
        assertTrue(
            "expected AppLog.w mentioning linkLongPressListener when listener is null; " +
                "saw entries=${recording.entries.map { it.message }}",
            warning != null,
        )
    }

    /**
     * HYPOTHESIS B: isComposing() returns true during long-press (IME
     * has an active composing region). LinkGesture short-circuits
     * `PassThrough` when composing — so the URL never reaches the
     * listener. This pins the existing behavior; if we want the
     * LinkDialog to win over IME during long-press, we'd need to
     * change this contract.
     */
    @Test
    fun longPress_onUrlCell_duringImeComposition_passesThrough() {
        // Force the InputConnection into a composing state by calling
        // setComposingText. setLinkLongPressListener is installed
        // (from setUp), so a non-composing long-press WOULD fire it.
        val ic = view.onCreateInputConnection(EditorInfo())
        ic.setComposingText("拼", 1)

        val url = "https://example.com"
        writeLine(row = 0, text = "see $url here")
        view.linkOverlayForView.refresh()

        val x = 10f * fontWidthPx + fontWidthPx / 2f
        val y = 0f * fontLineSpacingPx + fontLineSpacingPx / 2f

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0,
        )
        try {
            view.dispatchTouchEvent(down)
        } finally {
            down.recycle()
        }

        ShadowLooper.idleMainLooper(
            ViewConfiguration.getLongPressTimeout() + 50L,
            TimeUnit.MILLISECONDS,
        )

        // isComposing() true → LinkGesture PassThrough → listener NOT
        // fired. Matches the contract from LinkGesture kdoc "T1:
        // while IME is composing, this gesture is dormant".
        assertEquals(null, capturedUrl)
        // isComposing actually was true (proves the harness worked).
        assertEquals(true, view.isComposing())
    }

    // --- helpers --------------------------------------------------------

    private fun writeLine(row: Int, text: String) {
        val cursorRow = row + 1
        val sb = StringBuilder()
        //  = ESC (0x1B). The non-printable is invisible in source
        // editors; write the escape as a Kotlin unicode literal so the
        // CUP sequence ([1;1H) is actually emitted, not just `[1;1H`.
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