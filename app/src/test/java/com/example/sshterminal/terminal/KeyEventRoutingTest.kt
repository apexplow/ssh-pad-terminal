package com.example.sshterminal.terminal

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
