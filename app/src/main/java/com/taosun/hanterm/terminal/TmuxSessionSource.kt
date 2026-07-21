package com.taosun.hanterm.terminal

import com.termux.terminal.TerminalEmulator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Wires the tmux session list to the live terminal — injects a probe,
 * reads back the result from the emulator's screen buffer, parses it
 * into [TmuxSession]s.
 *
 * ## Probe protocol
 *
 * Two `printf` calls bracket a single `tmux list-sessions` invocation:
 *
 * ```text
 * printf '__HANTERM_TMUX_BEGIN__\n'
 * tmux list-sessions -F '...col1|col2|col3|col4...' 2>/dev/null
 * printf '__HANTERM_TMUX_END__\n'
 * ```
 *
 * The 2>/dev/null suppresses tmux's "no server running" noise when the
 * remote has no tmux server yet (still a valid result — drawer shows
 * "no sessions"). The trailing printf guarantees the END_SENTINEL lands
 * even when `tmux list-sessions` exits non-zero.
 *
 * ## Read protocol
 *
 * The probe writes to the same byte stream the user types into, so the
 * result is read back by **polling the emulator's screen buffer** for
 * [TmuxSessionParser.END_SENTINEL] every [POLL_INTERVAL_MS] until the
 * [PROBE_TIMEOUT_MS] ceiling is reached. We do not instrument
 * `SshSession.readInto` to tee bytes — that would couple the SSH layer
 * to the tmux feature and force every test that reads bytes to mock
 * a tmux hook. Reading from the emulator is the existing integration
 * point ([ScrollbackController] does the same via reflection for its
 * inner-top-row tracking), and the public API
 * `TerminalBuffer.getTranscriptTextWithoutJoinedLines()` is stable
 * across the v0.118.0 baseline.
 *
 * ## Threading
 *
 * [refresh] hops to [ioDispatcher] (default `Dispatchers.IO`) because
 * [TerminalEmulator.getScreen] is safe to call from any thread, but we
 * still want the polling to be off the Compose UI thread. The `endpoint.write`
 * call routes through [SshSession.write], which itself hops to a private
 * single-thread executor — that's fine, our caller just sees "bytes handed
 * off to SSH".
 *
 * ## Switch command
 *
 * [switchCommand] emits `tmux switch-client -t <name> 2>/dev/null || tmux attach -t <name>\r`:
 *   - inside a tmux client, `switch-client` switches immediately;
 *   - outside, `switch-client` exits non-zero ("can't find client") and
 *     the `||` falls through to `attach`, which attaches the current SSH
 *     shell to the named session (Ctrl+B D to leave).
 * `2>/dev/null` keeps the "can't find client" stderr off the terminal
 * when we're inside tmux and switch-client succeeds silently.
 */
class TmuxSessionSource(
    private val endpoint: TerminalEndpoint,
    private val emulatorProvider: () -> TerminalEmulator?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Overridable so unit tests can short-circuit the polling loop without
     * actually waiting 800ms — see TmuxSessionSourceTest.
     */
    private val pollDelay: suspend (Long) -> Unit = ::delay,
) {

    /**
     * Probes tmux, waits for the END sentinel in the emulator's screen
     * buffer, parses the bracketed region.
     *
     * Returns:
     *   - `Result.failure` when the emulator is unavailable (no connection
     *     yet, or the wrapper's bridge torn down mid-flight);
     *   - `Result.success(emptyList())` when tmux is not installed, no
     *     server is running, or the probe timed out without seeing
     *     [TmuxSessionParser.END_SENTINEL];
     *   - `Result.success(list)` otherwise.
     *
     * The caller is responsible for rendering the empty-list branch
     * ("no sessions / tmux not detected") — distinguishing it from
     * "tmux missing" would require parsing stderr, which the
     * `2>/dev/null` deliberately discards.
     */
    suspend fun refresh(): Result<List<TmuxSession>> = withContext(ioDispatcher) {
        val emulator = emulatorProvider()
            ?: return@withContext Result.failure<List<TmuxSession>>(
                IllegalStateException("terminal emulator unavailable"),
            )
        runCatching {
            endpoint.write(PROBE_BYTES)
            pollForEnd(emulator)
            TmuxSessionParser.parse(currentTranscript(emulator))
        }
    }

    /**
     * Builds the wire bytes to switch (or attach) to [sessionName].
     *
     * Shell-quoting via single quotes: tmux session names can contain
     * spaces and a few punctuation chars but cannot contain `'` (tmux
     * refuses them at create time), so single-quote wrapping is the
     * correct escape without further translation. The trailing `\r`
     * mirrors the existing KEYCODE_ENTER / SnippetPayload convention
     * (see SNP-SEND-01..02 kdoc) — the remote PTY's line discipline
     * converts CR into the newline the shell needs.
     */
    fun switchCommand(sessionName: String): ByteArray {
        val quoted = "'${sessionName.replace("'", "'\\''")}'"
        val cmd = "tmux switch-client -t $quoted 2>/dev/null || tmux attach -t $quoted\r"
        return cmd.toByteArray(Charsets.UTF_8)
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private suspend fun pollForEnd(emulator: TerminalEmulator) {
        val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (currentTranscript(emulator).contains(TmuxSessionParser.END_SENTINEL)) return
            pollDelay(POLL_INTERVAL_MS)
        }
    }

    private fun currentTranscript(emulator: TerminalEmulator): String =
        emulator.screen.transcriptTextWithoutJoinedLines

    companion object {
        /**
         * Sentinel pair echoed by the probe. Keep in lockstep with
         * [TmuxSessionParser.BEGIN_SENTINEL] / [TmuxSessionParser.END_SENTINEL].
         */
        private const val PROBE_BEGIN_SENTINEL = TmuxSessionParser.BEGIN_SENTINEL
        private const val PROBE_END_SENTINEL = TmuxSessionParser.END_SENTINEL

        /**
         * `-F` template matches [TmuxSessionParser] expectations
         * (4 pipe-separated fields, second column is windows count).
         */
        private const val PROBE_FORMAT =
            "#{session_name}|#{session_windows}|#{?session_attached,attached,detached}|#{session_activity_string}"

        private val PROBE_BYTES: ByteArray = buildString {
            append("printf '")
            append(PROBE_BEGIN_SENTINEL)
            append("\\n'\ntmux list-sessions -F '")
            append(PROBE_FORMAT)
            append("' 2>/dev/null\nprintf '")
            append(PROBE_END_SENTINEL)
            append("\\n'\n")
        }.toByteArray(Charsets.UTF_8)

        /** 800ms comfortably absorbs 24-row output over a 200ms-RTT link. */
        private const val POLL_INTERVAL_MS: Long = 100L

        /** 3 seconds: a slow remote (cellular + sshd + tmux) is comfortably inside. */
        private const val PROBE_TIMEOUT_MS: Long = 3_000L
    }
}
