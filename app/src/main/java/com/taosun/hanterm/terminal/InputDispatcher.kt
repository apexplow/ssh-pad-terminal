package com.taosun.hanterm.terminal

import android.view.KeyEvent

/**
 * Neutral input event fed into [InputDispatcher.dispatch].
 *
 * Adapters (currently [ImeKeyRouter] and [TerminalInputConnection]) translate
 * platform events — Android [KeyEvent] / `BaseInputConnection` callbacks — into
 * these values and pass them to the dispatcher. The dispatcher owns the
 * routing decision; the adapters own only the platform side effects
 * (writing bytes, calling `super.setComposingText`, toggling the composing
 * hint). See issue #14 and `docs/ARCHITECTURE.md` §7.
 */
sealed class InputEvent {
    data class Key(val keyEvent: KeyEvent) : InputEvent()
    data class ImeCommit(val text: CharSequence) : InputEvent()
    data class ImeComposing(val text: CharSequence) : InputEvent()
    data class ImeDelete(val beforeLength: Int) : InputEvent()
    data object ImeFinishComposing : InputEvent()
}

/**
 * Routing verdict returned by [InputDispatcher.dispatch].
 *
 * - [Send] — write these bytes to the SSH channel and consume the event.
 * - [Swallow] — consume the event but do NOT write any bytes (used for
 *   IME-internal keys like Ctrl+Space).
 * - [Ignore] — no opinion; let the caller return `false` so the platform
 *   routes the event to its default handler (typically InputConnection).
 * - [Paste] — read the system clipboard and write its UTF-8 bytes. The
 *   caller owns the clipboard access (the dispatcher has no Android
 *   dependencies).
 * - [FinishComposingThenSend] — first call `finishComposingText()` (so
 *   subsequent letter keys don't double as pinyin letters), THEN write
 *   these bytes. Encodes the "physical Ctrl/Alt chord wins over pinyin"
 *   rule documented in `CLAUDE.md` "Routing invariants" — see the
 *   Ctrl/Alt-modifier chord during composing row.
 */
sealed class DispatchResult {
    data class Send(val bytes: ByteArray) : DispatchResult()
    data object Swallow : DispatchResult()
    data object Ignore : DispatchResult()
    data object Paste : DispatchResult()
    data class FinishComposingThenSend(val bytes: ByteArray) : DispatchResult()
}

/**
 * Single owner of the terminal input routing state machine.
 *
 * Every routing decision documented in `CLAUDE.md` "Routing invariants" and
 * `implementation_plan.md` §"KeyEvent 路由规则表" lives here. Adapters
 * translate platform events into [InputEvent] values, call [dispatch], and
 * apply the returned [DispatchResult] (write bytes, swallow, return false,
 * read clipboard, …).
 *
 * State ([composing], [lastComposedDigits]) is consolidated from what used
 * to be split across [TerminalInputConnection] and [ImeKeyRouter]. Both
 * adapters share a single dispatcher instance (typically held by
 * [ImeKeyRouter] and handed to [TerminalInputConnection] on creation) so
 * the composing flag and digit-flush tracker stay coherent across the
 * View.onKeyDown path and the InputConnection callback path.
 *
 * Threading: [dispatch] may be called from the UI thread
 * (View.onKeyDown / View.dispatchKeyEvent) and from the IME thread
 * (BaseInputConnection callbacks). [composing] and [lastComposedDigits]
 * are `@Volatile` so reads from either thread see the latest value without
 * additional synchronisation.
 *
 * Adapters that need to manage the composing hint overlay observe
 * [isComposing] after each dispatch and call `terminalView.show/hide
 * ComposingHint` accordingly.
 */
class InputDispatcher {

    /**
     * "Is the IME currently in a composing session?" Mirrors the
     * `composing` flag previously owned by [TerminalInputConnection]
     * (`TerminalInputConnection.kt:37-38`). `true` while the user has
     * an active pinyin / candidate-selector session; `false` after
     * `commitText`, `finishComposingText`, an empty `setComposingText`,
     * or a successful digit-flush.
     */
    @Volatile
    private var composing: Boolean = false

