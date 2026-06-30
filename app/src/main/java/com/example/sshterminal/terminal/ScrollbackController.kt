package com.example.sshterminal.terminal

import android.view.MotionEvent
import android.view.View
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView as TermuxTerminalView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the two-finger page-by-page scrollback gesture on the pad SSH client.
 *
 * Responsibilities (full scope in
 * docs/superpowers/specs/2026-06-30-gesture-scrollback-design.md):
 *   1. Multi-touch detection — pass through single-finger events, consume
 *      two-finger events at the wrapper dispatchTouchEvent layer so the
 *      inner view's GestureDetector never sees them.
 *   2. Page scroll — on gesture end (ACTION_UP), if the swipe exceeds a
 *      half-page threshold, invoke
 *      `com.termux.view.TerminalView.doScroll(MotionEvent, Int)` via
 *      reflection with `±emulator.mRows` to step the inner view's
 *      `mTopRow` by exactly one page. The inner view's own scrollback
 *      path (branch 3 in the AltBufferScrollCrashGuardTest root-cause
 *      kdoc) handles the actual mutation.
 *   3. New-output counting — `pendingOutputCount` accumulates while the
 *      user is scrolled back; the banner reads it to render the
 *      "▼ N 行新输出" badge.
 *   4. State emission — `StateFlow<ScrollbackState>` is the single
 *      source of truth for the banner. Writes from IO thread go
 *      through `view.post { ... }` to land on the UI thread before
 *      any emission.
 *
 * No `release()` lifecycle — the controller is owned by the wrapper
 * and GC'd with it. Matches [SelectionController].
 */
class ScrollbackController(
    private val view: View,
    private val innerView: TermuxTerminalView,
    private val emulator: TerminalEmulator,
    private val fontLineSpacing: () -> Float,
) {
    /** Result of consulting the controller for a MotionEvent. */
    sealed interface TouchDecision {
        /** Wrapper should call super.dispatchTouchEvent (single-finger). */
        data object PassThrough : TouchDecision

        /** Wrapper should swallow the event and return true. */
        data object Consumed : TouchDecision
    }

    /** Banner state. Both fields are read on UI thread only. */
    data class ScrollbackState(
        val isInScrollback: Boolean = false,
        val pendingOutputCount: Int = 0,
    )

    private val _state = MutableStateFlow(ScrollbackState())
    val state: StateFlow<ScrollbackState> = _state.asStateFlow()

    private var gestureInitialY: Float? = null
    private var gestureFinalY: Float? = null
    private var lastMoveEvent: MotionEvent? = null

    private val doScrollMethod: java.lang.reflect.Method by lazy {
        TermuxTerminalView::class.java.getDeclaredMethod(
            "doScroll",
            MotionEvent::class.java,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
    }

    private val innerTopRowField: java.lang.reflect.Field by lazy {
        TermuxTerminalView::class.java.getDeclaredField("mTopRow").apply { isAccessible = true }
    }

    /**
     * Reads the inner view's `mTopRow` (package-private in
     * `com.termux.view.TerminalView`) via reflection. Used by the
     * auto-exit check in [commitGesture] and exposed as `internal` so
     * scrollback tests can observe the doScroll side-effect end-to-end
     * without re-implementing the same reflection.
     */
    internal fun readInnerTopRow(): Int = innerTopRowField.getInt(innerView)

    /**
     * Consult the controller for a single MotionEvent. The wrapper calls
     * this from `dispatchTouchEvent` BEFORE `super`. Returns PassThrough
     * for single-finger events (the inner view handles them); returns
     * Consumed for two-finger events (the controller owns the gesture).
     *
     * Page-by-page contract: the controller does NOT call doScroll on
     * every MOVE. It only tracks the gesture start/end Y positions; the
     * actual page scroll happens on ACTION_UP (and is implemented in
     * Task 3).
     *
     * Threading: UI thread only.
     */
    fun onTouchEvent(ev: MotionEvent): TouchDecision {
        // ACTION_UP is the gesture-commit signal even though it always
        // carries a single pointer. Without this carve-out the early
        // pointerCount<2 check would PassThrough and the page scroll
        // would never fire.
        if (ev.actionMasked == MotionEvent.ACTION_UP && gestureInitialY != null) {
            commitGesture()
            return TouchDecision.Consumed
        }
        if (ev.pointerCount < 2) return TouchDecision.PassThrough

        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                // First 2-finger frame: arm the initial centroid.
                if (gestureInitialY == null) {
                    gestureInitialY = centroidY(ev)
                }
                _state.value = _state.value.copy(isInScrollback = true)
            }
            MotionEvent.ACTION_MOVE -> {
                gestureFinalY = centroidY(ev)
                lastMoveEvent = ev
            }
            MotionEvent.ACTION_UP -> {
                commitGesture()
                // commitGesture resets the gesture state.
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                // One finger lifted but gesture is still active; the
                // remaining pointer can keep moving. Don't clear state.
            }
        }
        return TouchDecision.Consumed
    }

    /**
     * Called when the LAST finger lifts. Computes the total dy of the
     * gesture and dispatches a one-page scroll via doScroll if the swipe
     * crossed the half-page threshold.
     *
     * Threading: UI thread only.
     */
    private fun commitGesture() {
        val initial = gestureInitialY
        val final = gestureFinalY
        val move = lastMoveEvent
        gestureInitialY = null
        gestureFinalY = null
        lastMoveEvent = null
        if (initial == null || final == null || move == null) return
        // Alt-buffer mode: consume the gesture (we already returned
        // Consumed from onTouchEvent) but don't call doScroll — branch 2
        // would NPE. The remote TUI owns scrolling.
        if (emulator.isAlternateBufferActive && !emulator.isMouseTrackingActive) return

        val dy = final - initial
        val lineSpacing = fontLineSpacing().takeIf { it > 0f } ?: return
        val threshold = lineSpacing * emulator.mRows / 2f
        val amount = when {
            dy < -threshold -> -emulator.mRows   // page up
            dy > threshold -> +emulator.mRows    // page down
            else -> return                        // no-op for tiny swipes
        }
        invokeDoScroll(move, amount)
        // Auto-exit if the page scroll brought us back to the live view.
        if (readInnerTopRow() == 0) {
            _state.value = _state.value.copy(isInScrollback = false)
        }
    }

    private fun invokeDoScroll(move: MotionEvent, amount: Int) {
        runCatching {
            doScrollMethod.invoke(innerView, move, amount)
            innerView.postInvalidateOnAnimation()
        }.onFailure {
            com.example.sshterminal.logging.AppLog.w(
                "ScrollbackController", "doScroll reflection failed", it,
            )
        }
    }

    private fun centroidY(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getY(i)
        return sum / ev.pointerCount
    }
}