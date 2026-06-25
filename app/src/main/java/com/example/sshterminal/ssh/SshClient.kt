package com.example.sshterminal.ssh

import android.content.Context
import com.example.sshterminal.logging.AppLog
import com.example.sshterminal.ssh.auth.Auth
import com.example.sshterminal.ssh.auth.PasswordAuthProvider
import com.example.sshterminal.ssh.auth.PublicKeyAuthProvider
import com.example.sshterminal.ssh.auth.SshAuthProvider
import kotlinx.coroutines.CancellationException
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
 * ## Foreground service coupling
 *
 * On successful connect, this class starts [SshKeepAliveService] so the
 * OS does not reap the process when the user backgrounds the app. The
 * service is stopped in [disconnect], which is the single teardown point
 * for every user-driven and remote-driven session end. The [Context]
 * passed to the constructor is used only to call `Context.startForegroundService`
 * and `Context.stopService` — it is stored as `applicationContext` to
 * prevent Activity leaks, and a check in `init` enforces that callers
 * hand us an application context.
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
    context: Context,
) {

    private val context: Context = context.applicationContext

    private var ssh: SSHClient? = null

    init {
        // ApplicationContext-only guard. A caller passing an Activity would
        // leak the Activity for the lifetime of the SSH connection — easy
        // to do by accident because the only production caller
        // (SshTermApp) hands us the Composable's LocalContext. Catching it
        // at construction time turns a subtle leak into a loud crash.
        check(this.context === context) {
            "SshClient requires applicationContext; got ${context::class.java.simpleName}"
        }
    }

    private companion object {
        // Logcat tag. Suffix with the class name so SshClient + SshSession
        // errors are filterable independently.
        const val TAG = "SshClient"
    }

    /**
     * Connect, authenticate, and allocate a shell channel.
     *
     * On any failure — DNS error, TCP RST, auth rejection, etc. — the
     * returned [Result] is a failure wrapping an [SshException] whose
     * `message` is a user-readable English string (see [SshErrorMessages])
     * and whose `cause` is the original [Throwable] for log analysis. The
     * full stack trace is also emitted to Logcat at `Log.e` level.
     *
     * [CancellationException] is intentionally rethrown unwrapped so that
     * structured concurrency continues to work — the [SshClient.disconnect]
     * flow cancels the connect coroutine on Disconnect, and a wrapped
     * cancellation would either swallow the cancel or be misinterpreted as
     * a connect failure.
     */
    suspend fun connect(
        host: String,
        port: Int,
        username: String,
        auth: Auth,
    ): Result<SshSession> {
        // Build the connection summary up-front: it's purely a function of
        // the validated input parameters, so it cannot throw. Used as the
        // foreground-service notification content text.
        val summary = "$username@$host:$port"
        return try {
            val session = withContext(Dispatchers.IO) {
                BouncyCastleBootstrap.ensureRegistered()
                val client = SSHClient().apply {
                    addHostKeyVerifier(hostKeyVerifier)
                    // Connect timeout: short enough that a wrong port doesn't feel
                    // frozen.
                    setConnectTimeout(SshConfig.CONNECT_TIMEOUT_MS.toInt())
                    // sshj's setTimeout() is forwarded straight to
                    // Socket.setSoTimeout(), which takes **milliseconds**.
                    // SO_TIMEOUT_MS is already in millis — do NOT divide by 1000
                    // (a previous /1000 bug capped banner reads at 60 ms and
                    // surfaced as "Server didn't respond with an SSH banner").
                    setTimeout(SshConfig.SO_TIMEOUT_MS)
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
            // SSH session is live. Promote the process to foreground so the
            // OS does not kill us on background. runCatching is defensive:
            // a service-start failure (extremely unlikely — would mean a
            // revoked FOREGROUND_SERVICE permission or a future platform
            // quirk) must not unwind the successful connect, otherwise the
            // UI flips to Error and the user loses a working session.
            runCatching { SshKeepAliveService.start(context, summary) }
                .onFailure { AppLog.e(TAG, "SshKeepAliveService.start failed", it) }
            Result.success(session)
        } catch (ce: CancellationException) {
            // Don't wrap cancellation. Throw out of the function so the
            // launching coroutine (the UI's "Connect" handler) sees a normal
            // cancellation rather than a Result.failure(cancellation).
            throw ce
        } catch (t: Throwable) {
            // Any other failure: log the original with full stacktrace for
            // post-mortem analysis, then re-wrap with a user-readable
            // message so the status line says something useful instead of
            // "Read timed out" or "Connection refused". The log entry also
            // lands in filesDir/app.log so the user can read + copy it
            // from inside the app (the Connect error overlay surfaces it
            // in a monospace block with a Copy button).
            AppLog.e(
                TAG,
                "connect failed: host=$host port=$port user=$username " +
                    "auth=${auth::class.java.simpleName} " +
                    "friendly=\"${SshErrorMessages.friendly(t)}\"",
                t,
            )
            Result.failure(SshException(SshErrorMessages.friendly(t), t))
        }
    }

    /**
     * Tears down any live connection. Idempotent: safe to call from a
     * "Disconnect" button even if the user never connected, or after the
     * IO loop has already ended.
     *
     * Stops the [SshKeepAliveService] **before** closing the sshj client.
     * The order matters because `Context.stopService` is asynchronous: the
     * service's `onDestroy` (which calls `stopForeground(REMOVE)` to dismiss
     * the notification) runs on a later message-loop iteration, so there is
     * a brief window where the service is tearing down but the channel is
     * still open. That window is harmless because the next read on the
     * still-closing transport will see EOF and the [SshSession.readInto]
     * loop's `finally` will run. Doing the service-stop first (rather than
     * last) avoids a race where the sshj close completes and a reconnect
     * re-promotes a service we meant to retire.
     *
     * Both calls are wrapped in `runCatching`: a service-stop failure must
     * not prevent the sshj close, and a close failure must not prevent the
     * service stop.
     */
    fun disconnect() {
        runCatching { SshKeepAliveService.stop(context) }
            .onFailure { AppLog.e(TAG, "SshKeepAliveService.stop failed", it) }
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
