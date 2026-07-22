package com.taosun.hanterm.ssh

import com.taosun.hanterm.terminal.PtyBridge
import com.taosun.hanterm.terminal.TerminalEndpoint

/**
 * The bundle of connection facts that the UI consumes. Rebuilt atomically on
 * every connect / teardown so the UI never sees a half-built snapshot.
 *
 * Replaces the three-arg shape (`endpoint: TerminalEndpoint + bridge:
 * PtyBridge? + session: SshSession?`) `HanTermApp.kt` previously passed into
 * `TerminalPane` at three separate call sites (lines 351–353, 707–709, 752–754).
 *
 * Note: `bridge` and `session` are nullable because the legacy "test without a
 * bridge" path in `TerminalPane` (line 176) needs to keep working until the
 * Sprint after this lands.
 *
 * See `docs/superpowers/specs/2026-07-22-connection-runtime-design.md` §
 * "Public API" for the rationale behind one bundle instead of three StateFlows.
 */
data class ConnectionView(
    val endpoint: TerminalEndpoint,
    val bridge: PtyBridge?,
    val session: SshSession?,
)