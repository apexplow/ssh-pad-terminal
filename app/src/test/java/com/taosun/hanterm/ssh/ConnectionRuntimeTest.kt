package com.taosun.hanterm.ssh

import androidx.test.core.app.ApplicationProvider
import com.taosun.hanterm.ssh.auth.Auth
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Covers the ConnectionRuntime contract (process-scoped sole owner +
 * capability [ConnectionView]).
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
        // no-op — no process store
    }

    @After
    fun tearDown() {
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
        assertTrue(view.isLive)
        assertTrue(view is BridgedConnectionView)
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
        assertFalse(runtime.view.value.isLive)
        assertTrue(runtime.view.value is IdleConnectionView)
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
    fun reconnect_tearsDownPreviousSessionBeforeBuildingNew() = runTest(dispatcher) {
        val session1 = mockSession()
        val session2 = mockSession()
        val disconnectCount = AtomicInteger(0)
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

            override fun disconnect(userInitiated: Boolean) {
                disconnectCount.incrementAndGet()
            }
        }
        val runtime = newRuntime(connector)

        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()
        val firstView = runtime.view.value
        assertTrue(firstView.isLive)

        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()
        val secondView = runtime.view.value
        assertTrue(secondView.isLive)
        assertTrue(secondView !== firstView)
        // Previous session must have been closed via full teardown.
        verify { session1.close(userInitiated = true) }
        assertTrue(disconnectCount.get() >= 1)
        runtime.dispose()
    }

    @Test
    fun connect_afterDisconnectDuringHandshake_discardsLateSuccess() = runTest(dispatcher) {
        val handshakeEntered = CompletableDeferred<Unit>()
        val continueHandshake = CompletableDeferred<Unit>()
        val lateSession = mockSession()
        val disconnectCount = AtomicInteger(0)
        val connector = object : SshConnector {
            override suspend fun connect(
                host: String,
                port: Int,
                username: String,
                auth: Auth,
            ): Result<SshConnectResult> {
                handshakeEntered.complete(Unit)
                continueHandshake.await()
                return Result.success(SshConnectResult(lateSession))
            }

            override fun disconnect(userInitiated: Boolean) {
                disconnectCount.incrementAndGet()
            }
        }
        val runtime = newRuntime(connector)

        val connectJob = async { runtime.connect("h", 22, "u", dummyAuth) }
        handshakeEntered.await()
        assertEquals(ConnectionState.Connecting, runtime.state.value)

        runtime.disconnect(userInitiated = true)
        advanceUntilIdle()
        continueHandshake.complete(Unit)

        val outcome = connectJob.await()
        advanceUntilIdle()

        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertEquals(ConnectionState.Disconnected, runtime.state.value)
        assertFalse(runtime.view.value.isLive)
        verify { lateSession.close(userInitiated = true) }
        // abandonHandshake + disconnect path both may call connector.disconnect
        assertTrue(disconnectCount.get() >= 1)
        runtime.dispose()
    }

    @Test
    fun inboundFailure_callsTeardownAndPublishesError() = runTest(dispatcher) {
        val session = mockSession()
        val runtime = newRuntime(FakeSshConnector(Result.success(SshConnectResult(session))))
        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()

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
        assertFalse(runtime.view.value.isLive)
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
        assertFalse(runtime.view.value.isLive)
        runtime.dispose()
    }

    @Test
    fun view_publishesAtomically() = runTest(dispatcher) {
        val session = mockSession()
        val runtime = newRuntime(FakeSshConnector(Result.success(SshConnectResult(session))))

        val snapshots = mutableListOf<ConnectionView>()
        backgroundScope.launch {
            runtime.view.collect { snapshots += it }
        }
        advanceUntilIdle()

        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()

        for (snap in snapshots) {
            if (snap.isLive) {
                assertTrue(snap is BridgedConnectionView)
            } else {
                assertTrue(snap is IdleConnectionView)
            }
        }
        assertTrue(runtime.view.value.isLive)
        runtime.dispose()
    }

    @Test
    fun dispose_cancelsIoScope() = runTest(dispatcher) {
        val session = mockSession()
        val runtime = newRuntime(FakeSshConnector(Result.success(SshConnectResult(session))))
        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()
        assertTrue(runtime.view.value.isLive)

        runtime.dispose()
        advanceUntilIdle()

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
        every { session.lastCloseReason } returns SessionCloseReason.RemoteEof
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
