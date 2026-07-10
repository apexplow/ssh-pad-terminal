package com.example.sshterminal.ssh

import android.content.Context
import com.example.sshterminal.logging.AppLog
import com.example.sshterminal.ssh.auth.Auth
import com.example.sshterminal.ssh.auth.PasswordAuthProvider
import com.example.sshterminal.ssh.auth.PublicKeyAuthProvider
import com.example.sshterminal.ssh.security.HostKeyPrompt
import com.example.sshterminal.ssh.security.KnownHostsStore
import com.example.sshterminal.ssh.security.KnownHostsVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.Config
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.util.concurrent.atomic.AtomicReference

/**
 * Connects to a remote SSH host and returns an [SshSession] ready for use.
 *
 * Sprint 2.5 / SC-KHV-01: default connect path uses [KnownHostsVerifier] TOFU
 * backed by [KnownHostsStore] at `filesDir/known_hosts`.
 */
class SshClient(
    context: Context,
    /** Optional interactive TOFU gate (KHV-UX-02). See [HostKeyPrompt]. */
    private val hostKeyPrompt: HostKeyPrompt? = null,
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

    /**
     * Holds the live `SSHClient`, or `null` when disconnected.
     *
     * Uses [AtomicReference.getAndSet] (not a plain `var`) so [disconnect]
     * is exactly-once even when invoked concurrently from two different
     * threads — which is a real scenario, not a hypothetical one: a session
     * dying on its own races [SshSession]'s `writeExecutor` thread (via the
     * `onClose` hook) against the UI's `onSessionClosed` callback on the
     * main thread, and both call [disconnect]. Without atomicity, both
     * threads could observe a non-null client and race to close/log/stop
     * the service. `getAndSet(null)` guarantees only the first caller wins
     * the teardown; every other concurrent (or later) caller sees `null`
     * and returns immediately — a true no-op, not just a "probably harmless
     * double call".
     */
    private val sshRef = AtomicReference<SSHClient?>(null)
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

        /**
         * Shown when the user declines the interactive trust prompt
         * (KHV-UX-02) for a host that had NO prior entry — i.e. this is a
         * first connection, not a key change, so [MITM_WARNING_FORMAT]'s
         * "changed since first connection" language would be misleading.
         */
        const val NEW_HOST_REJECTED_FORMAT =
            "Connection to %s:%d cancelled: host key was not trusted."

        const val STORE_INIT_FAILURE_MESSAGE =
            "Cannot initialize host-key store"

        /** Module 11 / KHV-UX-01: one-line notice after first-use enroll. */
        const val ENROLLMENT_NOTICE_FORMAT =
            "New host %s:%d enrolled. Future connections will verify this key."

        /**
         * Builds the sshj [Config] used for every [connect] call.
         *
         * sshj's own `DefaultConfig` defaults `keepAliveProvider` to
         * `KeepAliveProvider.HEARTBEAT`, whose `Heartbeater` only *writes*
         * an `SSH_MSG_IGNORE` packet — it never expects or waits for a
         * reply, so it can keep a NAT mapping warm but can NEVER by itself
         * detect that the remote peer has gone dark. We explicitly opt into
         * `KeepAliveProvider.KEEP_ALIVE` (`KeepAliveRunner`), which sends
         * `keepalive@openssh.com` global requests, tracks unanswered ones,
         * and raises `ConnectionException(CONNECTION_LOST)` after
         * [SshConfig.SSH_KEEPALIVE_MAX_ALIVE_COUNT] consecutive misses —
         * see [connect] for where the interval/max-count are applied to the
         * running connection.
         *
         * Pulled out to a pure, side-effect-free function (no socket, no
         * Context) so a unit test can assert the provider without driving a
         * real TCP connect.
         */
        internal fun buildSshjConfig(): Config = DefaultConfig().apply {
            keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        }

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
            prompt = hostKeyPrompt,
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
                val client = SSHClient(buildSshjConfig()).apply {
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
                    client.connection.keepAlive.keepAliveInterval =
                        SshConfig.SSH_KEEPALIVE_INTERVAL_SECONDS
                    // The static return type of Connection.getKeepAlive() is the
                    // abstract KeepAlive base class; setMaxAliveCount only exists
                    // on KeepAliveRunner, the concrete type KeepAliveProvider.KEEP_ALIVE
                    // actually instantiates (see buildSshjConfig). The cast is safe
                    // as long as that provider choice doesn't change.
                    (client.connection.keepAlive as? KeepAliveRunner)?.maxAliveCount =
                        SshConfig.SSH_KEEPALIVE_MAX_ALIVE_COUNT
                    val session = client.startSession()
                    val shell = openShell(session)
                    sshRef.set(client)
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
                // hadHostEntryBeforeConnect distinguishes "this host had no
                // prior fingerprint and the trust prompt was declined" from
                // an actual key change — the former isn't a MITM signal,
                // it's just a first connection the user chose not to trust.
                if (hadHostEntryBeforeConnect) {
                    MITM_WARNING_FORMAT.format(currentHost, currentPort)
                } else {
                    NEW_HOST_REJECTED_FORMAT.format(currentHost, currentPort)
                }
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

    /**
     * True when [t]'s cause chain contains the failure sshj actually raises
     * when every configured [HostKeyVerifier] rejects the presented key.
     *
     * sshj's `KeyExchanger.verifyHost` (unchanged across the 0.38 → 0.40 bump
     * — confirmed against both jars' bytecode) throws a
     * `net.schmizz.sshj.transport.TransportException` — a subclass of
     * [SSHException], not [SSHException] itself — carrying
     * [DisconnectReason.HOST_KEY_NOT_VERIFIABLE] and a message of the form
     * "Could not verify `<type>` host key with fingerprint `<fp>` for `<host>`
     * on port <port>". The previous exact-class-name + "Host key
     * verification" substring check never matched that real shape, so a
     * genuine [KnownHostsVerifier] rejection (mismatch OR an enroll-write
     * failure) silently fell through to the generic
     * "SSH handshake failed..." message, hiding the actual cause and the fix
     * (reset the host entry in Settings) from the user. Checking the
     * [DisconnectReason] via `is SSHException` (matches subclasses) instead
     * of a brittle string/class-name comparison survives future sshj point
     * releases as long as the enum value's name doesn't change.
     *
     * The `OpenHostKeyVerificationException` name check is kept for older
     * sshj releases that used a dedicated exception type instead of folding
     * it into [DisconnectReason].
     */
    private fun isHostKeyMismatch(t: Throwable): Boolean {
        var current: Throwable? = t
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is SSHException &&
                current.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE
            ) {
                return true
            }
            if (current.javaClass.name.contains("OpenHostKeyVerificationException")) {
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
     *
     * SC-DC-03: idempotent, and safe to call concurrently — [sshRef]'s
     * `getAndSet(null)` atomically hands the live client to exactly one
     * caller. That matters in practice: a session dying on its own fires
     * this from [SshSession]'s single-thread write executor (via the
     * `onClose` hook) *and* from the UI's `onSessionClosed` callback on the
     * main thread, both racing to tear down the same client.
     */
    fun disconnect(userInitiated: Boolean = false) {
        // SC-DC-01: stop the keepalive service BEFORE closing sshj — see the
        // kdoc on the class for why the order matters. Only the caller that
        // wins the getAndSet race runs any teardown at all; every other
        // (concurrent or later) call is a true no-op.
        val client = sshRef.getAndSet(null)
        if (client == null) {
            AppLog.i(TAG, "disconnect invoked userInitiated=$userInitiated (already disconnected, no-op)")
            return
        }
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
        runCatching { client.close() }
            .onFailure { AppLog.e(TAG, "ssh.close failed", it) }
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
