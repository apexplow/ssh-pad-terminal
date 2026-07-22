package com.taosun.hanterm.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxSessionParserTest {

    @Test
    fun parse_multipleRows_preservesOrderAndStableIds() {
        val sessions = TmuxSessionParser.parse(
            """
            ${'$'}0|2|attached||main
            ${'$'}7|1|detached||构建
            """.trimIndent(),
        )

        assertEquals(2, sessions.size)
        assertEquals(TmuxSession("main", 2, true, "", id = "${'$'}0"), sessions[0])
        assertEquals(TmuxSession("构建", 1, false, "", id = "${'$'}7"), sessions[1])
    }

    @Test
    fun parse_nameContainingPipe_preservesWholeName() {
        val session = TmuxSessionParser.parse("${'$'}3|4|detached||dev|work").single()

        assertEquals("dev|work", session.name)
        assertEquals("${'$'}3", session.id)
    }

    @Test
    fun parse_stripsControlCharactersFromDisplayFields() {
        val session = TmuxSessionParser.parse(
            "${'$'}1|1|attached|to\u001Bday|ma\u0007in",
        ).single()

        assertEquals("today", session.lastActivity)
        assertEquals("main", session.name)
    }

    @Test
    fun parse_dropsMalformedRowsWithoutPoisoningNeighbours() {
        val sessions = TmuxSessionParser.parse(
            """
            not-an-id|1|attached||bad
            ${'$'}1|x|attached||bad
            ${'$'}2|1|unknown||bad
            ${'$'}3|2|detached||good
            """.trimIndent(),
        )

        assertEquals(listOf("good"), sessions.map { it.name })
    }

    @Test
    fun parse_emptyOutput_returnsEmpty() {
        assertTrue(TmuxSessionParser.parse("").isEmpty())
    }
}
