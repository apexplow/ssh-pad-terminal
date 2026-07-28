package com.apexplow.hanterm.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.apexplow.hanterm.data.prefs.AppPreferences
import com.apexplow.hanterm.ssh.ConnectionRuntime
import com.apexplow.hanterm.ssh.ConnectionState
import com.apexplow.hanterm.ssh.IdleConnectionView
import com.apexplow.hanterm.ssh.SshConnectResult
import com.apexplow.hanterm.ssh.SshConnector
import com.apexplow.hanterm.ssh.SshSession
import com.apexplow.hanterm.ssh.auth.Auth
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
@OptIn(ExperimentalCoroutinesApi::class)
class HanTermAppViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = AppPreferences(context)
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        prefs.clear()
        prefs.host = "example.com"
        prefs.port = 22
        prefs.username = "test"
        val keysDir = java.io.File(context.filesDir, "keys").also { it.mkdirs() }
        java.io.File(keysDir, "test_key.pem").writeText("fake-key")
        prefs.privateKeyName = "test_key.pem"
        // viewModelScope is bound to Dispatchers.Main — redirect to the
        // test dispatcher so `viewModelScope.launch { ... }` runs on the
        // virtual clock and the test can `advanceUntilIdle()` it.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startConnect_success_publishesLiveConnectionView() = runTest(dispatcher) {
        val session = mockSession()
        val connector = FakeSshConnector(Result.success(SshConnectResult(session)))
        val viewModel = createViewModel(this, connector)

        viewModel.startConnect()
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected("test@example.com:22"), viewModel.connectionState.value)
        assertTrue(viewModel.connectionView.value.isLive)
    }

    @Test
    fun startConnect_failure_fallsBackToIdleView() = runTest(dispatcher) {
        val connector = FakeSshConnector(Result.failure(IllegalStateException("boom")))
        val viewModel = createViewModel(this, connector)

        viewModel.startConnect()
        advanceUntilIdle()

        assertTrue(viewModel.connectionState.value is ConnectionState.Error)
        assertFalse(viewModel.connectionView.value.isLive)
        assertTrue(viewModel.connectionView.value is IdleConnectionView)
    }

    @Test
    fun disconnect_marksUserInitiatedAndTearsDown() = runTest(dispatcher) {
        val session = mockSession()
        val connector = FakeSshConnector(Result.success(SshConnectResult(session)))
        val viewModel = createViewModel(this, connector)

        viewModel.startConnect()
        advanceUntilIdle()
        viewModel.disconnect()
        awaitTeardown(viewModel)

        verify { session.close(userInitiated = true) }
        assertFalse(viewModel.connectionView.value.isLive)
        assertTrue(viewModel.connectionView.value is IdleConnectionView)
    }

    @Test
    fun onSessionClosed_teardownAndErrorState() = runTest(dispatcher) {
        val session = mockSession()
        val connector = FakeSshConnector(Result.success(SshConnectResult(session)))
        val viewModel = createViewModel(this, connector)

        viewModel.startConnect()
        advanceUntilIdle()
        viewModel.onSessionClosed("reason", com.apexplow.hanterm.ssh.SessionCloseReason.RemoteEof)
        awaitTeardown(viewModel)

        assertTrue(viewModel.connectionState.value is ConnectionState.Error)
        assertFalse(viewModel.connectionView.value.isLive)
    }

    // -----------------------------------------------------------------------
    // Issue #41 — font size + showTerminal SavedStateHandle coverage
    // -----------------------------------------------------------------------

    @Test
    fun fontSize_initialValueComesFromAppPreferences() = runTest(dispatcher) {
        prefs.fontSize = 22
        val viewModel = createViewModel(this, FakeSshConnector(Result.failure(IllegalStateException("x"))))
        advanceUntilIdle()
        assertEquals(22, viewModel.fontSize.value)
    }

    @Test
    fun fontSize_initialValueIsClampedToValidRange() = runTest(dispatcher) {
        prefs.fontSize = 9999  // out of [MIN, MAX]; AppPreferences' getter clamps
        val viewModel = createViewModel(this, FakeSshConnector(Result.failure(IllegalStateException("x"))))
        advanceUntilIdle()
        assertEquals(AppPreferences.MAX_FONT_SIZE, viewModel.fontSize.value)
    }

    @Test
    fun fontSize_bridgeRequestUpdatesStateAndPersists() = runTest(dispatcher) {
        val flow = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 4)
        val viewModel = createViewModel(
            this,
            FakeSshConnector(Result.failure(IllegalStateException("x"))),
            fontSizeRequests = flow,
        )
        advanceUntilIdle()

        flow.tryEmit(24)
        advanceUntilIdle()
        assertEquals(24, viewModel.fontSize.value)
        assertEquals(24, prefs.fontSize)

        flow.tryEmit(28)
        advanceUntilIdle()
        assertEquals(28, viewModel.fontSize.value)
        assertEquals(28, prefs.fontSize)
    }

    @Test
    fun fontSize_incomingSizeIsDefensivelyClamped() = runTest(dispatcher) {
        val flow = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 4)
        val viewModel = createViewModel(
            this,
            FakeSshConnector(Result.failure(IllegalStateException("x"))),
            fontSizeRequests = flow,
        )
        advanceUntilIdle()

        flow.tryEmit(0)   // below MIN
        advanceUntilIdle()
        assertEquals(AppPreferences.MIN_FONT_SIZE, viewModel.fontSize.value)

        flow.tryEmit(9999) // above MAX
        advanceUntilIdle()
        assertEquals(AppPreferences.MAX_FONT_SIZE, viewModel.fontSize.value)
    }

    @Test
    fun showTerminal_restoredFromSavedStateHandleWhenRuntimeIsLive() = runTest(dispatcher) {
        val session = mockSession()
        val connector = FakeSshConnector(Result.success(SshConnectResult(session)))
        // Pre-populate the saved state handle with a "show the terminal"
        // intent. The viewModel init must respect it on construction.
        val viewModel = createViewModel(
            this,
            connector,
            savedStateHandle = SavedStateHandle(mapOf("showTerminal" to true)),
        )
        // Need a live runtime before the gate can pass. Drive a connect.
        viewModel.startConnect()
        advanceUntilIdle()
        // After connect, runtime.view is live and the saved showTerminal=true
        // wins. The init gate (&& runtime.view.isLive) only matters when
        // the runtime never went live.
        assertTrue(viewModel.connectionView.value.isLive)
        assertTrue(viewModel.showTerminal.value)
    }

    @Test
    fun showTerminal_staleRestoredValueIsRejectedWhenRuntimeIsIdle() = runTest(dispatcher) {
        // Failure connector → no live session. Saved state says "show
        // terminal" but the runtime never went live. The gate must force
        // showTerminal to false so we don't paint a stale terminal over
        // a dead process.
        val viewModel = createViewModel(
            this,
            FakeSshConnector(Result.failure(IllegalStateException("no"))),
            savedStateHandle = SavedStateHandle(mapOf("showTerminal" to true)),
        )
        advanceUntilIdle()
        assertFalse(viewModel.connectionView.value.isLive)
        assertFalse(viewModel.showTerminal.value)
    }

    @Test
    fun showTerminal_setShowTerminalUpdatesStateAndSavedState() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val viewModel = createViewModel(
            this,
            FakeSshConnector(Result.failure(IllegalStateException("x"))),
            savedStateHandle = saved,
        )
        advanceUntilIdle()

        assertFalse(viewModel.showTerminal.value)

        viewModel.setShowTerminal(true)
        assertTrue(viewModel.showTerminal.value)
        assertEquals(true, saved.get<Boolean>("showTerminal"))

        viewModel.setShowTerminal(false)
        assertFalse(viewModel.showTerminal.value)
        assertEquals(false, saved.get<Boolean>("showTerminal"))
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private suspend fun kotlinx.coroutines.test.TestScope.awaitTeardown(
        viewModel: HanTermAppViewModel,
    ) {
        repeat(100) {
            advanceUntilIdle()
            if (!viewModel.connectionView.value.isLive) {
                return
            }
            withContext(Dispatchers.Default) { delay(20) }
        }
        advanceUntilIdle()
    }

    private fun mockSession(): SshSession {
        val session = mockk<SshSession>(relaxed = true)
        coEvery { session.readInto(any()) } coAnswers { awaitCancellation() }
        every { session.lastCloseReason } returns com.apexplow.hanterm.ssh.SessionCloseReason.RemoteEof
        return session
    }

    private fun createViewModel(
        scope: kotlinx.coroutines.test.TestScope,
        connector: SshConnector,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        fontSizeRequests: MutableSharedFlow<Int> = MutableSharedFlow(),
    ): HanTermAppViewModel {
        val runtime = ConnectionRuntime(
            context = context,
            connector = connector,
            ioDispatcher = dispatcher,
        )
        return HanTermAppViewModel(
            application = context,
            prefs = prefs,
            profile = com.apexplow.hanterm.data.profile.ConnectionProfiles.create(context, prefs),
            runtime = runtime,
            savedStateHandle = savedStateHandle,
            fontSizeRequests = fontSizeRequests,
            isNetworkAvailable = { true },
        )
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

        override fun disconnect(userInitiated: Boolean) {
            // no-op for unit tests
        }
    }
}
