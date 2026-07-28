package com.apexplow.hanterm.ssh

import com.apexplow.hanterm.logging.AppLog
import com.apexplow.hanterm.terminal.PtyBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Glues an [SshSession] to a [PtyBridge]'s transport side.
 *
 * The bridge is the hub; this adapter just plumbs the two ends
 * of the bridge to the SSH session so the rest of the app keeps
 * its existing flow (the IME chain calls `endpoint.write(bytes)`
 * into a [com.apexplow.hanterm.terminal.PtyBridgeEndpoint],
 * which goes to `bridge.view.write`; the adapter's outbound
 * coroutine reads `bridge.transport.read()` and forwards to
 * `session.write`).
 *
 * Two coroutines plus a resize listener:
 *
 * - **outbound** — `bridge.transport.read()` → `session.write`.
 *   Blocks on the bridge queue, exits cleanly when the bridge
 *   EOFs (EOF after `bridge.close()`).
 * - **inbound** — `session.readInto { ... }` →
 *   `bridge.transport.write`. Drains the SSH channel via the
 *   existing SshSession read loop, which handles socket errors
 *   and structured cancellation already.
 * - **resize** — `bridge.setResizeListener { cols, rows -> session.resizePty(cols, rows) }`.
 *   The view calls `bridge.resize`; we forward to the SSH
 *   channel as a window-change request.
 *
 * ## Lifecycle
 *
 * The adapter does **not** own its own scope. The caller passes
 * one (production: `ConnectionRuntime`'s `ioScope` =
 * `SupervisorJob() + Dispatchers.IO`; tests: a `StandardTestDispatcher`
 * scope) and cancellation of that scope ends both coroutines. The
 * adapter returns the launched [Job] so the caller can `join()` it
 * for orderly shutdown.
 *
 * The three children (outbound / inbound / watchdog) run on
 * [Dispatchers.IO] — production-correct and test-correct.
 * `BufferedPtyBridge.Endpoint.read()` uses Java's blocking
 * `LinkedBlockingQueue.take()` (not a suspending `Channel.receive()`),
 * so the children MUST run on a real thread pool; a virtual-time
 * dispatcher (e.g. `StandardTestDispatcher`) would deadlock the
 * caller the moment `take()` blocks — see memory note
 * `hanterm-ssh-bridgeadapter-io-children-vs-test-scheduler` for the
 * analysis. Tests rely on real-time ticking of the IO pool while
 * the test scheduler is parked on the launched teardown's
 * `cancelAndJoin`; this is the same trade-off the OLD suspend
 * `disconnect()` had, and it works because real time always
 * advances regardless of which dispatcher a coroutine is on.
 *
 * ## Why this lives in `ssh/`
 *
 * It depends on the SSH-specific [SshSession] and
 * [com.apexplow.hanterm.ssh.SshErrorMessages] reads. A
 * future mosh adapter (`MoshBridgeAdapter`) will sit next to
 * it under `ssh/` and share the [PtyBridge]-shaped contract.
 */
