package com.example.sshterminal.ssh

import android.content.Context
import com.example.sshterminal.logging.AppLog
import com.example.sshterminal.ssh.auth.Auth
import com.example.sshterminal.ssh.auth.PasswordAuthProvider
import com.example.sshterminal.ssh.auth.PublicKeyAuthProvider
import com.example.sshterminal.ssh.security.KnownHostsStore
import com.example.sshterminal.ssh.security.KnownHostsVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier

/**
 * Connects to a remote SSH host and returns an [SshSession] ready for use.
 *
 * Sprint 2.5 / SC-KHV-01: default connect path uses [KnownHostsVerifier] TOFU
 * backed by [KnownHostsStore] at `filesDir/known_hosts`.
 */
class SshClient(
    context: Context,
) {

    private val context: Context = context.applicationContext

    /**
     * Sprint 2.5 / SC-KHV-01: replaced the v1.0 [PromiscuousVerifier]
     * default with a TOFU [KnownHostsVerifier]. The verifier is rebuilt
     * per-connect (see [connect]) so the (host, port) pair binds
     * unambiguously to the TOFU store lookup. Tests can override via the
     * secondary constructor below.
     */
    private var hostKeyVerifier: HostKeyVerifier? = null

    /** Test-only secondary constructor that injects a custom verifier. */
    constructor(
        hostKeyVerifier: HostKeyVerifier,
        context: Context,
    ) : this(context) {
        this.hostKeyVerifier = hostKeyVerifier
    }

    private var ssh: SSHClient? = null
    private var currentHost: String = ""
    private var currentPort: Int = 0

    init {
        check(this.context === context) {
            "SshClient requires applicationContext; got ${context::class.java.simpleName}"
        }
    }

    companion object {
        const val TAG = "SshClient"

        const val MITM_WARNING_FORMAT =
            "Host key for %s:%d has changed since first connection. " +
                "Possible man-in-the-middle. " +
                "If you trust the new key, reset the host entry in Settings."

        const val STORE_INIT_FAILURE_MESSAGE =
            "Cannot initialize host-key store"

        /** Module 11 / KHV-UX-01: one-line notice after first-use enroll. */
        const val ENROLLMENT_NOTICE_FORMAT =
            "New host %s:%d enrolled. Future connections will verify this key."

        /** SC-KHV-01: for unit tests that cannot drive a real TCP connect. */
        internal fun buildDefaultKnownHostsVerifier(
            context: Context,
            host: String,
            port: Int,
        ): KnownHostsVerifier? {
            val probeStore = KnownHostsStore(context.applicationContext)
            val probeFailure = kotlinx.coroutines.runBlocking { probeStore.probe() }
            if (probeFailure != null) return null
            return KnownHostsVerifier(probeStore, host, port)
        }
    }

    private fun prepareKnownHostsVerifier(host: String, port: Int): Result<Boolean> {
        val probeStore = KnownHostsStore(context)
        val probeFailure: Throwable? = kotlinx.coroutines.runBlocking {
            probeStore.probe()
        }
        if (probeFailure != null) {
            AppLog.e(
                TAG,
                "host-key store unavailable; refusing connect",
                probeFailure,
            )
            return Result.failure(
                SshException(STORE_INIT_FAILURE_MESSAGE, probeFailure),
            )
        }
        val hadEntry = kotlinx.coroutines.runBlocking {
            probeStore.get(host, port) != null
        }
        hostKeyVerifier = KnownHostsVerifier(
            store = probeStore,
            host = host,
            port = port,
        )
        return Result.success(hadEntry)
    }

    suspend fun connect(
        host: String,
        port: Int,
        username: String,
        auth: Auth,
    ): Result<SshConnectResult> {
        val summary = "$username@$host:$port"
        currentHost = host
        currentPort = port
        var hadHostEntryBeforeConnect = true
        if (hostKeyVerifier == null || hostKeyVerifier is KnownHostsVerifier) {
            hadHostEntryBeforeConnect = prepareKnownHostsVerifier(host, port)
                .getOrElse { return Result.failure(it) }
        }
        return try {
            val session = withContext(Dispatchers.IO) {
                BouncyCastleBootstrap.ensureRegistered()
                val client = SSHClient().apply {
                    addHostKeyVerifier(hostKeyVerifier!!)
                    setConnectTimeout(SshConfig.CONNECT_TIMEOUT_MS.toInt())
                    setTimeout(SshConfig.SO_TIMEOUT_MS)
                }
                try {
                    client.connect(host, port)
                    when (auth) {
                        is Auth.PasswordAuth ->
                            PasswordAuthProvider.authenticate(client, username, auth)
                        is Auth.PublicKeyAuth ->
                            PublicKeyAuthProvider.authenticate(client, username, auth, context)
                    }
                    client.connection.keepAlive.setKeepAliveInterval(
                        SshConfig.SSH_KEEPALIVE_INTERVAL_SECONDS,
                    )
                    val session = client.startSession()
                    val shell = openShell(session)
                    ssh = client
                    SshSession(
                        transport = ChannelTransport(shell),
                        onClose = { userInitiated -> disconnect(userInitiated) },
                    )
                } catch (t: Throwable) {
                    runCatching { client.close() }
                    throw t
                }
            }
            runCatching { SshKeepAliveService.start(context, summary) }
                .onFailure { AppLog.e(TAG, "SshKeepAliveService.start failed", it) }
            val enrollmentNotice = if (
                !hadHostEntryBeforeConnect &&
                (hostKeyVerifier == null || hostKeyVerifier is KnownHostsVerifier)
            ) {
                ENROLLMENT_NOTICE_FORMAT.format(host, port)
            } else {
                null
            }
            Result.success(SshConnectResult(session, enrollmentNotice))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val friendly = if (isHostKeyMismatch(t)) {
                MITM_WARNING_FORMAT.format(currentHost, currentPort)
            } else {
                SshErrorMessages.friendly(t)
            }
            AppLog.e(
                TAG,
                "connect failed: host=$host port=$port user=$username " +
                    "auth=${auth::class.java.simpleName} " +
                    "friendly=\"$friendly\"",
                t,
            )
            Result.failure(SshException(friendly, t))
        }
    }

    private fun isHostKeyMismatch(t: Throwable): Boolean {
        var current: Throwable? = t
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val name = current.javaClass.name
            if (name == "net.schmizz.sshj.common.SSHException" &&
                (current.message?.contains("Host key verification", ignoreCase = true) == true)
            ) {
                return true
            }
            if (name.contains("OpenHostKeyVerificationException")) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Tears down the SSH client: stops the keepalive service and closes the
     * underlying sshj `SSHClient`.
     *
     * Sprint 3 / SCR-UI-01..02 + the `userInitiated` signal is propagated
     * from [SshSession.close] (when the user taps Disconnect) all the way
     * through the `onClose` hook to this function. The default
     * `userInitiated = false` keeps the existing call sites unchanged — see
     * [SshClient.connect]'s `onClose` hook, which passes through whatever
     * the session saw.
     */
    fun disconnect(userInitiated: Boolean = false) {
        // The userInitiated parameter is plumbed through the SshSession.onClose
        // hook for future debugging hooks (e.g. analytics distinguishing
        // user-initiated vs. transport-error disconnects); the existing
        // teardown steps (stop keepalive, close sshj) are the same either
        // way. Log at info level so the disambiguation is visible in logcat
        // without making the message look like an error.
        AppLog.i(
            TAG,
            "disconnect invoked userInitiated=$userInitiated",
        )
        runCatching { SshKeepAliveService.stop(context) }
            .onFailure { AppLog.e(TAG, "SshKeepAliveService.stop failed", it) }
        runCatching { ssh?.close() }
        ssh = null
    }

    private fun openShell(session: Session): Session.Shell {
        session.allocatePTY(
            SshConfig.DEFAULT_TERM_TYPE,
            SshConfig.DEFAULT_PTY_COLS,
            SshConfig.DEFAULT_PTY_ROWS,
            0,
            0,
            mapOf(
                PTYMode.ECHO to 1,
                PTYMode.ECHOE to 1,
                PTYMode.ICANON to 1,
                PTYMode.ONLCR to 1,
            ),
        )
        return session.startShell()
    }
}
