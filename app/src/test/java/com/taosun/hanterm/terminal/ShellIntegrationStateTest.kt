package com.taosun.hanterm.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellIntegrationStateTest {

    @Test
    fun parseTitle_readyOutsideTmux_canInject() {
        val state = ShellIntegrationState.parseTitle(
            "HANTERM;1;READY;0;;C-b",
        )!!

        assertEquals(ShellPhase.READY, state.phase)
        assertFalse(state.inTmux)
        assertTrue(state.canInjectAtPrompt)
        assertEquals("C-b", state.tmuxPrefix)
    }

    @Test
    fun parseTitle_busyInsideTmux_preservesSessionAndDisablesPromptInjection() {
        val state = ShellIntegrationState.parseTitle(
            "HANTERM;1;BUSY;1;${'$'}12;C-a",
        )!!

        assertEquals(ShellPhase.BUSY, state.phase)
        assertTrue(state.inTmux)
        assertEquals("${'$'}12", state.sessionId)
        assertFalse(state.canInjectAtPrompt)
    }

    @Test
    fun parseTitle_ignoresUnrelatedOrMalformedTitles() {
        assertNull(ShellIntegrationState.parseTitle("vim file.txt"))
        assertNull(ShellIntegrationState.parseTitle("HANTERM;2;READY;0;;C-b"))
        assertNull(ShellIntegrationState.parseTitle("HANTERM;1;READY;maybe;;C-b"))
    }

    @Test
    fun prefixEncoder_supportsControlAndAsciiPrefixes() {
        assertArrayEquals(byteArrayOf(0x02), TmuxPrefixEncoder.encode("C-b"))
        assertArrayEquals(byteArrayOf(0x1C), TmuxPrefixEncoder.encode("C-\\"))
        assertArrayEquals(byteArrayOf(0x1D), TmuxPrefixEncoder.encode("C-]"))
        assertArrayEquals(byteArrayOf('x'.code.toByte()), TmuxPrefixEncoder.encode("x"))
        assertNull(TmuxPrefixEncoder.encode("F12"))
    }
}
