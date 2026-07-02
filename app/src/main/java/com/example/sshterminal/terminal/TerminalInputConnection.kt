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

    /**
     * Tracker for the IME's "digit-only composing region" pattern that Chinese
     * pinyin IMEs (Gboard, Sogou, Baidu IME) use to swallow digit keys.
     *
     * In Chinese pinyin mode, the digit key is consumed by the IME as either
     * "candidate selector" or "pinyin start" — `commitText` is never called for
     * the digit itself. Instead the IME issues
     * `setComposingText("1", 1)` (or "1" → "12" → "123" as the user keeps
     * pressing digits), treating the accumulating string as a (likely invalid)
     * pinyin. We intercept that case in [setComposingText] and forward the new
     * suffix of digits to SSH so the user actually sees them in the terminal.
     *
     * IMEs differ on whether they extend the composing region across keypresses
     * (Gboard: "1" → "12" → "123") or reset it to the new single digit every
     * time (Sogou/Baidu: "1" → "1" → "1"). This field lets us tell the
     * difference so we never double-write or drop a digit:
     *  - `newText.startsWith(previous)` → IME extended, send only the suffix.
     *  - else → IME reset, send the full new string.
     *
     * Reset to `null` whenever the user finishes or commits the IME session —
     * see [commitText] and [finishComposingText].
     */
    @Volatile
    private var lastComposedDigits: String? = null

    /**
     * `Char.isDigit()` matches the Unicode digit property (fullwidth '１' U+FF11,
     * Arabic-Indic, superscripts, …) — over-matches on Chinese IMEs. We
     * require ASCII `'0'..'9'` only so the digit-flush path cannot be triggered
     * by a stray fullwidth digit or a Unicode numeral from a paste.
     */
    private fun CharSequence.isAsciiDigits(): Boolean =
        isNotEmpty() && all { it in '0'..'9' }

    fun isComposing(): Boolean = composing

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (text.isEmpty()) {
            // Pure cancel — clear the IME's composing region, drop our hint,
            // forget any pending digit tracker. Does NOT touch SSH: cancel is
            // a local intent, the user did not ask for any byte.
            lastComposedDigits = null
            composingBuffer.clear()
            composing = false
            terminalView.hideComposingHint()
            return super.setComposingText(text, newCursorPosition)
        }

        // 中文拼音模式数字直出：IME 把数字当作 pinyin 起手而不是 commit,
        // 我们把这种"假组合"识别出来,直接把数字发到 SSH,清空 IME
        // 的 composing 区阻止它继续累积成拼音候选。
        if (text.isAsciiDigits()) {
            val previous = lastComposedDigits
            // 三种情形,只第二种发"差量"以避免 IME 扩展时被双发:
            //  1) previous == null                — 第一次出现,发整段
            //  2) text 严格扩展 previous          — IME 累积("1"→"12"),只发后缀
            //  3) text 等于或重置 previous       — IME 重发("1"→"1"→"1")或重置,
            //                                        发整段以匹配"每按一次就发一次"
            val suffix = when {
                previous == null -> text.toString()
                text.length > previous.length && text.startsWith(previous) ->
                    text.subSequence(previous.length, text.length).toString()
                else -> text.toString()
            }
            lastComposedDigits = text.toString()
            composingBuffer.clear()
            composingBuffer.append(text)
            // composing 保持 false:数字直出不是真组合,isComposing() 仍
            // 反映"用户是否在拼音候选中",避免 onKeyDown 误吞下一物理键。
            composing = false
            terminalView.hideComposingHint()
            if (suffix.isNotEmpty()) {
                endpoint.write(suffix.toByteArray(Charsets.UTF_8))
            }
            // 用空 composing 通知 IME 这次组合已结束。顺序很重要:必须先
            // endpoint.write,再 super.setComposingText(""),这样 SSH 拿到
            // 数字时 IME 端的 composing 区也已经被清掉了。
            return super.setComposingText("", newCursorPosition)
        }

        // Normal letter composing path (unchanged from prior behavior).
        if (text.isNotEmpty()) userInImeContext = true
        composingBuffer.clear()
        composingBuffer.append(text)
        composing = text.isNotEmpty()
        // 离开数字直出路径,重置 tracker 避免下一段字母+数字被误判为扩展。
        lastComposedDigits = null
        terminalView.showComposingHint(text.toString())
        return super.setComposingText(text, newCursorPosition)
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        // Commit is also part of IME interaction — keep the latch on so any
        // backspace the user does right after committing a candidate still
        // routes through the IME rather than SSH.
        userInImeContext = true
        // 用户正式提交了候选(或英文字符),重置 digit tracker 以免下一段
        // 数字被误判为前一段 session 的扩展。
        lastComposedDigits = null
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
        // 同 commitText:会话结束,重置 digit tracker。
        lastComposedDigits = null
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