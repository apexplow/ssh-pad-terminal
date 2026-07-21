package com.taosun.hanterm.terminal

/**
 * One row of `tmux list-sessions` output.
 *
 * The wire shape comes from the `-F` template passed by [TmuxSessionSource]:
 * `#{session_name}|#{session_windows}|#{?session_attached,attached,detached}|`
 * (4th field intentionally blank — `session_activity_string` is not a real
 * tmux format token).
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
)
