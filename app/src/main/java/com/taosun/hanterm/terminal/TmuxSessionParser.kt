package com.taosun.hanterm.terminal

/**
 * Pure parser for `tmux list-sessions` output captured from the emulator's
 * screen buffer.
 *
 * Why sentinel-bracketed parsing: the output is read from the terminal's
 * visible text, which is a moving target — every keystroke the user typed
 * before the probe ran is in the same transcript. Without bookends, "is
 * this `tmux list-sessions` output or my earlier `git status`?" is
 * unanswerable. Sentinels are the smallest reliable fix: a unique prefix
 * and a unique suffix that no normal shell prompt collides with.
 *
 * The sentinel strings are exposed as [BEGIN_SENTINEL] / [END_SENTINEL] so
 * [TmuxSessionSource] can emit them on the same `printf` calls as the
 * probe — they MUST stay in lockstep, otherwise parse() silently returns
 * the empty list.
 *
 * Output tolerates:
 *   - `tmux` not installed — the BEGIN/END printfs still fire, the body is
 *     the shell's `command not found` message, [parse] returns `emptyList()`.
 *   - tmux server not running — body is `no current client` / `no server
 *     running on /tmp/...`, [parse] returns `emptyList()`.
 *   - session names containing the literal `|` — the `-F` template uses
 *     `|` as a separator, so such names get rejected by [parseLine]
 *     (returns null → row dropped). This is acceptable: tmux session names
 *     permit `.` and `_` but `|` is reserved by `-F` formatting.
 */
object TmuxSessionParser {

    /**
     * Extracts [TmuxSession]s from [transcript].
     *
     * Returns the empty list when either sentinel is absent. NEVER throws
     * — a malformed row is dropped silently so a single corrupt entry
     * can't blank the rest of the drawer.
     *
     * Missing END sentinel → empty list. The polling source ([TmuxSessionSource])
     * emits BEGIN before the probe and END after it; if we never saw END the
     * probe is still mid-flight or the remote died, and trusting the rows
     * between BEGIN and end-of-transcript would surface half-rendered tmux
     * output as a fake session.
     */
    fun parse(transcript: String): List<TmuxSession> {
        val lines = transcript.lines()
        val beginIdx = lines.indexOfFirst { it.trim() == BEGIN_SENTINEL }
        if (beginIdx < 0) return emptyList()
        val endIdx = lines.withIndex().indexOfFirst { (i, raw) ->
            i > beginIdx && raw.trim() == END_SENTINEL
        }
        if (endIdx < 0) return emptyList()
        return lines.subList(beginIdx + 1, endIdx)
            .mapNotNull { parseLine(it) }
    }

    /**
     * Parses one pipe-separated row. Returns null on any per-field error
     * so a single bad line doesn't poison the whole drawer's body.
     */
    private fun parseLine(raw: String): TmuxSession? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split("|")
        if (parts.size != FIELD_COUNT) return null
        val name = parts[0].trim()
        if (name.isEmpty()) return null
        val windows = parts[1].trim().toIntOrNull() ?: return null
        val attached = when (parts[2].trim()) {
            "attached" -> true
            "detached" -> false
            else -> return null
        }
        val activity = parts[3].trim()
        return TmuxSession(
            name = name,
            windows = windows,
            attached = attached,
            lastActivity = activity,
        )
    }

    /** Must match [TmuxSessionSource.PROBE_BEGIN_SENTINEL]. */
    const val BEGIN_SENTINEL: String = "__HANTERM_TMUX_BEGIN__"

    /** Must match [TmuxSessionSource.PROBE_END_SENTINEL]. */
    const val END_SENTINEL: String = "__HANTERM_TMUX_END__"

    /** Expected `-F` template column count — pin changes here AND in the source. */
    private const val FIELD_COUNT = 4
}
