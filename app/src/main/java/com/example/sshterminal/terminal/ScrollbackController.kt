package com.example.sshterminal.terminal

import android.view.MotionEvent
import android.view.View
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView as TermuxTerminalView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    /** True between the first 2-finger POINTER_DOWN and the final ACTION_UP.
     *  While active, [onTouchEvent] returns Consumed for ALL events regardless
     *  of pointerCount, so a POINTER_UP that drops pointerCount from 2 to 1
     *  does NOT leak to the inner view (the spec invariant). */
    private var gestureActive: Boolean = false

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
     * Account for [byteCount] bytes that the emulator just absorbed while
     * we were scrolled back. Line estimate = `max(1, byteCount / columns)`;
     * floor at 1 so a stray carriage return still registers as "something
     * happened" and the banner badge updates.
     *
     * Threading: the StateFlow.update is thread-safe (the underlying
     * AtomicReference inside MutableStateFlow uses compareAndSet under the
     * hood), so this can be called from any thread.
     */
    fun onTranscriptWrite(byteCount: Int, columns: Int) {
        if (byteCount <= 0) return
        val safeColumns = columns.coerceAtLeast(1)
        val lines = (byteCount / safeColumns).coerceAtLeast(1)
        _state.update { current ->
            current.copy(pendingOutputCount = current.pendingOutputCount + lines)
        }
    }

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
        // While a two-finger gesture is in flight, EVERY event is consumed
        // — including the single-pointer ACTION_POINTER_UP that drops
        // pointerCount from 2 to 1, and the final single-pointer ACTION_UP.
        // This is the spec invariant: two-finger events NEVER propagate to
        // the inner view.
        if (gestureActive) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    gestureActive = false
                    commitGesture()
                }
                MotionEvent.ACTION_CANCEL -> {
                    // System interrupted the gesture; clear state without
                    // dispatching a scroll.
                    gestureActive = false
                    gestureInitialY = null
                    gestureFinalY = null
                    lastMoveEvent = null
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Third (or later) finger landed mid-gesture; re-arm the
                    // anchor so the gesture centroid reflects all pointers.
                    if (gestureInitialY == null) {
                        gestureInitialY = centroidY(ev)
                    }
                    _state.value = _state.value.copy(isInScrollback = true)
                }
                MotionEvent.ACTION_MOVE -> {
                    gestureFinalY = centroidY(ev)
                    lastMoveEvent = ev
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // One finger lifted but the gesture continues; do nothing
                    // (the next MOVE updates the centroid).
                }
            }
            return TouchDecision.Consumed
        }

        // No active gesture: single-finger events pass through.
        if (ev.pointerCount < 2) return TouchDecision.PassThrough

        // Two-finger POINTER_DOWN starts a new gesture.
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            gestureActive = true
            gestureInitialY = centroidY(ev)
            gestureFinalY = centroidY(ev)
            lastMoveEvent = null
            _state.value = _state.value.copy(isInScrollback = true)
            return TouchDecision.Consumed
        }
        return TouchDecision.PassThrough
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

    /**
     * Jump to the live view, clear pending output, and exit scrollback
     * mode. Safe to call from the banner click handler.
     *
     * Implementation: write mTopRow=0 directly via the cached reflection
     * Field. This is O(1); using doScroll with an oversize amount would
     * make the inner view iterate Math.abs(amount) times. Also clears the
     * gesture state so a queued ACTION_UP that arrives after this call
     * does not re-page.
     *
     * Threading: UI thread only.
     */
    fun scrollToBottom() {
        // Clear any in-flight gesture so a late ACTION_UP doesn't trigger
        // a spurious commitGesture after the banner tap.
        gestureActive = false
        gestureInitialY = null
        gestureFinalY = null
        lastMoveEvent = null
        runCatching {
            innerTopRowField.setInt(innerView, 0)
            innerView.postInvalidateOnAnimation()
        }.onFailure {
            com.example.sshterminal.logging.AppLog.w(
                "ScrollbackController", "scrollToBottom reflection failed", it,
            )
        }
        _state.value = ScrollbackState() // isInScrollback=false, pending=0
    }

    private fun centroidY(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getY(i)
        return sum / ev.pointerCount
    }
}