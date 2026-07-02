package com.example.sshterminal.ui

/**
 * Builds the wire bytes for a snippet tap.
 *
 * Sprint 3 / Module 16 / SNP-SEND-01..02:
 *   - [appendNewline] = false → send `command` as UTF-8 only (no extra
 *     bytes). Useful for partial-keystroke snippets (e.g. `cd /tmp/`).
 *   - [appendNewline] = true → append a single `\r` (0x0D, CR) after the
 *     UTF-8 bytes. We deliberately use CR, not LF, because the existing
 *     KEYCODE_ENTER routing ([com.example.sshterminal.terminal.KeyMapper]
 *     KM-KC-02) emits CR for Enter, and shells on most remote hosts treat
 *     CR and LF equivalently inside a line discipline (PTYs are configured
 *     with `ONLCR` per `SshClient.openShell`).
 *
 * Pure function — extracted so the byte-level semantics can be unit-tested
 * without a [com.example.sshterminal.terminal.TerminalEndpoint] fake or a
 * Compose UI. See SnippetPayloadTest.
 */
internal fun buildSnippetPayload(command: String, appendNewline: Boolean): ByteArray {
    val bytes = command.toByteArray(Charsets.UTF_8)
    return if (appendNewline) bytes + "\r".toByteArray(Charsets.UTF_8) else bytes
}