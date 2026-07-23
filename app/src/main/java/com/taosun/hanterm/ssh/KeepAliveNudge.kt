package com.taosun.hanterm.ssh

/**
 * The FGS keepalive layer's one capability: write a one-way SSH keepalive
 * probe if a live transport is available.
 *
 * Implementations are expected to be cheap, non-blocking, and safe to invoke
 * concurrently. Returning `true` means a probe was actually written to the
 * wire; `false` means "no live transport" or "the write failed" — both
 * indistinguishable to the caller on purpose, so the service can simply log
 * and move on.
 *
 * Issue #17 replaced the previous `SshClient.companion` `AtomicReference`
 * + `hasKeepAliveNudge()` / `nudgeTransportKeepAlive()` globals with this
 * explicit interface, and a process-wide [KeepAliveNudgeRegistry] that
 * [ConnectionRuntime] binds in `handleConnectSuccess` and clears in every
 * teardown path. The service knows nothing about the connector — it just
 * reads the registry on every tick.
 *
 * See `docs/ARCHITECTURE.md` §5 "The `KeepAliveNudge` seam (Issue #17)"
 * for the full binding flow.
 */
fun interface KeepAliveNudge {
    fun nudge(): Boolean
}
