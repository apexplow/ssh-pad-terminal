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
            is SocketTimeoutException ->
                "Connection timed out. Check your network and the server's address."
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
