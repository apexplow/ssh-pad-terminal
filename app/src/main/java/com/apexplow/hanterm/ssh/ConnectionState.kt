package com.apexplow.hanterm.ssh

/**
 * Connection state machine. Owned by [ConnectionRuntime] (canonical home for
 * everything connection-layer). The UI reads it via the runtime's
 * `state: StateFlow<ConnectionState>` exposed through `HanTermAppViewModel`.
 *
 * See `docs/superpowers/specs/2026-07-22-connection-runtime-design.md` for the
 * "why the runtime owns this" rationale.
 *
 * Migrated from `com.apexplow.hanterm.ui.HanTermApp.kt` so the runtime (which
 * publishes it) doesn't take a back-reference into the UI package. The ui
 * package keeps a `typealias` shim for one Sprint; see step 7 of the spec.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val summary: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}