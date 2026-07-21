package com.taosun.hanterm.terminal.trzsz

/**
 * Binary-mode escape table from trzsz.js `escape.ts`.
 * Each entry is `[original, escape0, escape1]`.
 */
internal object TrzszEscape {
    fun unescape(data: ByteArray, codes: List<IntArray>): ByteArray {
        if (codes.isEmpty()) return data
        val out = ByteArray(data.size)
        var o = 0
        var i = 0
        while (i < data.size) {
            var hit = -1
            if (i + 1 < data.size) {
                val a = data[i].toInt() and 0xFF
                val b = data[i + 1].toInt() and 0xFF
                for (j in codes.indices) {
                    if (codes[j][1] == a && codes[j][2] == b) {
                        hit = j
                        break
                    }
                }
            }
            if (hit < 0) {
                out[o++] = data[i++]
            } else {
                out[o++] = codes[hit][0].toByte()
                i += 2
            }
        }
        return out.copyOf(o)
    }

    /**
     * Parse `escape_chars` from CFG JSON: array of `[from, to]` where
     * `to` is a 2-char escape sequence. Returns empty on parse failure.
     */
    fun parseEscapeChars(cfgJson: String): List<IntArray> {
        val start = cfgJson.indexOf("\"escape_chars\"")
        if (start < 0) return emptyList()
        val arrStart = cfgJson.indexOf('[', start)
        if (arrStart < 0) return emptyList()
        // Walk top-level array of [ "\x..", "\x..\x.." ] pairs — CFG uses
        // JSON unicode escapes already expanded by the time we see the string
        // after decodeUtf8. We only need pairs of quoted strings.
        val result = ArrayList<IntArray>()
        var i = arrStart + 1
        while (i < cfgJson.length) {
            while (i < cfgJson.length && cfgJson[i] != '"' && cfgJson[i] != ']') i++
            if (i >= cfgJson.length || cfgJson[i] == ']') break
            val first = readJsonString(cfgJson, i) ?: break
            i = first.second
            while (i < cfgJson.length && cfgJson[i] != '"') {
                if (cfgJson[i] == ']') return result
                i++
            }
            val second = readJsonString(cfgJson, i) ?: break
            i = second.second
            val from = first.first
            val to = second.first
            if (from.isNotEmpty() && to.length >= 2) {
                result.add(
                    intArrayOf(
                        from[0].code and 0xFF,
                        to[0].code and 0xFF,
                        to[1].code and 0xFF,
                    ),
                )
            }
        }
        return result
    }

    /** @return pair of decoded string and index after the closing quote */
    private fun readJsonString(s: String, start: Int): Pair<String, Int>? {
        if (start >= s.length || s[start] != '"') return null
        val out = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    when (val n = s[i + 1]) {
                        'n' -> { out.append('\n'); i += 2 }
                        'r' -> { out.append('\r'); i += 2 }
                        't' -> { out.append('\t'); i += 2 }
                        '\\', '"' -> { out.append(n); i += 2 }
                        'u' -> {
                            if (i + 5 < s.length) {
                                val hex = s.substring(i + 2, i + 6)
                                out.append(hex.toInt(16).toChar())
                                i += 6
                            } else {
                                out.append(n)
                                i += 2
                            }
                        }
                        else -> { out.append(n); i += 2 }
                    }
                }
                c == '"' -> return out.toString() to (i + 1)
                else -> { out.append(c); i++ }
            }
        }
        return null
    }
}
