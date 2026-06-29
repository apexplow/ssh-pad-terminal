package com.example.sshterminal.ssh

/**
 * Successful outcome of [SshClient.connect].
 *
 * @param session Live SSH session ready for IO.
 * @param enrollmentNotice Non-null when this connect newly enrolled a host key
 *   (Module 11 / KHV-UX-01). Consumed once by the UI; not persisted.
 */
data class SshConnectResult(
    val session: SshSession,
    val enrollmentNotice: String? = null,
)
