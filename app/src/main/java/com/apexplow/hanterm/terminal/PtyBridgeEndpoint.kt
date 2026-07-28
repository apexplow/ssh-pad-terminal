package com.apexplow.hanterm.terminal

/**
 * A [TerminalEndpoint] that forwards every byte the IME pipeline
 * produces into a [PtyBridge]'s view side.
 *
 * This is the one-line adapter that lets `TerminalView`'s existing
 * IME chain (`onKeyDown`, `TerminalInputConnection.commitText`,
 * `pasteFromClipboard`, etc.) keep calling
 * `endpoint.write(bytes)` unchanged once the bridge is in the
 * circuit — `PtyBridgeEndpoint.write(bytes)` just forwards to
 * `bridge.view.write(bytes)`. The matching read-loop is the
 * companion piece landing in step 2b (a `BridgeReadLoop` that
 * drains `bridge.view.read()` into `emulator.append`).
 *
 * Why a class and not just an `object : TerminalEndpoint { ... }`
 * from the call site: keeping the wiring as a real class makes
 * it (a) substitutable in tests (pass any `PtyBridge` mock),
 * (b) trivially reusable across `HanTermApp`'s `Connected` and
 * `Disconnected` panels, and (c) side-effect-free at construction
 * so it can be created eagerly in `remember { ... }` without a
 * real session attached yet.
 *
 * The empty-write-is-no-op and post-close-write-is-no-op rules
 * are inherited verbatim from [PtyEndpoint.write] — this class
 * does not need to do anything special; the bridge enforces the
 * contract.
 */
class PtyBridgeEndpoint(
    private val bridge: PtyBridge,
) : TerminalEndpoint {
    override fun write(bytes: ByteArray) {
        bridge.view.write(bytes)
    }
}
