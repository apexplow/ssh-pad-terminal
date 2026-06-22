package com.example.sshterminal.terminal

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection

class TerminalInputConnection(
    private val terminalView: TerminalComposingView,
    private val endpoint: TerminalEndpoint,
) : BaseInputConnection(terminalView.asView, true) {

    private val composingBuffer = StringBuilder()

    @Volatile
    private var composing = false

    fun isComposing(): Boolean = composing

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (text.isEmpty()) return finishComposingText()
        composingBuffer.clear()
        composingBuffer.append(text)
        composing = true
        terminalView.showComposingHint(text.toString())
        return super.setComposingText(text, newCursorPosition)
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        composingBuffer.clear()
        composing = false
        terminalView.hideComposingHint()
        if (text.isNotEmpty()) endpoint.write(text.toString().toByteArray(Charsets.UTF_8))
        return super.commitText(text, newCursorPosition)
    }

    override fun finishComposingText(): Boolean {
        composingBuffer.clear()
        composing = false
        terminalView.hideComposingHint()
        return super.finishComposingText()
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength <= 0) return super.deleteSurroundingText(beforeLength, afterLength)
        if (composing) return super.deleteSurroundingText(beforeLength, afterLength)
        repeat(beforeLength) { endpoint.write(byteArrayOf(0x7F.toByte())) }
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        return super.setComposingRegion(start, end)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        return super.setSelection(start, end)
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val sequence = KeyMapper.toAnsiSequence(event.keyCode, event) ?: return false
        endpoint.write(sequence)
        return true
    }
}
