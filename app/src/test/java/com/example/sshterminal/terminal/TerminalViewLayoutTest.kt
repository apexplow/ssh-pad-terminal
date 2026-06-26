package com.example.sshterminal.terminal

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
@Config(sdk = [33])
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
}
