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
 *     short-circuits to [LinkDecision.PassThrough]. The IME owns the
 *     touch mid-拼音 — stealing it for a URL dialog would be a worse
 *     failure than missing the long-press.
 *   - cancelInnerGesture (T2): when long-press fires on a URL, this
 *     consumer calls [TermuxViewBridge.cancelInnerGesture] so Termux's
 *     own GestureDetector doesn't also start text-selection mode on the
 *     same touch.
 *   - [isLinkLongPressActive] (ActionMode race): latched the moment a URL
 *     long-press is recognised — *before* Termux's peer GestureDetector
 *     may call `startActionMode`. `SafeTextSelectionActionModeCallback`
 *     reads this and returns `false` from `onCreateActionMode` so the
 *     Copy/Paste/More toolbar never renders beside `LinkDialog`. Cleared
 *     on the next [MotionEvent.ACTION_DOWN] (or via [clearLinkLongPressActive]).
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
     * `LongPress(url)` carrier is consumed inside `onTouchEvent` and
     * the URL is delivered through the registered [onLongPress]
     * callback before we return [TouchDecision.Consumed].
     */
    sealed interface LinkDecision {
        /**
         * Long-press landed on a URL span. [url] is the validated
         * URL string ready to dispatch to `LinkIntentLauncher`.
         */
        data class LongPress(val url: String) : LinkDecision
    }

    /** Single-shot latch — non-null while a long-press URL awaits dispatch. */
    private var pendingUrl: String? = null

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

    /** Drop the ActionMode-deny latch (dialog dismiss / test reset). */
    fun clearLinkLongPressActive() {
        isLinkLongPressActive = false
    }

    private val detector: GestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                if (isComposingProvider()) {
                    // IME owns the touch — swallow our long-press but
                    // don't queue a URL.
                    pendingUrl = null
                    return
                }
                val renderer = bridge.view.mRenderer ?: run {
                    pendingUrl = null
                    return
                }
                val fontWidth: Float = renderer.getFontWidth().toFloat()
                val fontLineSpacing: Float = renderer.getFontLineSpacing().toFloat()
                if (fontWidth <= 0f || fontLineSpacing <= 0f) {
                    pendingUrl = null
                    return
                }
                val row: Int = (e.y / fontLineSpacing).toInt()
                val col: Int = (e.x / fontWidth).toInt()
                val span = overlay.findUrlAt(row, col)
                pendingUrl = span?.url
                // Latch *here* (GestureDetector callback), not later in
                // onTouchEvent — Termux's peer long-press runnable is
                // posted to the same Main handler and may reach
                // onCreateActionMode before our next MOVE is dispatched.
                if (span != null) {
                    isLinkLongPressActive = true
                }
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
                pendingUrl = null
                isLinkLongPressActive = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // If the user lifts before long-press fires, the
                // detector's pending long-press callback is cancelled
                // internally. We just need to make sure we don't
                // dispatch a stale URL from a previous gesture.
                val stale = pendingUrl
                pendingUrl = null
                if (stale != null) {
                    // Undelivered URL — drop the deny latch so the next
                    // non-URL selection ActionMode is not blocked.
                    isLinkLongPressActive = false
                    // Should not happen — the detector cancels the
                    // pending long-press on UP/CANCEL. Logged at debug
                    // so a regression is visible in adb logcat.
                    return TouchDecision.PassThrough
                }
            }
        }

        detector.onTouchEvent(ev)

        val url = pendingUrl
        if (url != null) {
            pendingUrl = null
            isLinkLongPressActive = true
            // T2: pre-empt Termux's own GestureDetector so it doesn't
            // also enter text-selection mode on this touch.
            bridge.cancelInnerGesture()
            // Termux-first race: ActionMode may already be up — tear it
            // down so Copy/More does not sit beside LinkDialog.
            bridge.stopTextSelectionMode()
            onLongPress(url)
            return TouchDecision.Consumed
        }
        return TouchDecision.PassThrough
    }
}