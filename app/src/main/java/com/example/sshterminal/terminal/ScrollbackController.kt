package com.example.sshterminal.terminal

import android.view.MotionEvent
import android.view.View
import com.termux.terminal.TerminalEmulator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the two-finger scrollback gesture on the pad SSH client.
 *
 * Responsibilities (full scope in
 * docs/superpowers/specs/2026-06-30-gesture-scrollback-design.md):
 *   1. Multi-touch detection — pass through single-finger events,
 *      consume two-finger events and translate the dy to row deltas
 *      written to emulator.mTopRow.
 *   2. New-output counting — `pendingOutputCount` accumulates while
 *      the user is scrolled back; the banner reads it to render the
 *      "▼ N 行新输出" badge.
 *   3. State emission — `StateFlow<ScrollbackState>` is the single
 *      source of truth for the banner. Writes from IO thread go
 *      through `view.post { ... }` to land on the UI thread before
 *      any emission (Termux's emulator is single-threaded; same
 *      contract as TerminalView.reportPtyResize).
 *
 * No `release()` lifecycle — the controller is owned by the wrapper
 * and GC'd with it. Matches [SelectionController].
 */
class ScrollbackController(
    private val view: View,
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

    private var anchorPointerY: Float? = null

    /**
     * Consult the controller for a single MotionEvent. The wrapper calls
     * this from `dispatchTouchEvent` BEFORE `super`. Returns PassThrough
     * for single-finger events (the inner view handles them); returns
     * Consumed for two-finger events (the controller owns the gesture).
     *
     * Threading: UI thread only.
     */
    fun onTouchEvent(ev: MotionEvent): TouchDecision {
        if (ev.pointerCount < 2) return TouchDecision.PassThrough

        // We are in (or continuing) a two-finger gesture.
        if (anchorPointerY == null) {
            anchorPointerY = centroidY(ev)
        }
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            // First 2-finger frame; no delta yet. Just arm the anchor and
            // flip the state. Subsequent MOVE frames will scroll.
            _state.value = _state.value.copy(isInScrollback = true)
            return TouchDecision.Consumed
        }
        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            return applyMove(ev)
        }
        // Other two-finger events (POINTER_UP, etc.): consume so the
        // inner view doesn't see them, but don't change mTopRow.
        return TouchDecision.Consumed
    }

    private fun applyMove(ev: MotionEvent): TouchDecision {
        val lineSpacing = fontLineSpacing()
        if (lineSpacing <= 0f) {
            // Defensive: renderer not ready. Don't write to mTopRow.
            // State is still in scrollback (we entered on POINTER_DOWN).
            return TouchDecision.Consumed
        }
        val anchor = anchorPointerY ?: return TouchDecision.Consumed
        val currentY = centroidY(ev)
        val deltaY = currentY - anchor
        // deltaY > 0 → fingers moved DOWN → see NEWER content → mTopRow
        // DECREASES. deltaY < 0 → fingers moved UP → see OLDER content →
        // mTopRow INCREASES. So `mTopRow += -deltaY / lineSpacing`.
        val deltaRows = (-deltaY / lineSpacing).toInt()
        val maxTopRow = (emulator.mTotalRows - emulator.mRows).coerceAtLeast(0)
        val currentTopRow = emulator.mTopRow
        val newTopRow = (currentTopRow + deltaRows).coerceIn(0, maxTopRow)
        emulator.mTopRow = newTopRow
        // Update the anchor so the NEXT MOVE frame is incremental.
        anchorPointerY = currentY
        return TouchDecision.Consumed
    }

    private fun centroidY(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getY(i)
        return sum / ev.pointerCount
    }
}
