package com.example.sshterminal.terminal

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), TerminalComposingView {

    private var endpoint: TerminalEndpoint = TerminalEndpoint {}
    private var inputConnection: TerminalInputConnection? = null
    private var composingHintListener: ((String?) -> Unit)? = null
    private var ptyResizeListener: ((cols: Int, rows: Int, widthPx: Int, heightPx: Int) -> Unit)? = null

    /**
     * Tracks the last PTY dimensions we reported so we only fire the resize
     * listener when something actually changed. OnGlobalLayoutListener fires
     * for many unrelated reasons (keyboard insets, IME show/hide, scroll
     * bounds) and we don't want to spam the SSH channel with no-op SIGWINCHs.
     */
    private var lastResizeCols = 0
    private var lastResizeRows = 0

    val termuxView: com.termux.view.TerminalView =
        com.termux.view.TerminalView(context, attrs).also { child ->
            child.isFocusable = false
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            // The Termux view recomputes cols/rows in its own onSizeChanged
            // path. We listen on the same view: after it lays out, the
            // emulator's numColumns/numRows reflect the new grid, and we can
            // forward them to the SSH session.
            child.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                reportPtyResize(v.width, v.height)
            }
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun bindEndpoint(endpoint: TerminalEndpoint) {
        this.endpoint = endpoint
        inputConnection = null
    }

    fun setComposingHintListener(listener: (String?) -> Unit) {
        composingHintListener = listener
    }

    /**
     * Registers a callback fired whenever the visible terminal grid dimensions
     * change (rotation, split-screen, multi-window, soft-keyboard show/hide).
     *
     * The callback receives `(cols, rows, widthPx, heightPx)`. Pixel dimensions
     * are 0 if the view hasn't been laid out yet — call sites should treat 0
     * as "unspecified" and forward only the cell dimensions to the remote.
     *
     * Added in Sprint 2 to drive SIGWINCH. Does NOT touch any IME logic
     * (onKeyDown / onCreateInputConnection / InputConnection callbacks are
     * untouched), so the Sprint 1.5 IME chain remains stable.
     */
    fun setPtyResizeListener(listener: ((cols: Int, rows: Int, widthPx: Int, heightPx: Int) -> Unit)?) {
        ptyResizeListener = listener
        // Fire once on registration so a freshly-bound session gets the
        // current size rather than waiting for the next layout pass.
        if (listener != null) reportPtyResize(termuxView.width, termuxView.height)
    }

    fun activeInputConnection(): TerminalInputConnection? = inputConnection

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return TerminalInputConnection(this, endpoint).also { inputConnection = it }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val connection = inputConnection
        // While composing, the IME owns the input pipeline. Two exceptions:
        //  - DEL/ENTER still need to be consumed here so the IME doesn't see them
        //    twice (we return false to let the IME handle, but we still claim the
        //    physical event to avoid leaking it via an alternate path).
        //  - Ctrl+Space / Shift+Space / KEYCODE_LANGUAGE_SWITCH must be swallowed
        //    even mid-composition so they never reach the SSH channel.
        if (connection?.isComposing() == true) {
            val verdict = KeyMapper.resolve(keyCode, event)
            if (verdict is KeyResolution.Swallow) return true
            if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_ENTER) return false
            return false
        }

        if (event.isPrintingKey && !event.isCtrlPressed && !event.isAltPressed) return false

        when (val verdict = KeyMapper.resolve(keyCode, event)) {
            is KeyResolution.Send -> {
                endpoint.write(verdict.bytes)
                return true
            }
            KeyResolution.Swallow -> return true
            KeyResolution.Ignore -> return false
        }
    }

    override fun showComposingHint(text: String) {
        composingHintListener?.invoke(text)
    }

    override fun hideComposingHint() {
        composingHintListener?.invoke(null)
    }

    private fun reportPtyResize(widthPx: Int, heightPx: Int) {
        // Termux v0.118 exposes the emulator as a public field (mEmulator)
        // rather than a getEmulator() accessor; the underlying emulator's
        // dimensions are also public fields (mRows, mColumns). No
        // reflection needed — just attribute access on the Java fields.
        val emulator = termuxView.mEmulator ?: return
        val cols = emulator.mColumns
        val rows = emulator.mRows
        if (cols <= 0 || rows <= 0) return
        if (cols == lastResizeCols && rows == lastResizeRows) return
        lastResizeCols = cols
        lastResizeRows = rows
        ptyResizeListener?.invoke(cols, rows, widthPx, heightPx)
    }
}
