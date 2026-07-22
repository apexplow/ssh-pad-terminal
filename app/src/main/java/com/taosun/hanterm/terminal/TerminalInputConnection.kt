package com.taosun.hanterm.terminal

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection

/**
 * InputConnection that routes IME events (拼音 composing + 汉字 commit) and physical
 * keys to a [TerminalEndpoint] in a way that keeps the IME state and the SSH-side
 * character stream in lock-step.
 *
 * Per `implementation_plan.md` §"输入链路设计" and issue #14, this class is a thin
 * adapter. All routing decisions — the composing-state gate, the digit-flush
 * extension/reset logic, the Ctrl/Alt Send-during-composing finish rule, the
 * "swallow beats composing" rule for IME language switch — live in
 * [InputDispatcher]. This class only:
 *
 *  1. Translates the five `BaseInputConnection` callbacks and
 *     [sendKeyEvent] into neutral [InputEvent] values and feeds them to
 *     [InputDispatcher.dispatch].
 *  2. Applies the returned [DispatchResult]: writes bytes to [endpoint],
 *     calls `super.<callback>` to keep the IME's internal state coherent,
 *     toggles the composing hint overlay.
 *  3. Owns the [userInImeContext] one-shot latch that defeats the Gboard
 *     `setComposingText("") → deleteSurroundingText` race — see its kdoc.
 *
 * The two-link separation rule is enforced by the dispatcher (single
 * composing-state gate) and by [ImeKeyRouter.onKeyDown] (consuming the
 * dispatcher's verdict); this class no longer contains any composing-state
 * branching.
 */
