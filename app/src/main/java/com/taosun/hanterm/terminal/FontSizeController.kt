package com.taosun.hanterm.terminal

import androidx.compose.runtime.mutableStateOf
import com.taosun.hanterm.data.prefs.AppPreferences
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Cross-layer bridge for the user-adjustable terminal font size.
 *
 * MainActivity.onKeyDown writes the new value here (and persists to
 * [AppPreferences]); the Compose tree reads [state] and the
 * [snackbarMessages] channel to apply the new size to every live
 * TerminalView and to surface a "Font size: N" confirmation snackbar.
 *
 * Why a singleton and not a ViewModel: the rest of the project keeps
 * transient state as plain `mutableStateOf` inside the composable
 * (connectionState, activeSession, showTerminal — see HanTermApp.kt).
 * Introducing a ViewModel for a single Int would set a precedent we
 * don't want to pay for elsewhere. The font size is global by
 * definition (every terminal pane should agree on it) and is the only
 * state that needs to survive the Activity boundary, so a tiny object
 * is the right shape.
 *
 * `state` is a `mutableStateOf<Int>` so reads from Compose are
 * observable. `snackbarMessages` is a CONFLATED channel — a held
 * volume key fires many ACTION_DOWN events per second, and the
 * SnackbarHostState will only render the latest message anyway.
 */
object FontSizeController {

    /**
     * Current font size in pixels. Seeded from [AppPreferences.fontSize]
     * in MainActivity.onCreate before setContent so a relaunch restores
     * the user's last choice.
     */
    val state = mutableStateOf(AppPreferences.DEFAULT_FONT_SIZE)

    private val _snackbarMessages = Channel<String>(Channel.CONFLATED)

    /**
     * Stream of transient status messages (e.g. "Font size: 16") that the
     * UI should surface via its [androidx.compose.material3.SnackbarHostState].
     * CONFLATED so a held key doesn't queue dozens of identical messages.
     */
    val snackbarMessages: ReceiveChannel<String> = _snackbarMessages

    /** Bump the size up by [AppPreferences.FONT_SIZE_STEP], clamped to MAX. */
    fun stepUp() {
        state.value = (state.value + AppPreferences.FONT_SIZE_STEP)
            .coerceAtMost(AppPreferences.MAX_FONT_SIZE)
    }

    /** Bump the size down by [AppPreferences.FONT_SIZE_STEP], clamped to MIN. */
    fun stepDown() {
        state.value = (state.value - AppPreferences.FONT_SIZE_STEP)
            .coerceAtLeast(AppPreferences.MIN_FONT_SIZE)
    }

    /** Push a transient status message; later messages replace earlier ones. */
    fun showMessage(message: String) {
        _snackbarMessages.trySend(message)
    }
}
