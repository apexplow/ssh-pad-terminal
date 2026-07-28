package com.apexplow.hanterm.terminal

import kotlin.math.max

/**
 * Tracks terminal cell dimensions and forwards SIGWINCH-style resize events.
 *
 * The tracker debounces identical (cols, rows) so keyboard insets / IME
 * show-hide / unrelated layout passes don't spam the SSH channel. It also
 * resizes the emulator directly because Termux's `updateSize()` is a no-op
 * when `mTermSession` is deliberately left null.
 */
internal class PtyResizeTracker(
    private val view: com.termux.view.TerminalView,
) {

    private var lastResizeCols = 0
    private var lastResizeRows = 0
    private var listener: ((cols: Int, rows: Int, widthPx: Int, heightPx: Int) -> Unit)? = null

    /**
     * Registers the resize callback and, if non-null, fires it once immediately
     * so a freshly-bound session receives the current size rather than waiting
     * for the next layout pass.
     *
     * `force = true` bypasses the (cols, rows) debounce on that first fire.
     */
    fun setPtyResizeListener(
        listener: ((cols: Int, rows: Int, widthPx: Int, heightPx: Int) -> Unit)?,
    ) {
        this.listener = listener
        if (listener != null) {
            onSizeChanged(view.width, view.height, force = true)
        }
    }

    /**
     * Computes the cell grid from pixel dimensions and notifies the listener
     * when it changes.
     */
    fun onSizeChanged(widthPx: Int, heightPx: Int, force: Boolean = false) {
        if (widthPx <= 0 || heightPx <= 0) return
        val renderer = view.mRenderer ?: return
        val emulator = view.mEmulator ?: return

        val fontWidth = renderer.getFontWidth()
        val fontLineSpacing = renderer.getFontLineSpacing()
        if (fontWidth <= 0 || fontLineSpacing <= 0) return

        val newColumns = max(4, (widthPx / fontWidth).toInt())
        val newRows = max(4, heightPx / fontLineSpacing)
        if (newColumns != emulator.mColumns || newRows != emulator.mRows) {
            emulator.resize(newColumns, newRows)
            view.postInvalidateOnAnimation()
        }

        val cols = emulator.mColumns
        val rows = emulator.mRows
        if (cols <= 0 || rows <= 0) return

        if (!force && cols == lastResizeCols && rows == lastResizeRows) return
        lastResizeCols = cols
        lastResizeRows = rows
        listener?.invoke(cols, rows, widthPx, heightPx)
    }
}
