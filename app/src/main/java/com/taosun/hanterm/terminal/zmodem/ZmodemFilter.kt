package com.taosun.hanterm.terminal.zmodem

import java.io.OutputStream

/**
 * One result from [ZmodemFilter.onInbound].
 *
 * @param display bytes that should reach the terminal emulator (empty while
 *   a transfer is capturing the PTY stream)
 * @param reply bytes that must be written back to the remote via
 *   [com.taosun.hanterm.terminal.TerminalEndpoint.write]
 * @param event optional transfer lifecycle event for Snackbar / logging
 */
data class FilterResult(
    val display: ByteArray = EMPTY,
    val reply: ByteArray? = null,
    val event: TransferEvent? = null,
) {
    companion object {
        val EMPTY = ByteArray(0)
        fun pass(display: ByteArray) = FilterResult(display = display)
    }
}

sealed class TransferEvent {
    data class Done(val fileName: String) : TransferEvent()
    data class Failed(val reason: String) : TransferEvent()
}

/**
 * Deep module: auto-detect lrzsz `sz` ZMODEM on the inbound PTY byte stream,
 * suppress binary frames from the emulator, reply with ZRINIT/ZRPOS/ZACK/ZFIN,
 * and stream file bytes into [TransferSink].
 *
 * v1 receive-only (`sz`). `rz` upload is explicitly out of scope.
 *
 * Protocol surface is the lrzsz common path: hex headers with CRC-16,
 * binary headers with CRC-16 or CRC-32 (CANFC32), ZDLE escaping, ZCRCE/G/Q/W
 * subpackets. We advertise ESCCTL|CANFC32|CANFDX|CANOVIO so `sz -e` skips
 * ZSINIT and proceeds straight to ZFILE (matching stock `rz -e`).
 */
