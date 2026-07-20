package com.taosun.hanterm.terminal.zmodem

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ZmodemFilterTest {

    @Test
    fun passThroughAsciiWhenIdle() {
        val sink = InMemoryTransferSink()
        val filter = ZmodemFilter(sink)
        val text = "hello world\r\n".toByteArray()
        val result = filter.onInbound(text)
        assertArrayEquals(text, result.display)
        assertNull(result.reply)
        assertNull(result.event)
        assertFalse(filter.isCapturing)
    }

    @Test
    fun fullSzFixtureSavesFileAndEmitsDone() {
        val inbound = javaClass.getResourceAsStream("/zmodem/sz_hello_inbound.bin")!!
            .readBytes()
        val sink = InMemoryTransferSink()
        val filter = ZmodemFilter(sink)

        // Feed in uneven chunks to exercise reassembly.
        var event: TransferEvent? = null
        val replies = ByteArrayOutputStream()
        var offset = 0
        val chunks = intArrayOf(7, 13, 29, 41, 64, inbound.size)
        for (end in chunks) {
            val to = end.coerceAtMost(inbound.size)
            if (offset >= to) continue
            val result = filter.onInbound(inbound.copyOfRange(offset, to))
            offset = to
            result.reply?.let { replies.write(it) }
            if (result.event != null) event = result.event
            // After the ZRQINIT handshake, capture suppresses binary frames.
            // The transition chunk may still emit a short ASCII prefix ("rz\r").
            if (filter.isCapturing && result.reply == null) {
                assertEquals(0, result.display.size)
            }
        }
        // Drain any remainder
        if (offset < inbound.size) {
            val result = filter.onInbound(inbound.copyOfRange(offset, inbound.size))
            result.reply?.let { replies.write(it) }
            if (result.event != null) event = result.event
        }

        assertTrue(event is TransferEvent.Done)
        assertEquals("zm_fixture.txt", (event as TransferEvent.Done).fileName)
        assertEquals("zm_fixture.txt", sink.begunName)
        assertTrue(sink.committed)
        assertArrayEquals("hello-zmodem-fixture\n".toByteArray(), sink.bytes)
        // First reply must be ZRINIT advertising ESCCTL|CANFC32|…
        val reply = replies.toByteArray()
        assertTrue(reply.size >= 20)
        assertEquals('*'.code, reply[0].toInt() and 0xFF)
        assertEquals('*'.code, reply[1].toInt() and 0xFF)
        assertEquals(0x18, reply[2].toInt() and 0xFF)
        assertEquals('B'.code, reply[3].toInt() and 0xFF)
    }

    @Test
    fun abortMidTransferFailsAndResets() {
        val inbound = javaClass.getResourceAsStream("/zmodem/sz_hello_inbound.bin")!!
            .readBytes()
        val sink = InMemoryTransferSink()
        val filter = ZmodemFilter(sink)
        // Feed only the ZRQINIT prefix so we enter capture, then abort.
        filter.onInbound(inbound.copyOfRange(0, 24))
        assertTrue(filter.isCapturing)
        val failed = filter.abort()
        assertTrue(failed is TransferEvent.Failed)
        assertFalse(filter.isCapturing)
        // After abort, ASCII passes through again.
        val result = filter.onInbound("ok\r".toByteArray())
        assertArrayEquals("ok\r".toByteArray(), result.display)
    }

    @Test
    fun fileNameSanitizerRejectsPathTraversal() {
        assertEquals("passwd", FileNameSanitizer.sanitize("../../etc/passwd"))
        assertEquals("note.md", FileNameSanitizer.sanitize("/tmp/../note.md"))
        assertEquals("download.bin", FileNameSanitizer.sanitize("///"))
        assertEquals("download.bin", FileNameSanitizer.sanitize(""))
        assertEquals("a.txt", FileNameSanitizer.sanitize("a<>:\"|?*.txt"))
    }

    @Test
    fun hexHeaderCrcMatchesLrzszZrinit() {
        // rz -e sends **\x18B0100000063f694\r\x8a\x11
        val hdr = ZmodemFilter.hexHeader(1, byteArrayOf(0, 0, 0, 0x63))
        val asString = hdr.toString(Charsets.US_ASCII)
        assertTrue(asString.startsWith("**\u0018B0100000063f694"))
    }
}
