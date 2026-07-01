package com.example.sshterminal.terminal

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import com.termux.terminal.KeyHandler
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
    /**
     * Sends raw bytes to the remote (the bound [TerminalEndpoint.write]).
     * Used only by the alternate-buffer page-scroll path, which translates
     * a page gesture into cursor-key presses for the remote TUI (vim, less,
     * tmux, …) instead of touching the local scrollback.
     */
    private val sendToRemote: (ByteArray) -> Unit,
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
        /** User-visible result of the last two-finger gesture (no adb needed). */
        val gestureHint: String? = null,
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
    /** True between a 1-finger ACTION_DOWN and scroll/tap resolution. */
    private var singleFingerTracking: Boolean = false
    private var hintClearRunnable: Runnable? = null

    private val touchSlop: Int by lazy {
        ViewConfiguration.get(view.context).scaledTouchSlop
    }

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
        // While a scroll gesture is in flight, EVERY event is consumed
        // — including the single-pointer ACTION_POINTER_UP that drops
        // pointerCount from 2 to 1, and the final single-pointer ACTION_UP.
        // Multi-touch scroll events NEVER propagate to the inner view once
        // the gesture is armed; single-finger scroll arms after touchSlop.
        if (gestureActive) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    gestureActive = false
                    singleFingerTracking = false
                    commitGesture()
                }
                MotionEvent.ACTION_CANCEL -> {
                    gestureActive = false
                    singleFingerTracking = false
                    gestureInitialY = null
                    gestureFinalY = null
                    lastMoveEvent = null
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (gestureInitialY == null) {
                        gestureInitialY = centroidY(ev)
                    }
                    _state.value = _state.value.copy(isInScrollback = true)
                }
                MotionEvent.ACTION_MOVE -> {
                    gestureFinalY = centroidY(ev)
                    lastMoveEvent = ev
                }
                MotionEvent.ACTION_POINTER_UP -> { /* centroid updated on next MOVE */ }
            }
            return TouchDecision.Consumed
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (ev.pointerCount == 1) {
                    singleFingerTracking = true
                    gestureInitialY = ev.getY(0)
                    gestureFinalY = ev.getY(0)
                }
                return TouchDecision.PassThrough
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount == 1 && singleFingerTracking) {
                    val initial = gestureInitialY ?: return TouchDecision.PassThrough
                    val dy = ev.getY(0) - initial
                    if (abs(dy) > touchSlop) {
                        singleFingerTracking = false
                        cancelInnerGesture()
                        beginScrollGesture(initial)
                        gestureFinalY = ev.getY(0)
                        lastMoveEvent = ev
                        return TouchDecision.Consumed
                    }
                }
                return TouchDecision.PassThrough
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount >= 2) {
                    singleFingerTracking = false
                    cancelInnerGesture()
                    beginScrollGesture(centroidY(ev))
                    return TouchDecision.Consumed
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                singleFingerTracking = false
                gestureInitialY = null
                gestureFinalY = null
                return TouchDecision.PassThrough
            }
        }
        return TouchDecision.PassThrough
    }

    /**
     * The inner Termux view receives the first ACTION_DOWN before we know
     * whether the user is scrolling or long-pressing. Cancel its
     * GestureDetector once a scroll gesture is committed so sliding does
     * not also enter text-selection mode.
     */
    private fun cancelInnerGesture() {
        innerView.cancelLongPress()
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try {
            innerView.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    private fun beginScrollGesture(initialY: Float) {
        gestureActive = true
        gestureInitialY = initialY
        gestureFinalY = initialY
        lastMoveEvent = null
        _state.value = _state.value.copy(
            isInScrollback = true,
            gestureHint = SCROLL_GESTURE_HINT,
        )
    }

    /**
     * Called when the LAST finger lifts. Computes the total dy of the
     * gesture and, if the swipe crossed the half-page threshold, dispatches
     * a one-page scroll. On the normal buffer this drives the inner view's
     * doScroll (local scrollback); on the alternate buffer with no mouse
     * tracking it sends a page of cursor keys to the remote instead (see
     * [sendAltBufferPageScroll]).
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
        if (initial == null || final == null || move == null) {
            publishGestureHint(
                userMessage = "需滑动后再抬起（不能只点按）",
                logDetail = "commitGesture: skipped incomplete gesture " +
                    "(initial=$initial final=$final move=$move)",
            )
            return
        }

        val dy = final - initial
        val lineSpacing = fontLineSpacing().takeIf { it > 0f }
        if (lineSpacing == null) {
            publishGestureHint(
                userMessage = "终端未就绪，请稍后再试",
                logDetail = "commitGesture: skipped fontLineSpacing<=0 (renderer not ready?)",
            )
            return
        }
        val threshold = lineSpacing * emulator.mRows / 2f
        val pageUp = when {
            dy < -threshold -> true
            dy > threshold -> false
            else -> {
                publishGestureHint(
                    userMessage = "滑动距离不够（需超过半屏）",
                    logDetail = "commitGesture: skipped below threshold dy=$dy threshold=$threshold",
                )
                return
            }
        }

        // Alt-buffer mode (vim / less / man / tmux TUI, no mouse tracking):
        // the remote full-screen program owns the screen and its own history.
        // Termux's doScroll alt-buffer branch NPEs here (mTermSession is null),
        // so instead translate the page gesture into a screenful of cursor-key
        // presses sent to the remote — the standard xterm "alternateScroll"
        // behaviour. Works with no PageUp/PageDown key and no tmux config.
        if (emulator.isAlternateBufferActive && !emulator.isMouseTrackingActive) {
            sendAltBufferPageScroll(pageUp)
            // The remote owns the screen; there is no local scrollback to be
            // "in", so clear the banner state and show a transient page hint.
            _state.value = ScrollbackState()
            publishGestureHint(
                userMessage = if (pageUp) "↑ 已向上翻页" else "↓ 已向下翻页",
                logDetail = "commitGesture: alt-buffer cursor-key scroll up=$pageUp rows=${emulator.mRows}",
            )
            return
        }

        val amount = if (pageUp) -emulator.mRows else +emulator.mRows
        com.example.sshterminal.logging.AppLog.d(
            "ScrollbackController",
            "commitGesture: doScroll amount=$amount dy=$dy threshold=$threshold",
        )
        invokeDoScroll(move, amount)
        val pageHint = if (pageUp) "↑ 已向上翻一页" else "↓ 已向下翻一页"
        // Auto-exit if the page scroll brought us back to the live view.
        if (readInnerTopRow() == 0) {
            _state.value = ScrollbackState()
        } else {
            _state.value = _state.value.copy(gestureHint = pageHint)
        }
    }

    /**
     * Translate a one-page gesture into cursor-key presses for a remote
     * full-screen program on the alternate buffer. Sends [TerminalEmulator.mRows]
     * copies of the DPAD_UP / DPAD_DOWN escape sequence (honouring the remote's
     * DECCKM application-cursor-key mode via [KeyHandler.getCode]) in a single
     * write. This is what a normal terminal does when the wheel is scrolled
     * inside an alt-buffer app that hasn't enabled mouse tracking.
     */
    private fun sendAltBufferPageScroll(pageUp: Boolean) {
        val keyCode = if (pageUp) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        val seq = KeyHandler.getCode(
            keyCode,
            /* keyMode = */ 0,
            /* cursorApp = */ emulator.isCursorKeysApplicationMode,
            /* keypadApplication = */ emulator.isKeypadApplicationMode,
        ) ?: return
        val unit = seq.toByteArray(Charsets.UTF_8)
        if (unit.isEmpty()) return
        // One page minus a line, so the last visible row carries over as
        // context on the next page (matches less/vim PageUp overlap).
        val rows = (emulator.mRows - 1).coerceAtLeast(1)
        val out = java.io.ByteArrayOutputStream(unit.size * rows)
        repeat(rows) { out.write(unit) }
        runCatching { sendToRemote(out.toByteArray()) }.onFailure {
            com.example.sshterminal.logging.AppLog.w(
                "ScrollbackController", "alt-buffer cursor-key send failed", it,
            )
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
            publishGestureHint(
                userMessage = "翻页失败，请重试",
                logDetail = "doScroll reflection failed: ${it.message}",
            )
        }
    }

    /**
     * Surfaces gesture diagnostics on the scrollback banner (no adb).
     * Also mirrors to [AppLog.d] / filesDir/app.log for Copy logs.
     */
    private fun publishGestureHint(userMessage: String, logDetail: String) {
        com.example.sshterminal.logging.AppLog.d("ScrollbackController", logDetail)
        hintClearRunnable?.let { view.removeCallbacks(it) }
        hintClearRunnable = null
        _state.value = _state.value.copy(gestureHint = userMessage)
        if (!_state.value.isInScrollback) {
            val clear = Runnable {
                _state.update { current ->
                    if (current.gestureHint == userMessage) {
                        current.copy(gestureHint = null)
                    } else {
                        current
                    }
                }
                hintClearRunnable = null
            }
            hintClearRunnable = clear
            view.postDelayed(clear, TRANSIENT_HINT_MS)
        }
    }

    companion object {
        /** How long a hint stays visible when not in scrollback mode. */
        const val TRANSIENT_HINT_MS = 4_000L
        internal const val SCROLL_GESTURE_HINT = "滑动超过半屏后抬起"
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
        singleFingerTracking = false
        gestureInitialY = null
        gestureFinalY = null
        lastMoveEvent = null
        hintClearRunnable?.let { view.removeCallbacks(it) }
        hintClearRunnable = null
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