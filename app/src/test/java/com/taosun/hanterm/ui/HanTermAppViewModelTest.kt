package com.taosun.hanterm.ui

import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import com.taosun.hanterm.data.prefs.AppPreferences
import com.taosun.hanterm.ssh.ConnectionRuntime
import com.taosun.hanterm.ssh.ConnectionState
import com.taosun.hanterm.ssh.IdleConnectionView
import com.taosun.hanterm.ssh.SshConnectResult
import com.taosun.hanterm.ssh.SshConnector
import com.taosun.hanterm.ssh.SshSession
import com.taosun.hanterm.ssh.auth.Auth
import io.mockk.coEvery
import io.mockk.every
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
        val keysDir = java.io.File(context.filesDir, "keys").also { it.mkdirs() }
        java.io.File(keysDir, "test_key.pem").writeText("fake-key")
        prefs.privateKeyName = "test_key.pem"
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
        viewModel.dispose()
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
        awaitTeardown(viewModel)

        verify { session.close(userInitiated = true) }
        assertFalse(viewModel.connectionView.value.isLive)
        assertTrue(viewModel.connectionView.value is IdleConnectionView)
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
        assertFalse(viewModel.connectionView.value.isLive)
        viewModel.dispose()
    }

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
        every { session.lastCloseReason } returns com.taosun.hanterm.ssh.SessionCloseReason.RemoteEof
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
            profile = com.taosun.hanterm.data.profile.ConnectionProfiles.create(context, prefs),
            runtime = runtime,
            uiScope = scope,
            connectionState = mutableStateOf(ConnectionState.Disconnected),
            showTerminal = mutableStateOf(false),
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
