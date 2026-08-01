package com.apexplow.hanterm.terminal.link

import com.apexplow.hanterm.logging.AppLog
import com.apexplow.hanterm.logging.LogClassification
import com.termux.terminal.TerminalEmulator

/**
 * Visible-window URL scan. Holds a row → URL span map and refreshes it
 * from the live emulator on demand.
 *
 * **Sprint 4 eng-review decisions baked into this file:**
 *   - T3 visible-window batch: only scan `mTopRow .. mTopRow + mRows`.
 *     The full scrollback is irrelevant — links that scrolled off the
 *     top are gone from the user's view, and the scrollback scan cost
 *     grows unbounded (`cat largefile.log` = tens of thousands of rows).
 *   - T6 plain `HashMap`: all mutation + read happens on the Main
 *     thread. The original plan called for `ConcurrentHashMap`; the
 *     review surfaced that the marshalling via `view.post { refresh() }`
 *     (see [com.apexplow.hanterm.terminal.TerminalView]) means there's
 *     no concurrent mutation, and `HashMap` has cheaper put/clear.
 *   - Public `getSelectedText(x1, y1, x2, y2)` row-read — no reflection
 *     into Termux internals. The same API the existing
 *     `TermuxViewBridge.extractSelectedTextSafely` uses.
 *
 * **Threading invariant:** every `refresh()` and `findUrlAt` call is
 * expected to run on the Main thread. The visible-row read goes through
 * `TerminalEmulator.getSelectedText`, which has no documented
 * happens-before with `BufferedPtyBridge.Endpoint.write` (the sshj IO
 * loop calls `emulator.append(bytes)` on `Dispatchers.IO`). The
 * `BufferedPtyBridge.lastWriteUptimeMs` `@Volatile` (Sprint 4 T17) lets
 * [refresh] skip a torn read inside 16 ms of an IO append. The
 * marshalling contract is documented in `package-info.kt`.
 *
 * **Not thread-safe.** Callers MUST NOT call these methods from a
 * background thread. The `view.post { ... }` wrapper in
 * `TerminalView.dispatchTouchEvent` is the single entry point.
 */
internal class LinkOverlay(
    private val emulatorSource: () -> TerminalEmulator?,
    private val topRowSource: () -> Int,
    /**
     * Last IO-thread write timestamp (`SystemClock.uptimeMillis`). The
     * [`BufferedPtyBridge.lastWriteUptimeMs`] `@Volatile` is read here as
     * a torn-write guard — see [refresh] for the 16 ms skip window.
     */
    private val lastWriteUptimeMsSource: () -> Long,
) {

    /**
     * A URL located on a specific row, with its column range.
     *
     * [startCol] / [endCol] are positions within the row's text string
     * returned by [TerminalEmulator.getSelectedText] — NOT pixel
     * coordinates. The link overlay view multiplies by `fontLineSpacing`
     * to position the underline. T-MEDIUM-1 tracks a Robolectric
     * benchmark for this mapping.
     */
    data class UrlSpan(
        val row: Int,
        val startCol: Int,
        val endCol: Int,
        val url: String,
    )

    // T6: HashMap, not ConcurrentHashMap — Main-thread only.
    // Keyed by absolute row index in the live transcript (mTopRow + offset).
    private val spans: HashMap<Int, List<UrlSpan>> = HashMap()

    /**
     * Snapshot copy for the overlay view's `onDraw`. Returning a defensive
     * copy means the view iterates an immutable snapshot, so a concurrent
     * `refresh()` call from a different Main-thread frame cannot tear
     * the draw — even though Main is single-threaded, a future caller
     * might `view.post { refresh() }` from inside `onDraw` indirectly.
     */
    fun snapshot(): List<UrlSpan> {
        val out = ArrayList<UrlSpan>(spans.size)
        for (list in spans.values) out.addAll(list)
        return out
    }

    /**
     * URL span at absolute transcript [row] / [col], or `null` if no
     * span covers that cell. Returns the FIRST matching span on the
     * row — `LinkOverlay` keeps at most one span per row (the first
     * URL detected on that row).
     *
     * [endCol] is exclusive (`start + url.length`), matching
     * [LinkOverlayView]'s underline end.
     */
    fun findUrlAt(row: Int, col: Int): UrlSpan? {
        val list = spans[row] ?: return null
        return list.firstOrNull { col >= it.startCol && col < it.endCol }
    }

    /**
     * Hit-test using **screen** row/col (touch `y / lineSpacing`,
     * `x / fontWidth`). Translates via [topRowSource] so a long-press
     * matches the same absolute-row keys [refresh] wrote — without this,
     * scrolled content (`mTopRow != 0`) never resolves a URL.
     */
    fun findUrlAtScreen(screenRow: Int, col: Int): UrlSpan? =
        findUrlAt(topRowSource() + screenRow, col)

    /**
     * Visible-window batch (T3): iterate `top .. top + mRows`, run
     * [LinkDetector.firstUrlIn] per row, write the result into [spans].
     * Clear-then-fill (not merge-into) so rows that scrolled off the top
     * disappear naturally.
     *
     * 16 ms torn-write guard: if the IO thread wrote to the emulator
     * less than 16 ms before this call, skip the refresh. The IO thread
     * is still mid-append; reading rows now could see a partial row.
     * Skip this frame; the next invalidate cycle (post-write complete)
     * will catch up. Track skipped count in AppLog so we can detect
     * runaway-skip regressions.
     */
    fun refresh() {
        val emulator = emulatorSource() ?: return
        val top = topRowSource()
        val rows = emulator.mRows
        val cols = emulator.mColumns

        val now = android.os.SystemClock.uptimeMillis()
        val lastWrite = lastWriteUptimeMsSource()
        if (lastWrite != 0L && now - lastWrite < REFRESH_GUARD_MS) {
            // not an error — happens during normal sshj IO bursts. Log
            // at debug so a real-device log read can confirm we're not
            // skipping forever.
            AppLog.d(
                "LinkOverlay",
                "refresh skipped (${now - lastWrite}ms post-write)",
                classification = LogClassification.Diagnostic,
            )
            return
        }

        val fresh = HashMap<Int, List<UrlSpan>>(rows.coerceAtMost(64))
        for (rowOffset in 0 until rows) {
            val absRow = top + rowOffset
            val rowText = emulator.getSelectedText(0, absRow, cols, absRow) ?: continue
            val url = LinkDetector.firstUrlIn(rowText) ?: continue
            val start = rowText.indexOf(url)
            if (start < 0) continue
            val end = start + url.length
            fresh[absRow] = listOf(UrlSpan(row = absRow, startCol = start, endCol = end, url = url))
        }
        spans.clear()
        spans.putAll(fresh)
    }

    companion object {
        /**
         * Torn-write guard window. Matches one frame at 60 Hz; longer
         * than this and we'd be discarding refreshes the IO thread
         * actually finished. Tuned empirically against
         * `BufferedPtyBridge.Endpoint.write` cadence.
         */
        const val REFRESH_GUARD_MS = 16L
    }
}