package com.example.sshterminal.ssh

import com.example.sshterminal.terminal.PtyBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Glues an [SshSession] to a [PtyBridge]'s transport side.
 *
 * The bridge is the hub; this adapter just plumbs the two ends
 * of the bridge to the SSH session so the rest of the app keeps
 * its existing flow (the IME chain calls `endpoint.write(bytes)`
 * into a [com.example.sshterminal.terminal.PtyBridgeEndpoint],
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
 * one (production: `SshTermApp`'s `rememberCoroutineScope`;
 * tests: a fresh `CoroutineScope(SupervisorJob() +
 * Dispatchers.Unconfined)`) and cancellation of that scope ends
 * both coroutines. The adapter returns the launched [Job] so
 * the caller can `join()` it for orderly shutdown.
 *
 * ## Why this lives in `ssh/`
 *
 * It depends on the SSH-specific [SshSession] and
 * [com.example.sshterminal.ssh.SshErrorMessages] reads. A
 * future mosh adapter (`MoshBridgeAdapter`) will sit next to
 * it under `ssh/` and share the [PtyBridge]-shaped contract.
 */
class SshBridgeAdapter(
    private val session: SshSession,
    private val bridge: PtyBridge,
) {

    /**
     * Start the two coroutines and the resize forwarder. Returns
     * a [Job] that completes when both coroutines have finished
     * (i.e., the bridge has been closed OR the caller cancelled
     * the scope).
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
                // Both children complete when their own
                // conditions trip (transport EOF, session
                // close, scope cancel). The outer launch's Job
                // completes only after both have finished.
                outbound.await()
                inbound.await()
            }
        }
    }
}
