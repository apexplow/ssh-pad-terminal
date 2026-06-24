package com.example.sshterminal.ssh

import net.schmizz.sshj.common.SSHException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Pins [SshErrorMessages.friendly] output so the Connect-failure status line
 * never regresses to raw JDK strings. Each test owns one exception type —
 * adding a new mapping means adding a test, so a forgotten case is caught at
 * PR time rather than at user-report time.
 *
 * All tests use bare exception instances (no sshj, no Robolectric) because
 * the mapper is a pure function over a Throwable cause chain.
 */
class SshErrorMessagesTest {

    @Test
    fun test_socketTimeoutException_mentionsTimeoutAndNetwork() {
        // The literal "Read timed out" message from SocketTimeoutException is
        // the symptom users were seeing on connect. The translated string
        // must mention timeout and give the user two things to check
        // (network + server address) — that's the whole point of the fix.
        val msg = SshErrorMessages.friendly(SocketTimeoutException("Read timed out"))
        assertTrue("message should mention timeout: $msg", msg.contains("timed out", ignoreCase = true))
        assertTrue("message should hint at network/address: $msg",
            msg.contains("network", ignoreCase = true) || msg.contains("address", ignoreCase = true))
    }

    @Test
    fun test_unknownHostException_mentionsHostname() {
        val msg = SshErrorMessages.friendly(UnknownHostException("no-such-host.invalid"))
        assertTrue("message should mention host/hostname: $msg",
            msg.contains("host", ignoreCase = true))
        // Should not leak the raw "no-such-host.invalid" — DNS failures can
        // include user-controlled labels and we don't want them in the UI.
        assertTrue("message should not echo the raw hostname: $msg",
            !msg.contains("no-such-host.invalid"))
    }

    @Test
    fun test_connectException_mentionsPort() {
        // ConnectException = the kernel sent a TCP RST — server is up but
        // nothing is listening. The most likely cause is a wrong port or a
        // firewall. The translation must mention "port".
        val msg = SshErrorMessages.friendly(ConnectException("Connection refused"))
        assertTrue("message should mention port: $msg", msg.contains("port", ignoreCase = true))
    }

    @Test
    fun test_noRouteToHostException_mentionsNetwork() {
        // NoRouteToHostException = no path to the host at all (no SYN-ACK,
        // no RST). Different from ConnectException; the user fix is
        // "check network" not "check port".
        val msg = SshErrorMessages.friendly(NoRouteToHostException("No route to host"))
        assertTrue("message should mention network: $msg", msg.contains("network", ignoreCase = true))
    }

    @Test
    fun test_portUnreachableException_mentionsPort() {
        val msg = SshErrorMessages.friendly(PortUnreachableException("ICMP port unreachable"))
        assertTrue("message should mention port: $msg", msg.contains("port", ignoreCase = true))
    }

    @Test
    fun test_sshjSSHException_mentionsHandshake() {
        // sshj's SSHException is the umbrella for kex/auth/channel failures.
        // We can't usefully distinguish them at this layer, so the message
        // just points at "handshake" / SSH support.
        val msg = SshErrorMessages.friendly(SSHException("key exchange failed"))
        assertTrue("message should mention handshake/SSH: $msg",
            msg.contains("handshake", ignoreCase = true) || msg.contains("ssh", ignoreCase = true))
    }

    @Test
    fun test_genericIOException_saysConnectionLost() {
        // Broken pipe, connection reset, etc. — the link was up and died.
        val msg = SshErrorMessages.friendly(IOException("Broken pipe"))
        assertTrue("message should mention connection lost: $msg",
            msg.contains("connection lost", ignoreCase = true) || msg.contains("closed", ignoreCase = true))
    }

    @Test
    fun test_unknownThrowable_fallsBackToOriginalMessage() {
        // If we don't recognise the type, surface something useful: the
        // class name plus the original message. The leading
        // "Connection failed:" prefix is what the UI parses against.
        val msg = SshErrorMessages.friendly(IllegalStateException("something weird"))
        assertTrue("fallback should start with 'Connection failed:': $msg",
            msg.startsWith("Connection failed:"))
        assertTrue("fallback should include the original message: $msg",
            msg.contains("something weird"))
    }

    @Test
    fun test_unknownThrowableWithNullMessage_fallsBackToClassName() {
        // Null message + unknown type — the UI must still get a non-empty
        // string (status labels assume non-empty). The fallback is the
        // exception's simple class name.
        val msg = SshErrorMessages.friendly(object : Throwable() {})
        assertTrue("fallback should be non-empty: '$msg'", msg.isNotEmpty())
        assertTrue("fallback should start with 'Connection failed:': $msg",
            msg.startsWith("Connection failed:"))
    }

    @Test
    fun test_causeChain_unwrapsSshjWrapping() {
        // sshj wraps everything in ConnectionException / TransportException.
        // The leaf SocketTimeoutException is in `cause`. If the mapper only
        // looked at the top-level type, every sshj error would fall through
        // to the generic branch and the user would see the raw
        // "Read timed out" message we're trying to replace.
        val root = SocketTimeoutException("Read timed out")
        val wrapped = RuntimeException("kex blew up", root)
        val doubleWrapped = SSHException("connect failed", wrapped)
        val msg = SshErrorMessages.friendly(doubleWrapped)
        assertTrue("unwrapped message should mention timeout: $msg",
            msg.contains("timed out", ignoreCase = true))
    }

    @Test
    fun test_causeChain_handlesSelfReferentialCause() {
        // A pathological chain where `cause === this` (the JDK allows it
        // and sshj has been seen doing it on disconnect races) must not
        // hang the mapper. We don't care what string comes out, only that
        // the function returns at all.
        val pathological = object : RuntimeException() {
            override val cause: Throwable? get() = this
        }
        // If this doesn't hang / stack-overflow, the guard works.
        val msg = SshErrorMessages.friendly(pathological)
        assertTrue("self-referential cause should still return a message: '$msg'", msg.isNotEmpty())
    }

    @Test
    fun test_result_isAlwaysNonEmpty() {
        // The UI status label assumes the message is non-empty. Belt-and-
        // suspenders: try a representative sample of Throwable types and
        // assert all return a non-empty string.
        val samples: List<Throwable> = listOf(
            SocketTimeoutException(),
            UnknownHostException(),
            ConnectException(),
            NoRouteToHostException(),
            PortUnreachableException(),
            SSHException("x"),
            IOException("x"),
            RuntimeException(),
            Error(),
        )
        for (t in samples) {
            val msg = SshErrorMessages.friendly(t)
            assertTrue("friendly($t) should be non-empty: '$msg'", msg.isNotEmpty())
        }
    }

    @Test
    fun test_specificStrings_areStable() {
        // Pin the exact wording. The status line is the only place these
        // strings appear; if anyone "improves" the wording, this test forces
        // them to also update any translation / accessibility copy.
        assertEquals(
            "Connection timed out. Check your network and the server's address.",
            SshErrorMessages.friendly(SocketTimeoutException()),
        )
        assertEquals(
            "Connection refused. Check the port and that the SSH service is running.",
            SshErrorMessages.friendly(ConnectException()),
        )
    }
}
