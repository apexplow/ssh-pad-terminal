package com.apexplow.hanterm.terminal.link

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Transparent FrameLayout child that paints URL underlines and a faint
 * background highlight on top of the emulator view, but UNDER the IME.
 *
 * Added as a sibling of `termuxViewBridge.view` inside `TerminalView`'s
 * FrameLayout. Always has `isFocusable = false` and `isClickable = false`
 * — touch events fall through to the inner view (which dispatches them
 * to `TerminalView.dispatchTouchEvent`, where `LinkGesture` lives).
 *
 * **Sprint 4 eng-review decisions:**
 *   - T9 invalidate triggers: only invalidate on (a) `LinkOverlay.refresh()`
 *     completion, (b) `isInScrollback` transition, (c) `mTopRow` change.
 *     We do NOT subscribe to `termuxView.invalidate()` — that creates a
 *     paint loop with the emulator view (the plan §Step 4 explicit
 *     non-obvious constraint).
 *   - T16 edge-only: if `LinkOverlay.snapshot()` is empty, return
 *     immediately. Empty overlay = no draw cost. The underline + faint
 *     highlight are visual affordances; they're only present when at
 *     least one URL is visible.
 *
 * **Threading:** `onDraw` runs on the Main thread. `LinkOverlay.snapshot()`
 * returns a defensive copy, so a Main-thread `refresh()` happening
 * concurrently with `onDraw` (rare but possible if the OS schedules
 * both in the same frame) won't tear the draw.
 */
internal class LinkOverlayView(
    context: Context,
    private val overlay: LinkOverlay,
    private val topRowProvider: () -> Int,
    private val fontWidthProvider: () -> Int,
    private val fontLineSpacingProvider: () -> Int,
) : View(context) {

    private val underlinePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UNDERLINE_COLOR
        style = Paint.Style.STROKE
        strokeWidth = UNDERLINE_THICKNESS_PX
        // Same color as Termux's selection-mode highlight (a
        // light-blue accent that reads on both light and dark
        // terminal backgrounds). Picked to match Material 3
        // "tertiary" tonal palette without depending on
        // Compose at draw time.
    }

    private val highlightPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HIGHLIGHT_COLOR
        style = Paint.Style.FILL
    }

    init {
        isFocusable = false
        isClickable = false
        // Transparent until we have spans — `setBackgroundColor` with
        // alpha 0 would still cost a draw pass. The View's default
        // background is transparent.
    }

    /**
     * Hook for `TerminalView.onOverlayRefreshed()` to call after
     * `LinkOverlay.refresh()` completes on the Main thread. Calling
     * `invalidate()` from the IO thread (i.e. from inside the
     * `setOnTranscriptInvalidateListener` callback) is a no-op until
     * we hop to Main — but the typical caller is `view.post { ... }`,
     * so by the time we reach here we're already on Main.
     */
    fun onRefreshCompleted() = invalidate()

    /**
     * Hook for `ScrollbackController.state` transitions: when
     * `isInScrollback` flips, the spans become un-tappable (the
     * scrollback is read-only) so the highlight should follow.
     */
    fun onScrollbackStateChanged() = invalidate()

    /**
     * Hook for `mTopRow` change: when the user scrolls the inner view,
     * the absolute row indices in the snapshot need to re-map to screen
     * rows. Cheaper than re-running `LinkOverlay.refresh()` for a
     * pure-scroll-no-new-output gesture.
     */
    fun onTopRowChanged() = invalidate()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // T16 edge-only: bail out before doing any work if the snapshot
        // is empty. This is the common case (most rows have no URL).
        val spans = overlay.snapshot()
        if (spans.isEmpty()) return

        val topRow = topRowProvider()
        val fontWidth = fontWidthProvider()
        val fontLineSpacing = fontLineSpacingProvider()
        if (fontWidth <= 0 || fontLineSpacing <= 0) return

        val viewHeight = height
        val viewWidth = width

        for (span in spans) {
            // Translate the absolute transcript row to a screen row.
            // `screenRow == 0` is the topmost visible row.
            val screenRow = span.row - topRow
            if (screenRow < 0) continue
            val baselineY = (screenRow + 1) * fontLineSpacing
            if (baselineY > viewHeight) continue

            val startX = span.startCol * fontWidth
            // endCol is exclusive (the index just past the last URL
            // char), so the underline ends at `(endCol) * fontWidth`.
            val endX = span.endCol * fontWidth
            if (endX > viewWidth) continue
            if (startX >= endX) continue

            // Faint background highlight — sits BEHIND the text (but
            // we can't actually layer that without re-rendering the
            // emulator, so we draw with low alpha and trust that the
            // emulator's text glyphs sit on top in the FrameLayout z
            // order). Visually a subtle tint band.
            canvas.drawRect(
                startX.toFloat(),
                (baselineY - fontLineSpacing).toFloat(),
                endX.toFloat(),
                baselineY.toFloat(),
                highlightPaint,
            )
            // Underline — the primary visual affordance. Drawn 1px
            // above the baseline so it sits inside the row band,
            // matching Termux's selection cursor underline convention.
            val underlineY = (baselineY - 1.5f)
            canvas.drawLine(
                startX.toFloat(),
                underlineY,
                endX.toFloat(),
                underlineY,
                underlinePaint,
            )
        }
    }

    companion object {
        /**
         * Underline color. Material 3 "tertiary" tonal palette light-blue,
         * chosen to read on both light and dark terminal backgrounds.
         * 0xFF4FC3F7.
         */
        private const val UNDERLINE_COLOR: Int = 0xFF4FC3F7.toInt()

        /**
         * Highlight color. Same hue as the underline, 20% alpha. Drawn
         * behind the URL text (best-effort — FrameLayout can't layer).
         */
        private const val HIGHLIGHT_COLOR: Int = 0x334FC3F7

        /**
         * Underline thickness in pixels. 2 px reads cleanly at the
         * default 14 sp font; below that the underline disappears on
         * hidpi tablets.
         */
        private const val UNDERLINE_THICKNESS_PX: Float = 2f
    }
}