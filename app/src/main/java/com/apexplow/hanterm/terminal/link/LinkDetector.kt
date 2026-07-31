package com.apexplow.hanterm.terminal.link

/**
 * Pure URL detection over a string. No Android imports — testable without
 * Robolectric.
 *
 * Regex rationale (Sprint 4 eng-review finding OV #7):
 *   `((https?|ftp)://[a-zA-Z0-9][^\s<>"'\[\]]*)`
 *
 *   - `https?` covers `http` + `https`; `ftp` covers `ftp`. Per RFC 3986 the
 *     scheme is case-insensitive but real shell output is lower-case in
 *     practice. Upper-case variants deferred to v0.2.
 *   - First char after `://` must be alphanumeric. Rejects malformed URLs
 *     like `http:///foo`, `http://[`, `http:///path`. RFC 1123 allows
 *     single-letter hostnames in theory so we don't enforce minimum length.
 *   - The remaining chars exclude:
 *     - `\s` — whitespace (URL terminator; covers space, tab, CR, LF)
 *     - `<` and `>` (HTML/markdown anchors)
 *     - `"` and `'` (HTML attribute terminators)
 *     - `[` and `]` (markdown link syntax + ANSI SGR introducer)
 *     - 0x1B ESC byte (ANSI SGR / CSI sequences that wrap URLs in terminal output)
 *   - Trailing punctuation `,;.!)` is allowed by the regex, then stripped
 *     post-match by [stripTrailingPunctuation]. Common in shell output
 *     ("see https://github.com, for more"; "(https://github.com)").
 *
 * Re-validation: [LinkIntentLauncher] runs `LinkDetector.firstUrlIn(url)` on
 * the dialog's tap-Open path. If the round-trip fails, the URL is silently
 * treated as stale (no ACTION_VIEW fires).
 *
 * Out of scope (deferred to v0.2, see `docs/TODOS.md` T-MEDIUM-3):
 *   - URLs spanning row boundaries (current regex matches within a row only)
 *   - RFC 3986 escaped chars (`%20` etc. — current regex returns raw form)
 */
internal object LinkDetector {

    // Raw string literal; \s covers space/tab/newline, then explicit chars:
    // < > " ' [ ] (HTML/markdown terminators) and ESC byte 0x1B (ANSI SGR).
    // Edit carefully — a stray printable char in this class silently expands
    // the URL match.
    private val URL_REGEX = Regex("((https?|ftp)://[a-zA-Z0-9][^\\s<>\"'\\[\\]]*)")

    /**
     * Find the first URL in [text], or `null` if none.
     *
     * Anchored: returns the FIRST match, not all matches. The plan §Step 5
     * uses [firstUrlIn] for both per-row scan (find first URL on the row)
     * and dialog-tap re-validation (single URL round-trip).
     *
     * Trailing `,;.!)` are stripped from the matched URL. Common in shell
     * output ("see https://github.com, for more"; "(https://github.com)").
     * Strips in a loop because nested punctuation like `!))` can appear in
     * rustc error messages.
     */
    fun firstUrlIn(text: String): String? =
        URL_REGEX.find(text)?.value?.let(::stripTrailingPunctuation)

    private val TRAILING_PUNCTUATION = charArrayOf(',', ';', '.', '!', ')')

    private fun stripTrailingPunctuation(raw: String): String {
        var end = raw.length
        while (end > 0 && raw[end - 1] in TRAILING_PUNCTUATION) {
            end--
        }
        return if (end == raw.length) raw else raw.substring(0, end)
    }
}