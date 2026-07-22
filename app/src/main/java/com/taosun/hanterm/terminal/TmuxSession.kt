package com.taosun.hanterm.terminal

/**
 * One row of `tmux list-sessions` output.
 *
 * The wire shape comes from the `-F` template passed by [TmuxSessionSource]:
 * `#{session_id}|#{session_windows}|#{?session_attached,attached,detached}||#{session_name}`
 * (4th field intentionally blank — `session_activity_string` is not a real
 * tmux format token). The name is last so a literal `|` in it is preserved
 * by the parser's limited split.
 *
 * Kept as a data class (not a sealed hierarchy) because every field is
 * user-visible and there is no behavioral variation between sessions — the
 * switch command is uniform. If Sprint 3+ ever surfaces per-session state
 * (e.g. "preview" / "kill" actions) this is the type that grows.
 *
 * @property lastActivity relative/activity display string from the probe's
 *   4th column. Currently empty on the wire; the UI renders it verbatim.
 */
data class TmuxSession(
    val name: String,
    val windows: Int,
    val attached: Boolean,
    val lastActivity: String,
    /** Stable tmux server id (`$0`, `$1`, …), safe across renames. */
    val id: String = name,
)
