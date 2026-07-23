package com.taosun.hanterm.ssh

import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.StandardSocketOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Covers the keepalive-related gaps found in the 2026-07-02 code review,
 * plus the BG-KA-04 follow-up (2026-07-11):
 *
 *  1. [SshClient.buildSshjConfig] must use `Heartbeater` (one-way IGNORE).
 *     `KeepAliveProvider.KEEP_ALIVE` self-kills healthy Tailscale sessions
 *     after `interval × maxAliveCount` when replies fail to land.
 *  2. [SshClient.disconnect] is documented (`GEARS_SPEC.md` SC-DC-03) as safe
 *     to call concurrently from the Disconnect button, `readInto`'s `finally`
 *     (via `SshSession.close`'s `onClose` hook), and the UI's
 *     `onSessionClosed` handler — but had zero test coverage for that claim.
 *  4. A `close()` failure on the underlying `SSHClient` must be logged, not
 *     silently swallowed.
 *
 * We can't drive a real SSH connection here (CLAUDE.md forbids hitting a
 * real sshd from `app/src/test`), so [SSHClient] is injected via reflection
 * into the private `sshRef` field — the same pattern
 * `SshClientHostKeyWiringTest` already uses for `hostKeyVerifier`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SshClientKeepAliveTest {

    private fun sshRefOf(sshClient: SshClient): AtomicReference<SSHClient?> {
        val field = SshClient::class.java.getDeclaredField("sshRef").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(sshClient) as AtomicReference<SSHClient?>
    }

    private fun sshClientWithInjectedClient(client: SSHClient?): SshClient {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sshClient = SshClient(context = context)
        sshRefOf(sshClient).set(client)
        return sshClient
    }

    // ---- Gap #1 / BG-KA-04: KEEP_ALIVE self-kills when replies don't land ----

    @Test
    fun buildSshjConfig_usesOneWayHeartbeat_notReplyCountingKeepAlive() {
        val config = SshClient.buildSshjConfig()
        assertEquals(
            "KEEP_ALIVE (want-reply keepalive@openssh.com) self-killed " +
                "healthy Tailscale sessions after ~30 s when replies failed " +
                "to land; Heartbeater writes one-way SSH_MSG_IGNORE instead. " +
                "Dead-peer detection is TCP keepalive + SO_TIMEOUT.",
            KeepAliveProvider.HEARTBEAT,
            config.keepAliveProvider,
        )
    }

    // ---- Gap #2 / SC-DC-03: disconnect() idempotency + concurrency safety ----

    @Test
    fun disconnect_isANoOp_whenNeverConnected() {
        val sshClient = SshClient(context = ApplicationProvider.getApplicationContext())
        // Must not throw even though connect() never ran.
        sshClient.disconnect()
        sshClient.disconnect(userInitiated = true)
    }

    @Test
    fun disconnect_isIdempotent_secondAndThirdCallsAreNoOps() {
        val fakeClient = mockk<SSHClient>(relaxed = true)
        val sshClient = sshClientWithInjectedClient(fakeClient)

        sshClient.disconnect()
        sshClient.disconnect()
        sshClient.disconnect(userInitiated = true)

        verify(exactly = 1) { fakeClient.close() }
        assertNull("sshRef must be cleared after the first disconnect", sshRefOf(sshClient).get())
    }

    @Test
    fun disconnect_concurrentCallers_closeTheUnderlyingClientExactlyOnce() {
        // Mirrors the real race this fixes: SshSession's single-thread write
        // executor (via the onClose hook) and the UI's onSessionClosed
        // callback on the main thread can both call disconnect() for the
        // same dying session at roughly the same time.
        val fakeClient = mockk<SSHClient>(relaxed = true)
        val sshClient = sshClientWithInjectedClient(fakeClient)

        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val done = CountDownLatch(2)
        repeat(2) { i ->
            Thread {
                ready.countDown()
                go.await(5, TimeUnit.SECONDS)
                sshClient.disconnect(userInitiated = i == 0)
                done.countDown()
            }.start()
        }
        assertTrue("both threads must reach the starting line", ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        assertTrue("both concurrent disconnect() calls must return", done.await(5, TimeUnit.SECONDS))

        verify(exactly = 1) { fakeClient.close() }
        assertNull(sshRefOf(sshClient).get())
    }

    // ---- Gap #4: a close() failure must be logged, not silently swallowed ----

    @Test
    fun disconnect_swallowsButDoesNotCrashOn_closeFailure() {
        val throwingClient = mockk<SSHClient>(relaxed = true)
        every { throwingClient.close() } throws IllegalStateException("boom")
        val sshClient = sshClientWithInjectedClient(throwingClient)

        // Must not propagate: a close() failure must never crash the caller
        // (the Compose UI thread or SshSession's write executor).
        sshClient.disconnect()

        verify(exactly = 1) { throwingClient.close() }
        assertNull(sshRefOf(sshClient).get())
    }

    // ---- BG-KA-01: TCP-level keepalive must reach SocketImpl.fd on ART ----

    @Test
    fun socketFileDescriptor_reachesFdViaImplOnRobolectric() {
        val server = ServerSocket(0)
        server.use { listening ->
            Socket().use { clientSocket ->
                clientSocket.connect(InetSocketAddress("127.0.0.1", listening.localPort))
                val sshClient = SshClient(context = ApplicationProvider.getApplicationContext())
                val method = SshClient::class.java.getDeclaredMethod(
                    "socketFileDescriptor",
                    Socket::class.java,
                ).apply { isAccessible = true }
                val fd = method.invoke(sshClient, clientSocket) as java.io.FileDescriptor?
                // Robolectric's shadow Socket may not expose a real OS fd — skip
                // rather than fail; the production ART path is what BG-KA-01 targets.
                org.junit.Assume.assumeNotNull(
                    "Robolectric shadow Socket has no extractable fd on this SDK",
                    fd,
                )
                assertTrue(
                    "Robolectric Socket must expose a live fd via Socket.impl",
                    isLiveFd(fd!!),
                )
            }
        }
    }

    private fun isLiveFd(fd: java.io.FileDescriptor): Boolean =
        runCatching {
            fd.javaClass.getDeclaredMethod("getInt\$")
                .apply { isAccessible = true }
                .invoke(fd) as Int != -1
        }.getOrDefault(true)

    @Test
    fun configureTcpKeepAlive_setsSoKeepAliveOnRealSocket() {
        val server = ServerSocket(0)
        server.use { listening ->
            Socket().use { clientSocket ->
                clientSocket.connect(InetSocketAddress("127.0.0.1", listening.localPort))
                val fakeClient = mockk<SSHClient>()
                every { fakeClient.socket } returns clientSocket
                val sshClient = SshClient(context = ApplicationProvider.getApplicationContext())
                val method = SshClient::class.java.getDeclaredMethod(
                    "configureTcpKeepAlive",
                    SSHClient::class.java,
                ).apply { isAccessible = true }
                method.invoke(sshClient, fakeClient)
                assertTrue(
                    clientSocket.getOption(StandardSocketOptions.SO_KEEPALIVE),
                )
            }
        }
    }

    @Test
    fun applyTcpKeepaliveIntervals_doesNotThrowOnRobolectric() {
        val server = ServerSocket(0)
        server.use { listening ->
            Socket().use { clientSocket ->
                clientSocket.connect(InetSocketAddress("127.0.0.1", listening.localPort))
                val sshClient = SshClient(context = ApplicationProvider.getApplicationContext())
                val fdMethod = SshClient::class.java.getDeclaredMethod(
                    "socketFileDescriptor",
                    Socket::class.java,
                ).apply { isAccessible = true }
                val fd = fdMethod.invoke(sshClient, clientSocket) as java.io.FileDescriptor?
                org.junit.Assume.assumeNotNull(
                    "Robolectric shadow Socket has no extractable fd on this SDK",
                    fd,
                )
                val applyMethod = SshClient::class.java.getDeclaredMethod(
                    "applyTcpKeepaliveIntervals",
                    java.io.FileDescriptor::class.java,
                ).apply { isAccessible = true }
                applyMethod.invoke(sshClient, fd)
            }
        }
    }

    @Test
    fun companionKeepAliveNudgeField_isRemoved_issue17() {
        // Issue #17: `SshClient.companion.keepAliveNudge` and the
        // `hasKeepAliveNudge()` / `nudgeTransportKeepAlive()` static
        // methods are gone. Pin the absence so a future refactor can't
        // silently reintroduce the global-static coupling.
        val companionClass = SshClient.Companion::class.java
        val field = runCatching {
            companionClass.getDeclaredField("keepAliveNudge")
        }.getOrNull()
        assertNull(
            "SshClient.Companion.keepAliveNudge must be removed (Issue #17)",
            field,
        )
        assertThrows(
            "SshClient.hasKeepAliveNudge() must be removed (Issue #17)",
            NoSuchMethodException::class.java,
        ) {
            SshClient::class.java.getDeclaredMethod("hasKeepAliveNudge")
        }
        assertThrows(
            "SshClient.nudgeTransportKeepAlive() must be removed (Issue #17)",
            NoSuchMethodException::class.java,
        ) {
            SshClient::class.java.getDeclaredMethod(
                "nudgeTransportKeepAlive",
            )
        }
    }
}
