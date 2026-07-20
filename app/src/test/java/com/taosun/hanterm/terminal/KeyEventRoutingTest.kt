package com.taosun.hanterm.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * KeyEvent routing table tests (Sprint 1.5 §5).
 *
 * Verifies the dual-link dedup logic in [TerminalView.onKeyDown]: when the
 * view's hardware key path would double-write what the IME would also write
 * (printable chars, DEL while composing), the view returns `false` and lets
 * the IME handle the event. Otherwise it converts the event via [KeyMapper]
 * and consumes it.
 *
 * These cases were missing from the Sprint 1 suite and the only check we had
 * was at the [TerminalInputConnection] layer — that misses the View's own
 * key path, which is the other half of the "double key delivery" trap
 * (e.g. Ctrl+C: View.onKeyDown writes 0x03, AND the IME would also forward
 * it via sendKeyEvent, but the View consumed the event so the IME never
 * sees it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyEventRoutingTest {

    private lateinit var context: Context
    private lateinit var endpoint: MockEchoSession
    private lateinit var view: TerminalView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        endpoint = MockEchoSession()
        // TerminalView's bindEndpoint() nulls the inputConnection, so we wire
        // the endpoint first and then call onCreateInputConnection to install
        // the real TerminalInputConnection (matching what the framework does
        // when the IME comes up).
        view = TerminalView(context)
        view.bindEndpoint(endpoint)
        view.onCreateInputConnection(EditorInfo())
    }

    @Test
    fun test_printableChar_isHandledByImePath_notView() {
        val handled = view.onKeyDown(
            KeyEvent.KEYCODE_A,
            keyEvent(action = KeyEvent.ACTION_DOWN, keyCode = KeyEvent.KEYCODE_A),
        )

        assertFalse("printable char must be passed through to the IME", handled)
        assertEquals(
            "view must not write any bytes for a printable key",
            0,
            endpoint.bytesWritten().size,
        )
    }

    @Test
    fun test_ctrlC_writesInterruptAndConsumesEvent() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_C,
            metaState = KeyEvent.META_CTRL_ON,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_C, ev)

        assertTrue("Ctrl+C must be consumed by the view", handled)
        val written = endpoint.bytesWritten()
        assertEquals("must write exactly one byte for Ctrl+C", 1, written.size)
        assertEquals("Ctrl+C must produce ETX (0x03)", 0x03.toByte(), written[0])
    }

    // -----------------------------------------------------------------------
    // Full Ctrl+letter routing table (xterm convention).
    //
    // Before this set of tests, KeyMapper.ctrlSequence only handled C / D / Z /
    // `[` / Esc. Every other Ctrl+letter was silently dropped before reaching
    // the SSH channel — meaning Ctrl+B (tmux prefix), Ctrl+A/E (bash readline),
    // Ctrl+L (clear), Ctrl+R/U/K/W (readline), Ctrl+\ (SIGQUIT) and Ctrl+]
    // (telnet escape) all did nothing on a hardware keyboard. These tests pin
    // the byte values so a future tightening of the routing table can't
    // regress them. See KeyMapper.ctrlSequence kdoc for the surface decision
    // (A-Z + `\` + `]`; V deliberately omitted; no digits/@/^/_/?).
    // -----------------------------------------------------------------------

    @Test
    fun test_ctrlA_writesSohByte() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_A,
            metaState = KeyEvent.META_CTRL_ON,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_A, ev)

        assertTrue("Ctrl+A must be consumed by the view", handled)
        val written = endpoint.bytesWritten()
        assertEquals("must write exactly one byte for Ctrl+A", 1, written.size)
        assertEquals(
            "Ctrl+A must produce SOH (0x01) — bash readline beginning-of-line",
            0x01.toByte(),
            written[0],
        )
    }

    @Test
    fun test_ctrlB_writesStxByte() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_B,
            metaState = KeyEvent.META_CTRL_ON,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_B, ev)

        assertTrue("Ctrl+B must be consumed by the view", handled)
        val written = endpoint.bytesWritten()
        assertEquals("must write exactly one byte for Ctrl+B", 1, written.size)
        assertEquals(
            "Ctrl+B must produce STX (0x02) — tmux prefix",
            0x02.toByte(),
            written[0],
        )
    }

    @Test
    fun test_ctrlE_writesEnqByte() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_E,
            metaState = KeyEvent.META_CTRL_ON,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_E, ev)

        assertTrue("Ctrl+E must be consumed by the view", handled)
        val written = endpoint.bytesWritten()
        assertEquals(1, written.size)
        assertEquals(
            "Ctrl+E must produce ENQ (0x05) — bash readline end-of-line",
            0x05.toByte(),
            written[0],
        )
    }

    @Test
    fun test_ctrlL_writesFormFeedByte() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_L,
            metaState = KeyEvent.META_CTRL_ON,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_L, ev)

        assertTrue("Ctrl+L must be consumed by the view", handled)
        val written = endpoint.bytesWritten()
        assertEquals(1, written.size)
        assertEquals(
            "Ctrl+L must produce FF (0x0C) — clear screen",
            0x0C.toByte(),
            written[0],
        )
    }

    @Test
    fun test_ctrlR_writesDc2Byte() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_R,
            metaState = KeyEvent.META_CTRL_ON,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_R, ev)

        assertTrue("Ctrl+R must be consumed by the view", handled)
        val written = endpoint.bytesWritten()
        assertEquals(1, written.size)
        assertEquals(
            "Ctrl+R must produce DC2 (0x12) — bash readline reverse-i-search",
            0x12.toByte(),
            written[0],
        )
    }

    @Test
    fun test_ctrlU_writesNakByte() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_U,
            metaState = KeyEvent.META_CTRL_ON,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_U, ev)

        assertTrue("Ctrl+U must be consumed by the view", handled)
        val written = endpoint.bytesWritten()
        assertEquals(1, written.size)
        assertEquals(
            "Ctrl+U must produce NAK (0x15) — bash readline kill-line",
            0x15.toByte(),
            written[0],
        )
    }

    @Test
    fun test_ctrlW_writesEtbByte() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_W,
            metaState = KeyEvent.META_CTRL_ON,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_W, ev)

        assertTrue("Ctrl+W must be consumed by the view", handled)
        val written = endpoint.bytesWritten()
        assertEquals(1, written.size)
        assertEquals(
            "Ctrl+W must produce ETB (0x17) — bash readline kill-word",
            0x17.toByte(),
            written[0],
        )
    }

    @Test
    fun test_ctrlBackslash_writesFsByte() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_BACKSLASH,
            metaState = KeyEvent.META_CTRL_ON,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_BACKSLASH, ev)

        assertTrue("Ctrl+\\ must be consumed by the view", handled)
        val written = endpoint.bytesWritten()
        assertEquals(1, written.size)
        assertEquals(
            "Ctrl+\\ must produce FS (0x1C) — bash SIGQUIT",
            0x1C.toByte(),
            written[0],
        )
    }

    @Test
    fun test_ctrlRightBracket_writesGsByte() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_RIGHT_BRACKET,
            metaState = KeyEvent.META_CTRL_ON,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_RIGHT_BRACKET, ev)

        assertTrue("Ctrl+] must be consumed by the view", handled)
        val written = endpoint.bytesWritten()
        assertEquals(1, written.size)
        assertEquals(
            "Ctrl+] must produce GS (0x1D) — telnet escape",
            0x1D.toByte(),
            written[0],
        )
    }

    @Test
    fun test_ctrlL_whileComposing_sendsFormFeedByte_andClearsComposing() {
        // Ctrl/Alt-modifier chords are PHYSICAL keyboard signals and must
        // reach SSH even mid-pinyin-composition. Otherwise tmux prefix
        // (Ctrl+B), clear-screen (Ctrl+L), etc. silently break whenever the
        // user happens to leave the IME in Chinese mode.
        //
        // Composing is force-ended by [TerminalInputConnection.finishComposingText]
        // so the next letter key (e.g. the "d" in Ctrl+B D) goes through the
        // normal InputConnection path as a literal, not another pinyin letter.
        val inputConnection = view.activeInputConnection()!!
        inputConnection.setComposingText("ni", 0)
        endpoint.clear() // drop anything from setComposingText (none today)

        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_L,
            metaState = KeyEvent.META_CTRL_ON,
        )
        val handled = view.onKeyDown(KeyEvent.KEYCODE_L, ev)

        assertTrue("Ctrl+L while composing must be consumed by the view", handled)
        val written = endpoint.bytesWritten()
        assertEquals("Ctrl+L must write 0x0C (form feed) to SSH", 1, written.size)
        assertEquals(0x0C.toByte(), written[0])
        // Composing must be cleared so subsequent keys don't double as pinyin.
        assertFalse(
            "composing flag must be cleared after modifier-bearing Send",
            inputConnection.isComposing(),
        )
    }

    @Test
    fun test_ctrlB_whileComposing_sendsStxToEndpoint_finishingComposition() {
        // The exact tmux prefix scenario: user is mid-pinyin "ni", presses
        // Ctrl+B to send the prefix byte (0x02). Without the modifier-Send
        // bypass, 0x02 never reaches SSH and the subsequent "d" is rolled
        // into the IME's pinyin region instead of detaching tmux.
        val inputConnection = view.activeInputConnection()!!
        inputConnection.setComposingText("ni", 0)
        endpoint.clear()

        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_B,
            metaState = KeyEvent.META_CTRL_ON,
        )
        val handled = view.onKeyDown(KeyEvent.KEYCODE_B, ev)

        assertTrue("Ctrl+B while composing must be consumed by the view", handled)
        val written = endpoint.bytesWritten()
        assertEquals(
            "Ctrl+B must write exactly one byte (STX) so tmux sees the prefix",
            1,
            written.size,
        )
        assertEquals(0x02.toByte(), written[0])
        assertFalse(
            "composing flag must be cleared so 'd' follows as a literal letter",
            inputConnection.isComposing(),
        )
    }

    @Test
    fun test_printableLetter_whileComposing_isRoutedToIme_notView() {
        // Regression: a non-modified printable letter must STILL be deferred
        // to the IME while composing — that's what preserves pinyin. The
        // modifier-Send fix only opens the door for Ctrl/Alt chords; bare
        // letters continue to belong to the IME.
        val inputConnection = view.activeInputConnection()!!
        inputConnection.setComposingText("ni", 0)
        endpoint.clear()

        val ev = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D)
        val handled = view.onKeyDown(KeyEvent.KEYCODE_D, ev)

        assertFalse("'d' while composing must be passed to the IME", handled)
        assertEquals(
            "'d' must not leak to SSH — it stays a pinyin letter",
            0,
            endpoint.bytesWritten().size,
        )
        assertTrue(
            "composing state must be preserved so 'd' extends the pinyin region",
            inputConnection.isComposing(),
        )
    }

    @Test
    fun test_enter_writesCarriageReturn() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_ENTER,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_ENTER, ev)

        assertTrue("ENTER must be consumed by the view", handled)
        val written = endpoint.bytesWritten()
        assertEquals(1, written.size)
        assertEquals("ENTER must produce CR (0x0D)", '\r'.code.toByte(), written[0])
    }

    @Test
    fun test_backspaceWhenIdle_writesDelByte() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_DEL,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_DEL, ev)

        assertTrue("DEL (idle) must be consumed by the view", handled)
        val written = endpoint.bytesWritten()
        assertEquals(1, written.size)
        assertEquals("DEL must produce 0x7F", 0x7F.toByte(), written[0])
    }

    @Test
    fun test_backspaceWhileComposing_isRoutedToIme_noDelWritten() {
        // Simulate the user mid-pinyin: the IME is composing, and a hardware
        // backspace arrives. The view must NOT write DEL itself (that would
        // corrupt the composing state — the IME will reconcile when the user
        // commits). Instead the view returns false so the IME can handle it.
        val inputConnection = view.activeInputConnection()!!
        inputConnection.setComposingText("ni", 0)
        assertTrue("precondition: composing flag must be set", inputConnection.isComposing())
        endpoint.bytesWritten() // discard whatever the compose itself may have done (none)

        val ev = keyEvent(action = KeyEvent.ACTION_DOWN, keyCode = KeyEvent.KEYCODE_DEL)
        val handled = view.onKeyDown(KeyEvent.KEYCODE_DEL, ev)

        assertFalse("DEL while composing must be passed to the IME", handled)
        assertEquals(
            "view must not write any DEL byte while composing",
            0,
            endpoint.bytesWritten().size,
        )
        // Composing state should be preserved — the IME is in charge of clearing it.
        assertTrue("composing state must be preserved", inputConnection.isComposing())
    }

    @Test
    fun test_arrowUp_writesAnsiCursorSequence() {
        val ev = keyEvent(action = KeyEvent.ACTION_DOWN, keyCode = KeyEvent.KEYCODE_DPAD_UP)

        val handled = view.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, ev)

        assertTrue("arrow keys must be consumed by the view", handled)
        val written = endpoint.bytesWritten().toString(Charsets.UTF_8)
        assertEquals("[A", written)
    }

    // -----------------------------------------------------------------------
    // Post-spec-upgrade tests (Ctrl+Space / Shift+Space / KEYCODE_LANGUAGE_SWITCH
    // must be SWALLOWED — they are IME-internal language switches and must
    // never leak to the SSH channel).
    // -----------------------------------------------------------------------

    @Test
    fun test_ctrlSpace_isSwallowed_noBytesWritten() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_SPACE,
            metaState = KeyEvent.META_CTRL_ON,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_SPACE, ev)

        assertTrue("Ctrl+Space must be consumed (true), not passed through", handled)
        assertEquals(
            "Ctrl+Space is an IME toggle and must NEVER reach the SSH channel",
            0,
            endpoint.bytesWritten().size,
        )
    }

    @Test
    fun test_shiftSpace_isSwallowed_noBytesWritten() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_SPACE,
            metaState = KeyEvent.META_SHIFT_ON,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_SPACE, ev)

        assertTrue("Shift+Space must be consumed", handled)
        assertEquals(
            "Shift+Space is an IME toggle and must NEVER reach the SSH channel",
            0,
            endpoint.bytesWritten().size,
        )
    }

    @Test
    fun test_languageSwitchKey_isSwallowed_noBytesWritten() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_LANGUAGE_SWITCH,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_LANGUAGE_SWITCH, ev)

        assertTrue("KEYCODE_LANGUAGE_SWITCH must be consumed", handled)
        assertEquals(
            "language switch key must NEVER reach the SSH channel",
            0,
            endpoint.bytesWritten().size,
        )
    }

    @Test
    fun test_ctrlSpace_whileComposing_isStillSwallowed() {
        // Even mid-composition (user is in pinyin), Ctrl+Space must not reach
        // the SSH channel. The new "swallow beats composing-handling" branch
        // in onKeyDown ensures this.
        val inputConnection = view.activeInputConnection()!!
        inputConnection.setComposingText("ni", 0)

        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_SPACE,
            metaState = KeyEvent.META_CTRL_ON,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_SPACE, ev)

        assertTrue("Ctrl+Space while composing must still be consumed", handled)
        assertEquals(
            "Ctrl+Space while composing must NEVER write to SSH",
            0,
            endpoint.bytesWritten().size,
        )
    }

    // -----------------------------------------------------------------------
    // Ctrl+Shift+V → paste from system clipboard.
    //
    // The chord lives in [KeyMapper] as a [KeyResolution.Paste] verdict so the
    // dedup logic in onKeyDown can route it before the printable-char
    // short-circuit fires. The View then reads the system clipboard via
    // [android.content.ClipboardManager] and writes the UTF-8 bytes to the
    // bound endpoint.
    // -----------------------------------------------------------------------

    @Test
    fun test_ctrlShiftV_resolvesToPasteVerdict() {
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_V,
            metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
        )

        val verdict = KeyMapper.resolve(ev)

        assertEquals(
            "Ctrl+Shift+V must resolve to the Paste verdict so the View reads the clipboard",
            KeyResolution.Paste,
            verdict,
        )
    }

    @Test
    fun test_ctrlV_alone_doesNotResolveToPaste() {
        // Ctrl+V (without Shift) is intentionally NOT wired up — it's not a
        // terminal convention and the IME has no useful binding for it. We
        // assert it falls through to the printable-key short-circuit (Ignore),
        // so a user accidentally hitting Ctrl+V on a hardware keyboard gets a
        // literal "V" through the IME rather than an unexpected paste.
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_V,
            metaState = KeyEvent.META_CTRL_ON,
        )

        val verdict = KeyMapper.resolve(ev)

        assertEquals(
            "Ctrl+V alone must fall through to the printable-key path, not Paste",
            KeyResolution.Ignore,
            verdict,
        )
    }

    @Test
    fun test_shiftV_alone_doesNotResolveToPaste() {
        // Shift+V is the literal "V" key with the IME-driven capitalisation
        // path; the paste chord must NOT trigger here or we'd paste on every
        // capital V.
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_V,
            metaState = KeyEvent.META_SHIFT_ON,
        )

        val verdict = KeyMapper.resolve(ev)

        assertEquals(
            "Shift+V alone must not be hijacked as paste",
            KeyResolution.Ignore,
            verdict,
        )
    }

    @Test
    fun test_ctrlShiftV_writesClipboardTextToEndpoint() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "echo hello\n"))

        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_V,
            metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
        )
        val handled = view.onKeyDown(KeyEvent.KEYCODE_V, ev)

        assertTrue("Ctrl+Shift+V must be consumed by the view", handled)
        val written = endpoint.bytesWritten().toString(Charsets.UTF_8)
        assertEquals(
            "clipboard contents must be written verbatim (including newline) to the SSH channel",
            "echo hello\n",
            written,
        )
    }

    @Test
    fun test_ctrlShiftV_withEmptyClipboard_consumesEventButWritesNothing() {
        // No primary clip set. The chord still consumes the event (otherwise
        // Android would double-handle it) but the SSH channel stays silent.
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_V,
            metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_V, ev)

        assertTrue("Ctrl+Shift+V must be consumed even with an empty clipboard", handled)
        assertEquals(
            "empty clipboard must not produce any bytes on the SSH channel",
            0,
            endpoint.bytesWritten().size,
        )
    }

    @Test
    fun test_ctrlShiftV_whileComposing_stillPastesFromClipboard() {
        // Mid-IME composition (e.g. user mid-pinyin) should NOT swallow the
        // paste chord — finishing the composition is the user's call, but
        // the paste itself should fire. Without this branch, the IME gate at
        // the top of onKeyDown would route Ctrl+Shift+V back to the IME,
        // which has no useful binding for the chord and would silently drop
        // it.
        val inputConnection = view.activeInputConnection()!!
        inputConnection.setComposingText("ni", 0)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "pasted"))

        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_V,
            metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
        )

        val handled = view.onKeyDown(KeyEvent.KEYCODE_V, ev)

        assertTrue("Ctrl+Shift+V while composing must still be consumed", handled)
        val written = endpoint.bytesWritten().toString(Charsets.UTF_8)
        assertEquals(
            "clipboard contents must reach the SSH channel even mid-composition",
            "pasted",
            written,
        )
    }

    @Test
    fun test_ctrlShiftV_preImeHookPastesAndConsumesEvent() {
        // This is the case that motivated dispatchKeyEventPreIme: Gboard /
        // Google Pinyin consume Ctrl+Shift+V inside the IME input stage
        // before the event reaches onKeyDown. The pre-IME hook is the only
        // way to fire the paste in that flow — `onKeyDown` is downstream of
        // the IME in ViewRootImpl's input stages.
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "pre-ime-paste\n"))

        val down = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_V,
            metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
        )
        val consumed = view.dispatchKeyEventPreIme(down)

        assertTrue(
            "pre-IME hook must consume Ctrl+Shift+V so the IME never sees it",
            consumed,
        )
        val written = endpoint.bytesWritten().toString(Charsets.UTF_8)
        assertEquals(
            "clipboard contents must be written to the endpoint from the pre-IME hook",
            "pre-ime-paste\n",
            written,
        )
    }

    @Test
    fun test_ctrlShiftV_preImeHookConsumesKeyUpWithoutRePasting() {
        // The matching ACTION_UP must also be consumed — otherwise the IME
        // sees the up event and some IMEs use it to flip a sticky state
        // (Gboard historically uses key-up of modifier chords to commit a
        // transient mode). The hook must NOT paste a second time on ACTION_UP.
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "once"))

        val down = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_V,
            metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
        )
        assertTrue(view.dispatchKeyEventPreIme(down))

        val up = keyEvent(
            action = KeyEvent.ACTION_UP,
            keyCode = KeyEvent.KEYCODE_V,
            metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
        )
        assertTrue("ACTION_UP of Ctrl+Shift+V must also be consumed", view.dispatchKeyEventPreIme(up))

        val written = endpoint.bytesWritten().toString(Charsets.UTF_8)
        assertEquals(
            "the paste must fire exactly once across DOWN+UP",
            "once",
            written,
        )
    }

    @Test
    fun test_ctrlShiftV_preImeHookWithEmptyClipboard_consumesButWritesNothing() {
        // No primary clip set. Same contract as the onKeyDown path: the
        // event is still consumed (otherwise the IME would consume it
        // and we'd lose it) but no bytes reach the SSH channel.
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_V,
            metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
        )

        val consumed = view.dispatchKeyEventPreIme(ev)

        assertTrue(
            "pre-IME hook must consume Ctrl+Shift+V even with no clipboard contents",
            consumed,
        )
        assertEquals(
            "empty clipboard must not produce any bytes on the SSH channel",
            0,
            endpoint.bytesWritten().size,
        )
    }

    @Test
    fun test_ctrlShiftV_preImeHook_whileComposing_stillPastes() {
        // Composition context must not stop the pre-IME hook. Otherwise the
        // very common "mid-pinyin paste" workflow would break under Gboard.
        val inputConnection = view.activeInputConnection()!!
        inputConnection.setComposingText("ni", 0)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "composing-paste"))

        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_V,
            metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
        )
        val consumed = view.dispatchKeyEventPreIme(ev)

        assertTrue("pre-IME hook must consume Ctrl+Shift+V while composing", consumed)
        assertEquals(
            "composing state must not block the pre-IME paste",
            "composing-paste",
            endpoint.bytesWritten().toString(Charsets.UTF_8),
        )
    }

    @Test
    fun test_preImeHook_doesNotInterfereWithNonPasteShortcuts() {
        // The hook must be selective: Ctrl+Space is an IME language switch
        // (Swallow verdict) and the IME is supposed to consume it. If our
        // hook ate every chord, Ctrl+Space would lose its IME meaning too.
        val ev = keyEvent(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_SPACE,
            metaState = KeyEvent.META_CTRL_ON,
        )
        val consumed = view.dispatchKeyEventPreIme(ev)

        assertFalse(
            "pre-IME hook must only intercept the Paste verdict — let other chords through to the IME",
            consumed,
        )
        assertEquals(
            "non-paste chord must not produce any bytes",
            0,
            endpoint.bytesWritten().size,
        )
    }

    // -----------------------------------------------------------------------
    // New key bindings added in the 2026-06-29 vim/nano support design.
    // These were missing or broken in the previous routing table and are
    // the reason the whole refactor exists. See spec §3.2.
    //
    // Notes on the test design:
    //  - `test_ctrlCaret_writesRsByte` and `test_ctrlUnderscore_writesUsByte`
    //    use KEYCODE_UNKNOWN + `apply { unicodeChar = '^'.code / '_'.code }`
    //    because Android's KeyEvent class has no KEYCODE_CIRCUMFLEX or
    //    KEYCODE_UNDERSCORE constants. The KEY_MAP entries for these match
    //    on `ev.unicodeChar`, not `ev.keyCode`, so the test must populate
    //    `unicodeChar` the way the Android framework does for real
    //    hardware-keyboard events.
    // -----------------------------------------------------------------------

    @Test
    fun test_escapeAlone_writesEscByte() {
        val ev = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)

        val verdict = KeyMapper.resolve(ev)

        assertSendBytes(
            "physical ESC must send 0x1B so vim can exit insert mode",
            verdict,
            byteArrayOf(0x1B.toByte()),
        )
    }

    @Test
    fun test_ctrlEscape_writesEscByte() {
        // Ctrl+ESC was already mapped in the old ctrlSequence() (it shared
        // a row with Ctrl+[), but it was undocumented. This test pins the
        // behavior now that it's in the data-driven table.
        val ev = keyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.META_CTRL_ON,
        )

        val verdict = KeyMapper.resolve(ev)

        assertSendBytes(
            "Ctrl+ESC must produce 0x1B (same byte as Ctrl+[)",
            verdict,
            byteArrayOf(0x1B.toByte()),
        )
    }

    @Test
    fun test_escape_whileComposing_isPassedToIme() {
        // Mid-IME composition (e.g. user mid-pinyin) must defer ESC to the
        // IME so it can cancel the composition, not blast 0x1B to the
        // remote shell. Verified end-to-end through TerminalView.onKeyDown.
        val inputConnection = view.activeInputConnection()!!
        inputConnection.setComposingText("ni", 0)

        val ev = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)
        val handled = view.onKeyDown(KeyEvent.KEYCODE_ESCAPE, ev)

        assertFalse("ESC while composing must be passed to the IME", handled)
        assertEquals(
            "ESC must not write 0x1B to SSH while composing",
            0,
            endpoint.bytesWritten().size,
        )
    }

    @Test
    fun test_shiftTab_writesBackTabSequence() {
        val ev = keyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.META_SHIFT_ON,
        )

        val verdict = KeyMapper.resolve(ev)

        assertSendBytes(
            "Shift+Tab must produce ESC[Z (Back-Tab)",
            verdict,
            "\u001B[Z".toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    fun test_insertKey_writesInsertSequence() {
        val ev = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_INSERT)

        val verdict = KeyMapper.resolve(ev)

        assertSendBytes(
            "KEYCODE_INSERT must produce ESC[2~ (Insert key sequence)",
            verdict,
            "\u001B[2~".toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    fun test_ctrlCaret_writesRsByte() {
        // Android's KeyEvent has no KEYCODE_CIRCUMFLEX constant. Hardware
        // keyboards that emit Ctrl+^ arrive as KEYCODE_UNKNOWN with
        // unicodeChar = '^'.code (94). KEY_MAP entry 5b matches on
        // unicodeChar, not keyCode.
        val ev = keyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_UNKNOWN,
            KeyEvent.META_CTRL_ON,
        ).also { it.setCharacters("^") }

        val verdict = KeyMapper.resolve(ev)

        assertSendBytes(
            "Ctrl+^ must produce 0x1E (RS) — vim alt-file",
            verdict,
            byteArrayOf(0x1E.toByte()),
        )
    }

    @Test
    fun test_ctrlUnderscore_writesUsByte() {
        // Android's KeyEvent has no KEYCODE_UNDERSCORE constant. Same
        // workaround as test_ctrlCaret_writesRsByte above.
        val ev = keyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_UNKNOWN,
            KeyEvent.META_CTRL_ON,
        ).also { it.setCharacters("_") }

        val verdict = KeyMapper.resolve(ev)

        assertSendBytes(
            "Ctrl+_ must produce 0x1F (US) — vim undo / nano go-to-line",
            verdict,
            byteArrayOf(0x1F.toByte()),
        )
    }

    @Test
    fun test_ctrlAt_writesNulByte() {
        val ev = keyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_AT,
            KeyEvent.META_CTRL_ON,
        )

        val verdict = KeyMapper.resolve(ev)

        assertSendBytes(
            "Ctrl+@ must produce 0x00 (NUL) — bash set-mark / nano set mark",
            verdict,
            byteArrayOf(0x00.toByte()),
        )
    }

    @Test
    fun test_ctrlSlash_writesDelByte() {
        val ev = keyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_SLASH,
            KeyEvent.META_CTRL_ON,
        )

        val verdict = KeyMapper.resolve(ev)

        assertSendBytes(
            "Ctrl+? must produce 0x7F (DEL) — alternative DEL byte",
            verdict,
            byteArrayOf(0x7F.toByte()),
        )
    }

    @Test
    fun test_newKeys_endToEnd_throughView_writeExpectedBytes() {
        // Integration-style: drive the same key events through the View
        // (not just KeyMapper) and assert the SSH channel sees the
        // expected bytes. This catches the "View layer is missing the
        // new key" class of bug — e.g. a future refactor that adds an
        // entry to KEY_MAP but forgets to add a parallel branch in
        // TerminalView.onKeyDown.
        val cases: List<Pair<KeyEvent, ByteArray>> = listOf(
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE) to byteArrayOf(0x1B.toByte()),
            keyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_TAB,
                KeyEvent.META_SHIFT_ON,
            ) to "\u001B[Z".toByteArray(Charsets.UTF_8),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_INSERT) to "\u001B[2~".toByteArray(Charsets.UTF_8),
            // KEYCODE_CIRCUMFLEX / KEYCODE_UNDERSCORE don't exist in
            // android.view.KeyEvent; route through KEYCODE_UNKNOWN +
            // unicodeChar, matching the live KEY_MAP entry 5b/5c.
            keyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_UNKNOWN,
                KeyEvent.META_CTRL_ON,
            ).also { it.setCharacters("^") } to byteArrayOf(0x1E.toByte()),
            keyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_UNKNOWN,
                KeyEvent.META_CTRL_ON,
            ).also { it.setCharacters("_") } to byteArrayOf(0x1F.toByte()),
            keyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_AT,
                KeyEvent.META_CTRL_ON,
            ) to byteArrayOf(0x00.toByte()),
            keyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_SLASH,
                KeyEvent.META_CTRL_ON,
            ) to byteArrayOf(0x7F.toByte()),
        )

        for ((ev, expectedBytes) in cases) {
            endpoint.clear()
            val handled = view.onKeyDown(ev.keyCode, ev)
            assertTrue("keyCode=${ev.keyCode} meta=${ev.metaState} must be consumed", handled)
            val written = endpoint.bytesWritten()
            assertArrayEquals(
                "keyCode=${ev.keyCode} meta=${ev.metaState} wrote wrong bytes",
                expectedBytes,
                written,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Meta-test for the data-driven KEY_MAP table.
    //
    // The spec requires two things to be true for the routing table to be
    // safe to ship:
    //  1. KEY_MAP is non-empty (otherwise nothing routes).
    //  2. Every existing test's key event is matched by at least one entry —
    //     if a refactor accidentally drops a route, this catches it before
    //     the rest of the test suite has to.
    //
    // The list of events is hard-coded from the test cases above. If a
    // future test adds a new event type, append it here too — otherwise
    // the meta test passes but the new event may still be unrouted.
    // -----------------------------------------------------------------------

    @Test
    fun test_keyMapTable_isWellFormed() {
        val entries = KeyMapper.entriesForTest()
        assertTrue("KEY_MAP must be non-empty", entries.isNotEmpty())

        val knownEvents = listOf(
            // Printable char (test_printableChar_isHandledByImePath_notView)
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A),
            // Ctrl+letter (test_ctrlA/B/E/L/R/U/W)
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_R, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_U, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, KeyEvent.META_CTRL_ON),
            // Ctrl+C (test_ctrlC_writesInterruptAndConsumesEvent)
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON),
            // Ctrl+\ Ctrl+] (test_ctrlBackslash / test_ctrlRightBracket)
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACKSLASH, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.META_CTRL_ON),
            // Enter / DEL
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL),
            // Arrow up
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP),
            // IME language switch
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.META_SHIFT_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_LANGUAGE_SWITCH),
            // Paste
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
            // Ctrl+V alone (must NOT match the Paste entry — must fall through to printable-key path)
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON),
            // Shift+V alone (must NOT match the Paste entry)
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, KeyEvent.META_SHIFT_ON),
        )

        for (ev in knownEvents) {
            val matched = entries.any { it.match(ev) }
            assertTrue(
                "event keyCode=${ev.keyCode} metaState=${ev.metaState} must be matched by some entry in KEY_MAP",
                matched,
            )
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private fun keyEvent(
        action: Int,
        keyCode: Int,
        metaState: Int = 0,
    ): KeyEvent = KeyEvent(
        /* downTime = */ 0L,
        /* eventTime = */ 0L,
        /* action = */ action,
        /* code = */ keyCode,
        /* repeat = */ 0,
        /* metaState = */ metaState,
    )

    /**
     * Reflectively set the `mCharacters` private field on a [KeyEvent].
     *
     * Android's public [KeyEvent] constructors do not accept a characters
     * argument; the field is normally populated by the framework from
     * [android.view.KeyCharacterMap]. For tests of the data-driven KEY_MAP
     * entries that match on `ev.unicodeChar` (e.g. the `Ctrl+^` and `Ctrl+_`
     * entries, since [KeyEvent.KEYCODE_CIRCUMFLEX] and
     * [KeyEvent.KEYCODE_UNDERSCORE] don't exist in the Android framework),
     * we need a way to construct a [KeyEvent] with a specific characters
     * value.
     *
     * Setting `mCharacters` is the correct approach because [KeyEvent.getUnicodeChar]
     * consults `mCharacters` first (it doesn't read a separate `mUnicodeChar`
     * field — the `unicodeChar` value is computed on the fly from
     * `mCharacters` + metaState, or falls back to KeyCharacterMap). So this
     * helper makes the production match work for the test event too.
     *
     * Reflection is acceptable here because the alternative is changing the
     * production match strategy to something less faithful to what a real
     * hardware keyboard delivers (scancode-based matching is device-specific
     * and would be a worse abstraction).
     */
    private fun KeyEvent.setCharacters(chars: String) {
        val field = KeyEvent::class.java.getDeclaredField("mCharacters").apply {
            isAccessible = true
        }
        field.set(this, chars)
    }

    /**
     * Assert that [verdict] is a [KeyResolution.Send] with bytes exactly
     * equal (in length and content) to [expectedBytes]. This works around
     * the data-class `equals()` gotcha where two `KeyResolution.Send` with
     * `byteArrayOf(0x1B)` are NOT equal because Kotlin's data-class
     * equality uses reference equality on ByteArray fields. Use
     * [assertArrayEquals] for the actual comparison.
     */
    private fun assertSendBytes(message: String, verdict: KeyResolution, expectedBytes: ByteArray) {
        val actual = (verdict as? KeyResolution.Send)?.bytes
        assertArrayEquals(message, expectedBytes, actual)
    }
}
