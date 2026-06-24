package com.example.sshterminal.ssh

import com.example.sshterminal.ssh.auth.Auth
import com.example.sshterminal.ssh.auth.PasswordAuthProvider
import com.example.sshterminal.ssh.auth.PublicKeyAuthProvider
import com.example.sshterminal.ssh.auth.SshAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.PromiscuousVerifier

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
 *  4. `connect`, then `auth*`, then start a session, allocate the PTY, and
 *     open a shell channel with the configured terminal type.
 *  5. Wrap the resulting [Session.Shell] in an [SshSession] backed by a
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
                // frozen.
                setConnectTimeout(SshConfig.CONNECT_TIMEOUT_MS.toInt())
                // Socket read timeout: bounds how long a single read on the
                // underlying TCP socket can block. SSH-level keepalive
                // (configured after connect, below) is the primary defense
                // against half-open connections; this is a safety net that
                // ensures the read loop is never stuck past [SO_TIMEOUT_MS].
                setTimeout(SshConfig.SO_TIMEOUT_MS / 1000)
            }
            try {
                client.connect(host, port)
                authProviderFor(auth).authenticate(client, username, auth)
                // SSH-level keepalive: with no keepalive, a half-open
                // connection (mobile NAT timeout, captive-portal redirect,
                // silent server-side close) leaves the read loop blocked
                // forever — the OS only surfaces a RST minutes/hours later,
                // at which point the sshj internal Reader thread throws an
                // uncaught `SSHException: Software caused connection abort`.
                // 30s interval catches mobile NATs (typically 60-120s) without
                // spamming the server. See SshConfig.SSH_KEEPALIVE_INTERVAL_SECONDS.
                // sshj's KeepAlive is a Thread that's already running by this
                // point; setKeepAliveInterval is synchronized so updating it
                // on the live thread is safe.
                client.connection.keepAlive.setKeepAliveInterval(
                    SshConfig.SSH_KEEPALIVE_INTERVAL_SECONDS,
                )
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

    private fun openShell(session: Session): Session.Shell {
        // sshj 0.38 API:
        //   session.allocatePTY(term, cols, rows, widthPx, heightPx, modes)
        //     — sends SSH_MSG_CHANNEL_REQUEST pty-req with the given geometry
        //   session.startShell() — opens the shell channel and returns it
        //
        session.allocatePTY(
            SshConfig.DEFAULT_TERM_TYPE,
            SshConfig.DEFAULT_PTY_COLS,
            SshConfig.DEFAULT_PTY_ROWS,
            /* width  = */ 0,
            /* height = */ 0,
            /* modes  = */ mapOf(
                // ECHO + ECHOE: server echoes input back so the user sees what they typed
                // (otherwise the local emulator echoes — but emulator.append isn't wired
                // in the disconnected path, so ECHO is the only path the user sees anything).
                PTYMode.ECHO to 1,
                PTYMode.ECHOE to 1,
                // ICANON: canonical (line-buffered) mode — server buffers until newline,
                // so multi-byte UTF-8 sequences aren't split across reads.
                PTYMode.ICANON to 1,
                // ONLCR: translate NL to CR-NL on output (matches terminal conventions).
                PTYMode.ONLCR to 1,
            ),
        )
        return session.startShell()
    }

    private fun authProviderFor(auth: Auth): SshAuthProvider = when (auth) {
        is Auth.PasswordAuth -> PasswordAuthProvider
        is Auth.PublicKeyAuth -> PublicKeyAuthProvider
    }
}
