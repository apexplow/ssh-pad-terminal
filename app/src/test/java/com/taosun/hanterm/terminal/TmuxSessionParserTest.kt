package com.taosun.hanterm.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JUnit tests for [TmuxSessionParser].
 *
 * No Robolectric needed — the parser is a string → list function. Cases
 * pin the contract documented in `TmuxSessionParser.parse`'s kdoc:
 *   - sentinel-bracketed extraction survives prior and trailing noise
 *     (the terminal transcript has the user's prompt, scrollback, etc.);
 *   - per-row parse failures don't poison the rest of the list;
 *   - empty/missing-sentinel cases return the empty list, never throw.
 */
class TmuxSessionParserTest {

    @Test
    fun parse_singleSession_returnsOneRow() {
        val transcript = """
            user@host:~$ ls
            file1 file2
            ${TmuxSessionParser.BEGIN_SENTINEL}
            main|3|attached|2024-01-01 12:00:00
            ${TmuxSessionParser.END_SENTINEL}
            user@host:~$
        """.trimIndent()

        val sessions = TmuxSessionParser.parse(transcript)
        assertEquals(1, sessions.size)
        assertEquals(
            TmuxSession(name = "main", windows = 3, attached = true, lastActivity = "2024-01-01 12:00:00"),
            sessions.single(),
        )
    }

    @Test
    fun parse_multipleSessions_preservesOrder() {
        val transcript = """
            ${TmuxSessionParser.BEGIN_SENTINEL}
            dev|2|detached|3 days ago
            build|1|attached|just now
            scratch|5|detached|1 hour ago
            ${TmuxSessionParser.END_SENTINEL}
        """.trimIndent()

        val sessions = TmuxSessionParser.parse(transcript)
        assertEquals(3, sessions.size)
        assertEquals("dev", sessions[0].name)
        assertEquals("build", sessions[1].name)
        assertEquals("scratch", sessions[2].name)
        assertEquals(false, sessions[0].attached)
        assertEquals(true, sessions[1].attached)
    }

    @Test
    fun parse_skipsMalformedRows_doesNotPoisonNeighbours() {
        // Real-world transcript shape: tmux prints an error between
        // BEGIN and END when one of the requested format tokens is
        // unknown to an old tmux build. Rows without exactly 4 pipe-
        // separated fields are dropped silently.
        val transcript = """
            ${TmuxSessionParser.BEGIN_SENTINEL}
            main|3|attached|today
            garbage-no-pipes
            also-garbage-with|only|two-pipes
            dev|2|detached|yesterday
            ${TmuxSessionParser.END_SENTINEL}
        """.trimIndent()

        val sessions = TmuxSessionParser.parse(transcript)
        assertEquals(2, sessions.size)
        assertEquals("main", sessions[0].name)
        assertEquals("dev", sessions[1].name)
    }

    @Test
    fun parse_dropsRowsWithBadAttachedField() {
        // Anything other than `attached` / `detached` in column 3
        // means our -F template and tmux's `-F` parser drifted — surface
        // the drift by dropping the row instead of mislabeling.
        val transcript = """
            ${TmuxSessionParser.BEGIN_SENTINEL}
            main|1|unknown|just now
            dev|1|detached|just now
            ${TmuxSessionParser.END_SENTINEL}
        """.trimIndent()

        val sessions = TmuxSessionParser.parse(transcript)
        assertEquals(1, sessions.size)
        assertEquals("dev", sessions.single().name)
    }

    @Test
    fun parse_returnsEmpty_whenBeginSentinelAbsent() {
        // User's prompt echoed without the probe ever running (race
        // window, or remote disconnected mid-probe). Drawer shows
        // "no sessions" — never crashes.
        val transcript = "no servers running on /tmp/tmux-1000/default"
        assertTrue(TmuxSessionParser.parse(transcript).isEmpty())
    }

    @Test
    fun parse_returnsEmpty_whenOnlyBeginSentinel() {
        // Polling deadline hit before tmux printed the end marker.
        // The drawer must not show "the half-session that exists".
        val transcript = """
            ${TmuxSessionParser.BEGIN_SENTINEL}
            main|3|attached|today
        """.trimIndent()
        assertTrue(TmuxSessionParser.parse(transcript).isEmpty())
    }

    @Test
    fun parse_returnsEmpty_forEmptyTranscript() {
        assertTrue(TmuxSessionParser.parse("").isEmpty())
    }

    @Test
    fun parse_stripsLeadingTrailingWhitespaceOnEachRow() {
        // tmux sometimes pads rows when output goes through a TTY that
        // wraps; the parser must trim before splitting on `|`.
        val transcript = """
            ${TmuxSessionParser.BEGIN_SENTINEL}
              main | 3 | attached | today
            ${TmuxSessionParser.END_SENTINEL}
        """.trimIndent()
        val sessions = TmuxSessionParser.parse(transcript)
        assertEquals(1, sessions.size)
        assertEquals("main", sessions.single().name)
        assertEquals(3, sessions.single().windows)
    }
}
