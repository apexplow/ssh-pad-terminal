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

    val termuxView: com.termux.view.TerminalView =
        com.termux.view.TerminalView(context, attrs).also { child ->
            child.isFocusable = false
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        if (connection?.isComposing() == true) {
            if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_ENTER) return false
        }

        if (event.isPrintingKey && !event.isCtrlPressed && !event.isAltPressed) return false

        val sequence = KeyMapper.toAnsiSequence(keyCode, event) ?: return false
        endpoint.write(sequence)
        return true
    }

    override fun showComposingHint(text: String) {
        composingHintListener?.invoke(text)
    }

    override fun hideComposingHint() {
        composingHintListener?.invoke(null)
    }
}
