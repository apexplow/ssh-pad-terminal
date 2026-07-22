package com.taosun.hanterm.terminal

import com.taosun.hanterm.ssh.RemoteCommandExecutor
import com.taosun.hanterm.ssh.RemoteCommandResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lists tmux sessions over a side-band SSH exec channel and uses the
 * interactive endpoint only for an explicitly selected attach.
 *
 * Refresh never writes to [endpoint]. This remains safe while the visible
 * terminal is owned by an agent, editor, pager, REPL, or any other TUI.
 */
class TmuxSessionSource(
    private val endpoint: TerminalEndpoint,
    private val remoteCommandExecutor: RemoteCommandExecutor,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pollDelay: suspend (Long) -> Unit = ::delay,
) {
    private val refreshMutex = Mutex()

    suspend fun refresh(): Result<List<TmuxSession>> = refreshMutex.withLock {
        remoteCommandExecutor.execute(TMUX_LIST_COMMAND).fold(
            onSuccess = ::interpretResult,
            onFailure = { Result.failure(it) },
        )
    }

    /**
     * Builds the shell command (no trailing Enter) to switch/attach to
     * [sessionName]. Prefer [switchTo] for live use — it applies the
     * paste-gap Enter split. This helper exists for tests that assert on
     * the wire shape of the command text alone.
     *
     * Leading space: see class kdoc (history ignore). Shell-quoting via
     * single quotes: tmux session names can contain spaces and a few
     * punctuation chars but cannot contain `'` (tmux refuses them at
     * create time), so single-quote wrapping is the correct escape
     * without further translation.
     */
    fun switchCommand(target: String): ByteArray {
        val quoted = "'${target.replace("'", "'\\''")}'"
        val cmd = " tmux switch-client -t $quoted 2>/dev/null || tmux attach -t $quoted"
        return cmd.toByteArray(Charsets.UTF_8)
    }

    /**
     * Submits [switchCommand] with the same gap+Enter split as [refresh],
     * so the switch works inside a tmux client (paste detector) as well as
     * from a bare shell.
     */
    suspend fun switchTo(target: String) = withContext(ioDispatcher) {
        submitLine(switchCommand(target))
    }

    /**
     * Sends the configured tmux prefix followed by `d`. This is only called
     * from the explicit "退出当前 session" button after shell integration has
     * reported that the visible terminal is inside tmux.
     */
    suspend fun detach(prefix: String?): Result<Unit> = withContext(ioDispatcher) {
        val prefixBytes = TmuxPrefixEncoder.encode(prefix)
            ?: return@withContext Result.failure(
                IllegalArgumentException("unsupported tmux prefix: ${prefix ?: "unknown"}"),
            )
        runCatching {
            endpoint.write(prefixBytes)
            pollDelay(SWITCH_ENTER_GAP_MS)
            endpoint.write(byteArrayOf('d'.code.toByte()))
        }
    }

    private suspend fun submitLine(line: ByteArray) {
        endpoint.write(line)
        pollDelay(SWITCH_ENTER_GAP_MS)
        endpoint.write(ENTER_BYTES)
    }

    private fun interpretResult(result: RemoteCommandResult): Result<List<TmuxSession>> {
        val stdout = result.stdout.toString(Charsets.UTF_8)
        val stderr = result.stderr.toString(Charsets.UTF_8)
        return when (result.exitStatus) {
            0 -> Result.success(TmuxSessionParser.parse(stdout))
            1 -> {
                if (stderr.isBlank() || NO_SERVER_MESSAGES.any { stderr.contains(it, ignoreCase = true) }) {
                    Result.success(emptyList())
                } else {
                    Result.failure(IllegalStateException(stderrSummary(stderr)))
                }
            }
            127 -> Result.failure(
                IllegalStateException("tmux is not available in the non-interactive SSH PATH"),
            )
            null -> Result.failure(
                IllegalStateException(
                    result.exitSignal?.let { "tmux query ended by signal $it" }
                        ?: "tmux query ended without an exit status",
                ),
            )
            else -> Result.failure(
                IllegalStateException(
                    stderrSummary(stderr).ifBlank {
                        "tmux query failed with exit status ${result.exitStatus}"
                    },
                ),
            )
        }
    }

    private fun stderrSummary(stderr: String): String =
        stderr.filterNot { it.code in 0x00..0x1F || it.code in 0x7F..0x9F }
            .trim()
            .take(MAX_ERROR_CHARS)

    companion object {
        private const val TMUX_FORMAT =
            "#{session_id}|#{session_windows}|#{?session_attached,attached,detached}||#{session_name}"

        /**
         * Preserve the server-provided PATH and add common user/package paths.
         * We intentionally do not run a login shell: profile side effects and
         * prompt output would make a read-only query unpredictable.
         */
        internal const val TMUX_LIST_COMMAND =
            "PATH=\"\${PATH:+\${PATH}:}\${HOME}/.local/bin:\${HOME}/bin:\${HOME}/.nix-profile/bin:" +
                "/usr/local/bin:/usr/bin:/bin:/opt/homebrew/bin:" +
                "/home/linuxbrew/.linuxbrew/bin:/run/current-system/sw/bin\" " +
                "command tmux list-sessions -F '$TMUX_FORMAT'"

        private val ENTER_BYTES: ByteArray = byteArrayOf('\r'.code.toByte())

        internal const val SWITCH_ENTER_GAP_MS: Long = 50L
        private const val MAX_ERROR_CHARS = 240
        private val NO_SERVER_MESSAGES = listOf(
            "no server running",
            "no sessions",
            "failed to connect to server",
        )
    }
}
