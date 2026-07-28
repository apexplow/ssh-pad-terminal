package com.apexplow.hanterm.ssh

import androidx.test.core.app.ApplicationProvider
import com.apexplow.hanterm.ssh.auth.Auth
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
 * Covers the ConnectionRuntime contract (process-scoped sole owner +
 * capability [ConnectionView]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
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
        runCatching { unmockkObject(KeepAliveNudgeRegistry) }
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
                // Issue #15 deferred half: disconnect() suspends and hops
                // to ioDispatcher via withContext; runBlocking awaits the
                // teardown (real Dispatchers.IO since no ioDispatcher was
                // passed to the runtime constructor) before the thread
                // returns, so disconnectCount.get() below is checked
                // after teardown ran.
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
    fun disconnect_clearsRegistryBeforeStoppingFgsBeforeClosingSshj() = runTest(dispatcher) {
        val session = mockSession()
        val order = mutableListOf<String>()
        // Issue #17: the teardown order gains a registry-clear step in
        // front of the existing FGS-stop and connector-disconnect steps.
        // `teardownInternal` runs the 3 steps in sequence on the runtime
        // side; the safety-net `SshKeepAliveService.stop` inside
        // `SshClient.disconnect` is NOT exercised here because this test
        // injects a fake `SshConnector` whose `disconnect` records
        // directly. The real production call site is pinned by
        // `SshClientKeepAliveNudgeTest` + the new keepAliveNudge field
        // coverage.
        mockkObject(KeepAliveNudgeRegistry, SshKeepAliveService)
        // mockk 1.13.13: `matchNullable { it == null }` is the variant
        // that does match a null `set(...)` argument. The bind in
        // `handleConnectSuccess` is `set(non-null nudge)` — we pass a
        // fake non-null nudge through the `SshConnectResult` so the
        // bind does NOT match this predicate and only the unbind in
        // `teardownInternal` does. The bind coverage itself lives in the
        // dedicated `connect_success_bindsKeepAliveNudgeToRegistry` test
        // (commit 2).
        val fakeNudge = KeepAliveNudge { true }
        every { KeepAliveNudgeRegistry.set(matchNullable { it == null }) } answers {
            order += "registry-clear"
        }
        every { SshKeepAliveService.stop(any()) } answers {
            order += "fgs"
        }
        val connector = object : SshConnector {
            override suspend fun connect(
                host: String,
                port: Int,
                username: String,
                auth: Auth,
            ) = Result.success(SshConnectResult(session, keepAliveNudge = fakeNudge))

            override fun disconnect(userInitiated: Boolean) {
                order += "sshj"
            }
        }
        val runtime = newRuntime(connector)
        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()
        runtime.disconnect()
        advanceUntilIdle()

        assertEquals(listOf("registry-clear", "fgs", "sshj"), order)
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
    fun teardownState_isIdleAtConstruction() = runTest(dispatcher) {
        val runtime = newRuntime(FakeSshConnector(Result.success(SshConnectResult(mockSession()))))
        assertEquals(TeardownState.Idle, runtime.teardownState.value)
        runtime.dispose()
    }

    @Test
    fun teardownState_stampsTearingDownAndCompleteDuringDisconnect() = runTest(dispatcher) {
        // Issue #15: the [TeardownState] StateFlow publishes Idle before,
        // TearingDown synchronously at the top of disconnect(), and
        // Complete after step 7 publishes the idle pair. The synchronous
        // TearingDown stamp means the UI sees the intent was accepted
        // even if step 6/7 is blocked by socket I/O.
        val session = mockSession()
        val runtime = newRuntime(FakeSshConnector(Result.success(SshConnectResult(session))))
        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()
        assertEquals(TeardownState.Idle, runtime.teardownState.value)

        runtime.disconnect(userInitiated = true, finalState = ConnectionState.Disconnected)
        advanceUntilIdle()

        val final = runtime.teardownState.value
        assertTrue(final is TeardownState.Complete)
        assertEquals(ConnectionState.Disconnected, (final as TeardownState.Complete).finalState)
        runtime.dispose()
    }

    @Test
    fun teardownState_completesWithErrorFinalState() = runTest(dispatcher) {
        // Issue #15: an inbound-failure or watchdog teardown passes
        // finalState = ConnectionState.Error(...); the teardownState
        // seam must carry that error forward so the UI's mirror can
        // render the same Error message without inspecting _state.
        val session = mockSession()
        val runtime = newRuntime(FakeSshConnector(Result.success(SshConnectResult(session))))
        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()

        runtime.disconnect(
            userInitiated = false,
            finalState = ConnectionState.Error("Remote host closed the connection."),
        )
        advanceUntilIdle()

        val final = runtime.teardownState.value
        assertTrue(final is TeardownState.Complete)
        assertEquals(
            ConnectionState.Error("Remote host closed the connection."),
            (final as TeardownState.Complete).finalState,
        )
        runtime.dispose()
    }

    @Test
    fun teardownState_duplicateDisconnectDoesNotPublishSecondTearingDown() = runTest(dispatcher) {
        // Issue #15: a second [disconnect] while the first is in flight
        // (or already settled) is CAS-serialized — the second stamp is
        // suppressed so observers see at most one TearingDown → Complete
        // transition per accepted intent. Pins the [teardownGuard] CAS
        // contract through the new StateFlow.
        val session = mockSession()
        val runtime = newRuntime(FakeSshConnector(Result.success(SshConnectResult(session))))
        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()

        runtime.disconnect()
        runtime.disconnect()  // CAS-fails; no second TearingDown stamp
        runtime.disconnect()  // CAS-fails; no second TearingDown stamp
        advanceUntilIdle()

        assertTrue(runtime.teardownState.value is TeardownState.Complete)
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

        // Issue #15 deferred half: disconnect() hops to ioDispatcher via
        // withContext — independent of ioScope being cancelled. The
        // teardown still runs (sshj close on Dispatchers.IO is unaffected
        // by ioScope cancellation), and _state reaches Disconnected. This
        // matches the OLD behavior of this test (pre-#15-deferred-half).
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

    // ---- Issue #17: KeepAliveNudgeRegistry bind/clear coverage ----

    @Test
    fun connect_success_bindsKeepAliveNudgeToRegistry() = runTest(dispatcher) {
        // The runtime's `handleConnectSuccess` must put the
        // `SshConnectResult.keepAliveNudge` into the process-wide
        // registry BEFORE starting the FGS, so the service's first
        // tick (≤ 3 s later) already sees a live nudge.
        KeepAliveNudgeRegistry.set(null)
        val session = mockSession()
        val nudge = KeepAliveNudge { true }
        val connector = FakeSshConnector(
            Result.success(SshConnectResult(session, keepAliveNudge = nudge)),
        )
        val runtime = newRuntime(connector)

        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()

        assertSame(
            "handleConnectSuccess must bind the connect result's " +
                "nudge into the registry before returning",
            nudge,
            KeepAliveNudgeRegistry.get(),
        )
        runtime.disconnect()
        advanceUntilIdle()
        assertNull(
            "After disconnect the registry must be cleared (covered by " +
                "disconnect_clearsRegistry below; asserted here too so " +
                "this test is self-contained)",
            KeepAliveNudgeRegistry.get(),
        )
        runtime.dispose()
    }

    @Test
    fun disconnect_clearsRegistry() = runTest(dispatcher) {
        // Standalone pin for the teardown unbind — the test above
        // couples bind + unbind in one flow, this one isolates the
        // unbind path so a regression in `teardownInternal` is caught
        // independently of the bind coverage.
        val session = mockSession()
        val connector = FakeSshConnector(Result.success(SshConnectResult(session)))
        val runtime = newRuntime(connector)
        runtime.connect("h", 22, "u", dummyAuth)
        advanceUntilIdle()
        // Some non-null nudge must be bound (SshConnectResult default
        // is null; the test's FakeSshConnector passes that through).
        // `disconnect` must clear it regardless of the prior value.
        KeepAliveNudgeRegistry.set(KeepAliveNudge { true })

        runtime.disconnect()
        advanceUntilIdle()

        assertNull(
            "disconnect must clear KeepAliveNudgeRegistry (Issue #17)",
            KeepAliveNudgeRegistry.get(),
        )
        runtime.dispose()
    }

    @Test
    fun abandonHandshake_clearsRegistry() = runTest(dispatcher) {
        // The handshake-epoch-invalidation path (a Disconnect arrives
        // mid-handshake) must clear the registry before the FGS is
        // stopped, mirroring the order in `teardownInternal`. This is
        // the trickier of the three registry paths because the connect
        // itself succeeded — there is a real SshConnectResult to clean
        // up — and a runtime disconnect also fires immediately after.
        val session = mockSession()
        val handshakeEntered = CompletableDeferred<Unit>()
        val continueHandshake = CompletableDeferred<Unit>()
        val connector = object : SshConnector {
            override suspend fun connect(
                host: String,
                port: Int,
                username: String,
                auth: Auth,
            ): Result<SshConnectResult> {
                handshakeEntered.complete(Unit)
                continueHandshake.await()
                return Result.success(
                    SshConnectResult(
                        session = session,
                        keepAliveNudge = KeepAliveNudge { true },
                    ),
                )
            }

            override fun disconnect(userInitiated: Boolean) = Unit
        }
        val runtime = newRuntime(connector)

        KeepAliveNudgeRegistry.set(null)
        val connectJob = async { runtime.connect("h", 22, "u", dummyAuth) }
        handshakeEntered.await()

        // Disconnect during handshake — the connector returns success
        // eventually, but the runtime must discard it via
        // `abandonHandshake`, which now also clears the registry.
        runtime.disconnect(userInitiated = true)
        advanceUntilIdle()
        continueHandshake.complete(Unit)
        connectJob.await()
        advanceUntilIdle()

        assertNull(
            "abandonHandshake must clear the registry even when the " +
                "handshake later returns success (Issue #17)",
            KeepAliveNudgeRegistry.get(),
        )
        runtime.dispose()
    }
}
