package com.apexplow.hanterm.terminal

import com.apexplow.hanterm.terminal.trzsz.TrzszFilter
import com.apexplow.hanterm.terminal.zmodem.FilterResult
import com.apexplow.hanterm.terminal.zmodem.ZmodemFilter

/**
 * Mutually exclusive inbound router for ZMODEM (`sz`) and trzsz (`tsz`).
 *
 * At most one filter is capturing. While both are idle, [TrzszFilter] is
 * offered the chunk first (printable magic); if it stays idle with a pure
 * pass-through, [ZmodemFilter] sees the same bytes. This preserves both
 * protocols without feeding one filter's mid-transfer stream to the other.
 */
class InboundTransferRouter(
    private val trzsz: TrzszFilter,
    private val zmodem: ZmodemFilter,
) {
    val isCapturing: Boolean
        get() = trzsz.isCapturing || zmodem.isCapturing

    fun onInbound(bytes: ByteArray): FilterResult {
        if (bytes.isEmpty()) return FilterResult.pass(FilterResult.EMPTY)

        when {
            trzsz.isCapturing -> return trzsz.onInbound(bytes)
            zmodem.isCapturing -> return zmodem.onInbound(bytes)
        }

        // Both idle: try trzsz first. If it starts capturing or emits a
        // reply/event, that result wins. Otherwise fall through to ZMODEM
        // with the original bytes (trzsz pass-through equals identity when
        // no magic is held back — except a short holdback suffix).
        val trz = trzsz.onInbound(bytes)
        if (trzsz.isCapturing || trz.reply != null || trz.event != null) {
            return trz
        }

        // If trzsz held back a magic prefix, [trz.display] is shorter than
        // [bytes]; feed ZMODEM only what was released, then keep the suffix
        // in trzsz pending for the next chunk. ZMODEM must not see a partial
        // `::TRZSZ` prefix as terminal noise that also gets held — so pass
        // the released display to ZMODEM (or full bytes when nothing held).
        val forZmodem = if (trz.display.size == bytes.size) bytes else trz.display
        if (forZmodem.isEmpty()) {
            return trz
        }
        val zm = zmodem.onInbound(forZmodem)
        // Prefer ZMODEM reply/event when it engages; otherwise keep trz
        // display (already equals forZmodem when sizes match).
        if (zmodem.isCapturing || zm.reply != null || zm.event != null) {
            return zm
        }
        // Merge: if trz held a suffix, display is only the released prefix
        // after ZMODEM also passed it through.
        return if (trz.display.size < bytes.size) trz else zm
    }

    fun abort(): List<com.apexplow.hanterm.terminal.zmodem.TransferEvent> {
        val events = ArrayList<com.apexplow.hanterm.terminal.zmodem.TransferEvent>(2)
        trzsz.abort()?.let { events.add(it) }
        zmodem.abort()?.let { events.add(it) }
        return events
    }
}
