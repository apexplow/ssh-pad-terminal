package com.example.sshterminal.terminal

import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the IME -> SSH byte pipeline.
 *
 * These tests pin the contract documented in implementation_plan.md §"TerminalInputConnection 方法验收规格"
 * and the 6 cases listed in test_plan.md §1, plus the post-2026-06-22 spec upgrade
 * (P0 Gboard race fix via lastComposingSnapshot, Ctrl+Space swallow rule).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TerminalInputConnectionTest {

    private lateinit var context: Context
    private lateinit var endpoint: MockEchoSession
    private lateinit var view: TestComposingView
    private lateinit var connection: TerminalInputConnection

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        endpoint = MockEchoSession()
        view = TestComposingView(context)
        connection = TerminalInputConnection(view, endpoint)
    }

    @Test
    fun test_setComposingText_updatesStateButDoesNotWriteToSsh() {
        connection.setComposingText("ni", 0)

        assertTrue("expected composing state after setComposingText", connection.isComposing())
        assertEquals("hint should reflect composing text", "ni", view.lastHint)
        assertEquals(
            "no SSH bytes must be written during pinyin composing",
            0,
            endpoint.bytesWritten().size,
        )
    }

    @Test
    fun test_commitText_sendsUtf8BytesAndClearsComposing() {
        connection.setComposingText("ni", 0)
        connection.commitText("你", 0)

        assertFalse("composing flag must be cleared after commit", connection.isComposing())
        assertNull("hint must be hidden after commit", view.lastHint)

        val written = endpoint.bytesWritten()
        assertEquals("你", String(written, Charsets.UTF_8))
    }

    @Test
    fun test_commitText_emptyTextIsNoOp() {
        connection.commitText("", 0)

        assertEquals(
            "empty commit must not write any byte",
            0,
            endpoint.bytesWritten().size,
        )
        assertFalse(connection.isComposing())
    }

    @Test
    fun test_deleteSurroundingText_whenComposing_doesNotSendDel() {
        connection.setComposingText("ni", 0)

        connection.deleteSurroundingText(1, 0)

        assertEquals(
            "DEL must not be sent to SSH while composing",
            0,
            endpoint.bytesWritten().size,
        )
        assertTrue("composing state must be preserved", connection.isComposing())
    }

    @Test
    fun test_deleteSurroundingText_whenIdle_sendsDelSequence() {
        connection.deleteSurroundingText(3, 0)

        val written = endpoint.bytesWritten()
        assertEquals("must send exactly beforeLength DEL bytes", 3, written.size)
        for (b in written) {
            assertEquals("each byte must be 0x7F (DEL)", 0x7F.toByte(), b)
        }
    }

    @Test
    fun test_finishComposingText_clearsStateButDoesNotWriteToSsh() {
        connection.setComposingText("ni", 0)

        connection.finishComposingText()

        assertFalse("composing flag must be cleared", connection.isComposing())
        assertNull("hint must be hidden", view.lastHint)
        assertEquals(
            "finishComposingText must not write any byte",
            0,
            endpoint.bytesWritten().size,
        )
    }

    // ---- Post-spec-upgrade tests (P0 Gboard race + Ctrl+Space swallow) ----

    @Test
    fun test_deleteSurroundingText_afterSetComposingTextEmpty_stillSuppressesDel() {
        // Gboard race per implementation_plan.md:
        //   "Gboard 会先调 setComposingText(\"\") 将 isComposing 置 false,
        //    再在同一事务内调 deleteSurroundingText(1,0)。
        //    此时若直接读 isComposing 会误判为"非组合" → 发 DEL 到远端(BUG)。"
        // Fix: lastComposingSnapshot captures composing=true at the start of
        // setComposingText(""), so the subsequent delete sees snapshot=true.
        connection.setComposingText("ni", 0)
        assertTrue(connection.isComposing())

        connection.setComposingText("", 0)   // Gboard clears composing
        connection.deleteSurroundingText(1, 0)

        assertEquals(
            "snapshot must still see composing=true from before setComposingText(\"\"); no DEL must be sent",
            0,
            endpoint.bytesWritten().size,
        )
    }

    @Test
    fun test_deleteSurroundingText_afterCommitText_stillSuppressesDel() {
        // Commit flips isComposing to false BEFORE deleteSurroundingText. The
        // snapshot taken at commit time is true (was composing), so the
        // subsequent delete is still treated as IME-internal.
        connection.setComposingText("ni", 0)
        connection.commitText("你", 0)
        endpoint.clear()  // drain the UTF-8 commit bytes

        connection.deleteSurroundingText(1, 0)
        assertEquals(
            "post-commit delete must not write DEL (snapshot remembers the prior composing=true)",
            0,
            endpoint.bytesWritten().size,
        )
    }

    @Test
    fun test_deleteSurroundingText_idleAfterLongIdleSession_sendsDel() {
        // Sanity: a long-idle session with no composing activity should still
        // route backspace to SSH. Guards against the snapshot field
        // accidentally sticking at true forever.
        connection.deleteSurroundingText(1, 0)
        assertEquals(1, endpoint.bytesWritten().size)
        assertEquals(0x7F.toByte(), endpoint.bytesWritten()[0])
    }

    @Test
    fun test_sendKeyEvent_whileComposing_consumesAndDoesNotWrite() {
        // Spec: while composing, sendKeyEvent must consume the event and write
        // nothing to the endpoint (the IME owns the letter keys).
        connection.setComposingText("ni", 0)
        endpoint.clear()

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        val handled = connection.sendKeyEvent(event)

        assertTrue("sendKeyEvent while composing must return true (consumed)", handled)
        assertEquals(
            "sendKeyEvent while composing must NOT write any byte",
            0,
            endpoint.bytesWritten().size,
        )
    }

    @Test
    fun test_sendKeyEvent_whileIdle_writesAnsiSequence() {
        val event = KeyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_DPAD_UP,
        )
        val handled = connection.sendKeyEvent(event)

        assertTrue(handled)
        val written = endpoint.bytesWritten()
        assertEquals("\u001B[A", String(written, Charsets.UTF_8))
    }
}

/**
 * Lightweight [TerminalComposingView] double that records the last hint text
 * so tests can assert what the input connection tried to show to the user.
 */
internal class TestComposingView(context: Context) : View(context), TerminalComposingView {
    var lastHint: String? = null

    override fun showComposingHint(text: String) {
        lastHint = text
    }

    override fun hideComposingHint() {
        lastHint = null
    }
}