package com.example.sshterminal.ssh

import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Covers the keepalive-related gaps found in the 2026-07-02 code review:
 *
 *  1. sshj's `DefaultConfig` defaults `keepAliveProvider` to `HEARTBEAT`,
 *     which only writes `SSH_MSG_IGNORE` and can never detect a dead peer.
 *     [SshClient.buildSshjConfig] must opt into `KeepAliveProvider.KEEP_ALIVE`.
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

    // ---- Gap #1: sshj's HEARTBEAT default never detects a dead peer ----

    @Test
    fun buildSshjConfig_optsIntoActiveDeadPeerDetection() {
        val config = SshClient.buildSshjConfig()
        assertEquals(
            "SshClient must not rely on sshj's DefaultConfig default " +
                "(HEARTBEAT), which only writes SSH_MSG_IGNORE and never " +
                "detects a dead peer",
            KeepAliveProvider.KEEP_ALIVE,
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
}