class SshBridgeAdapter(
    private val session: SshSession,
    private val bridge: PtyBridge,
    /**
     * How long the inbound coroutine can go without reading any bytes
     * from the SSH channel before the watchdog optimistically closes
     * the session. The default 2 × [SshConfig.SO_TIMEOUT_MS] gives a
     * generous safety margin above the socket-level read timeout
     * (60 s) so we never race the OS — if SO_TIMEOUT fires, the
     * inbound's own catch wins; if it doesn't (e.g. sshj's
     * ChannelInputStream swallows the timeout in 0.40), the watchdog
     * still tears the session down within ~2 minutes.
     *
     * Tests pass a small value (e.g. 200 L) so the watchdog can fire
     * inside a `@Test(timeout = 10_000)` window.
     */
    private val idleTimeoutMs: Long = SshConfig.SO_TIMEOUT_MS.toLong() * 2,
) {

    /**
     * Timestamp of the last successful inbound read, in
     * `System.currentTimeMillis()` units. Initialised lazily in
     * [start] so a long gap between adapter construction and
     * `start()` can't trip the watchdog before the first real read.
     */
    private val lastReadAt = AtomicLong(0L)

    /**
     * Start the two coroutines, the resize forwarder, and the idle
     * watchdog. Returns a [Job] that completes when all three children
     * have finished (i.e., the bridge has been closed OR the caller
     * cancelled the scope OR the watchdog fired).
     */
    fun start(scope: CoroutineScope): Job {
        // The resize forwarder has to be registered outside the
        // structured-concurrency block: the bridge's listener
        // slot is shared across all bridges/sessions, so it must
        // be set up before any layout pass can fire. The closure
        // runs on whatever thread the bridge chose (typically the
        // view's main thread); SshSession.resizePty enqueues onto
        // its write executor and returns immediately, so it's
        // safe from any thread.
        bridge.setResizeListener { cols, rows ->
            session.resizePty(cols, rows)
        }
        // Pin lastReadAt to "now" so the watchdog's first poll
        // sees a fresh timestamp, not the AtomicLong's initial 0
        // (which would be ~55 years in the past and trip on the
        // very first iteration).
        lastReadAt.set(System.currentTimeMillis())

        return scope.launch {
            coroutineScope {
                val outbound = async(Dispatchers.IO) {
                    try {
                        while (currentCoroutineContext().isActive) {
                            // Pull user-side bytes from the
                            // bridge and forward upstream.
                            // Returning null means the bridge
                            // has been closed and will never
                            // deliver again — exit cleanly.
                            val bytes = bridge.transport.read() ?: return@async
                            // SshSession.write already drops
                            // empty payloads, but checking here
                            // keeps the contract explicit.
                            if (bytes.isNotEmpty()) session.write(bytes)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    }
                }
                val inbound = async(Dispatchers.IO) {
                    // SshSession.readInto handles EOF (clean),
                    // SocketException / SocketTimeoutException /
                    // SSHException (transport error), and
                    // CancellationException (structured cancel)
                    // — all of them exit this coroutine.
                    try {
                        session.readInto { bytes ->
                            // Stamp the watchdog timestamp BEFORE
                            // the bridge write — the read has
                            // already succeeded at this point, and
                            // a slow bridge.queue.put shouldn't
                            // let the watchdog fire.
                            lastReadAt.set(System.currentTimeMillis())
                            bridge.transport.write(bytes)
                        }
                    } finally {
                        // Inbound ended for any reason: clean EOF,
                        // session error, structured cancellation.
                        // Close the bridge so the view-side read
                        // loop ([bridge.view.read] in TerminalPane)
                        // sees EOF and exits, and so the outbound
                        // coroutine (still running above) unblocks
                        // and exits via its bridge.transport.read
                        // returning null. bridge.close() is
                        // idempotent (compareAndSet), so calling it
                        // here even when the user-initiated
                        // disconnect path also closed it is fine.
                        bridge.close()
                    }
                }
                val watchdog = launch(Dispatchers.IO) {
                    // Poll 4× per deadline so a single deferred
                    // tick can't delay the close by more than 25%
                    // of the budget. Floor at 5 s so we don't
                    // busy-loop on a tiny test timeout.
                    val pollMs = (idleTimeoutMs / 4).coerceAtLeast(5_000L)
                    var fired = false
                    while (!fired && currentCoroutineContext().isActive) {
                        delay(pollMs)
                        val age = System.currentTimeMillis() - lastReadAt.get()
                        if (age > idleTimeoutMs) {
                            // BG-IDLE: the optimistic-close path.
                            // Don't claim this is a network error
                            // — sshj's keepalive means the socket
                            // might still be alive, we just
                            // haven't heard from the remote in a
                            // while. The dedicated
                            // [SessionCloseReason.IdleTimeout] lets
                            // the UI render a distinct message
                            // instead of a generic "Network
                            // error" hint.
                            AppLog.w(
                                TAG,
                                "idle watchdog: no inbound read for ${age}ms (>= ${idleTimeoutMs}ms); force-closing session",
                            )
                            session.setCloseReasonFromWatchdog()
                            session.close()
                            // session.close() puts EOF on the
                            // bridge via the inbound's `finally`
                            // — outbound will exit on its next
                            // read. We can stop polling.
                            fired = true
                        }
                    }
                }
                // All three children complete when their own
                // conditions trip (transport EOF, session
                // close, scope cancel, watchdog fire). The
                // outer launch's Job completes only after
                // outbound and inbound have finished; the
                // watchdog is cancelled and joined last so its
                // exit state is observable. `cancelAndJoin()`
                // on an already-completed Job is a no-op, so
                // this is safe in the watchdog-fired path too.
                outbound.await()
                inbound.await()
                watchdog.cancelAndJoin()
            }
        }
    }

    companion object {
        // Distinct from "SshClient" / "SshSession" so a
        // logcat filter isolates watchdog-triggered closes
        // from socket-level catches. Kept private — the
        // watchdog only fires from inside this class.
        private const val TAG = "SshBridgeAdapter"
    }
}
