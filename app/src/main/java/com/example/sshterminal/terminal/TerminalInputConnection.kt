package com.example.sshterminal.terminal

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection

/**
 * InputConnection that routes IME events (拼音 composing + 汉字 commit) and physical
 * keys to a [TerminalEndpoint] in a way that keeps the IME state and the SSH-side
 * character stream in lock-step.
 *
 * Per `implementation_plan.md` §"输入链路设计". The two-link separation rule is
 * enforced in three places:
 *
 *  1. Every state-mutating method updates [lastComposingSnapshot] BEFORE mutating
 *     [isComposing], so [deleteSurroundingText] can ask "what was the composing
 *     state at the moment the user's intent was formed?" rather than the current
 *     state. Gboard's "setComposingText(\"\") then deleteSurroundingText(1, 0)"
 *     pattern is the motivating bug — without the snapshot, the delete would be
 *     routed to the SSH channel even though the user was mid-composition.
 *
 *  2. [sendKeyEvent] explicitly swallows the event while composing so the IME
 *     keeps exclusive control of the letter keys.
 *
 *  3. The KEYCODE_LANGUAGE_SWITCH / Ctrl+Space / Shift+Space routing is handled
 *     in [KeyMapper] as a "consumed but not transmitted" verdict — see
 *     [KeyMapper.toAnsiSequence].
 */
class TerminalInputConnection(
    private val terminalView: TerminalComposingView,
    private val endpoint: TerminalEndpoint,
) : BaseInputConnection(terminalView.asView, true) {

    private val composingBuffer = StringBuilder()

    @Volatile
    private var composing = false

    /**
     * "Was the user in an IME composition context the last time the IME talked to us?"
     *
     * Distinct from [composing]: [composing] mirrors the current `isComposing` text
     * flag (true while `setComposingText("ni")` is the latest call). This flag,
     * by contrast, latches to `true` once the user enters composition and stays
     * `true` until the session is idle (no composing / commit / finish since the
     * last `deleteSurroundingText` reset).
     *
     * [deleteSurroundingText] consults THIS flag, not [composing]. This is what
     * closes the P0 Gboard race in implementation_plan.md:
     *
     *   "Gboard 会先调 setComposingText("") 将 isComposing 置 false,
     *    再在同一事务内调 deleteSurroundingText(1,0)。
     *    此时若直接读 isComposing 会误判为"非组合" → 发 DEL 到远端(BUG)。"
     *
     * The key insight: even after the IME flips [composing] back to false, the
     * subsequent delete is still part of the same user intent (cancel a pinyin
     * selection with backspace), so it must NOT go to SSH.
     */
    @Volatile
    private var userInImeContext = false

    fun isComposing(): Boolean = composing

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        // Entering composition: latch the IME-context flag so subsequent deletes
        // (including ones Gboard issues after setComposingText("")) are swallowed.
        if (text.isNotEmpty()) userInImeContext = true
        composingBuffer.clear()
        composingBuffer.append(text)
        composing = text.isNotEmpty()
        terminalView.showComposingHint(text.toString())
        return super.setComposingText(text, newCursorPosition)
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        // Commit is also part of IME interaction — keep the latch on so any
        // backspace the user does right after committing a candidate still
        // routes through the IME rather than SSH.
        userInImeContext = true
        composingBuffer.clear()
        composing = false
        terminalView.hideComposingHint()
        if (text.isNotEmpty()) {
            endpoint.write(text.toString().toByteArray(Charsets.UTF_8))
        }
        return super.commitText(text, newCursorPosition)
    }

    override fun finishComposingText(): Boolean {
        // Same rationale as commitText: the user just cancelled, but a backspace
        // right after still belongs to the IME-driven interaction.
        userInImeContext = true
        composingBuffer.clear()
        composing = false
        terminalView.hideComposingHint()
        return super.finishComposingText()
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        super.setComposingRegion(start, end)

    override fun setSelection(start: Int, end: Int): Boolean =
        super.setSelection(start, end)

    /**
     * Backspace/delete from the IME. Uses [userInImeContext], NOT [composing] —
     * see the field's kdoc for the Gboard race this avoids.
     *
     * After a successful non-IME delete (we forwarded DEL bytes to SSH), reset
     * the latch so the NEXT truly-idle backspace also reaches SSH. If we DIDN'T
     * do this, every backspace forever would be swallowed after the first IME
     * interaction.
     */
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (userInImeContext) {
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
        if (beforeLength <= 0) return super.deleteSurroundingText(beforeLength, afterLength)
        repeat(beforeLength) { endpoint.write(byteArrayOf(0x7F.toByte())) }
        return true
    }

    /**
     * Some IMEs route physical keys via [sendKeyEvent] instead of [onKeyDown].
     * If the IME is composing we consume the event so the IME keeps exclusive
     * ownership of the letter keys (matches the dual-link contract).
     */
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (composing) return true
        val sequence = KeyMapper.toAnsiSequence(event.keyCode, event) ?: return false
        endpoint.write(sequence)
        return true
    }
}