class ZmodemFilter(
    private val sink: TransferSink,
) {
    private var state: State = State.Idle
    private val pending = ArrayDeque<Byte>()
    private var useCrc32: Boolean = true
    private var expectSubpacket: Boolean = false
    private var pendingFrameType: Int = -1
    private var fileOut: OutputStream? = null
    private var fileName: String? = null
    private var bytesReceived: Int = 0
    private var finishedName: String? = null

    /** True while a transfer is in progress (binary frames are suppressed). */
    val isCapturing: Boolean
        get() = state != State.Idle

    fun onInbound(bytes: ByteArray): FilterResult {
        if (bytes.isEmpty()) return FilterResult.pass(FilterResult.EMPTY)

        return when (state) {
            State.Idle -> feedIdle(bytes)
            else -> feedActive(bytes)
        }
    }

    /**
     * Abort any in-flight transfer (session disconnect). Returns a Failed
     * event when a receive was open; otherwise null.
     */
    fun abort(): TransferEvent? {
        if (state == State.Idle && fileOut == null) return null
        val name = fileName
        cleanup(failed = true)
        return TransferEvent.Failed(
            if (name != null) "Transfer aborted: $name" else "Transfer aborted",
        )
    }

    private fun feedIdle(bytes: ByteArray): FilterResult {
        for (b in bytes) pending.addLast(b)
        // Detect ZRQINIT hex header. Also accept a leading "rz\r" from sz.
        val marker = indexOfZrqinit()
        if (marker < 0) {
            // Only hold back a suffix that could still become `**\x18B00…`
            // (or `*\x18B00…`). Everything before that goes to the emulator.
            val keep = idleHoldback()
            val emitCount = pending.size - keep
            if (emitCount <= 0) return FilterResult.pass(FilterResult.EMPTY)
            val display = ByteArray(emitCount)
            for (i in 0 until emitCount) display[i] = pending.removeFirst()
            return FilterResult.pass(display)
        }
        val display = ByteArray(marker)
        for (i in 0 until marker) display[i] = pending.removeFirst()
        // Drop the ZRQINIT header itself (and trailing CR/0x8a/XON).
        consumeHexHeaderTrailer()
        state = State.Active
        useCrc32 = true
        val reply = hexHeader(ZRINIT, byteArrayOf(0, 0, 0, ZRINIT_FLAGS.toByte()))
        return FilterResult(display = display, reply = reply)
    }

    /**
     * How many trailing idle bytes might still grow into a ZRQINIT marker.
     * Holding more would stall normal shell output (every keystroke).
     */
    private fun idleHoldback(): Int {
        val arr = pending.toByteArray()
        // Longest prefix of `**\x18B00` we might be mid-way through: 6 bytes.
        val needle = byteArrayOf(
            ZPAD.toByte(), ZPAD.toByte(), ZDLE.toByte(), ZHEX.toByte(),
            '0'.code.toByte(), '0'.code.toByte(),
        )
        val alt = byteArrayOf(
            ZPAD.toByte(), ZDLE.toByte(), ZHEX.toByte(),
            '0'.code.toByte(), '0'.code.toByte(),
        )
        for (len in needle.size downTo 1) {
            if (arr.size >= len && arr.copyOfRange(arr.size - len, arr.size).contentEquals(needle.copyOf(len))) {
                return len
            }
        }
        for (len in alt.size downTo 1) {
            if (arr.size >= len && arr.copyOfRange(arr.size - len, arr.size).contentEquals(alt.copyOf(len))) {
                return len
            }
        }
        return 0
    }

    private fun feedActive(bytes: ByteArray): FilterResult {
        for (b in bytes) pending.addLast(b)
        val reply = ArrayList<Byte>()
        var event: TransferEvent? = null
        var guard = 0
        while (guard++ < 10_000) {
            // Remote `sz` abort: a run of CAN (0x18) bytes outside a
            // ZPAD-ZDLE frame. Without this, the filter stays in capture
            // forever, swallows the sender's error text, never emits
            // Failed, and leaves a MediaStore IS_PENDING entry invisible
            // in Downloads until session teardown.
            detectCancel()?.let { cancelEvent ->
                return FilterResult(
                    display = FilterResult.EMPTY,
                    reply = if (reply.isEmpty()) null else reply.toByteArray(),
                    event = cancelEvent,
                )
            }
            val before = pending.size
            val stepEvent = step(reply)
            if (stepEvent != null) event = stepEvent
            if (state == State.Idle) break
            if (pending.size == before) break
        }
        val doneName = finishedName
        if (doneName != null) {
            finishedName = null
            // Done wins over a Failed from the same chunk only when ZEOF
            // committed successfully; Failed paths clear finishedName.
            event = TransferEvent.Done(doneName)
        }
        return FilterResult(
            display = FilterResult.EMPTY,
            reply = if (reply.isEmpty()) null else reply.toByteArray(),
            event = event,
        )
    }

    /**
     * lrzsz / ZMODEM cancel is eight CAN bytes (`0x18`). ZDLE is also
     * `0x18`, but a real frame always starts `ZPAD ZDLE` (`* \x18`); a
     * bare run of CANs is the abort signal. Threshold of 5 matches
     * common receivers and fires before a full 8 when the sender is
     * already gone.
     *
     * Outside a subpacket we only accept a CAN run at the head of
     * [pending] (after optional non-frame junk). Mid-subpacket we scan
     * for any CAN run — file bytes may contain `0x2A 0x18`, which must
     * not be mistaken for a frame start that would suppress cancel.
     */
    private fun detectCancel(): TransferEvent? {
        if (state == State.Idle || pending.isEmpty()) return null
        val snapshot = pending.toList()
        if (expectSubpacket) {
            var i = 0
            while (i < snapshot.size) {
                if ((snapshot[i].toInt() and 0xFF) != ZDLE) {
                    i++
                    continue
                }
                var run = 0
                while (i < snapshot.size && (snapshot[i].toInt() and 0xFF) == ZDLE) {
                    run++
                    i++
                }
                if (run >= CAN_CANCEL_THRESHOLD) return failCancelled()
            }
            return null
        }
        var i = 0
        while (i < snapshot.size) {
            val v = snapshot[i].toInt() and 0xFF
            if (v == ZPAD && i + 1 < snapshot.size &&
                (snapshot[i + 1].toInt() and 0xFF) == ZDLE
            ) {
                return null
            }
            if (v == ZDLE) break
            i++
        }
        var run = 0
        while (i < snapshot.size && (snapshot[i].toInt() and 0xFF) == ZDLE) {
            run++
            i++
        }
        return if (run >= CAN_CANCEL_THRESHOLD) failCancelled() else null
    }

    private fun failCancelled(): TransferEvent {
        val name = fileName
        cleanup(failed = true)
        return TransferEvent.Failed(
            if (name != null) "Transfer cancelled: $name" else "Transfer cancelled",
        )
    }

    private fun step(reply: ArrayList<Byte>): TransferEvent? {
        if (expectSubpacket) {
            return readSubpacket(reply)
        }
        // Skip to next ZPAD ZDLE frame start. Do not drop a leading CAN
        // run — a partial cancel (fewer than [CAN_CANCEL_THRESHOLD]) must
        // stay buffered so the next chunk can complete the sequence.
        while (pending.isNotEmpty()) {
            val b = pending.first().toInt() and 0xFF
            if (b == ZPAD) {
                if (pending.size >= 2 && pending.elementAt(1) == ZPAD.toByte()) {
                    pending.removeFirst()
                    continue
                }
                if (pending.size >= 2 && (pending.elementAt(1).toInt() and 0xFF) == ZDLE) {
                    break
                }
            }
            if (b == ZDLE) {
                // Count a leading CAN run. Full cancel is handled by
                // detectCancel; a short run followed by other bytes is
                // junk (drop it). A short run alone must stay buffered
                // so the next chunk can complete the cancel sequence.
                var run = 0
                for (x in pending) {
                    if ((x.toInt() and 0xFF) != ZDLE) break
                    run++
                }
                if (run >= CAN_CANCEL_THRESHOLD) return null
                if (pending.size > run) {
                    repeat(run) { pending.removeFirst() }
                    continue
                }
                return null
            }
            pending.removeFirst()
        }
        if (pending.size < 3) return null
        // pending[0]=ZPAD, [1]=ZDLE, [2]=frame kind
        val kind = pending.elementAt(2).toInt() and 0xFF
        return when (kind) {
            ZHEX -> parseHexHeader(reply)
            ZBIN, ZBIN32 -> parseBinaryHeader(reply, crc32 = kind == ZBIN32)
            else -> {
                pending.removeFirst()
                null
            }
        }
    }

    private fun parseHexHeader(reply: ArrayList<Byte>): TransferEvent? {
        // ZPAD ZDLE ZHEX + 14 hex digits
        if (pending.size < 3 + 14) return null
        val hexChars = CharArray(14)
        for (i in 0 until 14) {
            hexChars[i] = pending.elementAt(3 + i).toInt().toChar()
        }
        val raw = try {
            hexChars.concatToString().hexToByteArray()
        } catch (_: IllegalArgumentException) {
            pending.removeFirst()
            return null
        }
        // Consume header
        repeat(3 + 14) { pending.removeFirst() }
        // Trailing CR / 0x8a / LF / XON
        while (pending.isNotEmpty()) {
            val c = pending.first().toInt() and 0xFF
            if (c == 0x0D || c == 0x8A || c == 0x0A || c == 0x11 || c == 0x8D) {
                pending.removeFirst()
            } else {
                break
            }
        }
        val type = raw[0].toInt() and 0xFF
        val data4 = raw.copyOfRange(1, 5)
        return onHeader(type, data4, reply)
    }

    private fun parseBinaryHeader(reply: ArrayList<Byte>, crc32: Boolean): TransferEvent? {
        useCrc32 = crc32
        val crcLen = if (crc32) 4 else 2
        val needed = 1 + 4 + crcLen
        val decoded = unescape(needed) ?: return null
        // unescape consumed from after ZPAD ZDLE KIND — drop those 3 first
        repeat(3) { pending.removeFirst() }
        // And drop the escaped bytes that unescape already walked… unescape
        // reads without removing; remove them now via a dedicated consume.
        consumeEscaped(needed)
        val type = decoded[0].toInt() and 0xFF
        val data4 = decoded.copyOfRange(1, 5)
        if (type == ZFILE || type == ZDATA || type == ZSINIT) {
            expectSubpacket = true
            pendingFrameType = type
        }
        return onHeader(type, data4, reply)
    }

    private fun readSubpacket(reply: ArrayList<Byte>): TransferEvent? {
        val data = ArrayList<Byte>()
        var i = 0
        val snapshot = pending.toList()
        while (i < snapshot.size) {
            val b = snapshot[i].toInt() and 0xFF
            i++
            if (b == 0x11 || b == 0x13 || b == 0x10) continue
            if (b != ZDLE) {
                data.add(b.toByte())
                continue
            }
            if (i >= snapshot.size) return null
            val c = snapshot[i].toInt() and 0xFF
            i++
            when {
                c == ZDLEE -> data.add(ZDLE.toByte())
                c == ZRUB0 -> data.add(0x7F)
                c == ZRUB1 -> data.add(0xFF.toByte())
                c == ZCRCE || c == ZCRCG || c == ZCRCQ || c == ZCRCW -> {
                    val crcLen = if (useCrc32) 4 else 2
                    // Need crcLen escaped bytes after position i; endIndex is
                    // absolute into [snapshot], so drop that many pending bytes.
                    val crcDecoded = unescapeFrom(snapshot, i, crcLen) ?: return null
                    val endIndex = crcDecoded.second
                    repeat(endIndex) { if (pending.isNotEmpty()) pending.removeFirst() }
                    expectSubpacket = false
                    return onSubpacket(data.toByteArray(), c, reply)
                }
                (c and 0x60) == 0x40 -> data.add((c xor 0x40).toByte())
                else -> data.add(c.toByte())
            }
        }
        return null
    }

    private fun onHeader(type: Int, @Suppress("UNUSED_PARAMETER") data4: ByteArray, reply: ArrayList<Byte>): TransferEvent? {
        when (type) {
            ZRQINIT -> {
                appendReply(reply, hexHeader(ZRINIT, byteArrayOf(0, 0, 0, ZRINIT_FLAGS.toByte())))
            }
            ZSINIT -> {
                expectSubpacket = true
                pendingFrameType = ZSINIT
            }
            ZFILE -> {
                expectSubpacket = true
                pendingFrameType = ZFILE
            }
            ZDATA -> {
                expectSubpacket = true
                pendingFrameType = ZDATA
            }
            ZEOF -> {
                try {
                    fileOut?.flush()
                    fileOut?.close()
                    fileOut = null
                    sink.commit()
                } catch (t: Throwable) {
                    cleanup(failed = true)
                    return TransferEvent.Failed(t.message ?: "commit failed")
                }
                finishedName = fileName
                appendReply(reply, hexHeader(ZRINIT, byteArrayOf(0, 0, 0, ZRINIT_FLAGS.toByte())))
                state = State.AfterEof
            }
            ZFIN -> {
                appendReply(reply, hexHeader(ZFIN) + "OO".toByteArray(Charsets.US_ASCII))
                val name = finishedName
                cleanup(failed = false)
                // Prefer Done already latched at ZEOF; if somehow missed, emit now.
                if (name != null && finishedName == null) {
                    // already consumed into event path via finishedName at ZEOF
                }
                state = State.Idle
            }
            else -> Unit
        }
        return null
    }

    private fun onSubpacket(data: ByteArray, frameEnd: Int, reply: ArrayList<Byte>): TransferEvent? {
        when (pendingFrameType) {
            ZSINIT -> {
                appendReply(reply, hexHeader(ZACK))
            }
            ZFILE -> {
                val rawName = data.split(0.toByte()).firstOrNull()
                    ?.toString(Charsets.UTF_8)
                    ?: "download.bin"
                val name = FileNameSanitizer.sanitize(rawName)
                fileName = name
                bytesReceived = 0
                try {
                    fileOut = sink.begin(name)
                } catch (t: Throwable) {
                    cleanup(failed = true)
                    state = State.Idle
                    return TransferEvent.Failed(t.message ?: "cannot open sink")
                }
                appendReply(reply, hexHeader(ZRPOS, leInt(0)))
                state = State.Receiving
            }
            ZDATA -> {
                val out = fileOut
                if (out != null) {
                    try {
                        out.write(data)
                        bytesReceived += data.size
                    } catch (t: Throwable) {
                        cleanup(failed = true)
                        state = State.Idle
                        return TransferEvent.Failed(t.message ?: "write failed")
                    }
                }
                if (frameEnd == ZCRCW) {
                    appendReply(reply, hexHeader(ZACK, leInt(bytesReceived)))
                }
            }
        }
        pendingFrameType = -1
        return null
    }

    private fun cleanup(failed: Boolean) {
        try {
            fileOut?.close()
        } catch (_: Throwable) {
        }
        fileOut = null
        if (failed) {
            try {
                sink.abort()
            } catch (_: Throwable) {
            }
            finishedName = null
        }
        expectSubpacket = false
        pendingFrameType = -1
        pending.clear()
        bytesReceived = 0
        fileName = null
        state = State.Idle
    }

    private fun indexOfZrqinit(): Int {
        // Search for **\x18B00 or *\x18B00
        val arr = pending.toByteArray()
        for (i in 0 until arr.size - 5) {
            if (arr[i] == ZPAD.toByte() && arr[i + 1] == ZPAD.toByte() &&
                arr[i + 2] == ZDLE.toByte() && arr[i + 3] == ZHEX.toByte() &&
                arr[i + 4] == '0'.code.toByte() && arr[i + 5] == '0'.code.toByte()
            ) {
                return i
            }
            if (arr[i] == ZPAD.toByte() && arr[i + 1] == ZDLE.toByte() &&
                arr[i + 2] == ZHEX.toByte() &&
                i + 4 < arr.size &&
                arr[i + 3] == '0'.code.toByte() && arr[i + 4] == '0'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun consumeHexHeaderTrailer() {
        // Drop ZPAD… through 14 hex digits and trailing control bytes.
        // Find start already at pending[0].
        if (pending.size >= 2 && pending.first() == ZPAD.toByte() &&
            pending.elementAt(1) == ZPAD.toByte()
        ) {
            // **\x18B + 14 hex
            if (pending.size < 2 + 1 + 1 + 14) return
            repeat(2 + 1 + 1 + 14) { if (pending.isNotEmpty()) pending.removeFirst() }
        } else if (pending.size >= 1 + 1 + 1 + 14) {
            repeat(1 + 1 + 1 + 14) { if (pending.isNotEmpty()) pending.removeFirst() }
        }
        while (pending.isNotEmpty()) {
            val c = pending.first().toInt() and 0xFF
            if (c == 0x0D || c == 0x8A || c == 0x0A || c == 0x11 || c == 0x8D) {
                pending.removeFirst()
            } else {
                break
            }
        }
    }

    /**
     * Peek-decode [count] raw bytes from the stream starting after the
     * 3-byte frame prefix (ZPAD ZDLE KIND), without removing.
     */
    private fun unescape(count: Int): ByteArray? {
        val snapshot = pending.toList()
        return unescapeFrom(snapshot, 3, count)?.first
    }

    private fun unescapeFrom(
        snapshot: List<Byte>,
        start: Int,
        count: Int,
    ): Pair<ByteArray, Int>? {
        val out = ByteArray(count)
        var i = start
        var n = 0
        while (n < count) {
            if (i >= snapshot.size) return null
            val b = snapshot[i].toInt() and 0xFF
            i++
            if (b != ZDLE) {
                out[n++] = b.toByte()
                continue
            }
            if (i >= snapshot.size) return null
            val c = snapshot[i].toInt() and 0xFF
            i++
            out[n++] = when {
                c == ZDLEE -> ZDLE.toByte()
                c == ZRUB0 -> 0x7F
                c == ZRUB1 -> 0xFF.toByte()
                (c and 0x60) == 0x40 -> (c xor 0x40).toByte()
                else -> c.toByte()
            }
        }
        return out to i
    }

    private fun consumeEscaped(count: Int) {
        var n = 0
        while (n < count && pending.isNotEmpty()) {
            val b = pending.removeFirst().toInt() and 0xFF
            if (b != ZDLE) {
                n++
                continue
            }
            if (pending.isEmpty()) return
            pending.removeFirst()
            n++
        }
    }

    private fun appendReply(reply: ArrayList<Byte>, bytes: ByteArray) {
        for (b in bytes) reply.add(b)
    }

    private fun ArrayList<Byte>.toByteArray(): ByteArray {
        val out = ByteArray(size)
        for (i in indices) out[i] = this[i]
        return out
    }

    private fun ArrayDeque<Byte>.toByteArray(): ByteArray {
        val out = ByteArray(size)
        var i = 0
        for (b in this) out[i++] = b
        return out
    }

    private fun ArrayDeque<Byte>.elementAt(index: Int): Byte {
        var i = 0
        for (b in this) {
            if (i == index) return b
            i++
        }
        throw IndexOutOfBoundsException(index)
    }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0)
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            out[i] = ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }

    private fun ByteArray.split(sep: Byte): List<ByteArray> {
        val parts = ArrayList<ByteArray>()
        var start = 0
        for (i in indices) {
            if (this[i] == sep) {
                parts.add(copyOfRange(start, i))
                start = i + 1
            }
        }
        parts.add(copyOfRange(start, size))
        return parts
    }

    private enum class State { Idle, Active, Receiving, AfterEof }

    companion object {
        private const val ZPAD = 0x2A
        private const val ZDLE = 0x18
        private const val ZDLEE = ZDLE xor 0x40
        private const val ZBIN = 0x41
        private const val ZHEX = 0x42
        private const val ZBIN32 = 0x43
        private const val ZRUB0 = 0x6C
        private const val ZRUB1 = 0x6D

        private const val ZRQINIT = 0
        private const val ZRINIT = 1
        private const val ZSINIT = 2
        private const val ZACK = 3
        private const val ZFILE = 4
        private const val ZFIN = 8
        private const val ZRPOS = 9
        private const val ZDATA = 10
        private const val ZEOF = 11

        private const val ZCRCE = 0x68
        private const val ZCRCG = 0x69
        private const val ZCRCQ = 0x6A
        private const val ZCRCW = 0x6B

        private const val CANFDX = 0x01
        private const val CANOVIO = 0x02
        private const val CANFC32 = 0x20
        private const val ESCCTL = 0x40

        /** Match stock `rz -e`: FDX + OVIO + FC32 + ESCCTL. */
        private const val ZRINIT_FLAGS = CANFDX or CANOVIO or CANFC32 or ESCCTL

        /** Consecutive CAN (`0x18`) bytes that mean "sender aborted". */
        private const val CAN_CANCEL_THRESHOLD = 5

        internal fun hexHeader(type: Int, data4: ByteArray = ByteArray(4)): ByteArray {
            require(data4.size == 4)
            val payload = ByteArray(5)
            payload[0] = type.toByte()
            System.arraycopy(data4, 0, payload, 1, 4)
            val crc = ZmodemCrc16.of(payload)
            val hex = buildString(14) {
                append(type.toString(16).padStart(2, '0'))
                for (b in data4) append((b.toInt() and 0xFF).toString(16).padStart(2, '0'))
                append(crc.toString(16).padStart(4, '0'))
            }
            val out = ArrayList<Byte>(24)
            out.add(ZPAD.toByte())
            out.add(ZPAD.toByte())
            out.add(ZDLE.toByte())
            out.add(ZHEX.toByte())
            for (ch in hex) out.add(ch.code.toByte())
            out.add(0x0D)
            out.add(0x8A.toByte())
            if (type != ZFIN && type != ZACK) {
                out.add(0x11)
            }
            return ByteArray(out.size) { out[it] }
        }

        private fun leInt(value: Int): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte(),
        )
    }
}
