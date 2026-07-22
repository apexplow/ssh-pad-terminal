package com.taosun.hanterm.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.taosun.hanterm.ssh.RemoteCommandExecutor
import com.taosun.hanterm.ssh.RemoteCommandResult
import com.taosun.hanterm.terminal.ShellIntegrationState
import com.taosun.hanterm.terminal.ShellPhase
import com.taosun.hanterm.terminal.TerminalEndpoint
import com.taosun.hanterm.terminal.TmuxSessionSource
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TmuxDrawerUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<TestActivity>()

    @Test
    fun unknownIntegration_showsInstallActionsAndDisablesSession() {
        composeTestRule.setContent {
            TmuxDrawer(
                source = source(),
                open = true,
                shellIntegrationState = ShellIntegrationState.Unknown,
                onAttachStarted = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("复制 Bash 安装命令").assertIsEnabled()
        composeTestRule.onNodeWithText("复制 Zsh 安装命令").assertIsEnabled()
        composeTestRule.onNodeWithText("main").assertIsNotEnabled()
    }

    @Test
    fun busyShell_allowsViewingButBlocksSelection() {
        composeTestRule.setContent {
            TmuxDrawer(
                source = source(),
                open = true,
                shellIntegrationState = state(ShellPhase.BUSY),
                onAttachStarted = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("当前前台程序正在占用输入", substring = true)
            .assertTextContains("agent/TUI", substring = true)
        composeTestRule.onNodeWithText("main").assertIsNotEnabled()
    }

    @Test
    fun readyShell_enablesSessionSelection() {
        composeTestRule.setContent {
            TmuxDrawer(
                source = source(),
                open = true,
                shellIntegrationState = state(ShellPhase.READY),
                onAttachStarted = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNode(hasText("main") and hasClickAction())
            .assertIsEnabled()
    }

    @Test
    fun detachAction_isAlwaysVisibleWithoutShellIntegration() {
        composeTestRule.setContent {
            TmuxDrawer(
                source = source(),
                open = true,
                shellIntegrationState = ShellIntegrationState.Unknown,
                onAttachStarted = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNode(
            hasText("脱离当前 tmux（Ctrl+B, D）") and hasClickAction(),
        )
            .assertIsEnabled()
    }

    @Test
    fun backWhileOpen_dismissesDrawer() {
        var dismissed = false
        composeTestRule.setContent {
            TmuxDrawer(
                source = source(),
                open = true,
                shellIntegrationState = state(ShellPhase.READY),
                onAttachStarted = {},
                onDismiss = { dismissed = true },
            )
        }

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeTestRule.waitUntil { dismissed }
        assertTrue(dismissed)
    }

    private fun source(
        endpoint: RecordingEndpoint = RecordingEndpoint(),
        sessionName: String = "main",
    ): TmuxSessionSource {
        val executor = object : RemoteCommandExecutor {
            override suspend fun execute(command: String): Result<RemoteCommandResult> {
                return Result.success(
                    RemoteCommandResult(
                        stdout = "${'$'}4|2|detached||$sessionName\n".toByteArray(),
                        stderr = byteArrayOf(),
                        exitStatus = 0,
                    ),
                )
            }
        }
        return TmuxSessionSource(
            endpoint,
            executor,
            ioDispatcher = Dispatchers.Unconfined,
            pollDelay = {},
        )
    }

    private fun state(phase: ShellPhase) = ShellIntegrationState(
        phase = phase,
        inTmux = false,
        sessionId = null,
        tmuxPrefix = "C-b",
    )

    private class RecordingEndpoint : TerminalEndpoint {
        val writes = mutableListOf<ByteArray>()
        override fun write(bytes: ByteArray) {
            writes += bytes.copyOf()
        }
    }
}
