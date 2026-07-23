package com.taosun.hanterm.ssh

/**
 * Lifecycle of an in-flight SSH teardown. Exposed by
 * [ConnectionRuntime.teardownState] so the UI can observe when a
 * `requestDisconnect()` is still running on the IO dispatcher versus
 * settled.
 *
 * Issue #15 — the canonical 7-step teardown order is load-bearing
 * (see `docs/ARCHITECTURE.md` §6 + [ConnectionRuntime.teardownInternal]).
 * This state is the public seam the UI uses to learn when that order has
 * finished — without depending on [ConnectionState], which already mirrors
 * the teardown's final state.
 */
sealed class TeardownState {
    /** No teardown in flight. The initial value and the value after a teardown completes. */
    data object Idle : TeardownState()

    /**
     * A `requestDisconnect()` intent has been accepted and the 7-step
     * teardown is running. The `_state` and `_view` still hold their
     * Connected values until step 7 publishes the idle pair; observers can
     * use this signal to show a "Disconnecting…" indicator without
     * flipping the connection state.
     */
    data object TearingDown : TeardownState()

    /**
     * Teardown completed. [finalState] mirrors the
     * [ConnectionState] that was published in step 7 (Disconnected for a
     * user-tap, Error for a transport-error / watchdog path).
     */
    data class Complete(val finalState: ConnectionState) : TeardownState()
}