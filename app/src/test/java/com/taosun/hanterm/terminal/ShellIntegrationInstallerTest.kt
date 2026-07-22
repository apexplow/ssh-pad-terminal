package com.taosun.hanterm.terminal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShellIntegrationInstallerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun bashCommand_installsScriptAndIdempotentRcSource() {
        val command = ShellIntegrationInstaller.buildInstallCommand(
            context,
            SupportedShell.BASH,
        )

        assertTrue(command.contains("hanterm/bash.sh"))
        assertTrue(command.contains(".bashrc"))
        assertTrue(command.contains("__hanterm_emit_state"))
        assertTrue(command.contains("grep -Fqx"))
    }

    @Test
    fun zshCommand_installsHookScript() {
        val command = ShellIntegrationInstaller.buildInstallCommand(
            context,
            SupportedShell.ZSH,
        )

        assertTrue(command.contains("hanterm/zsh.sh"))
        assertTrue(command.contains(".zshrc"))
        assertTrue(command.contains("add-zsh-hook"))
    }

    @Test
    fun installCommand_separatesSourceFromRcWithoutTrailingNewline() {
        val home = Files.createTempDirectory("hanterm-shell-install")
        val rc = home.resolve(".bashrc")
        Files.write(rc, "export EXISTING=1".toByteArray())
        val command = ShellIntegrationInstaller.buildInstallCommand(
            context,
            SupportedShell.BASH,
        )
        val process = ProcessBuilder("bash", "-c", command)
            .redirectErrorStream(true)
            .apply { environment()["HOME"] = home.toString() }
            .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(output, 0, process.waitFor())
        assertTrue(
            Files.readAllBytes(rc).toString(Charsets.UTF_8)
                .startsWith("export EXISTING=1\n. "),
        )
    }
}
