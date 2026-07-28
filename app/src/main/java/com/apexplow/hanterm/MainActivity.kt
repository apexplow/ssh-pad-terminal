package com.apexplow.hanterm

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.apexplow.hanterm.data.prefs.AppPreferences
import com.apexplow.hanterm.terminal.FontSizeController
import com.apexplow.hanterm.ui.HanTermApp
import com.apexplow.hanterm.ui.UiMessageBridge

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Issue #19 / targetSdk 36: edge-to-edge is enforced. Scaffold in
        // HanTermApp already applies contentWindowInsets via paddingValues.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Issue #41: the seed-before-setContent invariant is preserved by
        // reading `AppPreferences.fontSize` synchronously inside the VM
        // constructor. The ViewModel is built on the first composition of
        // HanTermApp (via `viewModel(factory = ...)`), so the first frame
        // already shows the persisted value. `AppPreferences.fontSize`
        // getter clamps to [MIN, MAX] so a corrupted store can never
        // reach the renderer.
        setContent { HanTermApp() }
    }

    /**
     * Volume up / down steps the terminal font size. Returning `true`
     * consumes the event so the system does NOT also adjust media volume
     * and does NOT pop the media-volume slider — a held volume key fires
     * many ACTION_DOWN events with `repeatCount > 0`; we step on every one
     * so holding the key ramps the size quickly, which matches the user's
     * mental model of "the bigger I press, the more it changes".
     *
     * Issue #41: the Activity no longer mutates a global Compose state
     * directly. It persists the new size to `AppPreferences` (the on-disk
     * source of truth), publishes the absolute size to the
     * [FontSizeController] bridge (consumed by `HanTermAppViewModel`),
     * and posts a "Font size: N" confirmation through the
     * [UiMessageBridge] snackbar bus.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val prefs = AppPreferences(this)
        val current = prefs.fontSize
        val newSize: Int = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP ->
                (current + AppPreferences.FONT_SIZE_STEP)
                    .coerceAtMost(AppPreferences.MAX_FONT_SIZE)
            KeyEvent.KEYCODE_VOLUME_DOWN ->
                (current - AppPreferences.FONT_SIZE_STEP)
                    .coerceAtLeast(AppPreferences.MIN_FONT_SIZE)
            else -> return super.onKeyDown(keyCode, event)
        }
        // Persist first — the on-disk value is the restart source of truth.
        // Skip the publish path when the clamp kept us at the same value
        // (a held key at the boundary should not re-fire snackbars).
        if (newSize != current) {
            prefs.fontSize = newSize
            FontSizeController.requestSizeChange(newSize)
        }
        // Always surface the (possibly unchanged) size so the user gets
        // visible feedback that the key was consumed even at the boundary.
        UiMessageBridge.showMessage("Font size: $newSize")
        return true
    }
}
