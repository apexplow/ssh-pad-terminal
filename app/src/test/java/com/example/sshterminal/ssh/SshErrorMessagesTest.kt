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
        // Pin the banner-read variant too. The default-constructed
        // SocketTimeoutException above has no banner frame in its stack, so
        // it falls through to the generic message; this assertion exercises
        // the OTHER branch with a synthetic stack so a regression that
        // collapses both into one message gets caught.
        val banner = SocketTimeoutException("Read timed out").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "net.schmizz.sshj.transport.TransportImpl",
                    "receiveServerIdent",
                    "TransportImpl.java",
                    193,
                ),
            )
        }
        assertEquals(
            "Server didn't respond with an SSH banner. " +
                "The address is reachable but may not be running SSH on this port.",
            SshErrorMessages.friendly(banner),
        )
    }

    @Test
    fun test_socketTimeoutException_withBannerReadFrame_returnsBannerMessage() {
        // Real production stack shape: SocketInputStream.socketRead blocked
        // on a banner that never arrived. SshErrorMessages.friendly walks
        // to the SocketTimeoutException, then checks its captured stack for
        // the sshj banner-read frame and returns the banner-specific hint.
        val e = SocketTimeoutException("Read timed out").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "java.net.SocketInputStream", "socketRead0",
                    "SocketInputStream.java", -2,
                ),
                StackTraceElement(
                    "java.net.SocketInputStream", "socketRead",
                    "SocketInputStream.java", 118,
                ),
                StackTraceElement(
                    "net.schmizz.sshj.transport.TransportImpl",
                    "receiveServerIdent",
                    "TransportImpl.java",
                    193,
                ),
                StackTraceElement(
                    "net.schmizz.sshj.transport.TransportImpl",
                    "init",
                    "TransportImpl.java",
                    158,
                ),
            )
        }
        assertEquals(
            "Server didn't respond with an SSH banner. " +
                "The address is reachable but may not be running SSH on this port.",
            SshErrorMessages.friendly(e),
        )
    }

    @Test
    fun test_socketTimeoutException_withNonBannerStack_keepsGenericMessage() {
        // The kernel connect-timeout stack: the read happens inside nio's
        // SocketChannel.connect, not inside sshj. No TransportImpl frame
        // anywhere, so the friendly mapper keeps the original
        // "check your network" hint — this is the case where the network
        // really is the problem.
        val e = SocketTimeoutException("connect timed out").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "sun.nio.ch.SocketChannelImpl", "connect",
                    "SocketChannelImpl.java", -1,
                ),
                StackTraceElement(
                    "net.schmizz.sshj.SocketClient", "connect",
                    "SocketClient.java", 69,
                ),
            )
        }
        assertEquals(
            "Connection timed out. Check your network and the server's address.",
            SshErrorMessages.friendly(e),
        )
    }

    @Test
    fun test_socketTimeoutException_inTransportExceptionChain_usesBannerMessage() {
        // Real production wrap: sshj's TransportException("Read timed out")
        // wraps a SocketTimeoutException whose stack has receiveServerIdent.
        // The cause-chain walk must reach the SocketTimeoutException so the
        // banner frame is found — without that, the umbrella
        // SSHException → "handshake failed" mapping would fire instead and
        // hide the real cause from the user.
        val root = SocketTimeoutException("Read timed out").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "net.schmizz.sshj.transport.TransportImpl",
                    "receiveServerIdent",
                    "TransportImpl.java",
                    193,
                ),
            )
        }
        val wrapped = net.schmizz.sshj.transport.TransportException(root)
        assertEquals(
            "Server didn't respond with an SSH banner. " +
                "The address is reachable but may not be running SSH on this port.",
            SshErrorMessages.friendly(wrapped),
        )
    }

    @Test
    fun test_socketTimeoutException_partialMethodMatch_doesNotCount() {
        // The detector matches class+method EXACTLY. A frame with the right
        // class but a different method name (e.g. a hypothetical future
        // `receiveServerIdentAsync`) must not count, otherwise we'd
        // misclassify other timeouts as banner reads.
        val e = SocketTimeoutException("Read timed out").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "net.schmizz.sshj.transport.TransportImpl",
                    "receiveServerIdentAsync",
                    "TransportImpl.java",
                    999,
                ),
            )
        }
        assertEquals(
            "Connection timed out. Check your network and the server's address.",
            SshErrorMessages.friendly(e),
        )
    }
}
