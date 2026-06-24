package com.example.sshterminal.ssh

import net.schmizz.sshj.common.SSHException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Translates low-level network / SSH exceptions thrown from
 * [SshClient.connect] into short, user-readable English strings.
 *
 * ## Why this lives in the ssh package
 *
 * The UI layer (`SshTermApp.ConnectionStatusLabel`) renders the connect
 * failure's `message` verbatim into a status line. Without translation, the
 * user sees raw JDK strings like `"Read timed out"` or
 * `"Failed to connect to /192.168.1.10 (port 22) connect timed out"` — both
 * of which are technically correct but completely opaque to someone who
 * hasn't memorized the `java.net` exception hierarchy.
 *
 * Walking the cause chain matters because sshj wraps everything. A TCP
 * timeout inside `client.connect(host, port)` surfaces as
 * `ConnectionException` whose `cause` is the original `SocketTimeoutException`.
 * We need to recognise the leaf cause, not just the wrapper, otherwise every
 * sshj error would fall through to the generic "Connection failed" message.
 *
 * ## Disambiguating two distinct timeout shapes
 *
 * The leaf `SocketTimeoutException` shows up in **two** situations that the
 * user must fix differently:
 *
 *  1. **TCP connect timeout** — the kernel never got a SYN-ACK within
 *     [SshConfig.CONNECT_TIMEOUT_MS]. The right hint is "check your network".
 *  2. **SSH banner read timeout** — TCP connected, but the server never sent
 *     the `SSH-2.0-…` identification line within [SshConfig.SO_TIMEOUT_MS].
 *     The right hint is "the address is reachable but not running SSH" — a
 *     wrong port, a non-SSH service, or a middlebox swallowing the banner.
 *
 * Both leaf exceptions are the same class, so we distinguish them by
 * inspecting the captured stack trace: the banner case has
 * `net.schmizz.sshj.transport.TransportImpl.receiveServerIdent` in the
 * frame list, the connect case doesn't. If a future sshj release renames
 * that method the detection degrades gracefully — we just fall back to the
 * generic "check your network" string, which is no worse than the previous
 * behavior.
 *
 * ## What this is NOT
 *
 * Not localised. The English strings are hard-coded by design — Sprint 2 is
 * English-only and the error copy lives next to the SSH code that throws it
 * so anyone reading both at the same time can see cause ↔ message. A future
 * Sprint that adds i18n should move the strings to `res/values/strings.xml`
 * keyed by exception class, not by hash-id.
 */
internal object SshErrorMessages {

    /**
     * Map [throwable] to a one-line user-facing string. Always non-empty,
     * always safe to render in a single-line status label.
     */
    fun friendly(throwable: Throwable): String {
        val root = rootCause(throwable)
        return when (root) {
            is SocketTimeoutException -> friendlySocketTimeout(root)
            is UnknownHostException ->
                "Server not found. Check the hostname in Settings."
            is ConnectException ->
                "Connection refused. Check the port and that the SSH service is running."
            is NoRouteToHostException ->
                "Host unreachable. Check your network connection."
            is PortUnreachableException ->
                "Server is not reachable on this port."
            is SSHException ->
                "SSH handshake failed. The server may not support SSH on this port."
            is IOException ->
                "Connection lost. The server may have closed the connection."
            else ->
                "Connection failed: ${root.message ?: root.javaClass.simpleName}"
        }
    }

    /**
     * Two distinct things can throw `SocketTimeoutException` during
     * `SshClient.connect`: the kernel's TCP connect timer (15s, "can't reach
     * the host") and sshj's banner read (60s, "host reachable but not
     * speaking SSH"). Same exception class, different fix; the stack frame
     * disambiguates them.
     */
    private fun friendlySocketTimeout(e: SocketTimeoutException): String {
        return if (isSshBannerRead(e)) {
            "Server didn't respond with an SSH banner. " +
                "The address is reachable but may not be running SSH on this port."
        } else {
            "Connection timed out. Check your network and the server's address."
        }
    }

    /**
     * True when the captured stack trace contains sshj's banner-read frame.
     * We match by class+method rather than a regex on the stringified stack
     * so the check survives minor sshj releases (additional frames, line
     * number shifts) — what we care about is the function, not the line.
     */
    private fun isSshBannerRead(e: SocketTimeoutException): Boolean {
        return e.stackTrace.any { frame ->
            frame.className == "net.schmizz.sshj.transport.TransportImpl" &&
                frame.methodName == "receiveServerIdent"
        }
    }

    /**
     * Walk `getCause()` until we hit a cycle or `null`. The JDK allows `cause`
     * to be set to `this`, and sshj in particular sometimes chains exceptions
     * that re-wrap themselves; the `seen` set guards against infinite loops so
     * a malformed chain doesn't hang the connect call.
     */
    private fun rootCause(throwable: Throwable): Throwable {
        var current: Throwable = throwable
        val seen = HashSet<Throwable>()
        seen.add(current)
        while (true) {
            val next = current.cause ?: return current
            if (next === current || !seen.add(next)) return current
            current = next
        }
    }
}
