package com.apexplow.hanterm.terminal.link

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.apexplow.hanterm.terminal.GestureConsumer
import com.apexplow.hanterm.terminal.TermuxViewBridge
import com.apexplow.hanterm.terminal.TouchDecision

/**
 * Single-tap recognizer that fires when the user taps a cell the
 * [LinkOverlay] has flagged as a URL span.
 *
 * Implements [GestureConsumer] (Sprint 4 T7), so it slots into the
 * wrapper's gesture chain AFTER
 * [com.apexplow.hanterm.terminal.ScrollbackController] (multi-touch
 * scroll wins, single-finger scroll loses via PassThrough, single-finger
 * tap-and-hold on a URL cell wins via Consume + dialog).
 *
 * **2026-08-01 redesign — long-press → single-tap.** The original
 * Sprint 4 long-press UX lost the URL on real tablets because the
 * race between our `GestureDetector.onLongPress` and Termux's peer
 * selection `GestureDetector` was finicky. Worse, long-press hid the
 * feature behind a gesture users had to discover. The user-visible
 * flow is now: tap a URL → ModalBottomSheet "Open link?" → tap Open →
 * default browser. Long-press still triggers Termux's selection
 * toolbar (with our Share / Search web overflow items) so copy /
 * share of a URL stays one gesture away.
 *
 * **Sprint 4 ties (kept):**
 *   - isComposing check (T1): while the IME has an active composing
 *     region, [isComposingProvider] returns `true` and this consumer
 *     short-circuits to [TouchDecision.PassThrough]. The IME owns the
 *     touch mid-拼音 — stealing it for a URL dialog would be a worse
 *     failure than missing the long-press.
 *   - Immediate deliver on detector callback: `GestureDetector` fires
 *     `onSingleTapUp` from the Main handler on ACTION_UP — we deliver
 *     from the detector callback itself so no MOVE/UP ordering race
 *     drops the URL.
 *   - [cancelInnerGesture] / [stopTextSelectionMode]: belt-and-braces,
 *     harmless when nothing's selected.
 *
 * **Single-tap vs double-tap trade-off.** `onSingleTapUp` fires on
 * the first UP of a tap. If the user is starting a double-tap (word
 * selection) on a URL cell, the first tap will pop the dialog and the
 * second tap will hit the dialog, not the terminal. This is an
 * intentional choice — "click a URL, get a prompt" reads better than
 * "click a URL, wait 300 ms, maybe get a prompt", and double-tap on a
 * URL is a rare gesture (users who want to copy a URL long-press it).
 *
 * **Threading:** Main thread only (same contract as the rest of the
 * gesture chain).
 */
internal class LinkGesture(
    private val context: Context,
    private val view: View,
    private val overlay: LinkOverlay,
    private val bridge: TermuxViewBridge,
    private val isComposingProvider: () -> Boolean,
    /**
     * Fires when a single tap lands on a URL span. `LinkDialog` (Step 11)
     * registers this callback; everything else in the system stays
     * unaware of the URL payload.
     */
    private val onSingleTap: (String) -> Unit,
) : GestureConsumer {

    private val detector: GestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isComposingProvider()) return false
                val renderer = bridge.view.mRenderer ?: return false
                val fontWidth: Float = renderer.getFontWidth().toFloat()
                val fontLineSpacing: Float = renderer.getFontLineSpacing().toFloat()
                if (fontWidth <= 0f || fontLineSpacing <= 0f) return false

                // Touch y/x are screen-local; spans are keyed by absolute
                // transcript row (mTopRow + screen offset) — same mapping
                // LinkOverlayView uses when painting underlines.
                val screenRow: Int = (e.y / fontLineSpacing).toInt()
                val col: Int = (e.x / fontWidth).toInt()
                val span = overlay.findUrlAtScreen(screenRow, col) ?: return false

                // Deliver from the detector callback — not from
                // onTouchEvent on a later MOVE/UP — so a tap that the
                // user releases before any MOVE reaches the dialog.
                onSingleTap(span.url)
                return true
            }
        },
    ).apply {
        setIsLongpressEnabled(false) // 2026-08-01: long-press removed
    }

    override fun onTouchEvent(ev: MotionEvent): TouchDecision {
        // T1: while IME is composing, this gesture is dormant. The IME
        // decides what the tap means (typically: confirm a candidate
        // from the IME's candidate list). We pass through so the
        // wrapper also falls through to `super.dispatchTouchEvent` —
        // the IME already has the InputConnection hooked up.
        if (isComposingProvider()) {
            return TouchDecision.PassThrough
        }

        detector.onTouchEvent(ev)
        // Single tap is always PassThrough from the gesture-chain POV —
        // the URL prompt lives in a Compose ModalBottomSheet on top of
        // the terminal, not in the touch-dispatch flow. The detector
        // fires its callback from inside detector.onTouchEvent above.
        return TouchDecision.PassThrough
    }
}