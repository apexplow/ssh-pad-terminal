package com.taosun.hanterm.terminal

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.TerminalEmulator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the wiring between [TerminalView.transcriptOutput] (the
 * TerminalOutput handed to `TerminalEmulator` as its `mSession`) and the
 * bound [TerminalEndpoint]. Without this hop, every emulator-originated
 * byte — mouse events, CSI 6n/5n reports, OSC title / palette / clipboard
 * responses, primary / secondary DA replies — is silently dropped.
 *
 * Concretely: with the wrapper's TranscriptOutput set as the emulator's
 * mSession (which we do deliberately to avoid constructing a Termux
 * TerminalSession — see TerminalView.kt kdoc), the emulator's only
 * outbound channel is `mSession.write(string)`. The default base-class
 * impl encodes the String as UTF-8 and forwards to
 * `write(byte[], int, int)`. Our override in TerminalView must therefore
 * route those bytes back to the SSH endpoint; otherwise:
 *
 *  - A swipe in tmux with `set -g mouse on` triggers
 *    `emulator.sendMouseEvent(...)` → mSession.write → no-op, scroll
 *    silently swallowed (this is what the prior fix bypassed by
 *    assembling SGR bytes inline in ScrollbackController).
 *  - Bash `tput u7` / readline cursor-position probe (`ESC[6n`) elicits
 *    a CSI response that never reaches the remote.
 *  - OSC 52 remote clipboard reads (`ESC]52;c;...ST`) get no answer.
 *
 * These tests reproduce all three by driving the emulator directly, then
 * assert the bytes the wrapper should have forwarded to the endpoint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TerminalViewTranscriptOutputTest {

    private lateinit var context: Context
    private lateinit var endpoint: MockEchoSession
    private lateinit var view: TerminalView
    private lateinit var emulator: TerminalEmulator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        endpoint = MockEchoSession()
        view = TerminalView(context)
        view.bindEndpoint(endpoint)
        view.onCreateInputConnection(EditorInfo())
        emulator = view.termuxView.mEmulator!!
    }

    @Test
    fun sendMouseEvent_inAltBufferWithSgrTracking_forwardsWheelToEndpoint() {
        // Drive the emulator's sendMouseEvent path. With DECSET 1006 set,
        // Termux emits the SGR encoding via mSession.write(string) → our
        // transcriptOutput.write(bytes, 0, len) → endpoint. Without the
        // wrapper forwarding, the byte sink stays empty.
        emulator.doDecSetOrReset(true, 1049) // alt buffer
        emulator.doDecSetOrReset(true, 1000) // mouse tracking
        emulator.doDecSetOrReset(true, 1006) // SGR encoding
        endpoint.clear()

        emulator.sendMouseEvent(
            TerminalEmulator.MOUSE_WHEELUP_BUTTON,
            /* col = */ 10,
            /* row = */ 5,
            /* down = */ true,
        )

        val written = endpoint.bytesWritten()
        assertTrue(
            "sendMouseEvent bytes must reach the SSH endpoint, was=${written.toHex()}",
            written.isNotEmpty(),
        )
        val expected = "\u001b[<64;10;5M".toByteArray(Charsets.UTF_8)
        assertArrayEquals(
            "exact SGR wheel-up sequence must be forwarded byte-for-byte",
            expected,
            written,
        )
    }

    @Test
    fun sendMouseEvent_inAltBufferWithoutSgr_forwardsLegacyEncodingToEndpoint() {
        // DECSET 1000 only — no 1006 — must produce the legacy 6-byte
        // form: ESC [ M <button+32> <col+32> <row+32>.
        emulator.doDecSetOrReset(true, 1049)
        emulator.doDecSetOrReset(true, 1000)
        endpoint.clear()

        emulator.sendMouseEvent(
            TerminalEmulator.MOUSE_WHEELDOWN_BUTTON,
            /* col = */ 20,
            /* row = */ 8,
            /* down = */ true,
        )

        val written = endpoint.bytesWritten()
        assertEquals("legacy wheel event is 6 bytes", 6, written.size)
        assertEquals(0x1B.toByte(), written[0]) // ESC
        assertEquals('['.code.toByte(), written[1])
        assertEquals('M'.code.toByte(), written[2])
        assertEquals(
            "button byte = MOUSE_WHEELDOWN_BUTTON(65) + 32 = 97",
            (TerminalEmulator.MOUSE_WHEELDOWN_BUTTON + 32).toByte(),
            written[3],
        )
        assertEquals((20 + 32).toByte(), written[4])
        assertEquals((8 + 32).toByte(), written[5])
    }

    @Test
    fun csiCursorPositionQuery_responseReachesEndpoint() {
        // Bash / readline / `tput u7` probes the cursor position with
        // ESC[6n. The emulator answers with ESC[<row>;<col>R. Without the
        // forwarding, readline thinks the terminal is dumb and prints
        // garbage characters when the user navigates a multi-line edit.
        // Drive the request through emulator.append (the inbound path),
        // then assert the response came out via endpoint.write.
        endpoint.clear()
        emulator.append("\u001b[6n".toByteArray(Charsets.UTF_8), 4)

        val written = endpoint.bytesWritten()
        val asString = String(written, Charsets.UTF_8)
        assertTrue(
            "CSI 6n must trigger an ESC[<row>;<col>R response forwarded to the endpoint, got='$asString'",
            asString.matches(Regex("\\[[0-9]+;[0-9]+R")),
        )
    }

    @Test
    fun inboundBytes_throughEmulatorAppend_doNotLoopBackToEndpoint() {
        // Regression guard for the obvious feedback-loop concern: when
        // the emulator processes inbound bytes, those bytes must NOT be
        // re-emitted through transcriptOutput.write to the endpoint.
        // If they were, every keystroke would echo back as input and the
        // shell would see "abcabc" when the user typed "abc".
        endpoint.clear()
        val payload = "hello, world\n".toByteArray(Charsets.UTF_8)
        emulator.append(payload, payload.size)

        val written = endpoint.bytesWritten()
        assertEquals(
            "plain text inbound must not be re-emitted to the endpoint (would loop back as input)",
            0,
            written.size,
        )
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }