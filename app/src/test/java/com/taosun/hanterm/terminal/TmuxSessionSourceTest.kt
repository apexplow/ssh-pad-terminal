package com.taosun.hanterm.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets
import kotlin.text.Charsets.UTF_8

/**
 * Tests for [TmuxSessionSource].
 *
 * The pure parts (`switchCommand`) are plain JUnit. The refresh path is
 * exercised under Robolectric because Termux's [TerminalEmulator] is an
 * Android-aware class (its `TerminalSessionClient` callbacks touch
 * Android Log in places). The emulator is constructed with a stub
 * [TerminalOutput] + [TerminalSessionClient] so we can drive its screen
 * buffer deterministically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TmuxSessionSourceTest {

    // ---------------------------------------------------------------------
    // switchCommand
    // ---------------------------------------------------------------------

    @Test
    fun switchCommand_simpleName_usesSingleQuotes() {
        val source = TmuxSessionSource(endpoint = RecordingEndpoint(), emulatorProvider = { null })

        val bytes = source.switchCommand("main")
        val expected = "tmux switch-client -t 'main' 2>/dev/null || tmux attach -t 'main'\r"
        assertArrayEquals(expected.toByteArray(UTF_8), bytes)
    }

    @Test
    fun switchCommand_nameWithSpaces_isQuoted() {
        val source = TmuxSessionSource(endpoint = RecordingEndpoint(), emulatorProvider = { null })

        val bytes = source.switchCommand("dev worktree")
        val text = String(bytes, UTF_8)
        // Single-quote wrapping is the POSIX shell escape for arbitrary
        // text; tmux session names can't contain `'`, so we never need
        // the more painful `'\''` close-then-reopen dance.
        assertEquals(
            "tmux switch-client -t 'dev worktree' 2>/dev/null || tmux attach -t 'dev worktree'\r",
            text,
        )
    }

    @Test
    fun switchCommand_chineseName_isUtf8Encoded() {
        val source = TmuxSessionSource(endpoint = RecordingEndpoint(), emulatorProvider = { null })

        val bytes = source.switchCommand("中文会话")
        // UTF-8 encoding is the only correct choice — see the SshConfig / PtyBridge
        // contract; the remote PTY decodes with the locale (usually UTF-8).
        val text = String(bytes, StandardCharsets.UTF_8)
        assertEquals(
            "tmux switch-client -t '中文会话' 2>/dev/null || tmux attach -t '中文会话'\r",
            text,
        )
    }

    @Test
    fun switchCommand_trailingCarriageReturn_mirrorsEnterKey() {
        // The trailing \r mirrors KEYCODE_ENTER (see SnippetPayload kdoc):
        // the remote PTY's line discipline (ONLCR) turns CR into the
        // newline the shell needs. Using \n would double-newline and
        // confuse shells whose history file records literal \n.
        val source = TmuxSessionSource(endpoint = RecordingEndpoint(), emulatorProvider = { null })

        val bytes = source.switchCommand("main")
        assertEquals('\r'.code.toByte(), bytes.last())
        assertTrue("must not include raw LF (would double-newline)", '\n'.code.toByte() !in bytes)
    }

    // ---------------------------------------------------------------------
    // refresh — end-to-end with a real (Robolectric) TerminalEmulator
    // ---------------------------------------------------------------------

    @Test
    fun refresh_writesProbeAndParsesPrePopulatedScreen() = kotlinx.coroutines.runBlocking {
        val endpoint = RecordingEndpoint()
        val emulator = newEmulator()
        // Pre-populate the emulator's screen as if tmux had already
        // printed its BEGIN/END-bracketed output. The source will still
        // write the probe to the endpoint (a no-op for our emulator —
        // we only care that the write happened), then poll the emulator
        // for END and find it immediately.
        val bytes = """
            ${TmuxSessionParser.BEGIN_SENTINEL}
            main|3|attached|2024-01-01 12:00:00
            dev|2|detached|3 days ago
            ${TmuxSessionParser.END_SENTINEL}
        """.trimIndent().toByteArray(UTF_8)
        emulator.append(bytes, bytes.size)

        val source = TmuxSessionSource(
            endpoint = endpoint,
            emulatorProvider = { emulator },
            pollDelay = { /* no-op so the test doesn't actually sleep 100ms */ },
        )

        val result = source.refresh()
        assertTrue("refresh should succeed when emulator is wired", result.isSuccess)
        val sessions = result.getOrThrow()
        if (sessions.size != 2) {
            // Surface what the emulator actually saw — easier than bisecting
            // the byte pipeline when this regresses.
            val transcript = emulator.screen.transcriptTextWithoutJoinedLines
            throw AssertionError(
                "expected 2 sessions, got ${sessions.size}\n" +
                    "transcript:\n$transcript",
            )
        }
        assertEquals(
            TmuxSession("main", windows = 3, attached = true, lastActivity = "2024-01-01 12:00:00"),
            sessions[0],
        )
        assertEquals(
            TmuxSession("dev", windows = 2, attached = false, lastActivity = "3 days ago"),
            sessions[1],
        )

        // And the probe MUST have been written — that's the outbound action
        // we care about; the poll/parse is best-effort.
        assertEquals(1, endpoint.writes.size)
        val written = String(endpoint.writes.single(), UTF_8)
        assertTrue(
            "probe must emit BEGIN sentinel; got: $written",
            written.contains("printf '${TmuxSessionParser.BEGIN_SENTINEL}"),
        )
        assertTrue(
            "probe must emit END sentinel",
            written.contains("printf '${TmuxSessionParser.END_SENTINEL}"),
        )
        assertTrue(
            "probe must invoke tmux list-sessions with -F",
            written.contains("tmux list-sessions -F '"),
        )
    }

    @Test
    fun refresh_returnsEmptyList_whenScreenHasNoSentinel() = kotlinx.coroutines.runBlocking {
        // tmux not installed or no server running: the BEGIN/END printfs
        // fire (we control them) but the middle line is an error message
        // like "no servers running on /tmp/tmux-1000/default", which the
        // parser correctly drops because it doesn't have 4 pipe-separated
        // fields.
        val endpoint = RecordingEndpoint()
        val emulator = newEmulator()
        val bytes = """
            ${TmuxSessionParser.BEGIN_SENTINEL}
            no servers running on /tmp/tmux-1000/default
            ${TmuxSessionParser.END_SENTINEL}
        """.trimIndent().toByteArray(UTF_8)
        emulator.append(bytes, bytes.size)

        val source = TmuxSessionSource(
            endpoint = endpoint,
            emulatorProvider = { emulator },
            pollDelay = { /* no-op */ },
        )

        val result = source.refresh()
        assertTrue(result.isSuccess)
        assertEquals(emptyList<TmuxSession>(), result.getOrThrow())
    }

    @Test
    fun refresh_waitsForExactEndLine_ignoresEchoedPrintfCommand() = kotlinx.coroutines.runBlocking {
        // Regression: PTY echo of `printf '__HANTERM_TMUX_END__\n'` puts the
        // sentinel substring in the transcript *before* printf executes.
        // pollForEnd used to String.contains() and return early; parse then
        // found no exact END line → Empty drawer despite real session rows.
        val endpoint = RecordingEndpoint()
        val emulator = newEmulator()
        val premature = """
            ${TmuxSessionParser.BEGIN_SENTINEL}
            myaws-24|1|detached|
            myjob-27|1|detached|
            tao@host:~${'$'} printf '${TmuxSessionParser.END_SENTINEL}\n'
        """.trimIndent().toByteArray(UTF_8)
        emulator.append(premature, premature.size)

        var polls = 0
        val source = TmuxSessionSource(
            endpoint = endpoint,
            emulatorProvider = { emulator },
            pollDelay = {
                polls++
                if (polls == 1) {
                    // Simulate printf finally running after the echoed command.
                    val endLine = "\n${TmuxSessionParser.END_SENTINEL}\n".toByteArray(UTF_8)
                    emulator.append(endLine, endLine.size)
                }
            },
        )

        val result = source.refresh()
        assertTrue(result.isSuccess)
        val sessions = result.getOrThrow()
        assertEquals(
            "expected 2 sessions after waiting for exact END; polls=$polls transcript=\n" +
                emulator.screen.transcriptTextWithoutJoinedLines,
            2,
            sessions.size,
        )
        assertEquals("myaws-24", sessions[0].name)
        assertEquals("myjob-27", sessions[1].name)
        assertTrue("must poll at least once past the echoed printf", polls >= 1)
    }

    @Test
    fun transcriptHasEndSentinel_rejectsEchoedPrintfCommand() {
        val source = TmuxSessionSource(endpoint = RecordingEndpoint(), emulatorProvider = { null })
        val echoedOnly = "tao@host:~${'$'} printf '${TmuxSessionParser.END_SENTINEL}\\n'"
        assertTrue(
            "substring must not count as done",
            !source.transcriptHasEndSentinel(echoedOnly),
        )
        assertTrue(
            source.transcriptHasEndSentinel(
                "${TmuxSessionParser.BEGIN_SENTINEL}\nmain|1|detached|\n${TmuxSessionParser.END_SENTINEL}\n",
            ),
        )
    }

    @Test
    fun refresh_returnsFailure_whenEmulatorUnavailable() = kotlinx.coroutines.runBlocking {
        // Disconnected state: emulatorProvider returns null (no TerminalView
        // published yet, or AndroidView already released). We must NOT emit a
        // probe in this state — there's nothing to read back into.
        val endpoint = RecordingEndpoint()
        val source = TmuxSessionSource(
            endpoint = endpoint,
            emulatorProvider = { null },
            pollDelay = { /* no-op */ },
        )

        val result = source.refresh()
        assertTrue("expected failure when emulator unavailable", result.isFailure)
        assertEquals(0, endpoint.writes.size)
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private fun newEmulator(): TerminalEmulator = TerminalEmulator(
        NoopTerminalOutput(),
        /* columns = */ 80,
        /* rows = */ 24,
        /* transcriptRows = */ 1000,
        NoopTerminalSessionClient(),
    )

    /**
     * Minimal [TerminalEndpoint] that records every `write` so tests can
     * assert on the wire bytes.
     */
    private class RecordingEndpoint : TerminalEndpoint {
        val writes: MutableList<ByteArray> = mutableListOf()
        override fun write(bytes: ByteArray) {
            writes.add(bytes.copyOf())
        }
    }

    private class NoopTerminalOutput : TerminalOutput() {
        override fun write(bytes: ByteArray?, offset: Int, length: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    /**
     * Implements all 15 methods of [TerminalSessionClient] with no-ops.
     * [TerminalEmulator]'s constructor needs an instance — it calls
     * [TerminalSessionClient.logError] / `logWarn` / `logInfo` etc. when
     * something inside its byte parser notices a malformed sequence, and
     * the rest is empty observer plumbing.
     */
    private class NoopTerminalSessionClient : TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession?) = Unit
        override fun onTitleChanged(session: TerminalSession?) = Unit
        override fun onSessionFinished(session: TerminalSession?) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession?) = Unit
        override fun onColorsChanged(session: TerminalSession?) = Unit
        override fun onTerminalCursorStateChange(visible: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: java.lang.Exception?) = Unit
        override fun logStackTrace(tag: String?, e: java.lang.Exception?) = Unit
    }
}
