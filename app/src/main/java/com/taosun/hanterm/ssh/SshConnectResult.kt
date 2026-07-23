package com.taosun.hanterm.ssh

/**
 * Successful outcome of [SshClient.connect].
 *
 * @param session Live SSH session ready for IO.
 * @param enrollmentNotice Non-null when this connect newly enrolled a host key
 *   (Module 11 / KHV-UX-01). Consumed once by the UI; not persisted.
 * @param keepAliveNudge The FGS keepalive probe capability for this session
 *   (Issue #17). `ConnectionRuntime.handleConnectSuccess` binds it into
 *   [KeepAliveNudgeRegistry] and starts the FGS; every teardown path clears
 *   the binding. Defaulted to `null` so existing call sites — production
 *   and 20+ tests — compile unchanged.
 */
data class SshConnectResult(
    val session: SshSession,
    val enrollmentNotice: String? = null,
    val keepAliveNudge: KeepAliveNudge? = null,
)
