package com.example.sshterminal.ssh

import com.hierynomus.sshj.transport.verification.PromiscuousVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.channel.ChannelShell
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import com.example.sshterminal.ssh.auth.Auth
import com.example.sshterminal.ssh.auth.PasswordAuthProvider
import com.example.sshterminal.ssh.auth.PublicKeyAuthProvider
import com.example.sshterminal.ssh.auth.SshAuthProvider

/**
 * Connects to a remote SSH host and returns an [SshSession] ready for use.
 *
 * ## Connection flow
 *
 *  1. Construct a fresh [SSHClient] (one per [SshClient] instance — sshj
 *     holds socket state, NOT thread-safe to share across connects).
 *  2. Register a [BouncyCastleProvider] (idempotent — see
 *     [BouncyCastleBootstrap]).
 *  3. Install a permissive [HostKeyVerifier]. v1.0 does not implement
 *     known_hosts; Sprint 3 adds a TOFU store. This is a deliberate trade-off
 *     documented in `implementation_plan.md` §"验证计划" — manual lab testing
 *     of "does the connection work" takes priority over MITM defense for
 *     the first release.
 *  4. `connect`, then `auth*`, then open a shell channel with the configured
 *     PTY dimensions.
 *  5. Wrap the resulting [ChannelShell] in an [SshSession] backed by a
 *     [ChannelTransport]. The [SshSession] holds a reference back to this
 *     [SshClient] so [SshSession.close] can tear the parent down too.
 *
 * ## Error handling
 *
 * Every failure point — TCP timeout, kex failure, auth rejection, channel
 * open failure — surfaces through [Result] rather than as an exception
 * crossing the coroutine boundary. The UI's Connect handler does
 * `result.fold(onSuccess = ..., onFailure = ...)` to drive status text and
 * the fallback to [com.example.sshterminal.terminal.MockEchoSession].
 *
 * NOTE: callers should NOT keep a reference to the [SSHClient] directly —
 * use [disconnect] on this object. The internal handle is private so the
 * lifecycle stays inside one place.
 */
class SshClient(
    private val hostKeyVerifier: HostKeyVerifier = PromiscuousVerifier(),
) {

    private var ssh: SSHClient? = null

    /**
     * Connect, authenticate, and allocate a shell channel.
     *
     * On any failure — DNS error, TCP RST, auth rejection, etc. — the
     * returned [Result] is a failure wrapping the originating [Throwable].
     * Callers can pattern-match on `result.exceptionOrNull()` to render a
     * user-friendly status message.
     */
    suspend fun connect(
        host: String,
        port: Int,
        username: String,
        auth: Auth,
    ): Result<SshSession> = runCatching {
        withContext(Dispatchers.IO) {
            BouncyCastleBootstrap.ensureRegistered()
            val client = SSHClient().apply {
                addHostKeyVerifier(hostKeyVerifier)
                // Connect timeout: short enough that a wrong port doesn't feel
                // frozen. Read/write timeout is left at sshj default (0 = block
                // indefinitely), which is correct for a long-lived shell.
                connectTimeout = SshConfig.CONNECT_TIMEOUT_MS.toInt()
            }
            try {
                client.connect(host, port)
                authProviderFor(auth).authenticate(client, username, auth)
                val session = client.startSession()
                val shell = openShell(session)
                // Stash on success only — close() on a partially-constructed
                // client would null out a still-null ssh and leak the failed
                // SSHClient's socket.
                ssh = client
                SshSession(
                    transport = ChannelTransport(shell),
                    onClose = ::disconnect,
                )
            } catch (t: Throwable) {
                runCatching { client.close() }
                throw t
            }
        }
    }

    /**
     * Tears down any live connection. Idempotent: safe to call from a
     * "Disconnect" button even if the user never connected, or after the
     * IO loop has already ended.
     */
    fun disconnect() {
        runCatching { ssh?.close() }
        ssh = null
    }

    private fun openShell(session: net.schmizz.sshj.connection.Session): ChannelShell {
        // sshj 0.38: openChannel("shell") returns ChannelShell. We bypass
        // allocateDefaultPTY() and set the type/cols/rows explicitly so the
        // values match SshConfig — easier to pin in tests.
        val shell = session.openChannel("shell") as ChannelShell
        shell.setTerminalType(SshConfig.DEFAULT_TERM_TYPE)
        shell.setTerminalCols(SshConfig.DEFAULT_PTY_COLS)
        shell.setTerminalRows(SshConfig.DEFAULT_PTY_ROWS)
        shell.open()
        return shell
    }

    private fun authProviderFor(auth: Auth): SshAuthProvider = when (auth) {
        is Auth.PasswordAuth -> PasswordAuthProvider
        is Auth.PublicKeyAuth -> PublicKeyAuthProvider
    }
}
