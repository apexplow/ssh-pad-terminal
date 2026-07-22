package com.taosun.hanterm.ssh.security

/**
 * UI-facing gate for TOFU trust decisions (Module 11 follow-up: interactive
 * host-key confirmation).
 *
 * [KnownHostsVerifier.verify] calls [confirm] — via `runBlocking`, from
 * sshj's transport reader thread, during the SSH handshake — whenever a
 * human decision is actually needed:
 *  - first-ever connection to a `(host, port)` (no stored fingerprint yet)
 *  - a fingerprint (or key-type) change against what's already stored
 *
 * Implementations are expected to suspend until the user answers a Yes/No
 * prompt. There is no timeout: an unanswered dialog blocks that one connect
 * attempt (and the sshj reader thread driving it) until the user answers,
 * which matches the existing single-shot Connect-button UX — nothing else
 * meaningful can happen mid-handshake anyway.
 *
 * When no [HostKeyPrompt] is wired (the default, and what every existing
 * test uses), [KnownHostsVerifier] falls back to its original TOFU-only
 * behavior: auto-accept + enroll on first use, auto-reject on mismatch.
 */
interface HostKeyPrompt {
    suspend fun confirm(request: HostKeyPromptRequest): Boolean
}

/** Everything the UI needs to render a single trust decision to the user. */
data class HostKeyPromptRequest(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprintBase64: String,
    /** Non-null when this is a change from a previously-trusted key (KHV-VF-04/05). */
    val previousFingerprint: HostFingerprint?,
)
