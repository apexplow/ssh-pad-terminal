package com.apexplow.hanterm.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Cross-layer bridge for transient UI messages (Snackbars).
 *
 * Used by anything that is *outside* the Compose composition but needs to
 * surface a transient message inside the live `SnackbarHostState` (which is
 * owned by [HanTermAppViewModel]). Today that is:
 *
 *  - `MainActivity.onKeyDown` — "Font size: N" confirmation when the user
 *    presses VOLUME_UP / VOLUME_DOWN.
 *  - `TerminalPane.applyInbound` — trzsz / zmodem transfer status
 *    ("Saved to Downloads: foo", "Transfer failed: ...").
 *
 * History: pre-Issue #41 these messages were piggy-backed on
 * `FontSizeController.snackbarMessages`, which was misleading because the
 * channel had nothing to do with font size. The split is part of the
 * Issue #41 hoist — `FontSizeController` is now a narrow font-size request
 * bridge, and this object owns the unrelated transient snackbar bus.
 *
 * **Implementation note**: the original prototype used `Channel(CONFLATED)`
 * for conflation semantics, but `Channel` iterators interact awkwardly with
 * the test dispatcher's virtual time — `for (m in channel)` raced with
 * synchronous `trySend` from a test-invoked `Activity.onKeyDown`. A
 * `MutableSharedFlow` with `extraBufferCapacity = 1` + `DROP_OLDEST` gives
 * the same "latest message wins" semantics, plays nicely with
 * `runTest { ... advanceUntilIdle() ... }`, and is consistent with
 * [com.apexplow.hanterm.terminal.FontSizeController]'s design.
 *
 * Process-singleton `object` is fine here because the flow is a one-way
 * fire-and-forget bus; no consumer ever holds the producer-side state.
 */
object UiMessageBridge {

    private val messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Stream of transient messages that the UI should surface via Snackbar. */
    val messageEvents: SharedFlow<String> = messages.asSharedFlow()

    /** Push a transient status message; later messages replace earlier ones. */
    fun showMessage(message: String) {
        messages.tryEmit(message)
    }
}
