package com.apexplow.hanterm.terminal.trzsz

import com.apexplow.hanterm.terminal.zmodem.InMemoryTransferSink
import com.apexplow.hanterm.terminal.zmodem.TransferEvent
import com.apexplow.hanterm.terminal.zmodem.TransferLimits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class TrzszFilterTest {

    @Test
    fun passThroughAsciiWhenIdle() {
        val sink = InMemoryTransferSink()
        val filter = TrzszFilter(sink)
        val text = "hello world\r\n".toByteArray()
        val result = filter.onInbound(text)
        assertArrayEquals(text, result.display)
        assertNull(result.reply)
        assertNull(result.event)
        assertFalse(filter.isCapturing)
    }

    @Test
    fun fullTszTransferSavesFileAndEmitsDone() {
        val sink = InMemoryTransferSink()
        val filter = TrzszFilter(sink)
        val fileBytes = "hello-trzsz-fixture\n".toByteArray()
        val md5 = MessageDigest.getInstance("MD5").digest(fileBytes)

        // 1) Magic — client replies with ACT.
        val magic = "::TRZSZ:TRANSFER:S:1.1.5:12345678901234\n".toByteArray()
        val r1 = filter.onInbound(magic)
        assertTrue(filter.isCapturing)
        assertTrue(r1.reply != null && String(r1.reply!!, Charsets.ISO_8859_1).startsWith("#ACT:"))
        assertEquals(0, r1.display.size)

        // 2) CFG (non-binary, no directory).
        val cfgJson = """{"lang":"py","binary":false,"quiet":true}"""
        val r2 = filter.onInbound(protoLine("CFG", TrzszCodec.encodeUtf8(cfgJson)))
        assertNull(r2.event)

        // 3) NUM → SUCC
        val r3 = filter.onInbound(protoLine("NUM", "1"))
        assertTrue(String(r3.reply!!, Charsets.ISO_8859_1).contains("#SUCC:1"))

        // 4) NAME → SUCC
        val r4 = filter.onInbound(protoLine("NAME", TrzszCodec.encodeUtf8("trz_fixture.txt")))
        assertEquals("trz_fixture.txt", sink.begunName)
        assertTrue(String(r4.reply!!, Charsets.ISO_8859_1).startsWith("#SUCC:"))

        // 5) SIZE → SUCC
        val r5 = filter.onInbound(protoLine("SIZE", fileBytes.size.toString()))
        assertTrue(String(r5.reply!!, Charsets.ISO_8859_1).contains("#SUCC:${fileBytes.size}"))

        // 6) DATA → SUCC
        val r6 = filter.onInbound(protoLine("DATA", TrzszCodec.encode(fileBytes)))
        assertTrue(String(r6.reply!!, Charsets.ISO_8859_1).contains("#SUCC:${fileBytes.size}"))

        // 7) MD5 → SUCC + EXIT + Done
        val r7 = filter.onInbound(protoLine("MD5", TrzszCodec.encode(md5)))
        assertTrue(r7.event is TransferEvent.Done)
        assertEquals("trz_fixture.txt", (r7.event as TransferEvent.Done).fileName)
        assertTrue(sink.committed)
        assertFalse(sink.aborted)
        assertArrayEquals(fileBytes, sink.bytes)
        assertFalse(filter.isCapturing)
        val reply = String(r7.reply!!, Charsets.ISO_8859_1)
        assertTrue(reply.contains("#EXIT:"))
    }

    @Test
    fun abortMidTransferFailsAndResets() {
        val sink = InMemoryTransferSink()
        val filter = TrzszFilter(sink)
        filter.onInbound("::TRZSZ:TRANSFER:S:1.1.5:1\n".toByteArray())
        assertTrue(filter.isCapturing)
        val failed = filter.abort()
        assertTrue(failed is TransferEvent.Failed)
        assertFalse(filter.isCapturing)
        val after = filter.onInbound("ok\r\n".toByteArray())
        assertArrayEquals("ok\r\n".toByteArray(), after.display)
    }

    @Test
    fun directoryConfigFailsCleanly() {
        val sink = InMemoryTransferSink()
        val filter = TrzszFilter(sink)
        filter.onInbound("::TRZSZ:TRANSFER:S:1.1.5:99\n".toByteArray())
        val cfgJson = """{"lang":"py","directory":true}"""
        val result = filter.onInbound(protoLine("CFG", TrzszCodec.encodeUtf8(cfgJson)))
        assertTrue(result.event is TransferEvent.Failed)
        assertEquals(
            "directory transfer not supported",
            (result.event as TransferEvent.Failed).reason,
        )
        assertFalse(sink.committed)
        assertFalse(filter.isCapturing)
        assertNull(sink.begunName)
    }

    @Test
    fun uploadMagicPassesThrough() {
        val sink = InMemoryTransferSink()
        val filter = TrzszFilter(sink)
        val magic = "::TRZSZ:TRANSFER:R:1.1.5:1\n".toByteArray()
        val result = filter.onInbound(magic)
        assertArrayEquals(magic, result.display)
        assertFalse(filter.isCapturing)
        assertNull(result.reply)
    }

    @Test
    fun codecRoundTrip() {
        val raw = "hello-trzsz".toByteArray()
        val enc = TrzszCodec.encode(raw)
        assertArrayEquals(raw, TrzszCodec.decode(enc))
        val text = """{"binary":false}"""
        assertEquals(text, TrzszCodec.decodeUtf8(TrzszCodec.encodeUtf8(text)))
    }

    @Test
    fun tmuxJunkOnCfgLineIsStripped() {
        val sink = InMemoryTransferSink()
        val filter = TrzszFilter(sink)
        filter.onInbound("::TRZSZ:TRANSFER:S:1.1.5:42\n".toByteArray())
        // First CFG enables tmux_output_junk for subsequent lines — set via a
        // clean CFG, then verify a junked NUM still parses.
        val cfgJson = """{"lang":"py","tmux_output_junk":true,"quiet":true}"""
        filter.onInbound(protoLine("CFG", TrzszCodec.encodeUtf8(cfgJson)))
        val junkedNum =
            ("\u001bP=status\u001bP=more\u001b\\prefix#NUM:1\n").toByteArray(Charsets.ISO_8859_1)
        val r = filter.onInbound(junkedNum)
        assertTrue(String(r.reply!!, Charsets.ISO_8859_1).contains("#SUCC:1"))
    }

    private fun protoLine(type: String, payload: String): ByteArray =
        "#$type:$payload\n".toByteArray(Charsets.ISO_8859_1)

    // ---- Issue #60: pending buffer cap ----

    @Test
    fun pendingOverflowAbortsTrzszFilter() {
        // Issue #60 / P2: idle-state pending buffer must be bounded.
        // Mirrors ZmodemFilterTest.pendingOverflowAbortsFilter — push
        // MAX_PENDING_BYTES + 1 ASCII bytes that never become the
        // trzsz magic marker. Filter must abort with Failed rather
        // than OOM.
        val sink = InMemoryTransferSink()
        val filter = TrzszFilter(sink)
        val junk = ByteArray(TransferLimits.MAX_PENDING_BYTES + 1) { 'a'.code.toByte() }
        val result = filter.onInbound(junk)
        assertTrue(
            "hostile junk stream must produce a Failed event; got " + result.event,
            result.event is TransferEvent.Failed,
        )
        // After overflow the filter is back to idle; shell output passes
        // through normally (the next inbound chunk is treated as terminal
        // text, not as a transfer).
        val next = filter.onInbound("ok\r\n".toByteArray())
        assertArrayEquals("ok\r\n".toByteArray(), next.display)
    }

}
