package com.taosun.hanterm.terminal

import com.taosun.hanterm.terminal.trzsz.TrzszFilter
import com.taosun.hanterm.terminal.zmodem.InMemoryTransferSink
import com.taosun.hanterm.terminal.zmodem.ZmodemFilter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- Issue #61 / P2: expand the router test matrix ----

    @Test
    fun zmodemZrqinitEngagesZmodemNotTrzsz() {
        // Issue #61: ZMODEM preempt when trzsz is idle. The router
        // must hand the bytes to ZmodemFilter (which transitions to
        // capture on ZRQINIT) and not let TrzszFilter hold them.
        //
        // We use the first 24 bytes of the sz_hello_inbound fixture
        // -- that fixture is a real lrzsz `sz hello.txt` capture, so
        // the leading ZRQINIT hex header is a byte-exact valid marker.
        // Hand-crafting a ZMODEM header in a unit test is fragile (the
        // CRC32 polynomial + escape rules are subtle) and unnecessary
        // when we have a fixture.
        val inbound = javaClass.getResourceAsStream("/zmodem/sz_hello_inbound.bin")!!
            .readBytes()
        val sink = InMemoryTransferSink()
        val trzsz = TrzszFilter(InMemoryTransferSink())
        val zmodem = ZmodemFilter(sink)
        val router = InboundTransferRouter(trzsz, zmodem)
        val result = router.onInbound(inbound.copyOfRange(0, 24))
        assertTrue(
            "zmodem must engage on ZRQINIT, got isCapturing=false",
            router.isCapturing,
        )
        assertTrue("zmodem must engage on ZRQINIT", zmodem.isCapturing)
        assertFalse("trzsz must NOT engage on a ZRQINIT marker", trzsz.isCapturing)
        // The ZRINIT reply goes out the wire.
        assertTrue("ZRINIT reply must be present", result.reply != null)
    }


    @Test
    fun bothIdleShellOutputMergesAndBothFiltersStayIdle() {
        // Issue #61: dual-idle path. A pure ASCII chunk that is
        // neither trzsz magic nor a ZRQINIT marker must pass through
        // both filters without engaging either. The router's display
        // equals the input bytes.
        val trzsz = TrzszFilter(InMemoryTransferSink())
        val zmodem = ZmodemFilter(InMemoryTransferSink())
        val router = InboundTransferRouter(trzsz, zmodem)
        val ascii = "ls -la\r\nif [ -f foo ]; then echo ok; fi\r\n".toByteArray()
        val result = router.onInbound(ascii)
        assertArrayEquals(ascii, result.display)
        assertFalse("trzsz must stay idle", trzsz.isCapturing)
        assertFalse("zmodem must stay idle", zmodem.isCapturing)
        assertFalse("router must not be capturing", router.isCapturing)
        assertNull("neither filter must emit a reply", result.reply)
        assertNull("neither filter must emit an event", result.event)
    }

    @Test
    fun abortPropagatesFailedEventsFromBothFilters() {
        // Issue #61: abort / cleanup failure path. When the router
        // aborts a live trzsz transfer, the trzsz Failed event must
        // bubble up. Same for zmodem. Both filters must end idle.
        val trzszSink = InMemoryTransferSink()
        val zmodemSink = InMemoryTransferSink()
        val trzsz = TrzszFilter(trzszSink)
        val zmodem = ZmodemFilter(zmodemSink)
        val router = InboundTransferRouter(trzsz, zmodem)

        // Engage trzsz via its magic.
        router.onInbound("::TRZSZ:TRANSFER:S:1.1.5:1\n".toByteArray())
        assertTrue(trzsz.isCapturing)

        val events = router.abort()
        assertTrue("router.abort must return at least one event",
            events.isNotEmpty())
        assertTrue("first event must be a Failed",
            events[0] is com.taosun.hanterm.terminal.zmodem.TransferEvent.Failed)
        assertFalse("trzsz must leave capturing mode after abort",
            trzsz.isCapturing)
        assertTrue("trzsz sink must be aborted",
            trzszSink.aborted)
    }

}
