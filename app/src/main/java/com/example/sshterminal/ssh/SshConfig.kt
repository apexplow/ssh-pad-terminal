package com.example.sshterminal.ssh

import java.util.concurrent.TimeUnit

/**
 * Central SSH tuning constants for Sprint 2.
 *
 * Pulled out of [SshClient] so tests can pin defaults without spinning up an
 * `SSHClient` and so the UI layer (status line, error messages) can refer to
 * the same numbers as the transport.
 *
 * NOTE: `DEFAULT_PTY_COLS`/`DEFAULT_PTY_ROWS` are the initial allocation only.
 * Once the Termux `TerminalView` lays out, [SshSession.resizePty] is called
 * with the actual pixel dimensions and we use those instead.
 */
object SshConfig {
    /** Default SSH port — matches the SharedPreferences default in [com.example.sshterminal.data.prefs.AppPreferences]. */
    const val DEFAULT_PORT: Int = 22

    /** Default `TERM` string we tell the remote to use. */
    const val DEFAULT_TERM_TYPE: String = "xterm-256color"

    /**
     * Initial PTY size used before the [com.termux.view.TerminalView] has
     * produced a layout. The first SIGWINCH (issued from TerminalView's
     * layout listener) will replace this.
     */
    const val DEFAULT_PTY_COLS: Int = 80
    const val DEFAULT_PTY_ROWS: Int = 24

    /** TCP connect timeout. Short enough that a wrong port doesn't feel frozen. */
    val CONNECT_TIMEOUT_MS: Long = TimeUnit.SECONDS.toMillis(15)

    /** Auth banner / kex timeout — kept generous; slow servers exist. */
    val KEX_TIMEOUT_MS: Long = TimeUnit.SECONDS.toMillis(30)

    /**
     * SSH-level keepalive interval (seconds). After [SshClient.connect] we
     * call `client.connection.setKeepAlive(...)` with this value, which
     * makes sshj's underlying trilead `Connection` send SSH keepalive
     * requests at this interval.
     *
     * Why this matters: a long-lived shell sitting on a phone's network can
     * hit a NAT timeout, a captive-portal redirect, or a silent server-side
     * close — and the OS won't surface it as a TCP RST for hours. Without
     * keepalive our blocking read in [SshSession.readInto] would hang forever
     * (only the socket-level [SO_TIMEOUT_MS] bounds it) and the user sees a
     * frozen terminal. 30 s is short enough to catch mobile NAT timeouts
     * (typically 60-120 s) and long enough not to spam the server.
     */
    const val SSH_KEEPALIVE_INTERVAL_SECONDS: Int = 30

    /**
     * Socket-level read timeout (millis). Backs up [SSH_KEEPALIVE_INTERVAL_SECONDS]:
     * if the underlying socket ever does go silent for longer than this we
     * get a `SocketTimeoutException` instead of a hang, which
     * [SshSession.readInto] converts into a clean connection-lost result.
     */
    val SO_TIMEOUT_MS: Int = TimeUnit.SECONDS.toMillis(60).toInt()
}
