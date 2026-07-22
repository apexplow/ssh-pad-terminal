package com.taosun.hanterm.ui

import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import com.taosun.hanterm.data.prefs.AppPreferences
import com.taosun.hanterm.ssh.ActiveSshSessionStore
import com.taosun.hanterm.ssh.ConnectionRuntime
import com.taosun.hanterm.ssh.ConnectionState
import com.taosun.hanterm.ssh.SshConnectResult
import com.taosun.hanterm.ssh.SshConnector
import com.taosun.hanterm.ssh.SshSession
import com.taosun.hanterm.ssh.auth.Auth
import com.taosun.hanterm.terminal.MockEchoSession
import com.taosun.hanterm.terminal.PtyBridgeEndpoint
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class HanTermAppViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val prefs = AppPreferences(context)
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        prefs.clear()
        prefs.host = "example.com"
        prefs.port = 22
        prefs.username = "test"
        // Provide a fake private-key file so hasUsableCredentials() passes
        // without needing Android Keystore (which is stubbed under Robolectric).
        val keysDir = java.io.File(context.filesDir, "keys").also { it.mkdirs() }
        java.io.File(keysDir, "test_key.pem").writeText("fake-key")
        prefs.privateKeyName = "test_key.pem"
        ActiveSshSessionStore.clear()
    }

    @Test
    fun startConnect_success_setsActiveSessionBridgeAndEndpoint() = runTest(dispatcher) {
        val session = mockSession()
        val connector = FakeSshConnector(Result.success(SshConnectResult(session)))
        val viewModel = createViewModel(this, connector)

        viewModel.startConnect()
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected("test@example.com:22"), viewModel.connectionState.value)
        assertNotNull(viewModel.activeSession.value)
        assertNotNull(viewModel.bridge.value)
        assertTrue(viewModel.endpoint.value is PtyBridgeEndpoint)
        assertNotNull(viewModel.connectionView.value)
        assertEquals(session, ActiveSshSessionStore.get())
        viewModel.dispose()
    }

    @Test
    fun startConnect_failure_clearsStoreAndFallsBackToMockEchoSession() = runTest(dispatcher) {
        val connector = FakeSshConnector(Result.failure(IllegalStateException("boom")))
        val viewModel = createViewModel(this, connector)

        viewModel.startConnect()
        advanceUntilIdle()

        assertTrue(viewModel.connectionState.value is ConnectionState.Error)
        assertNull(viewModel.activeSession.value)
        assertNull(viewModel.bridge.value)
        assertTrue(viewModel.endpoint.value is MockEchoSession)
        assertNull(ActiveSshSessionStore.get())
        viewModel.dispose()
    }

    @Test
    fun disconnect_marksUserInitiatedAndTearsDown() = runTest(dispatcher) {
        val session = mockSession()
        val connector = FakeSshConnector(Result.success(SshConnectResult(session)))
        val viewModel = createViewModel(this, connector)

        viewModel.startConnect()
        advanceUntilIdle()
        viewModel.disconnect()
        // SshBridgeAdapter's inbound/outbound run on Dispatchers.IO; cancelAndJoin
        // inside runtime.disconnect waits on those real threads. advanceUntilIdle
        // alone returns while cancelAndJoin is still pending — poll wall-clock.
        awaitTeardown(viewModel)

        verify { session.close(userInitiated = true) }
        assertNull(viewModel.activeSession.value)
        assertNull(viewModel.bridge.value)
        assertTrue(viewModel.endpoint.value is MockEchoSession)
        assertNull(ActiveSshSessionStore.get())
        viewModel.dispose()
    }

    @Test
    fun onSessionClosed_teardownAndErrorState() = runTest(dispatcher) {
        val session = mockSession()
        val connector = FakeSshConnector(Result.success(SshConnectResult(session)))
        val viewModel = createViewModel(this, connector)

        viewModel.startConnect()
        advanceUntilIdle()
        viewModel.onSessionClosed("reason", com.taosun.hanterm.ssh.SessionCloseReason.RemoteEof)
        awaitTeardown(viewModel)

        assertTrue(viewModel.connectionState.value is ConnectionState.Error)
        assertNull(viewModel.activeSession.value)
        assertNull(ActiveSshSessionStore.get())
        viewModel.dispose()
    }

    /**
     * Wait until runtime teardown (which joins Dispatchers.IO adapter jobs)
     * has cleared the active session. Interleave wall-clock polls with
     * [advanceUntilIdle] so Compose State mirrors on the test dispatcher
     * can apply between IO completions.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.awaitTeardown(
        viewModel: HanTermAppViewModel,
    ) {
        repeat(100) {
            advanceUntilIdle()
            if (viewModel.activeSession.value == null &&
                ActiveSshSessionStore.get() == null
            ) {
                return
            }
            withContext(Dispatchers.Default) { delay(20) }
        }
        advanceUntilIdle()
    }

    private fun mockSession(): SshSession {
        val session = mockk<SshSession>(relaxed = true)
        coEvery { session.readInto(any()) } coAnswers { awaitCancellation() }
        return session
    }

    private fun createViewModel(
        scope: kotlinx.coroutines.test.TestScope,
        connector: SshConnector,
    ): HanTermAppViewModel {
        val runtime = ConnectionRuntime(
            context = context,
            connector = connector,
            ioDispatcher = dispatcher,
        )
        return HanTermAppViewModel(
            context = context,
            prefs = prefs,
            runtime = runtime,
            uiScope = scope,
            connectionState = mutableStateOf(ConnectionState.Disconnected),
            showTerminal = mutableStateOf(false),
            isNetworkAvailable = { true },
            ioDispatcher = dispatcher,
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
