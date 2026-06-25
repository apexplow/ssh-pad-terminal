package com.example.sshterminal.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
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

        val verdict = KeyMapper.resolve(KeyEvent.KEYCODE_V, ev)

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

        val verdict = KeyMapper.resolve(KeyEvent.KEYCODE_V, ev)

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

        val verdict = KeyMapper.resolve(KeyEvent.KEYCODE_V, ev)

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
}
