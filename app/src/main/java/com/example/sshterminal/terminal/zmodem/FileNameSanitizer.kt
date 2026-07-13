package com.example.sshterminal.terminal.zmodem

/**
 * Strip directories and control characters from a ZFILE name so a remote
 * `sz ../../etc/passwd` cannot escape the Downloads folder.
 */
internal object FileNameSanitizer {
    fun sanitize(raw: String): String {
        val base = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
            .filter { ch ->
                ch.code in 0x20..0x7E && ch !in "<>:\"|?*"
            }
        val cleaned = base.trim('.', ' ')
        return cleaned.ifEmpty { "download.bin" }
    }
}
