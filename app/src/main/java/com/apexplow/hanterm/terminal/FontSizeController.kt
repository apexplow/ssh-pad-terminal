package com.apexplow.hanterm.terminal

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Cross-layer bridge for font-size change requests.
 *
 * **Owns no state.** The original font-size state machine lived here as a
 * `mutableStateOf<Int>` updated by `MainActivity.onKeyDown` and read by the
 * Compose tree. Issue #41 hoisted that state into [com.apexplow.hanterm.ui.HanTermAppViewModel]
 * — the ViewModel is the single owner of the live font-size value, persists
 * it via `AppPreferences`, and exposes it as a Compose `State<Int>`.
 *
 * This object's remaining job is to let `MainActivity.onKeyDown` (a
 * *non-Composable* call site) publish a new font size to the ViewModel
 * without holding a reference to it. The Activity does not know the
 * `ViewModelStoreOwner` and has no `viewModel(factory = ...)` call — it
 * simply computes the next size from `AppPreferences.fontSize` (clamped),
 * persists it, and emits the absolute value here. The VM collects
 * [sizeRequests] in `viewModelScope` and applies the change.
 *
 * Values are **absolute** (not deltas) and **latest-wins**:
 *  - `replay = 0` + `DROP_OLDEST` — a burst of key events never blocks the
 *    producer; the consumer eventually sees the latest value.
 *  - restart source of truth is `AppPreferences.fontSize`, so a missed
 *    pre-collector emission is harmless.
 *
 * Unrelated transient snackbar messages (font-size confirmation, trzsz /
 * zmodem transfer status) live on [com.apexplow.hanterm.ui.UiMessageBridge].
 */
object FontSizeController {

    private val _sizeRequests = MutableSharedFlow<Int>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Stream of absolute font-size values requested by an imperative writer. */
    val sizeRequests: SharedFlow<Int> = _sizeRequests.asSharedFlow()

    /**
     * Publish a new absolute font size (already clamped to
     * `AppPreferences.MIN_FONT_SIZE..MAX_FONT_SIZE` and persisted — the
     * production caller is `MainActivity.onKeyDown`).
     */
    fun requestSizeChange(size: Int) {
        _sizeRequests.tryEmit(size)
    }
}
