package com.taosun.hanterm.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.taosun.hanterm.logging.AppLog

/**
 * Owns the IME → terminal key routing pipeline.
 *
 * This class holds the current [TerminalEndpoint] and the cached
 * [TerminalInputConnection]. It implements the pre-IME hook, the composing-gate
 * logic, Ctrl/Alt Send chords, and the clipboard-paste path. All routing
 * invariants from `implementation_plan.md` §"KeyEvent 路由规则表" are
 * preserved here.
 */
internal class ImeKeyRouter(
    private val context: Context,
) {

    private var endpoint: TerminalEndpoint = TerminalEndpoint {}
    private var inputConnection: TerminalInputConnection? = null

    /**
     * Binds a new endpoint and drops the IME's cached state for [view].
     *
     * This method tells the [InputMethodManager] to restart input on [view],
     * which forces the IME to discard its old [TerminalInputConnection]. It
     * then updates the internal endpoint and clears the cached connection so
     * the next IME event creates a fresh [TerminalInputConnection] pointing at
     * the new endpoint. Call this whenever the terminal session changes (e.g.
     * after a reconnect or when swapping transports).
     */
    fun bindEndpoint(
        endpoint: TerminalEndpoint,
        view: android.view.View,
        imm: InputMethodManager?,
    ) {
        refreshInput(view, imm)
        this.endpoint = endpoint
    }

    /**
     * Drop the IME's cached [TerminalInputConnection] and ask
     * [InputMethodManager] to rebuild one.
     *
     * Used on reconnect ([bindEndpoint]) and when a remote TUI enters the
     * alternate screen — Gboard otherwise keeps a stale connection, so the
     * first switch into Chinese pinyin after `cursor-agent` (etc.) buffers
     * commits until the user mashes the language toggle enough times to
     * force an IME restart (then every pending 汉字 lands in one burst).
     */
    fun refreshInput(
        view: android.view.View,
        imm: InputMethodManager?,
    ) {
        inputConnection?.takeIf { it.isComposing() }?.finishComposingText()
        inputConnection = null
        imm?.restartInput(view)
    }

    fun onCreateInputConnection(
        outAttrs: EditorInfo,
        view: TerminalComposingView,
    ): InputConnection {
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_NORMAL or
            // Keep NO_SUGGESTIONS off so IMEs show candidate UI for CJK.
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return TerminalInputConnection(view, endpoint).also { inputConnection = it }
    }

    /**
     * Dispatch the key event through the View's standard key-dispatch path so
     * that [onKeyDown] runs on this router.
     */
    fun dispatchKeyEvent(event: KeyEvent, host: android.view.View): Boolean =
        event.dispatch(host, host.keyDispatcherState, host)

    /**
     * Pre-IME hook. Intercepts Ctrl+Shift+V before the IME can consume it.
     */
    fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (KeyMapper.resolve(event) is KeyResolution.Paste) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                pasteFromClipboard()
            }
            // Consume both DOWN and UP so the IME never sees either half.
            return true
        }
        return false
    }

    fun activeInputConnection(): TerminalInputConnection? = inputConnection

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val connection = inputConnection
        AppLog.d(
            "IME",
            "onKeyDown keyCode=$keyCode unicodeChar=${event.unicodeChar} " +
                "composing=${connection?.isComposing()} ctrl=${event.isCtrlPressed} shift=${event.isShiftPressed}",
        )

        // While composing, the IME owns the input pipeline for plain letters.
        if (connection?.isComposing() == true) {
            val verdict = KeyMapper.resolve(event)
            if (verdict is KeyResolution.Swallow) return true
            if (verdict is KeyResolution.Paste) {
                pasteFromClipboard()
                return true
            }
            if (verdict is KeyResolution.Send &&
                (event.isCtrlPressed || event.isAltPressed)
            ) {
                connection.finishComposingText()
                endpoint.write(verdict.bytes)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_ENTER) return false
            return false
        }

        if (event.isPrintingKey && !event.isCtrlPressed && !event.isAltPressed) return false

        return when (val verdict = KeyMapper.resolve(event)) {
            is KeyResolution.Send -> {
                endpoint.write(verdict.bytes)
                true
            }
            KeyResolution.Paste -> {
                pasteFromClipboard()
                true
            }
            KeyResolution.Swallow -> true
            KeyResolution.Ignore -> false
        }
    }

    /**
     * Reads the system clipboard's primary clip and writes its text contents to
     * the bound [TerminalEndpoint] as UTF-8 bytes.
     */
    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        if (!clipboard.hasPrimaryClip()) return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context) ?: return
        if (text.isEmpty()) return
        endpoint.write(text.toString().toByteArray(Charsets.UTF_8))
    }
}
