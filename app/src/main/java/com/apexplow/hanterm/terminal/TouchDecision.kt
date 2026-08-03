package com.apexplow.hanterm.terminal

import android.view.MotionEvent

/**
 * Verdict a [GestureConsumer] returns for a [MotionEvent].
 *
 * Lifted to a top-level type in Sprint 4 so multiple gesture handlers
 * could share the same vocabulary without one depending on the other
 * (the previous `TouchDecision` lived inside `ScrollbackController`).
 */
sealed interface TouchDecision {
    /**
     * Wrapper should call `super.dispatchTouchEvent` (this consumer
     * did not claim the event).
     */
    data object PassThrough : TouchDecision

    /**
     * Wrapper should swallow the event and return `true` — the consumer
     * owns the rest of this gesture and any subsequent MOVE/UP events.
     */
    data object Consumed : TouchDecision
}

/**
 * Sprint 4 T7 — uniform gesture-consumer interface. Any view-layer
 * gesture handler that wants first crack at the wrapper's
 * `dispatchTouchEvent` implements this and gets registered in
 * [TerminalView]'s gesture list.
 *
 * Invariants:
 *   - **Main thread only.** `onTouchEvent` is invoked from the wrapper's
 *     `dispatchTouchEvent`, which is Main-thread by Android contract.
 *   - **Stateless across consumers.** Each consumer is consulted
 *     independently; a `PassThrough` from one does not affect another's
 *     state.
 *   - **One-shot decisions.** Once a consumer returns [TouchDecision.Consumed],
 *     subsequent consumers are NOT consulted. The first claim wins.
 *
 * Existing implementations:
 *   - [ScrollbackController] — two-finger scrollback + single-finger
 *     scrollback after slop-cross.
 */
fun interface GestureConsumer {
    fun onTouchEvent(ev: MotionEvent): TouchDecision
}