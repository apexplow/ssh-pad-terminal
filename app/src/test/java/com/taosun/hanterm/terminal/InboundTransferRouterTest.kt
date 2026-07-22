package com.taosun.hanterm.terminal

import com.taosun.hanterm.terminal.trzsz.TrzszFilter
import com.taosun.hanterm.terminal.zmodem.InMemoryTransferSink
import com.taosun.hanterm.terminal.zmodem.ZmodemFilter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundTransferRouterTest {

    @Test
    fun idleAsciiPassesThroughBoth() {
        val router = InboundTransferRouter(
            TrzszFilter(InMemoryTransferSink()),
            ZmodemFilter(InMemoryTransferSink()),
        )
        val text = "ls -la\r\n".toByteArray()
        val result = router.onInbound(text)
        assertArrayEquals(text, result.display)
        assertFalse(router.isCapturing)
    }

    @Test
    fun trzszMagicEngagesTrzszNotZmodem() {
        val router = InboundTransferRouter(
            TrzszFilter(InMemoryTransferSink()),
            ZmodemFilter(InMemoryTransferSink()),
        )
        val result = router.onInbound("::TRZSZ:TRANSFER:S:1.1.5:1\n".toByteArray())
        assertTrue(router.isCapturing)
        assertTrue(result.reply != null)
        assertTrue(String(result.reply!!, Charsets.ISO_8859_1).startsWith("#ACT:"))
    }
}
