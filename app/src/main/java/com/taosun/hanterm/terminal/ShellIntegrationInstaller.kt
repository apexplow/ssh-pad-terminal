package com.taosun.hanterm.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.taosun.hanterm.R

enum class SupportedShell(
    val displayName: String,
    internal val resourceId: Int,
    internal val remoteFileName: String,
    internal val rcFileName: String,
) {
    BASH(
        displayName = "Bash",
        resourceId = R.raw.hanterm_shell_integration_bash,
        remoteFileName = "bash.sh",
        rcFileName = ".bashrc",
    ),
    ZSH(
        displayName = "Zsh",
        resourceId = R.raw.hanterm_shell_integration_zsh,
        remoteFileName = "zsh.sh",
        rcFileName = ".zshrc",
    ),
}

/** Builds a self-contained, user-run installer; HanTerm never edits dotfiles itself. */
object ShellIntegrationInstaller {
    fun buildInstallCommand(context: Context, shell: SupportedShell): String {
        val script = context.resources.openRawResource(shell.resourceId)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .trimEnd()
        val integrationPath = "\$HOME/.config/hanterm/${shell.remoteFileName}"
        val sourceLine = ". \"${integrationPath}\""
        return buildString {
            appendLine("mkdir -p \"\$HOME/.config/hanterm\"")
            appendLine("cat > \"${integrationPath}\" <<'__HANTERM_INTEGRATION__'")
            appendLine(script)
            appendLine("__HANTERM_INTEGRATION__")
            append("command grep -Fqx '")
            append(sourceLine)
            append("' \"\$HOME/${shell.rcFileName}\" 2>/dev/null || ")
            // A leading newline keeps the source directive separate when the
            // existing rc file's last line has no line terminator.
            append("printf '\\n%s\\n' '")
            append(sourceLine)
            appendLine("' >> \"\$HOME/${shell.rcFileName}\"")
            append(sourceLine)
        }
    }

    fun copyInstallCommand(context: Context, shell: SupportedShell): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val command = buildInstallCommand(context, shell)
        clipboard.setPrimaryClip(
            ClipData.newPlainText("HanTerm ${shell.displayName} integration", command),
        )
        return true
    }
}
