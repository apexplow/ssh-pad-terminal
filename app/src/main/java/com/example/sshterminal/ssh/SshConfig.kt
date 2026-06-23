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

    /** Read timeout on the IO loop, in millis. Triggers a clean EOF break. */
    val READ_TIMEOUT_MS: Long = TimeUnit.SECONDS.toMillis(0)
}
