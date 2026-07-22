package com.taosun.hanterm.terminal.trzsz

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * trzsz wire codec: zlib deflate/inflate + Base64, matching trzsz.js
 * `encodeBuffer` / `decodeBuffer` (Pako + base64-js).
 */
internal object TrzszCodec {
    fun encode(data: ByteArray): String {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size.coerceAtLeast(64))
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        deflater.end()
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    fun encodeUtf8(text: String): String = encode(text.toByteArray(Charsets.UTF_8))

    fun decode(payload: String): ByteArray {
        val raw = Base64.getDecoder().decode(payload)
        val inflater = Inflater(false)
        inflater.setInput(raw)
        val out = ByteArrayOutputStream(raw.size.coerceAtLeast(64))
        val buf = ByteArray(1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && inflater.needsInput()) break
            if (n > 0) out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    fun decodeUtf8(payload: String): String =
        decode(payload).toString(Charsets.UTF_8)
}