    /**
     * Tracker for the IME's "digit-only composing region" pattern that
     * Chinese pinyin IMEs (Gboard, Sogou, Baidu IME) use to swallow digit
     * keys. See [dispatch] for the three-case logic (first / extension /
     * reset). `null` outside a digit-flush session.
     */
    @Volatile
    private var lastComposedDigits: String? = null

    /**
     * True while the dispatcher considers itself inside a composing
     * session. Adapters read this to drive the hint overlay and to gate
     * the TIC-SK-05 special case (Gboard soft-keyboard ENTER while
     * composing).
     */
    fun isComposing(): Boolean = composing

    /**
     * Synchronously zero all composing state. Called by
     * [ImeKeyRouter.refreshInput] as a belt-and-suspenders reset for the
     * case where the cached `TerminalInputConnection` was dropped without
     * `finishComposingText` first being called (e.g. external nulling).
     */
    fun reset() {
        composing = false
        lastComposedDigits = null
    }

    /**
     * Compute the routing verdict for [event] without performing any
     * side effects. Adapters apply the returned [DispatchResult] (write
     * bytes, call `super.setComposingText`, toggle hint, …).
     */
    fun dispatch(event: InputEvent): DispatchResult = when (event) {
        is InputEvent.Key -> dispatchKey(event.keyEvent)
        is InputEvent.ImeCommit -> dispatchImeCommit(event.text)
        is InputEvent.ImeComposing -> dispatchImeComposing(event.text)
        is InputEvent.ImeDelete -> dispatchImeDelete(event.beforeLength)
        InputEvent.ImeFinishComposing -> dispatchImeFinishComposing()
    }

    // -----------------------------------------------------------------
    // dispatchKey — covers both View.onKeyDown and BaseInputConnection
    // .sendKeyEvent paths. The dispatcher returns the SAME verdict
    // regardless of source; adapters apply it. The only platform-routing
    // asymmetry (TIC-SK-05: Gboard soft-keyboard ENTER while composing
    // must force-end composing and deliver CR, otherwise composing
    // sticks forever) lives in [TerminalInputConnection.sendKeyEvent] as
    // a documented one-line adapter-local workaround — the dispatcher
    // returns [DispatchResult.Ignore] for ENTER while composing in both
    // paths, consistent with onKeyDown's "let IME handle" behaviour.
    // -----------------------------------------------------------------
    private fun dispatchKey(event: KeyEvent): DispatchResult {
        // While composing, the IME owns the input pipeline for plain
        // letters. Ctrl/Alt-modifier chords are still physical-keyboard
        // signals and MUST reach SSH (tmux prefix Ctrl+B, bash readline
        // Ctrl+A/E/F/K/L/N/P/R/U/W, etc. — see CLAUDE.md "Routing
        // invariants").
        if (composing) {
            val verdict = KeyMapper.resolve(event)
            when (verdict) {
                is KeyResolution.Swallow -> return DispatchResult.Swallow
                is KeyResolution.Paste -> return DispatchResult.Paste
                is KeyResolution.Send -> {
                    if (event.isCtrlPressed || event.isAltPressed) {
                        // Modifier-bearing Send during composing —
                        // finish composing first so the next letter
                        // (e.g. the "d" in Ctrl+B D) goes through the
                        // normal InputConnection path as a literal.
                        composing = false
                        lastComposedDigits = null
                        return DispatchResult.FinishComposingThenSend(verdict.bytes)
                    }
                    // Bare letter / digit / DEL / ENTER while composing —
                    // defer to IME so pinyin / candidate selection
                    // stays coherent.
                    return DispatchResult.Ignore
                }
                KeyResolution.Ignore -> return DispatchResult.Ignore
            }
        }

        // Idle. Printable char (no Ctrl/Alt) → let InputConnection
        // handle via commitText.
        if (event.isPrintingKey && !event.isCtrlPressed && !event.isAltPressed) {
            return DispatchResult.Ignore
        }

        return when (val verdict = KeyMapper.resolve(event)) {
            is KeyResolution.Send -> DispatchResult.Send(verdict.bytes)
            KeyResolution.Paste -> DispatchResult.Paste
            KeyResolution.Swallow -> DispatchResult.Swallow
            KeyResolution.Ignore -> DispatchResult.Ignore
        }
    }

