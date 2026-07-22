package com.taosun.hanterm.ssh

import androidx.test.core.app.ApplicationProvider
import com.taosun.hanterm.ssh.auth.Auth
import com.taosun.hanterm.terminal.MockEchoSession
import com.taosun.hanterm.terminal.PtyBridgeEndpoint
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the ConnectionRuntime contract from
 * `docs/superpowers/specs/2026-07-22-connection-runtime-design.md` §Tests.
 *
 * No real SSH sockets — [FakeSshConnector] + mockk [SshSession]. Robolectric
 * supplies the Application Context for [SshKeepAliveService.stop].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRuntimeTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val dispatcher = StandardTestDispatcher()
    private val dummyAuth = Auth.PublicKeyAuth("/tmp/test_key.pem")

    @Before
    fun setUp() {
        ActiveSshSessionStore.clear()
    }

    @After
    fun tearDown() {
        ActiveSshSessionStore.clear()
        runCatching { unmockkObject(SshKeepAliveService) }
    }

    @Test
    fun connect_bailsWhenAlreadyConnecting() = runTest(dispatcher) {
        val connectorEntered = CompletableDeferred<Unit>()
        val continueConnect = CompletableDeferred<Unit>()
        val connector = object : SshConnector {
            override suspend fun connect(
                host: String,
                port: Int,
                username: String,
                auth: Auth,
            ): Result<SshConnectResult> {
                connectorEntered.complete(Unit)
                continueConnect.await()
                return Result.success(SshConnectResult(mockSession()))
            }

            override fun disconnect(userInitiated: Boolean) = Unit
        }
        val runtime = newRuntime(connector)

        val first = async { runtime.connect("h", 22, "u", dummyAuth) }
        connectorEntered.await()
        assertEquals(ConnectionState.Connecting, runtime.state.value)

        val second = runtime.connect("h", 22, "u", dummyAuth)
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull() is IllegalStateException)

        continueConnect.complete(Unit)
        assertTrue(first.await().isSuccess)
        runtime.dispose()
    }

    @Test
    fun connect_publishesViewAndStateOnSuccess() = runTest(dispatcher) {
        val session = mockSession()
        val runtime = newRuntime(FakeSshConnector(Result.success(SshConnectResult(session))))

        val result = runtime.connect("example.com", 22, "test", dummyAuth)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals(ConnectionState.Connected("test@example.com:22"), runtime.state.value)
        val view = runtime.view.value
        assertNotNull(view)
        assertTrue(view!!.endpoint is PtyBridgeEndpoint)
        assertNotNull(view.bridge)
        assertSame(session, view.session)
        assertSame(session, runtime.activeSession.value)
        assertSame(session, ActiveSshSessionStore.get())
        runtime.dispose()
    }

    @Test
    fun connect_publishesErrorStateOnFailure() = runTest(dispatcher) {
        val runtime = newRuntime(
            FakeSshConnector(Result.failure(IllegalStateException("boom"))),
        )

        val result = runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertTrue(runtime.state.value is ConnectionState.Error)
        assertEquals("boom", (runtime.state.value as ConnectionState.Error).message)
        val view = runtime.view.value
        assertNotNull(view)
        assertTrue(view!!.endpoint is MockEchoSession)
        assertNull(view.bridge)
        assertNull(view.session)
        assertNull(runtime.activeSession.value)
        assertNull(ActiveSshSessionStore.get())
        runtime.dispose()
    }

    @Test
    fun disconnect_isIdempotent() = runTest(dispatcher) {
        val session = mockSession()
        val disconnectCount = AtomicInteger(0)
        val connector = object : SshConnector {
            override suspend fun connect(
                host: String,
                port: Int,
                username: String,
                auth: Auth,
            ) = Result.success(SshConnectResult(session))

            override fun disconnect(userInitiated: Boolean) {
                disconnectCount.incrementAndGet()
            }
        }
        val runtime = newRuntime(connector)
        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()

        runtime.disconnect()
        advanceUntilIdle()
        runtime.disconnect()
        advanceUntilIdle()
        runtime.disconnect()
        advanceUntilIdle()

        // teardownGuard is re-armed only on a subsequent connect success —
        // so second/third disconnects after a completed teardown are no-ops
        // until the next connect. Exactly one connector.disconnect.
        assertEquals(1, disconnectCount.get())
        assertEquals(ConnectionState.Disconnected, runtime.state.value)
        runtime.dispose()
    }

    @Test
    fun disconnect_concurrentCallers_teardownExactlyOnce() {
        val session = mockSession()
        val disconnectCount = AtomicInteger(0)
        val connector = object : SshConnector {
            override suspend fun connect(
                host: String,
                port: Int,
                username: String,
                auth: Auth,
            ) = Result.success(SshConnectResult(session))

            override fun disconnect(userInitiated: Boolean) {
                disconnectCount.incrementAndGet()
            }
        }
        val runtime = ConnectionRuntime(
            context = context,
            connector = connector,
        )
        runBlocking {
            runtime.connect("h", 22, "u", dummyAuth)
        }

        val ready = CountDownLatch(4)
        val go = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) {
            Thread {
                ready.countDown()
                go.await(5, TimeUnit.SECONDS)
                runBlocking { runtime.disconnect() }
                done.countDown()
            }.start()
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))

        assertEquals(1, disconnectCount.get())
        runtime.dispose()
    }

    @Test
    fun disconnect_stopsFgsBeforeClosingSshj() = runTest(dispatcher) {
        val session = mockSession()
        val order = mutableListOf<String>()
        mockkObject(SshKeepAliveService)
        every { SshKeepAliveService.stop(any()) } answers {
            order += "fgs"
        }
        val connector = object : SshConnector {
            override suspend fun connect(
                host: String,
                port: Int,
                username: String,
                auth: Auth,
            ) = Result.success(SshConnectResult(session))

            override fun disconnect(userInitiated: Boolean) {
                order += "sshj"
            }
        }
        val runtime = newRuntime(connector)
        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()
        runtime.disconnect()
        advanceUntilIdle()

        assertEquals(listOf("fgs", "sshj"), order)
        runtime.dispose()
    }

    @Test
    fun disconnect_clearsActiveSshSessionStore() = runTest(dispatcher) {
        val session = mockSession()
        val runtime = newRuntime(FakeSshConnector(Result.success(SshConnectResult(session))))
        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()
        assertSame(session, ActiveSshSessionStore.get())

        runtime.disconnect()
        advanceUntilIdle()

        assertNull(ActiveSshSessionStore.get())
        runtime.dispose()
    }

    @Test
    fun reconnect_tearsDownPreviousBridgeBeforeBuildingNew() = runTest(dispatcher) {
        val session1 = mockSession()
        val session2 = mockSession()
        val results = mutableListOf(
            Result.success(SshConnectResult(session1)),
            Result.success(SshConnectResult(session2)),
        )
        val connector = object : SshConnector {
            override suspend fun connect(
                host: String,
                port: Int,
                username: String,
                auth: Auth,
            ): Result<SshConnectResult> = results.removeAt(0)

            override fun disconnect(userInitiated: Boolean) = Unit
        }
        val runtime = newRuntime(connector)

        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()
        val firstBridge = runtime.view.value!!.bridge
        assertNotNull(firstBridge)

        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()
        val secondView = runtime.view.value!!
        assertNotNull(secondView.bridge)
        assertTrue(secondView.bridge !== firstBridge)
        assertSame(session2, secondView.session)
        assertSame(session2, ActiveSshSessionStore.get())
        runtime.dispose()
    }

    @Test
    fun inboundFailure_callsTeardownAndPublishesError() = runTest(dispatcher) {
        val session = mockSession()
        val runtime = newRuntime(FakeSshConnector(Result.success(SshConnectResult(session))))
        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()

        // Mirrors TerminalPane.finally → ViewModel.onSessionClosed →
        // runtime.disconnect(userInitiated = false, finalState = Error(...)).
        runtime.disconnect(
            userInitiated = false,
            finalState = ConnectionState.Error("Remote host closed the connection."),
        )
        advanceUntilIdle()

        verify(exactly = 0) { session.close(userInitiated = true) }
        assertTrue(runtime.state.value is ConnectionState.Error)
        assertEquals(
            "Remote host closed the connection.",
            (runtime.state.value as ConnectionState.Error).message,
        )
        assertNull(runtime.activeSession.value)
        assertNull(ActiveSshSessionStore.get())
        runtime.dispose()
    }

    @Test
    fun watchdogFire_callsTeardownWithIdleReason() = runTest(dispatcher) {
        val session = mockSession()
        val runtime = newRuntime(FakeSshConnector(Result.success(SshConnectResult(session))))
        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()

        runtime.disconnect(
            userInitiated = false,
            finalState = ConnectionState.Error("Session ended due to inactivity."),
        )
        advanceUntilIdle()

        assertTrue(runtime.state.value is ConnectionState.Error)
        assertEquals(
            "Session ended due to inactivity.",
            (runtime.state.value as ConnectionState.Error).message,
        )
        assertNull(ActiveSshSessionStore.get())
        runtime.dispose()
    }

    @Test
    fun view_publishesAtomically() = runTest(dispatcher) {
        val session = mockSession()
        val runtime = newRuntime(FakeSshConnector(Result.success(SshConnectResult(session))))

        val snapshots = mutableListOf<ConnectionView?>()
        backgroundScope.launch {
            runtime.view.collect { snapshots += it }
        }
        advanceUntilIdle()

        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()

        // Every non-null snapshot must be fully built — never a half-built
        // view with e.g. a bridge but no session (or vice versa).
        for (snap in snapshots.filterNotNull()) {
            if (snap.bridge != null) {
                assertNotNull("bridge present ⇒ session must be present", snap.session)
                assertTrue(snap.endpoint is PtyBridgeEndpoint)
            }
            if (snap.session != null && snap.bridge == null) {
                // Re-attach degraded shape is OK; endpoint must be the session.
                assertSame(snap.session, snap.endpoint)
            }
        }
        val final = runtime.view.value!!
        assertNotNull(final.bridge)
        assertSame(session, final.session)
        assertTrue(final.endpoint is PtyBridgeEndpoint)
        runtime.dispose()
    }

    @Test
    fun dispose_cancelsIoScope() = runTest(dispatcher) {
        val session = mockSession()
        val runtime = newRuntime(FakeSshConnector(Result.success(SshConnectResult(session))))
        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()
        assertNotNull(runtime.view.value?.bridge)

        runtime.dispose()
        advanceUntilIdle()

        // After dispose the IO scope is cancelled. A subsequent disconnect
        // must still be safe (teardownGuard / runCatching) and must not throw.
        runtime.disconnect()
        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, runtime.state.value)
    }

    private fun newRuntime(connector: SshConnector): ConnectionRuntime =
        ConnectionRuntime(
            context = context,
            connector = connector,
            ioDispatcher = dispatcher,
        )

    private fun mockSession(): SshSession {
        val session = mockk<SshSession>(relaxed = true)
        coEvery { session.readInto(any()) } coAnswers { awaitCancellation() }
        return session
    }

    private class FakeSshConnector(
        private val result: Result<SshConnectResult>,
    ) : SshConnector {
        override suspend fun connect(
            host: String,
            port: Int,
            username: String,
            auth: Auth,
        ): Result<SshConnectResult> = result

        override fun disconnect(userInitiated: Boolean) = Unit
    }
}
