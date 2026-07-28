package com.apexplow.hanterm.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.apexplow.hanterm.ui.TestActivity
import com.apexplow.hanterm.data.prefs.AppPreferences
import com.apexplow.hanterm.ssh.SessionCloseReason
import com.apexplow.hanterm.ssh.SshConnectResult
import com.apexplow.hanterm.ssh.SshConnector
import com.apexplow.hanterm.ssh.SshSession
import com.apexplow.hanterm.ssh.auth.Auth
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
class HanTermAppUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<TestActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun setUp() {
        (context as? com.apexplow.hanterm.HanTermApplication)?.clearConnectionRuntimeForTests()
        AppPreferences(context).clear()
        AppPreferences(context).apply {
            host = "example.com"
            port = 22
            username = "test"
            privateKeyName = "test_key.pem"
        }
        val keysDir = File(context.filesDir, "keys").also { it.mkdirs() }
        File(keysDir, "test_key.pem").writeText("fake-key")
    }

    @Test
    fun connectFlow_rendersConnectedStatus() {
        val session = mockSession()
        val connector = FakeSshConnector(Result.success(SshConnectResult(session)))

        composeTestRule.setContent {
            HanTermApp(
                connector = connector,
                ioDispatcher = Dispatchers.Unconfined,
                autoShowTerminalOnConnect = false,
                isNetworkAvailable = { true },
            )
        }

        composeTestRule.onNodeWithText("Connect").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Connected to test@example.com:22").assertIsDisplayed()
    }

    @Test
    fun disconnectFlow_rendersDisconnectedStatus() {
        val session = mockSession()
        val connector = FakeSshConnector(Result.success(SshConnectResult(session)))

        composeTestRule.setContent {
            HanTermApp(
                connector = connector,
                ioDispatcher = Dispatchers.Unconfined,
                autoShowTerminalOnConnect = false,
                isNetworkAvailable = { true },
            )
        }

        composeTestRule.onNodeWithText("Connect").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Disconnect").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Disconnected").assertIsDisplayed()
        verify { session.close(userInitiated = true) }
    }

    @Test
    fun connectFailure_rendersErrorOverlay() {
        val connector = FakeSshConnector(Result.failure(IllegalStateException("boom")))

        composeTestRule.setContent {
            HanTermApp(
                connector = connector,
                ioDispatcher = Dispatchers.Unconfined,
                autoShowTerminalOnConnect = false,
                isNetworkAvailable = { true },
            )
        }

        composeTestRule.onNodeWithText("Connect").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Error: boom").assertIsDisplayed()
    }

    private fun mockSession(): SshSession {
        val session = mockk<SshSession>(relaxed = true)
        coEvery { session.readInto(any()) } coAnswers { awaitCancellation() }
        every { session.lastCloseReason } returns SessionCloseReason.UserInitiated
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

        override fun disconnect(userInitiated: Boolean) {
        }
    }
}
