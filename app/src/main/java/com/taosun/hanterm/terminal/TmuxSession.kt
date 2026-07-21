package com.taosun.hanterm.terminal

/**
 * One row of `tmux list-sessions` output.
 *
 * The wire shape comes from the `-F` template passed by [TmuxSessionSource]:
 * `#{session_name}|#{session_windows}|#{?session_attached,attached,detached}|#{session_activity_string}`.
 *
 * Kept as a data class (not a sealed hierarchy) because every field is
 * user-visible and there is no behavioral variation between sessions — the
 * switch command is uniform. If Sprint 3+ ever surfaces per-session state
 * (e.g. "preview" / "kill" actions) this is the type that grows.
 *
 * @property lastActivity tmux's `session_activity_string` — the relative
 *   time the session last had client activity (e.g. "3 days ago"). We do
 *   not parse this into a [kotlin.time.Duration] because the format is
 *   tmux's, not machine-friendly, and the UI just renders it verbatim.
 */
data class TmuxSession(
    val name: String,
    val windows: Int,
    val attached: Boolean,
    val lastActivity: String,
)
