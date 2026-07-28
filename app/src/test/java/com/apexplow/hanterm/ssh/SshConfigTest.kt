package com.apexplow.hanterm.ssh

import com.apexplow.hanterm.data.prefs.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [SshConfig] defaults to the values documented in
 * `implementation_plan.md` §"模块划分与边界" and §"SSHJ 在 Android 上的正确配置".
 *
 * The test fails loudly if anyone bumps a default without updating the spec
 * — the UI layer (status line, error messages) renders these values directly,
 * and a wrong `DEFAULT_PTY_COLS` would surface as a visibly squashed terminal
 * on first connect.
 */
class SshConfigTest {

    @Test
    fun test_defaultPort_matchesSharedPreferencesDefault() {
        // The ConfigScreen form pre-fills from AppPreferences.DEFAULT_PORT.
        // SshConfig.DEFAULT_PORT must match so a user who types nothing into
        // the port field lands on the same value the SSH layer expects.
        assertEquals(AppPreferences.DEFAULT_PORT, SshConfig.DEFAULT_PORT)
        assertEquals(22, SshConfig.DEFAULT_PORT)
    }

    @Test
    fun test_termType_isXterm256color() {
        // 256-color terminals are the floor for the v1.0 feature set
        // (htop, vim, lazygit). TrueColor is a Sprint 2.5 follow-up.
        assertEquals("xterm-256color", SshConfig.DEFAULT_TERM_TYPE)
    }

    @Test
    fun test_initialPtyDimensions_areReasonable() {
        // 80x24 is the historical terminal size and matches sshj's own
        // allocateDefaultPTY() fallback. The first SIGWINCH from the
        // TerminalView's layout listener will override these.
        assertEquals(80, SshConfig.DEFAULT_PTY_COLS)
        assertEquals(24, SshConfig.DEFAULT_PTY_ROWS)
        assertTrue(
            "initial pty dimensions must be positive (otherwise SSH shell hangs at open)",
            SshConfig.DEFAULT_PTY_COLS > 0 && SshConfig.DEFAULT_PTY_ROWS > 0,
        )
    }

    @Test
    fun test_connectTimeout_isLongEnoughForSlowNetworks() {
        // Too short: users on flakey hotel Wi-Fi see "Connection refused" before
        // the TCP SYN even leaves the device. Too long: a typo'd port feels
        // frozen. 15s is the conventional dial-up-to-mobile sweet spot.
        val timeoutMs = SshConfig.CONNECT_TIMEOUT_MS
        assertTrue("connect timeout must be > 5s", timeoutMs >= 5_000)
        assertTrue("connect timeout must be <= 60s", timeoutMs <= 60_000)
    }

    @Test
    fun test_soTimeout_isAtLeastOneSecond() {
        // sshj setTimeout → Socket.setSoTimeout (milliseconds). Values below 1000
        // cause banner-read failures on any non-trivial RTT.
        assertTrue(
            "socket read timeout must be >= 1s, got ${SshConfig.SO_TIMEOUT_MS}ms",
            SshConfig.SO_TIMEOUT_MS >= 1_000,
        )
    }

    @Test
    fun test_keepAliveInterval_catchesTypicalMobileNatTimeouts() {
        // Mobile NAT idle timeouts are typically 60-120s; the keepalive probe
        // must fire well inside that window. Tightened from 30 → 10 s in the
        // "切到后台就断开" Sprint 4 follow-up — 30 s let Tailscale NAT timeouts
        // and aggressive sshd ClientAliveInterval values RST the socket
        // before the first probe even landed.
        assertEquals(10, SshConfig.SSH_KEEPALIVE_INTERVAL_SECONDS)
        assertTrue(
            "keepalive interval must be short enough to beat a 60s NAT timeout",
            SshConfig.SSH_KEEPALIVE_INTERVAL_SECONDS < 60,
        )
    }

    @Test
    fun test_keepAliveMaxAliveCount_isRetainedButUnusedByHeartbeater() {
        // Constant kept for compatibility; Heartbeater ignores it. Pin the
        // value so a drive-by bump doesn't silently change docs/tests that
        // still reference the old KEEP_ALIVE ride-through math.
        assertEquals(3, SshConfig.SSH_KEEPALIVE_MAX_ALIVE_COUNT)
        val rideThroughSeconds =
            SshConfig.SSH_KEEPALIVE_INTERVAL_SECONDS * SshConfig.SSH_KEEPALIVE_MAX_ALIVE_COUNT
        assertTrue(
            "legacy ride-through math ($rideThroughSeconds s) must stay >= 30s",
            rideThroughSeconds >= 30,
        )
        assertTrue(
            "legacy ride-through math ($rideThroughSeconds s) must stay < 300s",
            rideThroughSeconds < 300,
        )
    }

    @Test
    fun test_fgsKeepaliveNudge_beatsAggressiveServerClientAlive() {
        assertEquals(3, SshConfig.FGS_SSH_KEEPALIVE_NUDGE_SECONDS)
        assertTrue(
            "FGS nudge must fire more often than sshj's own keepalive interval",
            SshConfig.FGS_SSH_KEEPALIVE_NUDGE_SECONDS < SshConfig.SSH_KEEPALIVE_INTERVAL_SECONDS,
        )
    }
}
