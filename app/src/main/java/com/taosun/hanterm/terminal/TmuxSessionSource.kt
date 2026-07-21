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
 * Three shell lines (each with paste-gap Enter — see below):
 *
 * ```text
 *  stty -echo 2>/dev/null                          # hide subsequent input echo
 *  printf 'BEGIN\n'; tmux list-sessions -F '...'; printf 'END\n'; history -d $HISTCMD 2>/dev/null || true
 *  stty echo 2>/dev/null                           # restore echo (always)
 * ```
 *
 * Each line starts with a **leading space** so bash `HISTCONTROL=ignorespace`
 * / `ignoreboth` and zsh `HIST_IGNORE_SPACE` skip it. The trailing
 * `history -d $HISTCMD` is a bash belt-and-suspenders when ignorespace is
 * off (zsh ignores the failing builtin via `2>/dev/null`).
 *
 * `stty -echo` is a **separate** line submitted *before* the long probe:
 * echo applies to subsequent input only, so putting `stty -echo` in the
 * same burst as the printf line would still echo the printf text. After
 * `-echo`, the probe body is invisible; BEGIN / session rows / END still
 * print (they are stdout, not input echo) so the transcript parser can
 * read them. `stty echo` always runs in a `finally` so a timeout cannot
 * leave the PTY mute.
 *
 * Why a **single ;-joined probe line** (not three `\n`-separated printf
 * lines): outside tmux the SSH PTY is cooked and bare LF often works as
 * EOL, but inside a tmux client the outer PTY is raw and Enter is CR.
 *
 * Why **Enter is a second write after [PROBE_ENTER_GAP_MS]**: tmux's
 * `assume-paste-time` (default 1 ms) treats a burst of keys as a paste.
 * A single `write(command + '\r')` folds the trailing CR into the paste,
 * so readline inserts the text but does not accept the line. Splitting
 * Enter into its own write after a gap makes tmux treat it as a real
 * keypress.
 *
 * `;` (not `&&`) keeps the END printf running when list-sessions exits
 * non-zero. `2>/dev/null` suppresses "no server running" noise.
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
 * [switchTo] emits a leading-space
 * `tmux switch-client -t <name> 2>/dev/null || tmux attach -t <name>`
 * then the same gap+`\r` Enter split as the probe:
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
     * Overridable so unit tests can short-circuit the polling loop and the
     * paste-gap sleep without actually waiting — see TmuxSessionSourceTest.
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
            submitLine(STTY_MINUS_ECHO)
            try {
                submitLine(PROBE_BODY)
                pollForEnd(emulator)
                TmuxSessionParser.parse(currentTranscript(emulator))
            } finally {
                // Always restore echo — a timed-out probe must not leave
                // the remote PTY with input invisible.
                submitLine(STTY_ECHO)
            }
        }
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
    fun switchCommand(sessionName: String): ByteArray {
        val quoted = "'${sessionName.replace("'", "'\\''")}'"
        val cmd = " tmux switch-client -t $quoted 2>/dev/null || tmux attach -t $quoted"
        return cmd.toByteArray(Charsets.UTF_8)
    }

    /**
     * Submits [switchCommand] with the same gap+Enter split as [refresh],
     * so the switch works inside a tmux client (paste detector) as well as
     * from a bare shell.
     */
    suspend fun switchTo(sessionName: String) = withContext(ioDispatcher) {
        submitLine(switchCommand(sessionName))
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /**
     * Writes [line] (no trailing CR), waits [PROBE_ENTER_GAP_MS], then
     * writes Enter. See class kdoc for the tmux paste-detector rationale.
     */
    private suspend fun submitLine(line: ByteArray) {
        endpoint.write(line)
        pollDelay(PROBE_ENTER_GAP_MS)
        endpoint.write(ENTER_BYTES)
    }

    private suspend fun pollForEnd(emulator: TerminalEmulator) {
        val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            // Exact line match — NOT String.contains. The probe's third
            // command is itself `printf '__HANTERM_TMUX_END__\n'`, and with
            // PTY echo that string appears in the transcript *before* printf
            // runs. contains() would false-trigger on the echoed command,
            // parse() would then miss the still-absent exact END line, and
            // the drawer would show Empty despite list-sessions having
            // already printed real rows. Mirror TmuxSessionParser's
            // line-equality check so we wait for the printf output.
            if (transcriptHasEndSentinel(currentTranscript(emulator))) return
            pollDelay(POLL_INTERVAL_MS)
        }
    }

    private fun currentTranscript(emulator: TerminalEmulator): String =
        emulator.screen.transcriptTextWithoutJoinedLines

    /**
     * True iff [transcript] has a line that trims to
     * [TmuxSessionParser.END_SENTINEL]. Shared criterion with
     * [TmuxSessionParser.parse] so poll and parse agree on "probe done".
     */
    internal fun transcriptHasEndSentinel(transcript: String): Boolean =
        transcript.lines().any { it.trim() == TmuxSessionParser.END_SENTINEL }

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
         *
         * Trailing empty 4th field: `session_activity_string` is not a real
         * tmux format token (only `session_activity` exists as a unix time),
         * so we leave activity blank — the drawer still renders the row.
         */
        private const val PROBE_FORMAT =
            "#{session_name}|#{session_windows}|#{?session_attached,attached,detached}|"

        /** Leading space — history ignore. See class kdoc. */
        private val STTY_MINUS_ECHO: ByteArray =
            " stty -echo 2>/dev/null".toByteArray(Charsets.UTF_8)

        private val STTY_ECHO: ByteArray =
            " stty echo 2>/dev/null".toByteArray(Charsets.UTF_8)

        /**
         * Probe shell line without trailing Enter — Enter is [ENTER_BYTES].
         * Leading space + trailing `history -d $HISTCMD` — see class kdoc.
         */
        private val PROBE_BODY: ByteArray = buildString {
            append(" printf '")
            append(PROBE_BEGIN_SENTINEL)
            // Literal \n for printf — not a wire LF. Commands are `;`-joined
            // into one line; Enter is a separate write (see submitLine).
            append("\\n'; tmux list-sessions -F '")
            append(PROBE_FORMAT)
            append("' 2>/dev/null; printf '")
            append(PROBE_END_SENTINEL)
            // \$ so Kotlin does not treat HISTCMD as a template expr.
            append("\\n'; history -d \$HISTCMD 2>/dev/null || true")
        }.toByteArray(Charsets.UTF_8)

        private val ENTER_BYTES: ByteArray = byteArrayOf('\r'.code.toByte())

        /**
         * Gap between the command burst and Enter. Must exceed tmux's
         * `assume-paste-time` (default 1 ms) so Enter is not folded into the
         * paste. 50 ms is still invisible in the drawer Loading state and
         * leaves margin for write-executor scheduling + one-packet bursts.
         */
        internal const val PROBE_ENTER_GAP_MS: Long = 50L

        /** 800ms comfortably absorbs 24-row output over a 200ms-RTT link. */
        private const val POLL_INTERVAL_MS: Long = 100L

        /** 3 seconds: a slow remote (cellular + sshd + tmux) is comfortably inside. */
        private const val PROBE_TIMEOUT_MS: Long = 3_000L
    }
}
