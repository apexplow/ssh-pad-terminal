package com.taosun.hanterm.ssh

import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.Transport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference

/**
 * Primary seam for `SshClient.keepAliveNudge` (Issue #17) — the
 * `inner class SshClientKeepAliveNudge` that writes a one-way
 * `SSH_MSG_IGNORE` through the live sshj [SSHClient.transport] when a
 * transport is connected.
 *
 * Mirrors the `SshClientKeepAliveTest` patterns for `sshRef` injection
 * via reflection — the inner class is private and only exposed through
 * the public [KeepAliveNudge] field on the outer [SshClient] instance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SshClientKeepAliveNudgeTest {

    private fun sshRefOf(sshClient: SshClient): AtomicReference<SSHClient?> {
        val field = SshClient::class.java.getDeclaredField("sshRef")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(sshClient) as AtomicReference<SSHClient?>
    }

    private fun sshClientWithInjectedClient(client: SSHClient?): SshClient {
        val sshClient = SshClient(context = ApplicationProvider.getApplicationContext())
        sshRefOf(sshClient).set(client)
        return sshClient
    }

    @Test
    fun nudge_sshRefNull_returnsFalse() {
        val sshClient = sshClientWithInjectedClient(null)
        val result = sshClient.keepAliveNudge.nudge()
        assertFalse(
            "A nudge with no live SSHClient must return false (not " +
                "throw) so the FGS can continue ticking",
            result,
        )
    }

    @Test
    fun nudge_clientNotConnected_returnsFalse() {
        // The sshj transport reports `!isConnected` BEFORE the write —
        // no SSHPacket is constructed, no transport call is made.
        val fakeClient = mockk<SSHClient>(relaxed = true)
        every { fakeClient.isConnected } returns false
        val sshClient = sshClientWithInjectedClient(fakeClient)

        assertFalse(sshClient.keepAliveNudge.nudge())
        // No transport interaction should have happened.
        verify(exactly = 0) { fakeClient.transport.write(any()) }
    }

    @Test
    fun nudge_transportNotRunning_returnsFalse() {
        // `!isRunning` is the second early-exit check; same shape as
        // the not-connected branch but on the transport.
        val fakeTransport = mockk<Transport>(relaxed = true)
        every { fakeTransport.isRunning } returns false
        val fakeClient = mockk<SSHClient>(relaxed = true)
        every { fakeClient.isConnected } returns true
        every { fakeClient.transport } returns fakeTransport
        val sshClient = sshClientWithInjectedClient(fakeClient)

        assertFalse(sshClient.keepAliveNudge.nudge())
        verify(exactly = 0) { fakeTransport.write(any()) }
    }

    @Test
    fun nudge_liveClient_writesIgnorePacketAndReturnsTrue() {
        // The happy path: isConnected + transport.isRunning + write
        // succeeds. The exact SSHPacket shape (message type IGNORE
        // with an empty payload) is exercised by the production code
        // itself; here we just pin that the write was called and the
        // nudge returned true. (We do NOT inspect the packet's
        // message-type byte — `SSHPacket.readMessageID()` walks the
        // internal read cursor and mockk's slot capture is sensitive
        // to that; the SSH-protocol contract is the responsibility of
        // the production code, not this test.)
        val fakeTransport = mockk<Transport>(relaxed = true)
        every { fakeTransport.isRunning } returns true
        // `Transport.write(SSHPacket)` returns the number of bytes
        // written on the underlying socket; a `relaxed = true` mock
        // default of 0 is fine for this test — we only assert the
        // packet was written, not the return value.
        every { fakeTransport.write(any()) } returns 0L
        val fakeClient = mockk<SSHClient>(relaxed = true)
        every { fakeClient.isConnected } returns true
        every { fakeClient.transport } returns fakeTransport
        val sshClient = sshClientWithInjectedClient(fakeClient)

        assertTrue(sshClient.keepAliveNudge.nudge())
        verify(exactly = 1) { fakeTransport.write(any()) }
    }

    @Test
    fun nudge_transportWriteThrows_returnsFalse() {
        // A write that throws (e.g. a closing socket, or a peer that
        // RST'd mid-write) must NOT propagate — the FGS sleep loop
        // would otherwise die and the keepalive cadence would stop.
        val fakeTransport = mockk<Transport>(relaxed = true)
        every { fakeTransport.isRunning } returns true
        every { fakeTransport.write(any()) } throws SocketException("Software caused connection abort")
        val fakeClient = mockk<SSHClient>(relaxed = true)
        every { fakeClient.isConnected } returns true
        every { fakeClient.transport } returns fakeTransport
        val sshClient = sshClientWithInjectedClient(fakeClient)

        val result = sshClient.keepAliveNudge.nudge()
        assertFalse(
            "A write exception must be swallowed and reported as a " +
                "failed nudge so the FGS loop survives transport errors",
            result,
        )
    }
}
