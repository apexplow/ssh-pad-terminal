package com.apexplow.hanterm.ssh

import com.apexplow.hanterm.terminal.MockEchoSession
import com.apexplow.hanterm.terminal.PtyBridge
import com.apexplow.hanterm.terminal.PtyBridgeEndpoint
import com.apexplow.hanterm.terminal.TerminalEndpoint

/**
 * Minimal capability surface the UI consumes from [ConnectionRuntime].
 *
 * Hides the resource topology (`SshSession` / `PtyBridge` / adapter job).
 * Callers only need four actions: write keystrokes, read remote bytes,
 * resize the PTY, and inspect why the session closed.
 *
 * Also implements [TerminalEndpoint] so IME / paste wiring can bind
 * without a second type.
 */
interface ConnectionView : TerminalEndpoint {
    /** Block until remote bytes arrive, or `null` on EOF. */
    fun read(): ByteArray?

    /** Forward cols/rows to the transport (SIGWINCH / equivalent). */
    fun resize(cols: Int, rows: Int)

    /** Structured close reason; stable after [SshSession.close] stamps it. */
    val lastCloseReason: SessionCloseReason

    /**
     * True while this view is backed by a live remote session.
     * Idle / disconnected views return false.
     */
    val isLive: Boolean
}

/**
 * Disconnected / error fallback. Writes are recorded like [MockEchoSession];
 * [read] returns `null` immediately so the TerminalPane IO loop does not spin.
 */
class IdleConnectionView(
    private val echo: MockEchoSession = MockEchoSession(),
) : ConnectionView {
    override fun write(bytes: ByteArray) = echo.write(bytes)

    override fun read(): ByteArray? = null

    override fun resize(cols: Int, rows: Int) = Unit

    override val lastCloseReason: SessionCloseReason
        get() = SessionCloseReason.UserInitiated

    override val isLive: Boolean = false
}

/**
 * Production view: bytes flow through [PtyBridge]; close reason is read from
 * the owning [SshSession] without exposing the session itself.
 */
internal class BridgedConnectionView(
    private val bridge: PtyBridge,
    private val endpoint: PtyBridgeEndpoint,
    private val session: SshSession,
) : ConnectionView {
    override fun write(bytes: ByteArray) = endpoint.write(bytes)

    override fun read(): ByteArray? = bridge.view.read()

    override fun resize(cols: Int, rows: Int) = bridge.resize(cols, rows)

    override val lastCloseReason: SessionCloseReason
        get() = session.lastCloseReason

    override val isLive: Boolean = true

    /** Stamp UserInitiated on the underlying session (Disconnect path). */
    fun closeUserInitiated() {
        session.close(userInitiated = true)
    }
}