class TerminalInputConnection(
    private val terminalView: TerminalComposingView,
    private val endpoint: TerminalEndpoint,
    private val dispatcher: InputDispatcher,
) : BaseInputConnection(terminalView.asView, true) {

    /**
     * "Was the user in an IME composition context the last time the IME
     * talked to us?"
     *
     * Distinct from [InputDispatcher.isComposing] — the dispatcher's
     * composing flag mirrors the current `isComposing` text flag (true
     * while `setComposingText("ni")` is the latest call). This flag, by
     * contrast, latches to `true` once the user enters composition and
     * stays `true` until [deleteSurroundingText] consumes it. The
     * dispatcher does NOT see this latch — the latch exists because
     * the dispatcher's `composing` can already be false (the IME has
     * cancelled its composing region via `setComposingText("")`) while
     * the user's NEXT `deleteSurroundingText` is still part of the same
     * pinyin-cancel user intent and must NOT go to SSH as DEL.
     *
     * [deleteSurroundingText] consults THIS flag, not
     * [InputDispatcher.isComposing]. This is what closes the P0 Gboard
     * race in `implementation_plan.md`:
     *
     *   "Gboard 会先调 setComposingText("") 将 isComposing 置 false,
     *    再在同一事务内调 deleteSurroundingText(1,0)。
     *    此时若直接读 isComposing 会误判为"非组合" → 发 DEL 到远端(BUG)。"
     *
     * The key insight: even after the dispatcher flips its `composing`
     * back to false, the subsequent delete is still part of the same
     * user intent (cancel a pinyin selection with backspace), so it
     * must NOT go to SSH.
     */
    @Volatile
    private var userInImeContext = false

    fun isComposing(): Boolean = dispatcher.isComposing()

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        val result = dispatcher.dispatch(InputEvent.ImeComposing(text))
        return when {
            // Pure cancel — clear the IME's composing region, drop our
            // hint, forget any pending digit tracker. Does NOT touch
            // SSH: cancel is a local intent, the user did not ask for
            // any byte.
            text.isEmpty() -> {
                terminalView.hideComposingHint()
                super.setComposingText(text, newCursorPosition)
            }
            // 中文拼音模式数字直出：IME 把数字当作 pinyin 起手而不是
            // commit, 触发 ASCII-digit 数字直出路径 (see
            // InputDispatcher). Dispatcher returns Send(suffix bytes)
            // so the adapter writes them and clears the IME's
            // composing region to prevent the IME from accumulating
            // digits into a fake pinyin candidate.
            result is DispatchResult.Send -> {
                endpoint.write(result.bytes)
                terminalView.hideComposingHint()
                // 用空 composing 通知 IME 这次组合已结束。顺序很重要：
                // 必须先 endpoint.write, 再 super.setComposingText(""),
                // 这样 SSH 拿到数字时 IME 端的 composing 区也已经被清掉了。
                super.setComposingText("", newCursorPosition)
            }
            // Normal letter composing path (unchanged from prior
            // behavior). Dispatcher returns Swallow and sets its own
            // composing = true; this adapter shows the hint.
            else -> {
                userInImeContext = true
                terminalView.showComposingHint(text.toString())
                super.setComposingText(text, newCursorPosition)
            }
        }
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        // Commit is also part of IME interaction — keep the latch on so
        // any backspace the user does right after committing a
        // candidate still routes through the IME rather than SSH.
        userInImeContext = true
        val result = dispatcher.dispatch(InputEvent.ImeCommit(text))
        terminalView.hideComposingHint()
        if (result is DispatchResult.Send) {
            endpoint.write(result.bytes)
        }
        return super.commitText(text, newCursorPosition)
    }

    override fun finishComposingText(): Boolean {
        // Same rationale as commitText: the user just cancelled, but a
        // backspace right after still belongs to the IME-driven
        // interaction.
        userInImeContext = true
        dispatcher.dispatch(InputEvent.ImeFinishComposing)
        terminalView.hideComposingHint()
        return super.finishComposingText()
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        super.setComposingRegion(start, end)

    override fun setSelection(start: Int, end: Int): Boolean =
        super.setSelection(start, end)

    /**
     * Backspace/delete from the IME. Uses [userInImeContext], NOT
     * [InputDispatcher.isComposing] — see the field's kdoc for the
     * Gboard race this avoids.
     *
     * [userInImeContext] is a ONE-SHOT latch, consumed by this very
     * call (TIC-DS-04): the Gboard race is exactly one
     * `setComposingText("")` / `commitText` / `finishComposingText`
     * followed by exactly one `deleteSurroundingText` in the same IME
     * transaction. Reading the latch to decide THIS call's routing and
     * then immediately clearing it means that delete is the only one
     * suppressed — any *later* `deleteSurroundingText` (not itself
     * preceded by a fresh composing/commit/finish) falls through to
     * the dispatcher's `Send([0x7F] * beforeLength)` branch below and
     * reaches SSH normally. If we instead left the latch set after
     * taking the suppress branch, it could never turn back false on
     * its own (the write-DEL branch — the only place a naive "reset
     * after success" could live — is unreachable while the latch reads
     * true), so every backspace forever would be swallowed after the
     * very first IME interaction of this View's lifetime.
     *
     * Note the order: the latch is consumed BEFORE the dispatcher
     * call, so the dispatcher's `ImeDelete` decision uses the
     * dispatcher's OWN `composing: Boolean` (now consistent with the
     * "real" composing flag, post-cancel) — not this adapter-local
     * latch.
     */
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val wasInImeContext = userInImeContext
        userInImeContext = false
        if (wasInImeContext) {
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
        val result = dispatcher.dispatch(InputEvent.ImeDelete(beforeLength))
        return when (result) {
            is DispatchResult.Send -> {
                endpoint.write(result.bytes)
                true
            }
            // beforeLength == 0 (dispatcher returns Ignore) — no SSH
            // bytes; still call super so the IME's internal cursor
            // state stays coherent.
            else -> super.deleteSurroundingText(beforeLength, afterLength)
        }
    }

    /**
     * Some IMEs route physical keys via [sendKeyEvent] instead of [onKeyDown].
     *
     * Two documented platform-routing asymmetries live in this adapter
     * (not in [InputDispatcher]):
     *
     *  1. **TIC-SK-05** — Gboard soft-keyboard ENTER while composing
     *     must force-end composing and deliver CR, otherwise the
     *     pinyin session sticks forever and later `sendKeyEvent`
     *     letters are silently dropped (cursor-agent Chinese-prompt
     *     deadlock). The dispatcher's `dispatch(Key(ENTER))` while
     *     composing returns `Ignore` (consistent with onKeyDown
     *     behaviour, which defers to IME); only [sendKeyEvent] needs
     *     the force-end variant because the IME does not follow up
     *     with `commitText` from this path.
     *
     *  2. **"composing swallows all sendKeyEvent"** — while the IME
     *     is in a composing session, the original code (pre #14)
     *     short-circuited to `return true` for every physical key
     *     delivered via sendKeyEvent, with the lone exception of the
     *     TIC-SK-05 ENTER case. That preserved the IME's exclusive
     *     ownership of the letter keys: a hardware letter reaching
     *     sendKeyEvent mid-pinyin must not double-write via the
     *     unicodeChar-fallback path. We dispatch the event first so
     *     the dispatcher's own composing state stays coherent (a
     *     `FinishComposingThenSend` verdict for a Ctrl/Alt chord will
     *     correctly clear `dispatcher.composing`), then return true
     *     regardless of the verdict. The end-to-end contract is
     *     pinned by
     *     `TerminalInputConnectionTest.test_sendKeyEvent_whileComposing
     *     _consumesAndDoesNotWrite`.
     *
     * Idle `sendKeyEvent` (no composing) applies the dispatcher's
     * verdict normally. The Ignore + unicodeChar-fallback branch is
     * the Gboard idle replay fix pinned by
     * `TerminalInputConnectionTest.test_sendKeyEvent_whileIdle_printable
     * Digit_writesRawByte_repro`.
     */
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        // TIC-SK-05: Gboard soft-keyboard ENTER while composing.
        if (dispatcher.isComposing() && event.keyCode == KeyEvent.KEYCODE_ENTER) {
            dispatcher.dispatch(InputEvent.ImeFinishComposing)
            terminalView.hideComposingHint()
            endpoint.write(byteArrayOf(0x0D))
            return true
        }
        // "composing swallows all sendKeyEvent" — dispatch first so
        // dispatcher state stays coherent, then return true regardless
        // of verdict. Pre-refactor contract.
        if (dispatcher.isComposing()) {
            dispatcher.dispatch(InputEvent.Key(event))
            return true
        }
        val result = dispatcher.dispatch(InputEvent.Key(event))
        return when (result) {
            is DispatchResult.Send -> {
                endpoint.write(result.bytes)
                true
            }
            is DispatchResult.FinishComposingThenSend -> {
                // Modifier chord while idle — the dispatcher has not
                // cleared composing (it was already false); just write
                // the bytes.
                endpoint.write(result.bytes)
                true
            }
            DispatchResult.Swallow -> true
            DispatchResult.Paste -> false
            DispatchResult.Ignore -> {
                // Gboard idle replay: dispatcher returned Ignore (bare
                // printable, no Ctrl/Alt), but the user pressed a
                // hardware key the IME never followed up on via
                // commitText — write the unicode char directly. See
                // TIC-SK-05 idle case kdoc.
                if (event.unicodeChar <= 0) return false
                endpoint.write(event.unicodeChar.toChar().toString().toByteArray(Charsets.UTF_8))
                true
            }
        }
    }
}