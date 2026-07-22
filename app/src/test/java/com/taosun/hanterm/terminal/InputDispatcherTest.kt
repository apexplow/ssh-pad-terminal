package com.taosun.hanterm.terminal

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Routing-verdict tests for [InputDispatcher] — the primary test seam
 * introduced by issue #14.
 *
 * Why Robolectric instead of pure JUnit: the dispatcher's
 * `dispatch(KeyEvent)` path delegates to [KeyMapper.resolve], which
 * consults `KeyEvent.getUnicodeChar()` (via `KeyCharacterMap`) for the
 * Alt+letter and bare-printable entries. Without the Robolectric shadow,
 * synthetic KeyEvents would compute `unicodeChar == 0` and silently
 * re-route. Robolectric is the convention in this codebase (see
 * `KeyEventRoutingTest`, `TerminalInputConnectionTest`); we match it.
 *
 * These tests pin the [DispatchResult] returned for every documented
 * routing case in `CLAUDE.md` "Routing invariants" and
 * `implementation_plan.md` §"KeyEvent 路由规则表". The end-to-end wiring
 * (adapter → dispatcher → endpoint bytes) is verified separately by
 * `KeyEventRoutingTest` and `TerminalInputConnectionTest`, which still
 * drive through `TerminalView` / `TerminalInputConnection` unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InputDispatcherTest {

    private lateinit var dispatcher: InputDispatcher

    @Before
    fun setUp() {
        dispatcher = InputDispatcher()
    }

    // =================================================================
    // State hygiene
    // =================================================================

    @Test
    fun isComposing_initiallyFalse() {
        assertFalse(dispatcher.isComposing())
    }

    @Test
    fun reset_zeroesStateFromComposing() {
        // Enter composing.
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))
        assertTrue(dispatcher.isComposing())

        dispatcher.reset()

        assertFalse("reset must zero composing", dispatcher.isComposing())
    }

    @Test
    fun reset_zeroesStateFromDigitTracker() {
        // Prime the digit tracker.
        dispatcher.dispatch(InputEvent.ImeComposing("1"))
        // digit flush leaves composing=false but populates
        // lastComposedDigits. Drive a reset.
        dispatcher.reset()

        // After reset, a letter composing must work normally (no
        // extension-detection against a stale "1" prefix).
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))
        assertTrue(dispatcher.isComposing())
    }

    // =================================================================
    // dispatch(Key) — idle
    // =================================================================

    @Test
    fun dispatchKey_idlePrintableLetter_returnsIgnore() {
        val result = dispatcher.dispatch(
            InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)),
        )
        assertEquals(
            "idle printable letter must be passed through to InputConnection",
            DispatchResult.Ignore,
            result,
        )
    }

    @Test
    fun dispatchKey_idlePrintableDigit_returnsIgnore() {
        // Bare digit (idle, no Ctrl/Alt) is a printable key — the
        // digit-flush path is gated behind IME composing calls, not
        // physical KEYCODE_3.
        val result = dispatcher.dispatch(
            InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_3)),
        )
        assertEquals(DispatchResult.Ignore, result)
    }

    @Test
    fun dispatchKey_idleCtrlA_returnsSendSoh() {
        assertSendBytes(
            "Ctrl+A → SOH (0x01) — bash readline beginning-of-line",
            dispatcher.dispatch(
                InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)),
            ),
            byteArrayOf(0x01.toByte()),
        )
    }

    @Test
    fun dispatchKey_idleCtrlB_returnsSendStx() {
        assertSendBytes(
            "Ctrl+B → STX (0x02) — tmux prefix",
            dispatcher.dispatch(
                InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON)),
            ),
            byteArrayOf(0x02.toByte()),
        )
    }

    @Test
    fun dispatchKey_idleCtrlC_returnsSendEtx() {
        assertSendBytes(
            "Ctrl+C → ETX (0x03) — interrupt",
            dispatcher.dispatch(
                InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)),
            ),
            byteArrayOf(0x03.toByte()),
        )
    }

    @Test
    fun dispatchKey_idleCtrlV_returnsIgnore() {
        // Ctrl+V alone (no Shift) is intentionally NOT Paste — the IME
        // should receive it and emit a literal "V".
        assertEquals(
            DispatchResult.Ignore,
            dispatcher.dispatch(
                InputEvent.Key(
                    keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON),
                ),
            ),
        )
    }

    @Test
    fun dispatchKey_idleCtrlShiftV_returnsPaste() {
        assertEquals(
            "Ctrl+Shift+V must resolve to Paste so the adapter reads the clipboard",
            DispatchResult.Paste,
            dispatcher.dispatch(
                InputEvent.Key(
                    keyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_V,
                        KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
                    ),
                ),
            ),
        )
    }

    @Test
    fun dispatchKey_idleCtrlBackslash_returnsSendFs() {
        assertSendBytes(
            "Ctrl+\\ → FS (0x1C) — SIGQUIT",
            dispatcher.dispatch(
                InputEvent.Key(
                    keyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_BACKSLASH,
                        KeyEvent.META_CTRL_ON,
                    ),
                ),
            ),
            byteArrayOf(0x1C.toByte()),
        )
    }

    @Test
    fun dispatchKey_idleCtrlRightBracket_returnsSendGs() {
        assertSendBytes(
            "Ctrl+] → GS (0x1D) — telnet escape",
            dispatcher.dispatch(
                InputEvent.Key(
                    keyEvent(
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_RIGHT_BRACKET,
                        KeyEvent.META_CTRL_ON,
                    ),
                ),
            ),
            byteArrayOf(0x1D.toByte()),
        )
    }

    @Test
    fun dispatchKey_idleCtrlSpace_returnsSwallow() {
        // Ctrl+Space is an IME language switch — MUST NEVER reach SSH.
        assertEquals(
            DispatchResult.Swallow,
            dispatcher.dispatch(
                InputEvent.Key(
                    keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON),
                ),
            ),
        )
    }

    @Test
    fun dispatchKey_idleShiftSpace_returnsSwallow() {
        assertEquals(
            DispatchResult.Swallow,
            dispatcher.dispatch(
                InputEvent.Key(
                    keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.META_SHIFT_ON),
                ),
            ),
        )
    }

    @Test
    fun dispatchKey_idleLanguageSwitchKey_returnsSwallow() {
        assertEquals(
            DispatchResult.Swallow,
            dispatcher.dispatch(
                InputEvent.Key(
                    keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_LANGUAGE_SWITCH),
                ),
            ),
        )
    }

    @Test
    fun dispatchKey_idleEscape_returnsSendEsc() {
        assertSendBytes(
            "ESC alone → 0x1B — vim normal-mode exit",
            dispatcher.dispatch(
                InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)),
            ),
            byteArrayOf(0x1B.toByte()),
        )
    }

    @Test
    fun dispatchKey_idleShiftTab_returnsSendBackTab() {
        assertSendBytes(
            "Shift+Tab → ESC[Z (Back-Tab)",
            dispatcher.dispatch(
                InputEvent.Key(
                    keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON),
                ),
            ),
            "[Z".toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    fun dispatchKey_idleEnter_returnsSendCr() {
        assertSendBytes(
            "ENTER → 0x0D (CR)",
            dispatcher.dispatch(
                InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)),
            ),
            byteArrayOf(0x0D.toByte()),
        )
    }

    @Test
    fun dispatchKey_idleBackspace_returnsSendDel() {
        assertSendBytes(
            "KEYCODE_DEL → 0x7F",
            dispatcher.dispatch(
                InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)),
            ),
            byteArrayOf(0x7F.toByte()),
        )
    }

    @Test
    fun dispatchKey_idleArrowUp_returnsSendAnsiUp() {
        assertSendBytes(
            "ArrowUp → ESC[A",
            dispatcher.dispatch(
                InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)),
            ),
            "[A".toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    fun dispatchKey_idleInsert_returnsSendAnsiInsert() {
        assertSendBytes(
            "KEYCODE_INSERT → ESC[2~ (vim mode-toggle)",
            dispatcher.dispatch(
                InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_INSERT)),
            ),
            "[2~".toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    fun dispatchKey_idleCtrlSlash_returnsSendDel() {
        assertSendBytes(
            "Ctrl+? → 0x7F — alternative DEL byte",
            dispatcher.dispatch(
                InputEvent.Key(
                    keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SLASH, KeyEvent.META_CTRL_ON),
                ),
            ),
            byteArrayOf(0x7F.toByte()),
        )
    }

    @Test
    fun dispatchKey_idleCtrlAt_returnsSendNul() {
        assertSendBytes(
            "Ctrl+@ → 0x00 (NUL) — bash set-mark",
            dispatcher.dispatch(
                InputEvent.Key(
                    keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_AT, KeyEvent.META_CTRL_ON),
                ),
            ),
            byteArrayOf(0x00.toByte()),
        )
    }

    // =================================================================
    // dispatch(Key) — composing
    // =================================================================

    @Test
    fun dispatchKey_composingBareLetter_returnsIgnore() {
        // Bare letter while composing must defer to IME so pinyin
        // remains coherent (e.g. user types 'n' → 'ni' → 'nihao').
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))
        assertTrue(dispatcher.isComposing())

        assertEquals(
            DispatchResult.Ignore,
            dispatcher.dispatch(
                InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D)),
            ),
        )
        assertTrue(
            "composing must be preserved after a bare-letter dispatch",
            dispatcher.isComposing(),
        )
    }

    @Test
    fun dispatchKey_composingCtrlL_returnsFinishComposingThenSend() {
        // The Ctrl+L-while-composing test in KeyEventRoutingTest
        // exercises this end-to-end. Here we pin the dispatcher's
        // verdict: it must be FinishComposingThenSend, not just Send,
        // so the adapter forces composing off first.
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))

        assertFinishComposingThenSend(
            "Ctrl+L while composing → FF (0x0C) after finishing composing",
            dispatcher.dispatch(
                InputEvent.Key(
                    keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON),
                ),
            ),
            byteArrayOf(0x0C.toByte()),
        )
        assertFalse(
            "composing must be cleared by the modifier-bearing Send verdict",
            dispatcher.isComposing(),
        )
    }

    @Test
    fun dispatchKey_composingCtrlB_returnsFinishComposingThenSend() {
        // The tmux prefix scenario: Ctrl+B while mid-pinyin.
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))

        assertFinishComposingThenSend(
            "Ctrl+B while composing → STX (0x02) after finishing composing",
            dispatcher.dispatch(
                InputEvent.Key(
                    keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON),
                ),
            ),
            byteArrayOf(0x02.toByte()),
        )
    }

    @Test
    fun dispatchKey_composingCtrlSpace_returnsSwallow() {
        // Ctrl+Space is an IME language switch — must NOT reach SSH
        // even mid-composition. The "swallow beats composing" rule.
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))

        assertEquals(
            DispatchResult.Swallow,
            dispatcher.dispatch(
                InputEvent.Key(
                    keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON),
                ),
            ),
        )
        assertTrue(
            "composing must survive a Swallow verdict (IME owns the toggle)",
            dispatcher.isComposing(),
        )
    }

    @Test
    fun dispatchKey_composingEscape_returnsIgnore() {
        // ESC alone while composing must defer to IME so it can cancel
        // the candidate selection. End-to-end test:
        // KeyEventRoutingTest.test_escape_whileComposing_isPassedToIme.
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))

        assertEquals(
            DispatchResult.Ignore,
            dispatcher.dispatch(
                InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)),
            ),
        )
    }

    @Test
    fun dispatchKey_composingEnter_returnsIgnore() {
        // ENTER while composing → Ignore (consistent with onKeyDown
        // behaviour). The TIC-SK-05 special case (Gboard soft-keyboard
        // ENTER force-ends composing + writes CR) is an adapter-local
        // workaround in TerminalInputConnection.sendKeyEvent, NOT
        // dispatcher policy. Pin the dispatcher's verdict here.
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))

        assertEquals(
            DispatchResult.Ignore,
            dispatcher.dispatch(
                InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)),
            ),
        )
    }

    @Test
    fun dispatchKey_composingBackspace_returnsIgnore() {
        // BACKSPACE while composing → Ignore (let IME handle pinyin
        // editing). End-to-end: KeyEventRoutingTest.test_backspace
        // WhileComposing_isRoutedToIme_noDelWritten.
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))

        assertEquals(
            DispatchResult.Ignore,
            dispatcher.dispatch(
                InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)),
            ),
        )
    }

    // =================================================================
    // dispatch(ImeCommit)
    // =================================================================

    @Test
    fun dispatchImeCommit_empty_returnsSwallow() {
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))
        assertTrue(dispatcher.isComposing())

        assertEquals(
            DispatchResult.Swallow,
            dispatcher.dispatch(InputEvent.ImeCommit("")),
        )
        assertFalse("commit must clear composing", dispatcher.isComposing())
    }

    @Test
    fun dispatchImeCommit_chinese_returnsSendUtf8() {
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))

        assertSendBytes(
            "commit 你 → UTF-8 bytes for '你'",
            dispatcher.dispatch(InputEvent.ImeCommit("你")),
            "你".toByteArray(Charsets.UTF_8),
        )
        assertFalse(dispatcher.isComposing())
    }

    @Test
    fun dispatchImeCommit_ascii_returnsSendUtf8() {
        // Fast-path: commit a literal English char (no pinyin session)
        // and verify the UTF-8 byte lands.
        assertSendBytes(
            "commit 'a' → [0x61]",
            dispatcher.dispatch(InputEvent.ImeCommit("a")),
            byteArrayOf(0x61),
        )
    }

    @Test
    fun dispatchImeCommit_clearsDigitTracker() {
        // Regression for the digit-tracker-not-cleared bug — covered
        // in TerminalInputConnectionTest, but pin the dispatcher's
        // half here too.
        dispatcher.dispatch(InputEvent.ImeComposing("1"))
        dispatcher.dispatch(InputEvent.ImeCommit("你"))
        // After commit, a letter composing must work normally (no
        // tracker stuck on the previous "1").
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))
        assertTrue(dispatcher.isComposing())
    }

    // =================================================================
    // dispatch(ImeComposing)
    // =================================================================

    @Test
    fun dispatchImeComposing_empty_returnsSwallowAndClearsComposing() {
        // Gboard race fix: setComposingText("") must clear our
        // composing flag so a subsequent deleteSurroundingText sees
        // the IME side as idle.
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))
        assertTrue(dispatcher.isComposing())

        assertEquals(
            DispatchResult.Swallow,
            dispatcher.dispatch(InputEvent.ImeComposing("")),
        )
        assertFalse(dispatcher.isComposing())
    }

    @Test
    fun dispatchImeComposing_letters_returnsSwallowAndSetsComposing() {
        assertEquals(
            DispatchResult.Swallow,
            dispatcher.dispatch(InputEvent.ImeComposing("ni")),
        )
        assertTrue(dispatcher.isComposing())
    }

    // ---- Digit flush: ASCII digits only ----

    @Test
    fun dispatchImeComposing_singleAsciiDigit_returnsSendDigitAndComposingStaysFalse() {
        // 中文拼音无候选时按 "1": IME 走 setComposingText("1", 1),
        // 必须直达 SSH.
        assertSendBytes(
            "single ASCII digit '1' → [0x31]",
            dispatcher.dispatch(InputEvent.ImeComposing("1")),
            byteArrayOf(0x31),
        )
        assertFalse(
            "composing must stay false after digit flush (digit flush is NOT a real session)",
            dispatcher.isComposing(),
        )
    }

    @Test
    fun dispatchImeComposing_extendingDigitRegion_writesOnlySuffix() {
        // Gboard pattern: "1" → "12" → "123". SSH must receive
        // "123", not "112123" or "1123".
        dispatcher.dispatch(InputEvent.ImeComposing("1"))
        dispatcher.dispatch(InputEvent.ImeComposing("12"))
        assertSendBytes(
            "extending digit region '12' → [0x32] (only the suffix delta)",
            dispatcher.dispatch(InputEvent.ImeComposing("123")),
            byteArrayOf(0x33),
        )
    }

    @Test
    fun dispatchImeComposing_resettingDigitRegion_writesFullEachTime() {
        // Sogou / Baidu pattern: every setComposingText is a single
        // "1" — the tracker sees no extension and writes the whole
        // text each time. SSH must receive "111".
        assertSendBytes(
            "first reset '1' → [0x31]",
            dispatcher.dispatch(InputEvent.ImeComposing("1")),
            byteArrayOf(0x31),
        )
        assertSendBytes(
            "second reset '1' → [0x31] (whole text, not dedup)",
            dispatcher.dispatch(InputEvent.ImeComposing("1")),
            byteArrayOf(0x31),
        )
        assertSendBytes(
            "third reset '1' → [0x31]",
            dispatcher.dispatch(InputEvent.ImeComposing("1")),
            byteArrayOf(0x31),
        )
    }

    @Test
    fun dispatchImeComposing_letterThenCommitThenDigit_resetsTracker() {
        // Regression: commit must clear the digit tracker. Verify the
        // dispatcher half: after commit, a single "1" is a brand-new
        // session, not an extension of any prior.
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))
        dispatcher.dispatch(InputEvent.ImeCommit("你"))

        assertSendBytes(
            "digit after commit → [0x31] (not an extension of any prior digit session)",
            dispatcher.dispatch(InputEvent.ImeComposing("1")),
            byteArrayOf(0x31),
        )
    }

    @Test
    fun dispatchImeComposing_pinyinWithToneDigit_doesNotFireDigitShortcut() {
        // 声调标记: IME 发 "ni" 然后 "ni3". "ni3" 含字母,绝不能
        // 被 ASCII-digit 路径吞掉 — must go through the normal
        // composing flow.
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))

        assertEquals(
            "'ni3' (tone marker) must NOT trigger digit flush — must be Swallow + composing",
            DispatchResult.Swallow,
            dispatcher.dispatch(InputEvent.ImeComposing("ni3")),
        )
        assertTrue(dispatcher.isComposing())
    }

    @Test
    fun dispatchImeComposing_fullwidthDigit_doesNotFireDigitShortcut() {
        // '１' (U+FF11) is a Unicode digit but NOT ASCII '0'..'9'.
        // The shortcut must NOT fire — even if the IME somehow emits
        // it, we must not write a halfwidth "1".
        assertEquals(
            DispatchResult.Swallow,
            dispatcher.dispatch(InputEvent.ImeComposing("１")),
        )
        assertTrue(
            "fullwidth digit must enter the normal composing session",
            dispatcher.isComposing(),
        )
    }

    @Test
    fun dispatchImeComposing_digitFlushThenLetterKey_doesNotWriteBytes() {
        // After a digit flush, composing stays false. A subsequent
        // physical letter key (e.g. 'd' on a hardware keyboard) must
        // return Ignore, NOT write bytes — the IME owns the next
        // composing extension.
        dispatcher.dispatch(InputEvent.ImeComposing("1"))

        assertEquals(
            DispatchResult.Ignore,
            dispatcher.dispatch(
                InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D)),
            ),
        )
    }

    @Test
    fun dispatchImeComposing_mixedLetterDigit_doesNotFireDigitShortcut() {
        // "1n" — mixed letter + digit. Not pure digits, so the
        // shortcut must NOT fire. Normal composing path.
        assertEquals(
            DispatchResult.Swallow,
            dispatcher.dispatch(InputEvent.ImeComposing("1n")),
        )
        assertTrue(dispatcher.isComposing())
    }

    // =================================================================
    // dispatch(ImeDelete)
    // =================================================================

    @Test
    fun dispatchImeDelete_zero_returnsIgnore() {
        // beforeLength == 0 is a no-op for the SSH side; the adapter
        // still calls super.deleteSurroundingText for IME-internal
        // state consistency.
        assertEquals(
            DispatchResult.Ignore,
            dispatcher.dispatch(InputEvent.ImeDelete(0)),
        )
    }

    @Test
    fun dispatchImeDelete_positive_returnsSendDelBytes() {
        assertSendBytes(
            "ImeDelete(3) → three 0x7F bytes",
            dispatcher.dispatch(InputEvent.ImeDelete(3)),
            byteArrayOf(0x7F.toByte(), 0x7F.toByte(), 0x7F.toByte()),
        )
    }

    // =================================================================
    // dispatch(ImeFinishComposing)
    // =================================================================

    @Test
    fun dispatchImeFinishComposing_clearsStateAndReturnsSwallow() {
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))
        assertTrue(dispatcher.isComposing())

        assertEquals(
            DispatchResult.Swallow,
            dispatcher.dispatch(InputEvent.ImeFinishComposing),
        )
        assertFalse(dispatcher.isComposing())
    }

    @Test
    fun dispatchImeFinishComposing_idempotent() {
        // Calling finish twice must not throw, must keep composing
        // false, must return Swallow both times.
        assertEquals(
            DispatchResult.Swallow,
            dispatcher.dispatch(InputEvent.ImeFinishComposing),
        )
        assertEquals(
            DispatchResult.Swallow,
            dispatcher.dispatch(InputEvent.ImeFinishComposing),
        )
    }

    // =================================================================
    // Inter-event transitions
    // =================================================================

    @Test
    fun composingThenFinishComposingThenKeyDel_returnsSendDel() {
        // Post-composing idle backspace must reach SSH as DEL. Guards
        // against the dispatcher state accidentally sticking at true.
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))
        dispatcher.dispatch(InputEvent.ImeFinishComposing)

        assertSendBytes(
            "post-composing DEL → [0x7F]",
            dispatcher.dispatch(
                InputEvent.Key(keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)),
            ),
            byteArrayOf(0x7F.toByte()),
        )
    }

    @Test
    fun composingThenCommitThenKeyDel_returnsSendDel() {
        // Post-commit idle backspace reaches SSH normally. Pinning
        // the dispatcher's half of the
        // TerminalInputConnectionTest.test_deleteSurroundingText_after
        // Commit_onlyTheFirstDelIsSuppressed contract: the DISPATCHER
        // returns Send on the second delete; the ADAPTER's latch
        // suppression is what makes the first one a no-op.
        dispatcher.dispatch(InputEvent.ImeComposing("ni"))
        dispatcher.dispatch(InputEvent.ImeCommit("你"))
        // Simulate the adapter's latch consumption on the first
        // delete: dispatcher doesn't know about the latch, so from
        // its perspective every post-composing delete is a real DEL.
        assertSendBytes(
            "post-commit DEL → [0x7F]",
            dispatcher.dispatch(InputEvent.ImeDelete(1)),
            byteArrayOf(0x7F.toByte()),
        )
    }

    // =================================================================
    // helpers
    // =================================================================

    private fun keyEvent(action: Int, keyCode: Int, metaState: Int = 0): KeyEvent = KeyEvent(
        /* downTime = */ 0L,
        /* eventTime = */ 0L,
        /* action = */ action,
        /* code = */ keyCode,
        /* repeat = */ 0,
        /* metaState = */ metaState,
    )

    /**
     * Reflective setter for [KeyEvent.setCharacters] — needed for the
     * `Ctrl+^` / `Ctrl+_` entries that match on `characters` rather
     * than `keyCode`. Mirrors the helper in `KeyEventRoutingTest`.
     */
    private fun KeyEvent.setCharacters(chars: String) {
        val field = KeyEvent::class.java.getDeclaredField("mCharacters").apply {
            isAccessible = true
        }
        field.set(this, chars)
    }

    /**
     * Assert [result] is [DispatchResult.Send] with bytes exactly
     * equal to [expectedBytes]. Kotlin's data-class `equals()` uses
     * reference equality on `ByteArray` fields, so two
     * [DispatchResult.Send] wrapping equal-bytes arrays compare
     * unequal. This helper uses [assertArrayEquals] for the actual
     * comparison. Same pattern as `KeyEventRoutingTest.assertSendBytes`.
     */
    private fun assertSendBytes(message: String, result: DispatchResult, expectedBytes: ByteArray) {
        val send = result as? DispatchResult.Send
            ?: throw AssertionError("expected DispatchResult.Send, got $result ($message)")
        assertArrayEquals(message, expectedBytes, send.bytes)
    }

    /**
     * Assert [result] is [DispatchResult.FinishComposingThenSend] with
     * bytes exactly equal to [expectedBytes]. Same data-class
     * equality pitfall as [assertSendBytes].
     */
    private fun assertFinishComposingThenSend(
        message: String,
        result: DispatchResult,
        expectedBytes: ByteArray,
    ) {
        val send = result as? DispatchResult.FinishComposingThenSend
            ?: throw AssertionError("expected FinishComposingThenSend, got $result ($message)")
        assertArrayEquals(message, expectedBytes, send.bytes)
    }
}