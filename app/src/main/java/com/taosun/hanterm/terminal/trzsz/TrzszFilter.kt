package com.taosun.hanterm.terminal.trzsz

import com.taosun.hanterm.terminal.zmodem.FileNameSanitizer
import com.taosun.hanterm.terminal.zmodem.FilterResult
import com.taosun.hanterm.terminal.zmodem.TransferEvent
import com.taosun.hanterm.terminal.zmodem.TransferLimits
import com.taosun.hanterm.terminal.zmodem.TransferSink
import java.io.OutputStream
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * Deep module: auto-detect remote `tsz` (trzsz mode `S`) on the inbound PTY
 * byte stream, suppress transfer traffic from the emulator, reply with
 * ACT/SUCC/EXIT, and stream file bytes into [TransferSink].
 *
 * Works inside and outside tmux (unlike ZMODEM). v1 receive-only; `trz`
 * upload (modes R/D) is left as pass-through. Directory transfers are
 * rejected cleanly.
 *
 * Protocol surface matches trzsz.js client download path: zlib+Base64 lines
 * `#TYPE:payload\n`, optional binary DATA with escape table, MD5 integrity,
 * and `tmux_output_junk` line stripping.
 */
class TrzszFilter(
    private val sink: TransferSink,
) {
    private var phase: Phase = Phase.Idle
    private val pending = ArrayDeque<Byte>()
    private var protocolNewline: String = "\n"
    private var binary: Boolean = false
    private var tmuxOutputJunk: Boolean = false
    private var escapeCodes: List<IntArray> = emptyList()

    private var filesRemaining: Int = 0
    private var fileOut: OutputStream? = null
    private var fileName: String? = null
    private var fileSize: Long = 0
    private var bytesReceived: Long = 0
    private var md5: MessageDigest? = null
    private var binaryDataRemaining: Int = -1
    private var finishedName: String? = null
    private var lastUniqueId: String? = null

    val isCapturing: Boolean
        get() = phase != Phase.Idle

    fun onInbound(bytes: ByteArray): FilterResult {
        if (bytes.isEmpty()) return FilterResult.pass(FilterResult.EMPTY)
        return when (phase) {
            Phase.Idle -> feedIdle(bytes)
            else -> feedActive(bytes)
        }
    }

    fun abort(): TransferEvent? {
        if (phase == Phase.Idle && fileOut == null) return null
        val name = fileName
        cleanup(failed = true)
        return TransferEvent.Failed(
            if (name != null) "Transfer aborted: $name" else "Transfer aborted",
        )
    }

    private fun feedIdle(bytes: ByteArray): FilterResult {
        for (b in bytes) pending.addLast(b)
        // Issue #60: bound the idle-state holdback buffer (mirrors the
        // ZmodemFilter cap). Without this, a hostile peer can stream
        // bytes that never become the trzsz magic marker and grow
        // `pending` without limit. 64 KB is well above the longest
        // legitimate holdback (a prefix of the magic marker).
        if (pending.size > TransferLimits.MAX_PENDING_BYTES) {
            pending.clear()
            return FilterResult(
                display = FilterResult.EMPTY,
                event = TransferEvent.Failed(
                    "idle buffer overflow (${TransferLimits.MAX_PENDING_BYTES} bytes)",
                ),
            )
        }
        val text = pendingAscii()
        val match = MAGIC_REGEX.find(text) ?: run {
            val keep = idleHoldback(text)
            val emitCount = pending.size - keep
            if (emitCount <= 0) return FilterResult.pass(FilterResult.EMPTY)
            val display = ByteArray(emitCount)
            for (i in 0 until emitCount) display[i] = pending.removeFirst()
            return FilterResult.pass(display)
        }

        // Upload triggers (R/D): pass through — v1 is receive-only.
        if (match.groupValues[1] != "S") {
            val display = drainPending()
            return FilterResult.pass(display)
        }

        val uniqueId = match.groupValues.getOrNull(3).orEmpty()
        if (uniqueId.isNotEmpty() && uniqueId == lastUniqueId) {
            return FilterResult.pass(drainPending())
        }
        lastUniqueId = uniqueId.ifEmpty { null }

        val markerStart = match.range.first
        val display = ByteArray(markerStart)
        for (i in 0 until markerStart) display[i] = pending.removeFirst()
        repeat(match.value.length) { if (pending.isNotEmpty()) pending.removeFirst() }
        while (pending.isNotEmpty()) {
            val c = pending.first().toInt() and 0xFF
            if (c == 0x0D || c == 0x0A) pending.removeFirst() else break
        }

        phase = Phase.WaitCfg
        protocolNewline = "\n"
        binary = false
        tmuxOutputJunk = false
        escapeCodes = emptyList()
        val reply = StringBuilder()
        reply.append(line("ACT", TrzszCodec.encodeUtf8(ACTION_JSON)))
        val event = try {
            pump(reply)
        } catch (e: RemoteFail) {
            fail(reply, e.message ?: "remote fail")
        } catch (e: ProtocolError) {
            fail(reply, e.message ?: "protocol error")
        }
        return FilterResult(
            display = display,
            reply = replyBytes(reply),
            event = event,
        )
    }

    private fun feedActive(bytes: ByteArray): FilterResult {
        for (b in bytes) pending.addLast(b)
        // Issue #60: bound the active-state buffer (mirrors the
        // ZmodemFilter cap). Trzsz DATA frames are ≤ 10 KB
        // (`trzsz` default); 64 KB is ~6× headroom. A peer streaming
        // more without advancing the state machine is broken or
        // hostile — abort rather than OOM.
        if (pending.size > TransferLimits.MAX_PENDING_BYTES) {
            pending.clear()
            val name = fileName
            cleanup(failed = true)
            return FilterResult(
                display = FilterResult.EMPTY,
                event = TransferEvent.Failed(
                    if (name != null) "pending buffer overflow: $name" else "pending buffer overflow",
                ),
            )
        }
        val reply = StringBuilder()
        val event = try {
            pump(reply)
        } catch (e: RemoteFail) {
            fail(reply, e.message ?: "remote fail")
        } catch (e: ProtocolError) {
            fail(reply, e.message ?: "protocol error")
        }
        val doneName = finishedName
        if (doneName != null && phase == Phase.Idle) {
            finishedName = null
            return FilterResult(
                display = FilterResult.EMPTY,
                reply = replyBytes(reply),
                event = TransferEvent.Done(doneName),
            )
        }
        return FilterResult(
            display = FilterResult.EMPTY,
            reply = replyBytes(reply),
            event = event,
        )
    }

    private fun pump(reply: StringBuilder): TransferEvent? {
        var guard = 0
        while (guard++ < 10_000) {
            when (phase) {
                Phase.Idle -> return null
                Phase.WaitCfg -> {
                    val line = tryReadLine() ?: return null
                    val payload = requireType(line, "CFG")
                    val cfg = TrzszCodec.decodeUtf8(payload)
                    if (hasJsonTrue(cfg, "directory")) {
                        return fail(reply, "directory transfer not supported")
                    }
                    binary = hasJsonTrue(cfg, "binary")
                    tmuxOutputJunk = hasJsonTrue(cfg, "tmux_output_junk")
                    escapeCodes = if (binary) TrzszEscape.parseEscapeChars(cfg) else emptyList()
                    phase = Phase.WaitNum
                }
                Phase.WaitNum -> {
                    val line = tryReadLine() ?: return null
                    val payload = requireType(line, "NUM")
                    val num = payload.toIntOrNull()
                        ?: return fail(reply, "bad NUM: $payload")
                    filesRemaining = num
                    reply.append(line("SUCC", num.toString()))
                    if (num <= 0) {
                        finishedName = "(empty)"
                        reply.append(line("EXIT", TrzszCodec.encodeUtf8("Saved 0 file")))
                        cleanup(failed = false)
                        return null
                    }
                    phase = Phase.WaitName
                }
                Phase.WaitName -> {
                    val line = tryReadLine() ?: return null
                    val payload = requireType(line, "NAME")
                    val rawName = TrzszCodec.decodeUtf8(payload)
                    if (rawName.trimStart().startsWith("{")) {
                        return fail(reply, "directory transfer not supported")
                    }
                    val name = FileNameSanitizer.sanitize(rawName)
                    fileName = name
                    bytesReceived = 0
                    fileSize = 0
                    binaryDataRemaining = -1
                    md5 = MessageDigest.getInstance("MD5")
                    try {
                        fileOut = sink.begin(name)
                    } catch (t: Throwable) {
                        return fail(reply, t.message ?: "cannot open sink")
                    }
                    reply.append(line("SUCC", TrzszCodec.encodeUtf8(name)))
                    phase = Phase.WaitSize
                }
                Phase.WaitSize -> {
                    val line = tryReadLine() ?: return null
                    val payload = requireType(line, "SIZE")
                    val size = payload.toLongOrNull()
                        ?: return fail(reply, "bad SIZE: $payload")
                    fileSize = size
                    bytesReceived = 0
                    reply.append(line("SUCC", size.toString()))
                    phase = if (size == 0L) Phase.WaitMd5 else Phase.WaitData
                }
                Phase.WaitData -> {
                    when (val r = recvDataChunk(reply)) {
                        DataResult.NeedMore -> return null
                        DataResult.Ok -> {
                            if (bytesReceived >= fileSize) phase = Phase.WaitMd5
                        }
                        is DataResult.Failed -> return r.event
                    }
                }
                Phase.WaitMd5 -> {
                    val line = tryReadLine() ?: return null
                    val payload = requireType(line, "MD5")
                    val expect = TrzszCodec.decode(payload)
                    val actual = md5!!.digest()
                    if (!expect.contentEquals(actual)) {
                        return fail(reply, "Check MD5 failed")
                    }
                    reply.append(line("SUCC", TrzszCodec.encode(actual)))
                    try {
                        fileOut?.flush()
                        fileOut?.close()
                        fileOut = null
                        sink.commit()
                    } catch (t: Throwable) {
                        return fail(reply, t.message ?: "commit failed")
                    }
                    finishedName = fileName
                    fileName = null
                    md5 = null
                    filesRemaining--
                    if (filesRemaining > 0) {
                        phase = Phase.WaitName
                    } else {
                        val name = finishedName ?: "download.bin"
                        reply.append(
                            line(
                                "EXIT",
                                TrzszCodec.encodeUtf8("Saved 1 file/directory\r\n- $name"),
                            ),
                        )
                        // Keep finishedName for Done event; clear transfer state.
                        val kept = finishedName
                        cleanup(failed = false)
                        finishedName = kept
                    }
                }
            }
        }
        return null
    }

    private fun recvDataChunk(reply: StringBuilder): DataResult {
        if (!binary) {
            val line = tryReadLine() ?: return DataResult.NeedMore
            val payload = try {
                requireType(line, "DATA")
            } catch (e: RemoteFail) {
                return DataResult.Failed(fail(reply, e.message ?: "remote fail"))
            } catch (e: ProtocolError) {
                return DataResult.Failed(fail(reply, e.message ?: "expected DATA"))
            }
            val data = try {
                TrzszCodec.decode(payload)
            } catch (t: Throwable) {
                return DataResult.Failed(fail(reply, t.message ?: "DATA decode failed"))
            }
            return writeChunk(reply, data)
        }

        if (binaryDataRemaining < 0) {
            val line = tryReadLine() ?: return DataResult.NeedMore
            val payload = try {
                requireType(line, "DATA")
            } catch (e: RemoteFail) {
                return DataResult.Failed(fail(reply, e.message ?: "remote fail"))
            } catch (e: ProtocolError) {
                return DataResult.Failed(fail(reply, e.message ?: "expected DATA size"))
            }
            val size = payload.toIntOrNull()
                ?: return DataResult.Failed(fail(reply, "bad DATA size"))
            binaryDataRemaining = size
        }
        if (pending.size < binaryDataRemaining) return DataResult.NeedMore
        val raw = ByteArray(binaryDataRemaining)
        for (i in 0 until binaryDataRemaining) raw[i] = pending.removeFirst()
        binaryDataRemaining = -1
        val data = TrzszEscape.unescape(raw, escapeCodes)
        return writeChunk(reply, data)
    }

    private fun writeChunk(reply: StringBuilder, data: ByteArray): DataResult {
        try {
            fileOut?.write(data)
            md5?.update(data)
            bytesReceived += data.size
            // Issue #60: per-file size cap (mirrors ZmodemFilter ZDATA).
            // We check AFTER the write so a DATA frame that straddles
            // the boundary is allowed to commit; the NEXT frame fails
            // before the next chunk lands. Bounds total bytes written
            // to ≤ MAX_DOWNLOAD_BYTES + (one DATA frame), ~10 KB above
            // the cap — well inside the design margin.
            if (bytesReceived > TransferLimits.MAX_DOWNLOAD_BYTES) {
                val name = fileName
                cleanup(failed = true)
                return DataResult.Failed(
                    fail(
                        reply,
                        if (name != null) "file exceeds ${TransferLimits.MAX_DOWNLOAD_BYTES} bytes: $name"
                        else "file exceeds ${TransferLimits.MAX_DOWNLOAD_BYTES} bytes",
                    ),
                )
            }
            reply.append(line("SUCC", data.size.toString()))
        } catch (t: Throwable) {
            return DataResult.Failed(fail(reply, t.message ?: "write failed"))
        }
        return DataResult.Ok
    }

    private sealed class DataResult {
        data object NeedMore : DataResult()
        data object Ok : DataResult()
        data class Failed(val event: TransferEvent) : DataResult()
    }

    private fun line(type: String, payload: String): String =
        "#$type:$payload$protocolNewline"

    private fun requireType(rawLine: String, expectType: String): String {
        var cleaned = rawLine
        if (tmuxOutputJunk) {
            cleaned = stripTmuxStatusLine(cleaned)
            val idx = cleaned.lastIndexOf("#$expectType:")
            cleaned = when {
                idx >= 0 -> cleaned.substring(idx)
                else -> {
                    val hash = cleaned.lastIndexOf('#')
                    if (hash > 0) cleaned.substring(hash) else cleaned
                }
            }
        }
        val colon = cleaned.indexOf(':')
        if (colon < 2 || cleaned[0] != '#') {
            throw ProtocolError("expected #$expectType, got: ${cleaned.take(48)}")
        }
        val typ = cleaned.substring(1, colon)
        val body = cleaned.substring(colon + 1)
        if (typ == "fail" || typ == "FAIL" || typ == "EXIT") {
            val msg = try {
                TrzszCodec.decodeUtf8(body)
            } catch (_: Throwable) {
                body
            }
            throw RemoteFail(msg)
        }
        if (typ != expectType) {
            throw ProtocolError("expected #$expectType, got #$typ")
        }
        return body
    }

    private class RemoteFail(message: String) : Exception(message)
    private class ProtocolError(message: String) : Exception(message)

    private fun tryReadLine(): String? {
        var i = 0
        for (b in pending) {
            if ((b.toInt() and 0xFF) == 0x0A) {
                val chars = CharArray(i)
                for (j in 0 until i) {
                    chars[j] = (pending.removeFirst().toInt() and 0xFF).toChar()
                }
                pending.removeFirst() // LF
                var line = String(chars)
                if (line.endsWith("\r")) line = line.dropLast(1)
                return line
            }
            i++
        }
        return null
    }

    private fun fail(reply: StringBuilder, reason: String): TransferEvent {
        try {
            reply.append(line("fail", TrzszCodec.encodeUtf8(reason)))
        } catch (_: Throwable) {
        }
        cleanup(failed = true)
        return TransferEvent.Failed(reason)
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
        md5 = null
        fileName = null
        fileSize = 0
        bytesReceived = 0
        filesRemaining = 0
        binaryDataRemaining = -1
        pending.clear()
        phase = Phase.Idle
    }

    private fun drainPending(): ByteArray {
        val display = ByteArray(pending.size)
        for (i in display.indices) display[i] = pending.removeFirst()
        return display
    }

    private fun pendingAscii(): String {
        val sb = StringBuilder(pending.size)
        for (b in pending) sb.append((b.toInt() and 0xFF).toChar())
        return sb.toString()
    }

    private fun idleHoldback(text: String): Int {
        for (len in MAGIC_PREFIX.length downTo 1) {
            if (text.length >= len && text.endsWith(MAGIC_PREFIX.substring(0, len))) {
                return len
            }
        }
        return 0
    }

    private fun replyBytes(reply: StringBuilder): ByteArray? {
        if (reply.isEmpty()) return null
        return reply.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun hasJsonTrue(json: String, key: String): Boolean {
        val re = Regex(""""$key"\s*:\s*true""")
        return re.containsMatchIn(json)
    }

    private fun stripTmuxStatusLine(buf: String): String {
        var s = buf
        while (true) {
            val beginIdx = s.indexOf("\u001bP=")
            if (beginIdx < 0) return s
            var bufIdx = beginIdx + 3
            val midRel = s.substring(bufIdx).indexOf("\u001bP=")
            if (midRel < 0) return s.substring(0, beginIdx)
            bufIdx += midRel + 3
            val endRel = s.substring(bufIdx).indexOf("\u001b\\")
            if (endRel < 0) return s.substring(0, beginIdx)
            bufIdx += endRel + 2
            s = s.substring(0, beginIdx) + s.substring(bufIdx)
        }
    }

    private enum class Phase {
        Idle, WaitCfg, WaitNum, WaitName, WaitSize, WaitData, WaitMd5,
    }

    companion object {
        private const val MAGIC_PREFIX = "::TRZSZ:TRANSFER:"
        private val MAGIC_REGEX =
            Regex("""::TRZSZ:TRANSFER:([SRD]):(\d+\.\d+\.\d+)(:\d+)?""")

        private const val ACTION_JSON =
            """{"lang":"kt","confirm":true,"version":"1.1.5","support_dir":false}"""
    }
}
