package com.apexplow.hanterm.terminal.link

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.apexplow.hanterm.terminal.GestureConsumer
import com.apexplow.hanterm.terminal.TermuxViewBridge
import com.apexplow.hanterm.terminal.TouchDecision

/**
 * Long-press recognizer that fires when the user holds a finger on a
 * cell that [LinkOverlay] has flagged as a URL span.
 *
 * Implements [GestureConsumer] (Sprint 4 T7), so it slots into the
 * wrapper's gesture chain AFTER [com.apexplow.hanterm.terminal.ScrollbackController]
 * (multi-touch scroll wins, single-finger scroll loses via PassThrough,
 * single-finger long-press wins when on a URL cell).
 *
 * **Verdicts** — see [LinkDecision]. `LinkGesture` augments the
 * top-level [TouchDecision] with [LinkDecision.LongPress], which carries
 * the URL payload the dialog needs. The wrapper's gesture chain still
 * only sees `TouchDecision` values, so [LinkDecision.LongPress] is
 * collapsed to [TouchDecision.Consumed] at the boundary — the URL
 * payload is delivered through the [onLongPress] callback registered
 * by `LinkDialog` (Sprint 4 Step 11).
 *
 * **Sprint 4 ties:**
 *   - isComposing check (T1): while the IME has an active composing
 *     region, [isComposingProvider] returns `true` and this consumer
 *     short-circuits to [TouchDecision.PassThrough]. The IME owns the
 *     touch mid-拼音 — stealing it for a URL dialog would be a worse
 *     failure than missing the long-press.
 *   - Immediate deliver on detector long-press: `GestureDetector` fires
 *     `onLongPress` via a Main-handler runnable between DOWN and UP,
 *     often with **no** intervening MOVE. Delivering only from
 *     `onTouchEvent` lost the URL on the subsequent UP (cleared as
 *     "stale") — real devices then saw only Termux's Copy/More. The
 *     dialog callback is invoked from the detector callback itself.
 *   - cancelInnerGesture (T2) + stopTextSelectionMode: pre-empt /
 *     tear down Termux's peer selection ActionMode on the same touch.
 *   - [isLinkLongPressActive] (ActionMode race): latched when a URL
 *     long-press is recognised so `SafeTextSelectionActionModeCallback`
 *     can refuse `onCreateActionMode`. Cleared on the next
 *     [MotionEvent.ACTION_DOWN] (or via [clearLinkLongPressActive]).
 *
 * **Threading:** Main thread only (same contract as the rest of the
 * gesture chain). The active latch is `@Volatile` so a late ActionMode
 * create on the same Main handler always observes the write.
 */
internal class LinkGesture(
    private val context: Context,
    private val view: View,
    private val overlay: LinkOverlay,
    private val bridge: TermuxViewBridge,
    private val isComposingProvider: () -> Boolean,
    /**
     * Fires when long-press lands on a URL span. `LinkDialog` (Step 11)
     * registers this callback; everything else in the system stays
     * unaware of the URL payload. Single-shot per gesture; the dialog
     * dismisses itself on user action.
     */
    private val onLongPress: (String) -> Unit,
) : GestureConsumer {

    /**
     * Sprint 4 LinkDecision — internal verdict carrier for [LinkGesture].
     *
     * Stands alone (does NOT extend [TouchDecision]) because Kotlin
     * sealed types require all subclasses to live in the same package
     * as the base. `TouchDecision` is in `terminal/`; `LinkGesture`
     * is in `terminal/link/`. The seam is fine: the wrapper's
     * [com.apexplow.hanterm.terminal.TerminalView.dispatchTouchEvent]
     * sees only [TouchDecision] values from this consumer — the
     * `LongPress(url)` carrier is consumed inside the detector callback
     * and [claimUntilUp] collapses subsequent events to
     * [TouchDecision.Consumed].
     */
    sealed interface LinkDecision {
        /**
         * Long-press landed on a URL span. [url] is the validated
         * URL string ready to dispatch to `LinkIntentLauncher`.
         */
        data class LongPress(val url: String) : LinkDecision
    }

    /**
     * `true` from the moment a URL long-press is recognised until the
     * next gesture cycle ([MotionEvent.ACTION_DOWN]) or an explicit
     * [clearLinkLongPressActive]. Read by
     * `TerminalView.SafeTextSelectionActionModeCallback` to deny Termux's
     * floating selection toolbar when `LinkDialog` owns the gesture.
     */
    @Volatile
    var isLinkLongPressActive: Boolean = false
        private set

    /**
     * After a URL long-press is delivered, consume the remainder of this
     * pointer sequence (MOVE/UP/CANCEL) so Termux's inner view does not
     * keep driving selection on the same finger.
     */
    private var claimUntilUp: Boolean = false

    /** Drop the ActionMode-deny latch (dialog dismiss / test reset). */
    fun clearLinkLongPressActive() {
        isLinkLongPressActive = false
    }

    private val detector: GestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                if (isComposingProvider()) return
                val renderer = bridge.view.mRenderer ?: return
                val fontWidth: Float = renderer.getFontWidth().toFloat()
                val fontLineSpacing: Float = renderer.getFontLineSpacing().toFloat()
                if (fontWidth <= 0f || fontLineSpacing <= 0f) return

                // Touch y/x are screen-local; spans are keyed by absolute
                // transcript row (mTopRow + screen offset) — same mapping
                // LinkOverlayView uses when painting underlines.
                val screenRow: Int = (e.y / fontLineSpacing).toInt()
                val col: Int = (e.x / fontWidth).toInt()
                val span = overlay.findUrlAtScreen(screenRow, col) ?: return

                isLinkLongPressActive = true
                claimUntilUp = true
                // T2: pre-empt Termux's own GestureDetector / tear down an
                // ActionMode that already won the race.
                bridge.cancelInnerGesture()
                bridge.stopTextSelectionMode()
                // Deliver HERE — do not wait for a later onTouchEvent.
                // Real tablets often go DOWN → (handler long-press) → UP
                // with no MOVE; deferring to onTouchEvent drops the URL.
                onLongPress(span.url)
            }
        },
    ).apply {
        // Default long-press threshold (ViewConfiguration.getLongPressTimeout())
        // is 500 ms — matches Material defaults.
        setIsLongpressEnabled(true)
    }

    override fun onTouchEvent(ev: MotionEvent): TouchDecision {
        // T1: while IME is composing, this gesture is dormant. The IME
        // decides what the long-press means (typically: select a word
        // from the candidate list). We pass through so the wrapper
        // also falls through to `super.dispatchTouchEvent` — the IME
        // already has the InputConnection hooked up.
        if (isComposingProvider()) {
            return TouchDecision.PassThrough
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Reset for the new touch. The detector's own state
                // gets the DOWN it needs from `detector.onTouchEvent`
                // below.
                isLinkLongPressActive = false
                claimUntilUp = false
            }
        }

        detector.onTouchEvent(ev)

        if (claimUntilUp) {
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                claimUntilUp = false
                // Keep isLinkLongPressActive until dismiss / next DOWN so
                // a late Termux ActionMode create is still denied.
            }
            return TouchDecision.Consumed
        }
        return TouchDecision.PassThrough
    }
}
