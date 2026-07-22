package com.taosun.hanterm.terminal

/** Shell lifecycle reported by HanTerm's Bash/Zsh integration. */
enum class ShellPhase {
    UNKNOWN,
    READY,
    BUSY,
}

/**
 * Versioned state received through the terminal-title callback.
 *
 * The title is an advisory UI signal, not a trust boundary: remote programs
 * can set terminal titles. HanTerm therefore never performs an automatic
 * action from it; bytes are sent only after an explicit user tap.
 */
data class ShellIntegrationState(
    val phase: ShellPhase,
    val inTmux: Boolean,
    val sessionId: String?,
    val tmuxPrefix: String?,
) {
    val isInstalled: Boolean get() = phase != ShellPhase.UNKNOWN
    val canInjectAtPrompt: Boolean get() = phase == ShellPhase.READY && !inTmux

    companion object {
        const val TITLE_PREFIX = "HANTERM;1;"

        val Unknown = ShellIntegrationState(
            phase = ShellPhase.UNKNOWN,
            inTmux = false,
            sessionId = null,
            tmuxPrefix = null,
        )

        /**
         * Parses `HANTERM;1;<READY|BUSY>;<0|1>;<session-id>;<prefix>`.
         * Returns null for unrelated titles so programs that set their own
         * title do not erase the last shell state.
         */
        fun parseTitle(title: String?): ShellIntegrationState? {
            if (title == null || !title.startsWith(TITLE_PREFIX)) return null
            val fields = title.split(';', limit = 6)
            if (fields.size != 6 || fields[0] != "HANTERM" || fields[1] != "1") {
                return null
            }
            val phase = when (fields[2]) {
                "READY" -> ShellPhase.READY
                "BUSY" -> ShellPhase.BUSY
                else -> return null
            }
            val inTmux = when (fields[3]) {
                "0" -> false
                "1" -> true
                else -> return null
            }
            val sessionId = fields[4].takeIf { it.matches(Regex("""\$\d+""")) }
            val prefix = fields[5].takeIf(::isSafeProtocolField)
            return ShellIntegrationState(phase, inTmux, sessionId, prefix)
        }

        private fun isSafeProtocolField(value: String): Boolean =
            value.isNotBlank() && value.all { it.code in 0x20..0x7E && it != ';' }
    }
}

/** Converts the supported subset of tmux key names to terminal bytes. */
object TmuxPrefixEncoder {
    fun encode(prefix: String?): ByteArray? {
        if (prefix == null) return null
        if (prefix.length == 3 && prefix.startsWith("C-")) {
            val key = prefix[2]
            val control = when (key.lowercaseChar()) {
                in 'a'..'z' -> key.lowercaseChar().code - 'a'.code + 1
                '\\' -> 0x1C
                ']' -> 0x1D
                else -> return null
            }
            return byteArrayOf(control.toByte())
        }
        if (prefix.length == 1 && prefix[0].code in 0x20..0x7E) {
            return byteArrayOf(prefix[0].code.toByte())
        }
        return null
    }
}
