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
     * SSH-level keepalive interval (seconds). After authenticating,
     * [SshClient.connect] sets `client.connection.keepAlive.keepAliveInterval`
     * to this value.
     *
     * Why this matters: a long-lived shell sitting on a phone's network can
     * hit a NAT timeout, a captive-portal redirect, or a silent server-side
     * close — and the OS won't surface it as a TCP RST for hours. 30 s is
     * short enough to catch mobile NAT timeouts (typically 60-120 s) and
     * long enough not to spam the server.
     *
     * IMPORTANT: sshj's `DefaultConfig` defaults to
     * `KeepAliveProvider.HEARTBEAT`, whose `Heartbeater` only *writes* an
     * `SSH_MSG_IGNORE` packet — it never waits for a reply, so it can keep a
     * NAT mapping alive but can NEVER detect a dead/unresponsive peer on its
     * own. [SshClient.buildSshjConfig] explicitly opts into
     * `KeepAliveProvider.KEEP_ALIVE` (`KeepAliveRunner`), which sends
     * `keepalive@openssh.com` global requests and expects replies — that's
     * what actually gives us active dead-peer detection, bounded by
     * [SSH_KEEPALIVE_MAX_ALIVE_COUNT] below. [SO_TIMEOUT_MS] remains a
     * second, independent line of defense for anything the keepalive
     * mechanism itself misses (e.g. a hang before the keepalive thread ever
     * starts).
     */
    const val SSH_KEEPALIVE_INTERVAL_SECONDS: Int = 30

    /**
     * Number of consecutive unanswered SSH-level keepalive probes
     * (`KeepAliveProvider.KEEP_ALIVE` / `KeepAliveRunner`) tolerated before
     * sshj actively kills the connection with `ConnectionException(CONNECTION_LOST)`.
     *
     * Ride-through window = [SSH_KEEPALIVE_INTERVAL_SECONDS] * this value =
     * 30 s * 3 = 90 s — long enough to survive a brief cellular/Wi‑Fi
     * handover blip without tearing down the session, short enough that a
     * genuinely dead peer is detected well before a human loses patience.
     * sshj's own default is 5 (150 s); we tighten it slightly for faster
     * feedback on a tablet where the user is actively watching the screen.
     */
    const val SSH_KEEPALIVE_MAX_ALIVE_COUNT: Int = 3

    /**
     * Socket-level read timeout (millis). Passed to sshj's [net.schmizz.sshj.SSHClient.setTimeout],
     * which forwards it unchanged to [java.net.Socket.setSoTimeout] — also millis.
     *
     * Backs up [SSH_KEEPALIVE_INTERVAL_SECONDS]: if the underlying socket ever does go
     * silent for longer than this we get a `SocketTimeoutException` instead of a hang,
     * which [SshSession.readInto] converts into a clean connection-lost result.
     */
    val SO_TIMEOUT_MS: Int = TimeUnit.SECONDS.toMillis(60).toInt()
}
