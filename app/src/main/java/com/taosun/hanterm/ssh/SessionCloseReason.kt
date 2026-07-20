package com.taosun.hanterm.ssh

/**
 * Why an [SshSession] is no longer usable, as captured at the moment of close.
 *
 * Sprint 3 / Module 17: disambiguates "the user tapped Disconnect" from
 * "the remote end went away". The previous design conflated both because
 * `SshSession.close()` tore the socket down asynchronously, racing the
 * [TerminalPane] IO loop's `finally` block. By the time the catch ran in
 * `readInto`, the user-initiated signal had no synchronously-visible trace.
 *
 * The fix has two halves:
 *   1. [SshSession.close] writes `UserInitiated` to [SshSession.lastCloseReason]
 *      synchronously, *before* enqueueing the transport teardown. So even if
 *      `readInto` observes a SocketException on the very next `readBytes()`
 *      call, the field already reflects "user asked first".
 *   2. [TerminalPane]'s `finally` block skips `onSessionClosed` when
 *      [UserInitiated] is set, so the "Connection Closed" red overlay does
 *      not pop after a deliberate disconnect.
 *
 * Internal to `ssh/` + [TerminalPane]; the UI-facing
 * `onSessionClosed: (reason: String) -> Unit` callback in
 * [com.taosun.hanterm.ui.HanTermApp] still receives a plain `String`
 * so the caller-side diff stays minimal (spec SCR-UI-01 / NOT in scope).
 */
sealed class SessionCloseReason {

    /**
     * The caller explicitly invoked [SshSession.close] with `userInitiated = true`.
     *
     * Once set, the [SshSession] invariant (SCR-CL-02) guarantees this is
     * never overwritten — even if a concurrent `readInto` loop subsequently
     * sees a `SocketException` from the in-flight socket teardown, the
     * [RemoteEof] / [TransportError] / [SinkError] branches all check
     * `lastCloseReason !is UserInitiated` before writing.
     */
    data object UserInitiated : SessionCloseReason()

    /**
     * `readInto` exited via clean EOF: `transport.readBytes()` returned
     * `null`. The remote end closed the connection politely.
     */
    data object RemoteEof : SessionCloseReason()

    /**
     * `readInto` exited because the underlying socket died. The wrapped
     * message is the [SshErrorMessages.friendly] translation so it matches
     * what the UI would see if the same failure happened on the connect path.
     */
    data class TransportError(val message: String) : SessionCloseReason()

    /**
     * `readInto`'s `sink` callback threw (e.g. emulator backing was null).
     * The wrapped message is `e.message ?: e.javaClass.simpleName`. The
     * spec treats sink exceptions as a distinct category from transport
     * errors so a future debugging surface can tell them apart.
     */
    data class SinkError(val message: String) : SessionCloseReason()

    /**
     * [com.taosun.hanterm.ssh.SshBridgeAdapter]'s idle watchdog fired:
     * no bytes had been read from the SSH channel for longer than the
     * configured timeout (default 2 × [SshConfig.SO_TIMEOUT_MS]).
     *
     * This is distinct from [TransportError] because the socket wasn't
     * actually verified dead — `sshj` keeps the connection "warm" via
     * one-way `SSH_MSG_IGNORE` keepalives, so a quiet remote cannot be
     * distinguished from a blackholed network at the transport layer.
     * The watchdog closes the session optimistically when the silence
     * exceeds the threshold; if the user re-connects immediately they
     * get a fresh session and the old one's packets are abandoned.
     *
     * Same SCR-CL-02 / SCR-CL-04 protection as [UserInitiated]: once
     * this value is set, no subsequent read-loop catch may overwrite it
     * (see [SshSession.setCloseReasonUnlessUserInitiated]).
     */
    data object IdleTimeout : SessionCloseReason()
}