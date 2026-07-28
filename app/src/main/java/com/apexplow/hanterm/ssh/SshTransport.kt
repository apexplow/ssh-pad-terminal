package com.apexplow.hanterm.ssh

/**
 * Narrow, testable surface for the SSH transport layer.
 *
 * SSHJ's [com.hierynomus.sshj.channel.Channel] is a 700-line abstract class
 * with a pile of internal state machines; mocking it directly is painful and
 * the mocks drift every time SSHJ bumps its internal API. We instead expose
 * just the four operations [SshSession] actually performs and back them with
 * a real [ChannelTransport] in production.
 *
 * Production code constructs [SshSession] with a [ChannelTransport] (see
 * [SshClient]). Tests construct it with a hand-rolled fake — typically a
 * [LinkedBlockingQueue]-backed implementation that records `write` calls
 * and replays canned `readBytes` results.
 */
internal interface SshTransport {
    /**
     * Sends bytes from the local terminal (keystrokes, IME commits) to the
     * SSH channel's remote end. MUST flush — SSHJ's `Channel.outputStream`
     * is buffered.
     */
    fun write(bytes: ByteArray)

    /**
     * Blocks until at least one byte is available from the remote, then
     * returns up to 8 KiB of it. Returns `null` on EOF (channel closed
     * cleanly by remote) or on an irrecoverable read error.
     */
    fun readBytes(): ByteArray?

    /**
     * Notifies the remote of a terminal resize. Implementations translate
     * this into SSHJ's `setTerminalCols/Rows` calls — the server responds
     * by re-issuing a SIGWINCH to the foreground process group.
     */
    fun resizePty(cols: Int, rows: Int, widthPx: Int, heightPx: Int)

    /**
     * Idempotent close. Safe to call after a peer-initiated disconnect.
     */
    fun close()
}
