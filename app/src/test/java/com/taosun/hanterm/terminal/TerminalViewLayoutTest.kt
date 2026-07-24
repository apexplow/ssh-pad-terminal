package com.taosun.hanterm.terminal

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the "terminal renders as a small block in the
 * wrapper's top-left corner on first launch" bug.
 *
 * Root cause: `com.termux.view.TerminalView.onMeasure` reads the
 * emulator's grid dimensions (initialised to 80×24 in the wrapper's
 * constructor — see [TerminalView] line ~131) to compute its own
 * desired size, ignoring the `MATCH_PARENT` layout params we install
 * on line 91. On the first measure pass the inner view therefore
 * reports a small intrinsic size (~640×336 at the default 14pt font),
 * and [FrameLayout.onLayout] places it in the wrapper's top-left.
 * The `OnLayoutChangeListener` on the inner view then fires with that
 * small size, `reportPtyResize` shrinks the emulator to match, and the
 * terminal is locked into a ~1/4-screen block on a tablet — until a
 * configuration change (rotation) triggers a fresh layout pass that
 * happens to read the right size. This is the symptom users reported
 * as "I have to rotate the device once to make tmux full screen".
 *
 * The fix is [TerminalView.onLayout], which detects the size mismatch
 * after the super pass and re-measures the inner view with the
 * wrapper's actual pixel dimensions. The next `OnLayoutChangeListener`
 * fire then carries the real size into `reportPtyResize`, which
 * recomputes cols/rows from the actual view dimensions and resizes the
 * emulator to fill the wrapper.
 *
 * What this test pins:
 *   1. After `view.measure` + `view.layout` on a 1600×1000 wrapper,
 *      the inner view's `width` and `height` equal the wrapper's.
 *      Without [TerminalView.onLayout]'s re-measure, the inner view
 *      would stay at the small intrinsic size and these assertions
 *      would fail on a real device.
 *   2. The full measure → layout → OnLayoutChangeListener → reportPtyResize
 *      chain completes without throwing. `reportPtyResize` carries a
 *      defensive guard against zero font metrics (see its kdoc); this
 *      test exercises that guard path on the Robolectric shadow renderer
 *      and proves the layout pass doesn't NPE.
 *
 * What this test deliberately does NOT pin:
 *   - The emulator's `mColumns` / `mRows` reaching the wrapper-derived
 *     values. That check requires valid `mRenderer` font metrics, which
 *     Robolectric can't supply (the Termux renderer reads a real font
 *     file from the AAR, and Robolectric's font shadows report zero).
 *     The defensive zero-metrics guard in `reportPtyResize` makes that
 *     downstream branch a no-op in CI, so the emulator stays at the
 *     constructor's 80×24 default — which would falsely fail the
 *     assertion. On a real device, `mRenderer.getFontWidth()` and
 *     `getFontLineSpacing()` return real metrics, `reportPtyResize`
 *     computes ~200 cols / ~50 rows for a 1600×1000 wrapper at 14pt,
 *     and the emulator fills the screen. That branch is covered by
 *     the manual test plan in `/home/tao/.claude/plans/curious-napping-snowflake.md`,
 *     not by this Robolectric test.
 *
 * This is the first test in the suite that drives `View.measure` /
 * `View.layout` directly — the rest poke state through reflection.
 * The pattern is documented here for future layout-level regression
 * tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TerminalViewLayoutTest {

    private lateinit var context: Context
    private lateinit var view: TerminalView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
    }

    @Test
    fun innerView_matchesWrapperSize_afterFirstLayout() {
        val wrapperW = 1600
        val wrapperH = 1000
        view.measure(
            View.MeasureSpec.makeMeasureSpec(wrapperW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(wrapperH, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, wrapperW, wrapperH)

        val inner = view.termuxView
        // Inner view must occupy the full wrapper after layout. Without
        // TerminalView.onLayout's re-measure, the inner view would stay
        // at ~640×336 (the Termux view's intrinsic size from the emulator's
        // 80×24 default grid at 14pt) and these two assertions would fail
        // on a real device.
        assertEquals(
            "inner view width should match wrapper width after first layout — " +
                "TerminalView.onLayout must re-measure the inner view with the wrapper's " +
                "actual size when the Termux view's own onMeasure returns a small " +
                "intrinsic size based on the emulator's 80×24 default",
            wrapperW,
            inner.width,
        )
        assertEquals(
            "inner view height should match wrapper height after first layout",
            wrapperH,
            inner.height,
        )
    }

    /**
     * Regression test for the "tmux status bar lands in the middle on first
     * open" race.
     *
     * Pins TV-PTY-02 (GEARS_SPEC.md:201): "When `ptyResizeListener` is
     * registered, the View shall invoke it once immediately with the current
     * `termuxView.width, termuxView.height`."
     *
     * The race: TerminalView's constructor installs an
     * [addOnLayoutChangeListener] on the inner Termux view (line ~93) that
     * calls [TerminalView.reportPtyResize]. On the first layout pass the
     * listener fires while `ptyResizeListener` is still null — so the
     * SIGWINCH is dropped on the floor, but `lastResizeCols/Rows` are
     * still written to the wrapper-derived (correct) values. The Compose
     * `LaunchedEffect` in `TerminalPane` then runs and calls
     * [TerminalView.setPtyResizeListener] (line ~350), which fires
     * `reportPtyResize` once more with the same dimensions — and the
     * debounce check at line ~556 (`if (cols == lastResizeCols && rows ==
     * lastResizeRows) return`) drops that one too. Result: the SSH PTY
     * stays at `SshConfig.DEFAULT_PTY_COLS=80 / DEFAULT_PTY_ROWS=24`
     * forever, tmux renders at 80×24 inside a 200×71 visible grid, and
     * the status bar lands roughly in the middle of the screen. The bug
     * self-heals as soon as the IME comes up (a new layout pass with a
     * different size makes the debounce check pass), which is why the user
     * sees the status bar jump to the bottom after tapping the screen.
     *
     * The fix: [TerminalView.setPtyResizeListener] now calls
     * `reportPtyResize(..., force = true)`, bypassing the debounce for the
     * one protocol-mandatory fire on registration.
     *
     * Why this test mocks `TerminalRenderer`: the Termux AAR's renderer
     * shadows `getFontWidth` / `getFontLineSpacing` to 0 in Robolectric
     * (see this class's kdoc), which short-circuits `reportPtyResize` at
     * the defensive zero-metrics guard before the debounce check ever
     * runs. Stubbing a renderer with realistic metrics lets the function
     * actually reach the debounce check, so this test can pin the bug
     * end-to-end.
     *
     * Caveat: do NOT call `view.setTextSize(...)` after the mock is
     * installed — `setTextSize` constructs a fresh `TerminalRenderer` and
     * overwrites `mRenderer`, silently removing our stub. The constructor
     * already called `setTextSize(14)` once at line ~89, so the mock
     * installed *after* construction is the one the rest of this test sees.
     */
    @Test
    fun setPtyResizeListener_invokesListenerImmediately_afterLayoutPass() {
        // Inject a renderer with real-looking font metrics so the
        // zero-metrics defensive guard at TerminalView.kt:~545 passes
        // and the function actually reaches the debounce check.
        // TerminalRenderer lives in com.termux.view (NOT com.termux.terminal
        // — only the emulator-state classes are in the latter; the renderer
        // ships in the terminal-view AAR).
        val renderer = mockk<com.termux.view.TerminalRenderer>()
        // getFontWidth() returns float (per com.termux.view.TerminalRenderer),
        // getFontLineSpacing() returns int.
        every { renderer.getFontWidth() } returns 8f
        every { renderer.getFontLineSpacing() } returns 16
        val mRendererField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mRenderer")
            .apply { isAccessible = true }
        mRendererField.set(view.termuxView, renderer)

        val wrapperW = 1600
        val wrapperH = 1000
        view.measure(
            View.MeasureSpec.makeMeasureSpec(wrapperW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(wrapperH, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, wrapperW, wrapperH)
        // The first layout pass fires reportPtyResize with ptyResizeListener
        // still null; lastResizeCols/Rows get populated to the wrapper-derived
        // size (200, 62) but the listener invocation is dropped. That
        // populated state is the precondition this regression test exploits.

        val captured = mutableListOf<Size4<Int, Int, Int, Int>>()
        view.setPtyResizeListener { cols, rows, widthPx, heightPx ->
            captured.add(Size4(cols, rows, widthPx, heightPx))
        }

        // Pre-fix: captured is empty — the debounce in reportPtyResize
        // drops the registration fire because lastResizeCols/Rows already
        // match (200, 62).
        // Post-fix: captured has exactly one entry with the wrapper-derived
        // (cols, rows, widthPx, heightPx).
        // cols = 1600 / 8 = 200; rows = 1000 / 16 = 62.
        assertEquals(
            "setPtyResizeListener must fire the listener once with the current " +
                "wrapper size — this is TV-PTY-02. The race (see class kdoc) " +
                "otherwise leaves the SSH PTY at SshConfig.DEFAULT_PTY_* (80×24) " +
                "forever and tmux's status bar lands in the middle of the visible " +
                "terminal until the IME comes up.",
            listOf(Size4(200, 62, wrapperW, wrapperH)),
            captured,
        )
    }

    /**
     * TV-FS-01 (`docs/GEARS_SPEC.md`): `setTextSize(size)` must be a no-op
     * when `size == currentTextSize`. This is the fix for the held-volume-key
     * bug — every volume-key tap re-ran `setTextSize` with the *same* size on
     * repeat, and without the idempotency guard each call would rebuild the
     * inner Termux renderer and re-fire `reportPtyResize`, flooding a
     * dropbear/busybox server with SIGWINCH-equivalent window-change requests
     * until it dropped the connection.
     *
     * This test reuses the "renderer replacement" probe from
     * [setPtyResizeListener_invokesListenerImmediately_afterLayoutPass]'s
     * kdoc: `com.termux.view.TerminalView.setTextSize` always constructs a
     * fresh `TerminalRenderer` and overwrites `mRenderer`. If our wrapper's
     * `setTextSize` forwarded to the inner view despite the size being
     * unchanged, the mock installed below would silently disappear.
     */
    @Test
    fun setTextSize_sameValueAsCurrent_isNoOpAndDoesNotTouchRenderer() {
        val renderer = mockk<com.termux.view.TerminalRenderer>()
        val mRendererField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mRenderer")
            .apply { isAccessible = true }
        mRendererField.set(view.termuxView, renderer)

        // The wrapper constructor initialises the inner view with size 14
        // (see TerminalView.kt's private DEFAULT_TEXT_SIZE) and tracks that
        // same value as its own currentTextSize baseline — so calling
        // setTextSize(14) here must hit the early-return guard.
        view.setTextSize(14)

        assertSame(
            "setTextSize with the same size as currentTextSize must be a no-op " +
                "(TV-FS-01) — it must not rebuild the inner Termux renderer, which " +
                "is what the held-volume-key SIGWINCH-flood regression exploited",
            renderer,
            mRendererField.get(view.termuxView),
        )
    }

    /** Tiny test-only 4-tuple so the assertion stays self-contained. */
    private data class Size4<A, B, C, D>(
        val a: A, val b: B, val c: C, val d: D,
    )
}
