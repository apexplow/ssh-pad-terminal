package com.taosun.hanterm.terminal

/** Pure parser for one side-band `tmux list-sessions` stdout payload. */
object TmuxSessionParser {

    /**
     * Malformed rows are dropped independently. Names are the final field and
     * the split is limited, so a literal `|` remains part of the name.
     */
    fun parse(output: String): List<TmuxSession> =
        output.lineSequence().mapNotNull(::parseLine).toList()

    private fun parseLine(raw: String): TmuxSession? {
        val parts = raw.trimEnd('\r').split("|", limit = FIELD_COUNT)
        if (parts.size != FIELD_COUNT) return null
        val id = sanitize(parts[0])
        if (!id.matches(SESSION_ID)) return null
        val windows = parts[1].trim().toIntOrNull() ?: return null
        val attached = when (parts[2].trim()) {
            "attached" -> true
            "detached" -> false
            else -> return null
        }
        val activity = sanitize(parts[3])
        val name = sanitize(parts[4])
        if (name.isEmpty()) return null
        return TmuxSession(
            name = name,
            windows = windows,
            attached = attached,
            lastActivity = activity,
            id = id,
        )
    }

    /** Remote names are display data: strip terminal controls before Compose. */
    private fun sanitize(value: String): String =
        value.filterNot { char ->
            char.code in 0x00..0x1F || char.code in 0x7F..0x9F
        }.trim()

    private val SESSION_ID = Regex("""\$\d+""")
    private const val FIELD_COUNT = 5
}
