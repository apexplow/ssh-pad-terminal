package com.taosun.hanterm.ssh

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
    /** Default SSH port — matches the SharedPreferences default in [com.taosun.hanterm.data.prefs.AppPreferences]. */
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

    /**
     * SSH-level heartbeat interval (seconds). After authenticating,
     * [SshClient.connect] sets `client.connection.keepAlive.keepAliveInterval`
     * to this value. With [KeepAliveProvider.HEARTBEAT] this emits one-way
     * `SSH_MSG_IGNORE` packets.
     *
     * Why 10 s: keeps Tailscale / mobile NAT mappings warm and beats typical
     * sshd `ClientAliveInterval` values (15–30 s) so the server sees protocol
     * traffic before it RSTs us.
     *
     * Dead-peer detection is NOT done by counting unanswered SSH probes —
     * `KeepAliveProvider.KEEP_ALIVE` was tried and **self-killed healthy
     * Tailscale sessions after ~30 s** when replies failed to land
     * (BG-KA-04). Detection is owned by TCP keepalive (25 s window) and
     * [SO_TIMEOUT_MS] instead. [FGS_SSH_KEEPALIVE_NUDGE_SECONDS] covers
     * Doze pausing the Heartbeater thread.
     */
    const val SSH_KEEPALIVE_INTERVAL_SECONDS: Int = 10

    /**
     * Retained for call-site / test compatibility. No longer applied to
     * sshj — we use `Heartbeater`, which has no max-alive-count. See
     * [SSH_KEEPALIVE_INTERVAL_SECONDS] for why `KEEP_ALIVE` was abandoned.
     */
    const val SSH_KEEPALIVE_MAX_ALIVE_COUNT: Int = 3

    /**
     * How often [com.taosun.hanterm.ssh.SshKeepAliveService] writes an
     * `SSH_MSG_IGNORE` while the session is live.
     *
     * sshj's Heartbeater is a plain [Thread] that Android Doze can pause
     * even while a foreground service is running. The FGS-driven nudge
     * runs on a [android.os.HandlerThread] owned by the perceptible
     * foreground service so SSH TX still happens when the user backgrounds
     * the app.
     *
     * Kept at 3 s (tighter than half of [SSH_KEEPALIVE_INTERVAL_SECONDS]) so
     * a single deferred tick cannot open a ≥15 s TX gap — the BG-KA-05
     * device log showed Tailscale / ClientAlive RST after such a gap.
     */
    const val FGS_SSH_KEEPALIVE_NUDGE_SECONDS: Int = 3

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
