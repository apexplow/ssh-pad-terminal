package com.taosun.hanterm

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.taosun.hanterm.data.prefs.AppPreferences
import com.taosun.hanterm.terminal.FontSizeController
import com.taosun.hanterm.ui.HanTermApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Issue #19 / targetSdk 36: edge-to-edge is enforced. Scaffold in
        // HanTermApp already applies contentWindowInsets via paddingValues.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Seed the font-size controller from persisted prefs BEFORE Compose
        // runs, so the first frame already shows the user's chosen size.
        // AppPreferences' fontSize getter clamps to [MIN, MAX] so a corrupted
        // store can never reach the renderer.
        FontSizeController.state.value = AppPreferences(this).fontSize
        setContent { HanTermApp() }
    }

    /**
     * Volume up / down steps the terminal font size. Returning `true`
     * consumes the event so the system does NOT also adjust media volume
     * and does NOT pop the media-volume slider — a held volume key fires
     * many ACTION_DOWN events with `repeatCount > 0`; we step on every one
     * so holding the key ramps the size quickly, which matches the user's
     * mental model of "the bigger I press, the more it changes".
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val current = FontSizeController.state.value
        val newSize: Int? = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP ->
                (current + AppPreferences.FONT_SIZE_STEP)
                    .coerceAtMost(AppPreferences.MAX_FONT_SIZE)
            KeyEvent.KEYCODE_VOLUME_DOWN ->
                (current - AppPreferences.FONT_SIZE_STEP)
                    .coerceAtLeast(AppPreferences.MIN_FONT_SIZE)
            else -> null
        }
        if (newSize != null) {
            FontSizeController.state.value = newSize
            // Persist so the choice survives process death. HanTermApp reads
            // the same SharedPreferences on next launch (via its own
            // AppPreferences instance) and MainActivity re-seeds the
            // controller from it in onCreate.
            AppPreferences(this).fontSize = newSize
            // The snackbar lives in Compose (mounted in HanTermApp's
            // Scaffold). Push the message through the controller's channel
            // and let the LaunchedEffect there render it.
            FontSizeController.showMessage("Font size: $newSize")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
