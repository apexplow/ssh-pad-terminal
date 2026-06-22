package com.example.sshterminal.terminal

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
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
 * Robolectric tests for the IME -> SSH byte pipeline.
 *
 * These tests pin the contract documented in implementation_plan.md §"TerminalInputConnection 方法验收规格"
 * and the 6 cases listed in test_plan.md §1.
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