    private fun dispatchImeCommit(text: CharSequence): DispatchResult {
        // Commit ends the composing session (per `commitText` semantics)
        // and forwards the text to SSH.
        composing = false
        lastComposedDigits = null
        return if (text.isEmpty()) {
            DispatchResult.Swallow
        } else {
            DispatchResult.Send(text.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun dispatchImeComposing(text: CharSequence): DispatchResult {
        // Empty composing text = cancel the IME's composing region. Per
        // the Gboard `setComposingText("") → deleteSurroundingText`
        // race documented in `implementation_plan.md`, this must drop
        // our digit tracker and leave `composing = false` so the
        // subsequent delete sees the IME side as idle.
        if (text.isEmpty()) {
            lastComposedDigits = null
            composing = false
            return DispatchResult.Swallow
        }

        // 中文拼音模式数字直出：IME 把数字当作 pinyin 起手而不是 commit,
        // 我们把这种"假组合"识别出来,直接把数字发到 SSH,清空 IME
        // 的 composing 区阻止它继续累积成拼音候选。
        //
        // `Char.isDigit()` matches the Unicode digit property
        // (fullwidth '１' U+FF11, Arabic-Indic, superscripts, …) —
        // over-matches on Chinese IMEs. We require ASCII `'0'..'9'`
        // only so the digit-flush path cannot be triggered by a stray
        // fullwidth digit or a Unicode numeral from a paste.
        if (isAsciiDigits(text)) {
            val previous = lastComposedDigits
            // Three cases; only the second writes a "delta" to avoid
            // double-writing when the IME extends its composing region:
            //  1) previous == null        — first occurrence, write whole text
            //  2) text strictly extends previous — IME accumulating
            //                                 ("1" → "12"), write only suffix
            //  3) text equals or resets previous — IME re-sends
            //                                 ("1" → "1" → "1"), write whole
            //                                 text to match "one per press"
            val suffix = when {
                previous == null -> text.toString()
                text.length > previous.length && text.startsWith(previous) ->
                    text.subSequence(previous.length, text.length).toString()
                else -> text.toString()
            }
            lastComposedDigits = text.toString()
            // composing 保持 false:数字直出不是真组合,isComposing() 仍
            // 反映"用户是否在拼音候选中",避免 onKeyDown 误吞下一物理键。
            // (composing stays false because a digit flush is NOT a real
            // composing session — isComposing() should keep reflecting
            // "is the user picking a pinyin candidate?", so onKeyDown
            // doesn't accidentally swallow the next physical key.)
            return if (suffix.isEmpty()) {
                DispatchResult.Swallow
            } else {
                DispatchResult.Send(suffix.toByteArray(Charsets.UTF_8))
            }
        }

        // Normal letter composing path.
        composing = true
        lastComposedDigits = null
        return DispatchResult.Swallow
    }

    private fun dispatchImeDelete(beforeLength: Int): DispatchResult {
        // beforeLength == 0 → no bytes to write; the caller falls
        // through to super.deleteSurroundingText() for IME-internal
        // state consistency (cursor-agent pinyin-cursor dance depends
        // on the IME seeing the empty delete too).
        if (beforeLength <= 0) return DispatchResult.Ignore
        // Repeat the DEL byte once per requested character. ByteArray
        // initialiser fills the array with the same value — single
        // allocation, single write call from the adapter.
        return DispatchResult.Send(ByteArray(beforeLength) { 0x7F.toByte() })
    }

    private fun dispatchImeFinishComposing(): DispatchResult {
        // Pure cancel — never sends bytes. Drops both the composing
        // flag and the digit tracker so the next interaction starts
        // from a clean slate.
        composing = false
        lastComposedDigits = null
        return DispatchResult.Swallow
    }

    private companion object {
        // 'Char.isDigit()' over-matches on Chinese IMEs; mirror the
        // ASCII-only check that lived in TerminalInputConnection.kt:94-95
        // so the digit-flush rule cannot be triggered by a fullwidth
        // digit or a Unicode numeral.
        private fun isAsciiDigits(text: CharSequence): Boolean =
            text.isNotEmpty() && text.all { it in '0'..'9' }
    }
}