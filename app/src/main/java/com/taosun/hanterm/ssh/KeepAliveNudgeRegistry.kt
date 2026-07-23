package com.taosun.hanterm.ssh

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide binding point between [ConnectionRuntime] (the writer) and
 * [SshKeepAliveService] (the reader).
 *
 * Set after a connect succeeds — the writer is the live
 * `SshClient.keepAliveNudge` (or any future transport's analog). Cleared in
 * every teardown path:
 *  - `ConnectionRuntime.teardownInternal` (full disconnect)
 *  - `ConnectionRuntime.abandonHandshake` (handshake discarded by epoch
 *    invalidation)
 *  - `SshClient.disconnect` (safety net for the `SshSession.onClose` path,
 *    where the SSH thread calls `SshClient.disconnect` before
 *    `HanTermAppViewModel.onSessionClosed` fires on main)
 *
 * Process-wide is the right scope: [ConnectionRuntime] is itself
 * process-wide (constructed once in `HanTermApplication`), and the FGS
 * runs in the same process. The single-slot shape is deliberate — at
 * most one live transport at a time — and an `AtomicReference` keeps
 * the bind / read race-free without bringing in `MutableStateFlow`.
 *
 * Test isolation: the registry is a process-wide singleton, so unit
 * tests must `KeepAliveNudgeRegistry.set(null)` in `@After` to avoid
 * leakage across test classes (mirrors `AppLogTest`'s pattern for
 * `AppLog.policy`).
 */
object KeepAliveNudgeRegistry {
    private val current = AtomicReference<KeepAliveNudge?>(null)

    /** Bind the live nudge. Pass `null` to unbind. */
    fun set(nudge: KeepAliveNudge?) {
        current.set(nudge)
    }

    /** Returns the bound nudge, or `null` if no transport is live. */
    fun get(): KeepAliveNudge? = current.get()
}
