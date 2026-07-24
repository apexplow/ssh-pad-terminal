package com.taosun.hanterm.ssh

import android.content.Context
import com.taosun.hanterm.logging.AppLog
import com.taosun.hanterm.ssh.auth.Auth
import com.taosun.hanterm.ssh.auth.PasswordAuthProvider
import com.taosun.hanterm.ssh.auth.PublicKeyAuthProvider
import com.taosun.hanterm.ssh.security.HostKeyPrompt
import com.taosun.hanterm.ssh.security.KnownHostsStore
import com.taosun.hanterm.ssh.security.KnownHostsVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.Config
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.FileDescriptor
import java.net.StandardSocketOptions
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
) : SshConnector {

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

    /**
     * Issue #17: FGS keepalive capability exposed to the rest of the app via
     * the [KeepAliveNudge] interface (no more companion global). The
     * implementation captures the live `sshRef` so it always writes through
     * the current `SSHClient` and never holds a stale reference. Lives as
     * an `inner class` rather than a top-level type because it needs
     * `this@SshClient.sshRef`; the public surface is a single read-only
     * [KeepAliveNudge] reference that `SshConnectResult` carries out.
     */
    val keepAliveNudge: KeepAliveNudge = SshClientKeepAliveNudge()

    private inner class SshClientKeepAliveNudge : KeepAliveNudge {
        override fun nudge(): Boolean {
            val client = sshRef.get() ?: return false
            return runCatching {
                if (!client.isConnected || !client.transport.isRunning) {
                    return@runCatching false
                }
                val packet = SSHPacket(Message.IGNORE).apply { putString("") }
                client.transport.write(packet)
                true
            }.getOrElse { t ->
                AppLog.w(TAG, "FGS keepalive nudge failed", t)
                false
            }
        }
    }

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
         * We deliberately keep [KeepAliveProvider.HEARTBEAT] (sshj's default):
         * it writes one-way `SSH_MSG_IGNORE` packets and never waits for a
         * reply. The previous Sprint 3 choice of `KEEP_ALIVE`
         * (`KeepAliveRunner` + `keepalive@openssh.com` with want-reply)
         * self-killed healthy sessions after
         * `interval × maxAliveCount` (~30 s once the interval was tightened
         * to 10 s) whenever replies failed to land — which is exactly the
         * Tailscale / Doze path (BG-KA-04 / 2026-07-11 device log:
         * abort ~35 s after connect despite FGS nudge + TCP keepalive).
         *
         * Dead-peer detection is now owned by:
         *  - kernel TCP keepalive (25 s window) via [configureTcpKeepAlive]
         *  - [SshConfig.SO_TIMEOUT_MS] on the read loop
         *  - [SshKeepAliveService]'s FGS IGNORE nudge (Doze-resistant TX)
         *
         * Pulled out to a pure, side-effect-free function (no socket, no
         * Context) so a unit test can assert the provider without driving a
         * real TCP connect.
         */
        internal fun buildSshjConfig(): Config = DefaultConfig().apply {
            keepAliveProvider = KeepAliveProvider.HEARTBEAT
        }

        /** SC-KHV-01: for unit tests that cannot drive a real TCP connect. */
        internal suspend fun buildDefaultKnownHostsVerifier(
            context: Context,
            host: String,
            port: Int,
        ): KnownHostsVerifier? {
            val probeStore = KnownHostsStore(context.applicationContext)
            val probeFailure = probeStore.probe()
            if (probeFailure != null) return null
            return KnownHostsVerifier(probeStore, host, port)
        }
    }

    private suspend fun prepareKnownHostsVerifier(host: String, port: Int): Result<Boolean> {
        val probeStore = KnownHostsStore(context)
        val probeFailure = probeStore.probe()
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
        val hadEntry = probeStore.get(host, port) != null
        hostKeyVerifier = KnownHostsVerifier(
            store = probeStore,
            host = host,
            port = port,
            prompt = hostKeyPrompt,
        )
        return Result.success(hadEntry)
    }

    override suspend fun connect(
        host: String,
        port: Int,
        username: String,
        auth: Auth,
    ): Result<SshConnectResult> {
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
                    // BG-KA-01: TCP-level keepalive ON TOP OF the SSH-level
                    // keepalive set later in this block. SSH-level probes run
                    // in sshj's user-space `KeepAliveRunner` thread, which
                    // Android Doze / app standby may suspend when the user
                    // backgrounds the app — meaning probes can stop landing
                    // for tens of seconds at a time. TCP-level keepalive
                    // runs in the kernel, which Doze does not pause, so the
                    // socket stays alive across the exact "切到后台就断开"
                    // scenario. The 10/5/3 parameters give a 25 s end-to-end
                    // detection window, well inside Tailscale / mobile NAT
                    // timeouts. Fall back silently if the libcore setsockopt
                    // path is unavailable on the running ROM.
                    configureTcpKeepAlive(client)
                    when (auth) {
                        is Auth.PasswordAuth ->
                            PasswordAuthProvider.authenticate(client, username, auth)
                        is Auth.PublicKeyAuth ->
                            PublicKeyAuthProvider.authenticate(client, username, auth, context)
                    }
                    applySshKeepAliveSettings(client)
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
            val enrollmentNotice = if (
                !hadHostEntryBeforeConnect &&
                (hostKeyVerifier == null || hostKeyVerifier is KnownHostsVerifier)
            ) {
                ENROLLMENT_NOTICE_FORMAT.format(host, port)
            } else {
                null
            }
            Result.success(
                SshConnectResult(
                    session = session,
                    enrollmentNotice = enrollmentNotice,
                    keepAliveNudge = keepAliveNudge,
                ),
            )
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
    override fun disconnect(userInitiated: Boolean) {
        // SC-DC-01: stop the keepalive service BEFORE closing sshj — see the
        // kdoc on the class for why the order matters. Only the caller that
        // wins the getAndSet race runs any teardown at all; every other
        // (concurrent or later) call is a true no-op.
        //
        // Issue #17 — safety net: this is the path the `SshSession.onClose`
        // hook hits when the SSH transport dies, BEFORE
        // `HanTermAppViewModel.onSessionClosed` fires on main and reaches
        // `ConnectionRuntime.teardownInternal`. Without clearing the
        // registry and stopping the FGS here, a tick that fires in that
        // window would write to a closed sshj transport. The double-stop
        // with `ConnectionRuntime.teardownInternal` is idempotent
        // (`Context.stopService` is).
        runCatching { KeepAliveNudgeRegistry.set(null) }
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

    /**
     * Apply SSH-level heartbeat interval and start sshj's Heartbeater thread.
     *
     * sshj only auto-starts the keepalive [Thread] inside [SSHClient]'s
     * `onConnect()` when [net.schmizz.keepalive.KeepAlive.isEnabled] is already
     * true — but we set the interval *after* auth, so without an explicit
     * [net.schmizz.keepalive.KeepAlive.start] here the heartbeater never runs
     * (BG-KA-02 / 2026-07-11 device repro).
     */
    private fun applySshKeepAliveSettings(client: SSHClient) {
        val keepAlive = client.connection.keepAlive
        keepAlive.keepAliveInterval = SshConfig.SSH_KEEPALIVE_INTERVAL_SECONDS
        if (keepAlive.isEnabled && !keepAlive.isAlive) {
            keepAlive.start()
            AppLog.i(
                TAG,
                "sshj Heartbeater started interval=" +
                    "${SshConfig.SSH_KEEPALIVE_INTERVAL_SECONDS}s",
            )
        }
    }

    /**
     * Configure TCP-level keepalive on the underlying socket.
     *
     * sshj's `KeepAliveProvider.KEEP_ALIVE` only sends SSH-level
     * `keepalive@openssh.com` global requests — those run on a user-space
     * scheduled thread that Android Doze can pause when the app is
     * backgrounded. By the time the foreground service "perceptible"
     * bucket resumes the thread, the peer has already RST'd the socket
     * (reproduces as `Software caused connection abort` ~10 s into a
     * backgrounded session over Tailscale).
     *
     * This method flips TCP keepalive AND shortens the kernel-default
     * 2-hour probe interval to a 25 s window:
     *   - TCP_KEEPIDLE = 10 s  — first probe sent 10 s after the socket
     *     last saw a packet
     *   - TCP_KEEPINTVL = 5 s  — gap between subsequent probes
     *   - TCP_KEEPCNT  = 3     — probes unanswered before the kernel
     *     declares the peer dead and RSTs the local socket
     *
     * End-to-end ride-through = 10 + 5 × 3 = 25 s, which is inside both
     * Tailscale's NAT timeout and aggressive sshd `ClientAliveInterval`
     * settings. The kernel runs the probes; Doze does not pause them.
     *
     * The interval constants (`TCP_KEEPIDLE` etc.) are not in
     * `StandardSocketOptions`, and `android.system.Os` is `@hide`, so we
     * reflect into libcore's `Os` singleton. Every reflection step is
     * wrapped in `runCatching` — if a future Android release reshuffles
     * the field/method names we fall back to sshj's plain SO_KEEPALIVE
     * (2-hour default) rather than crashing the connect.
     */
    private fun configureTcpKeepAlive(client: SSHClient) {
        // sshj's Transport/Connection APIs do not expose
        // setSocketOption(...) in 0.40 — the public route is the inherited
        // `getSocket()` on SocketClient, which returns the live
        // java.net.Socket sshj already opened.
        val socket: java.net.Socket = runCatching { client.socket }.getOrNull() ?: run {
            AppLog.w(TAG, "TCP keepalive: client.socket unavailable")
            return
        }
        runCatching {
            // Step 1: flip SO_KEEPALIVE. Standard JDK API; works on every
            // Android API level minSdk supports (36+ as of Issue #19).
            socket.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
            AppLog.i(TAG, "TCP keepalive: SO_KEEPALIVE=true")
        }.onFailure {
            AppLog.w(TAG, "TCP keepalive: SO_KEEPALIVE setOption failed", it)
            return
        }
        runCatching {
            // Step 2: tighten the kernel-default 2-hour interval.
            // Reach the raw FileDescriptor, then set Linux TCP keepalive
            // params from <netinet/tcp.h>:
            //   IPPROTO_TCP = 6
            //   TCP_KEEPIDLE = 4
            //   TCP_KEEPINTVL = 5
            //   TCP_KEEPCNT = 6
            //
            // IMPORTANT: on API 29+ ART, `fd` lives on SocketImpl (field
            // `impl`), NOT on java.net.Socket itself — reading socket.fd
            // throws NoSuchFieldException and leaves SO_KEEPALIVE stuck at
            // the kernel's 2-hour default (the exact failure reproduced on
            // device in the 2026-07-11 handoff).
            //
            // IMPORTANT: Libcore.os is an *instance* whose runtime class is
            // android.app.ActivityThread$AndroidOs — setsockoptInt is declared
            // on libcore.io.ForwardingOs, not on that subclass, so
            // os.javaClass.getMethod(...) throws NoSuchMethodException on
            // modern ART (2026-07-11 device repro #2). Prefer the public
            // android.system.Os static wrapper; fall back to resolving the
            // method on ForwardingOs and invoking against the Libcore.os
            // singleton.
            val fd = socketFileDescriptor(socket)
                ?: error("could not reach FileDescriptor from ${socket.javaClass.name}")
            applyTcpKeepaliveIntervals(fd)
            AppLog.i(
                TAG,
                "TCP keepalive: idle=10s intvl=5s cnt=3 (25s detection window)",
            )
        }.onFailure {
            AppLog.w(
                TAG,
                "TCP keepalive: tightened-interval setsockoptInt failed; " +
                    "falling back to kernel default (2h)",
                it,
            )
        }
    }

    /**
     * Set TCP_KEEPIDLE / TCP_KEEPINTVL / TCP_KEEPCNT on [fd].
     *
     * End-to-end detection window = 10 + 5 × 3 = 25 s.
     */
    private fun applyTcpKeepaliveIntervals(fd: FileDescriptor) {
        val IPPROTO_TCP = 6
        val TCP_KEEPIDLE = 4
        val TCP_KEEPINTVL = 5
        val TCP_KEEPCNT = 6
        val intervals = arrayOf(
            TCP_KEEPIDLE to 10,
            TCP_KEEPINTVL to 5,
            TCP_KEEPCNT to 3,
        )
        val paramTypes = arrayOf(
            FileDescriptor::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        // Path 1: android.system.Os.setsockoptInt (static, API 21+ @hide).
        // Less brittle than reaching through the Libcore.os singleton's
        // concrete subclass — this is what the 2026-07-11 device repro needed.
        runCatching {
            val method = Class.forName("android.system.Os")
                .getMethod("setsockoptInt", *paramTypes)
            for ((option, value) in intervals) {
                method.invoke(null, fd, IPPROTO_TCP, option, value)
            }
            return
        }
        // Path 2: Libcore.os instance + method resolved on ForwardingOs.
        val os = Class.forName("libcore.io.Libcore")
            .getDeclaredField("os")
            .apply { isAccessible = true }
            .get(null)
        val method = Class.forName("libcore.io.ForwardingOs")
            .getDeclaredMethod("setsockoptInt", *paramTypes)
            .apply { isAccessible = true }
        for ((option, value) in intervals) {
            method.invoke(os, fd, IPPROTO_TCP, option, value)
        }
    }

    /**
     * Reach the live [FileDescriptor] backing a connected [java.net.Socket].
     *
     * Android ART moved `fd` off [java.net.Socket] onto [java.net.SocketImpl]
     * (reachable via the package-private `impl` field) starting around API 29.
     * We walk a small fallback chain so a single ROM reshuffle doesn't silently
     * regress us back to the 2-hour kernel keepalive default.
     */
    private fun socketFileDescriptor(socket: java.net.Socket): FileDescriptor? {
        // Legacy path: very old libcore builds exposed fd directly on Socket.
        runCatching {
            val fd = socket.javaClass
                .getDeclaredField("fd")
                .apply { isAccessible = true }
                .get(socket) as FileDescriptor
            if (fd.isLiveHandle()) return fd
        }
        // Modern path: Socket.impl -> SocketImpl.fd (walk superclasses).
        runCatching {
            val impl = socket.javaClass
                .getDeclaredField("impl")
                .apply { isAccessible = true }
                .get(socket)
            var walk: Class<*>? = impl.javaClass
            while (walk != null) {
                runCatching {
                    val fd = walk!!.getDeclaredField("fd")
                        .apply { isAccessible = true }
                        .get(impl) as FileDescriptor
                    if (fd.isLiveHandle()) return fd
                }
                walk = walk.superclass
            }
        }
        // Belt-and-suspenders: some ART builds expose getFileDescriptor() on impl.
        runCatching {
            val impl = socket.javaClass
                .getDeclaredField("impl")
                .apply { isAccessible = true }
                .get(socket)
            var walk: Class<*>? = impl.javaClass
            while (walk != null) {
                runCatching {
                    val method = walk!!.getDeclaredMethod("getFileDescriptor")
                        .apply { isAccessible = true }
                    val fd = method.invoke(impl) as FileDescriptor
                    if (fd.isLiveHandle()) return fd
                }
                walk = walk!!.superclass
            }
        }
        // Hidden @hide helper on Socket itself (seen on some API 34 builds).
        runCatching {
            val method = socket.javaClass.getDeclaredMethod("getFileDescriptor\$")
                .apply { isAccessible = true }
            val fd = method.invoke(socket) as FileDescriptor
            if (fd.isLiveHandle()) return fd
        }
        return null
    }

    /**
     * True when the descriptor is backed by a live OS handle. Named
     * `isLiveHandle()` rather than `valid()` because Android API 33+ added a
     * `valid()` member on [FileDescriptor], which would shadow an extension
     * of the same name and quietly skip our libcore `getInt$` probe.
     */
    private fun FileDescriptor.isLiveHandle(): Boolean =
        runCatching {
            javaClass.getDeclaredMethod("getInt\$")
                .apply { isAccessible = true }
                .invoke(this) as Int != -1
        }.getOrDefault(true)
}
