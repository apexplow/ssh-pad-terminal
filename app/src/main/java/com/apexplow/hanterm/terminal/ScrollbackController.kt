package com.apexplow.hanterm.terminal

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
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
) : GestureConsumer {

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
    /** Maximum signed (centroid - initialY) seen across MOVE events of the
     *  current scroll gesture. Negative = peak went upward. UP consults this
     *  in addition to the net `dy = final - initial` so a quick up-then-back
     *  gesture still fires the page flip the user intended (the back-stroke
     *  used to cancel out half the swipe). */
    private var peakDisplacement: Float = 0f
    /** Snapshot of the first ACTION_DOWN (single-finger entry) so the
     *  VelocityTracker has a t=0 sample at arm time. Obtain()-ed to avoid
     *  recycling the live event; recycle() on every exit path that owns it. */
    private var lastDownEvent: MotionEvent? = null
    /** Per-gesture VelocityTracker; nulled and recycled by
     *  [releaseVelocityTracker] on every exit. Never recycled twice. */
    private var velocityTracker: VelocityTracker? = null
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
     * Reflected handle to [TerminalEmulator.isDecsetInternalBitSet] so the
     * alt-buffer mouse-wheel path can branch on DECSET 1006 (SGR encoding)
     * without depending on any public API. Termux exposes only
     * [TerminalEmulator.isMouseTrackingActive] — not the protocol selector —
     * and the constant for bit 512 is package-private, so reflection is the
     * only way to match the encoder logic in v0.118.0.
     */
    private val isDecsetInternalBitSetMethod: java.lang.reflect.Method by lazy {
        TerminalEmulator::class.java.getDeclaredMethod(
            "isDecsetInternalBitSet",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
    }

    /**
     * Reflected handle to `TerminalEmulator.mMainBuffer` (package-private in
     * Termux v0.118.0) so [commitGesture] can ask how many transcript rows
     * are available above the live area, and clamp `amount` to the actual
     * remaining scrollback. If the field is renamed in a future Termux bump
     * this becomes null and we silently fall back to the legacy
     * `±mRows`-and-let-Termux-clamp behaviour; the gesture itself never breaks.
     */
    private val mainBufferField: java.lang.reflect.Field? by lazy {
        runCatching {
            TerminalEmulator::class.java.getDeclaredField("mMainBuffer")
                .apply { isAccessible = true }
        }.getOrElse {
            com.apexplow.hanterm.logging.AppLog.w(
                "ScrollbackController",
                "mMainBuffer reflection unavailable; adaptive amount clamping disabled",
            )
            null
        }
    }

    /**
     * Reflected handle to the public `TerminalBuffer.getActiveTranscriptRows()`.
     * Pairs with [mainBufferField] to read the total scrollback size; cached as
     * nullable for the same graceful-degradation reason.
     */
    private val activeTranscriptRowsMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            Class.forName("com.termux.terminal.TerminalBuffer")
                .getDeclaredMethod("getActiveTranscriptRows")
                .apply { isAccessible = true }
        }.getOrNull()
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
    override fun onTouchEvent(ev: MotionEvent): TouchDecision {
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
                    lastMoveEvent?.let { velocityTracker?.addMovement(it) }
                    velocityTracker?.addMovement(ev)
                    commitGesture()
                }
                MotionEvent.ACTION_CANCEL -> {
                    gestureActive = false
                    singleFingerTracking = false
                    gestureInitialY = null
                    gestureFinalY = null
                    peakDisplacement = 0f
                    lastMoveEvent = null
                    lastDownEvent?.recycle()
                    lastDownEvent = null
                    releaseVelocityTracker()
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (gestureInitialY == null) {
                        gestureInitialY = centroidY(ev)
                    }
                    _state.value = _state.value.copy(isInScrollback = true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val initial = gestureInitialY
                    val centroid = centroidY(ev)
                    gestureFinalY = centroid
                    if (initial != null) {
                        val disp = centroid - initial
                        if (abs(disp) > abs(peakDisplacement)) peakDisplacement = disp
                    }
                    lastMoveEvent = ev
                    velocityTracker?.addMovement(ev)
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
                    lastDownEvent = MotionEvent.obtain(ev)
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
                        // Seed VelocityTracker with the cached single-finger
                        // DOWN plus this slop-crossing MOVE before arming.
                        seedVelocityTracker(ev)
                        beginScrollGesture(initial)
                        gestureFinalY = ev.getY(0)
                        peakDisplacement = ev.getY(0) - initial
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
                    // Seed from lastDownEvent if the user 1-finger-then-2;
                    // falls through to empty-sample (velocity≈0) if they
                    // 2-fingered directly — fine, spatial check still fires.
                    seedVelocityTracker(ev)
                    beginScrollGesture(centroidY(ev))
                    return TouchDecision.Consumed
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                singleFingerTracking = false
                gestureInitialY = null
                gestureFinalY = null
                peakDisplacement = 0f
                lastDownEvent?.recycle()
                lastDownEvent = null
                releaseVelocityTracker()
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
     *
     * Mirrors [TermuxViewBridge.cancelInnerGesture] — keep in sync. The
     * ScrollbackController copy lives here because this class owns the
     * inner view reference and predates the bridge extraction (Sprint 4
     * added the bridge helper for [LinkGesture] and other view-layer
     * gesture consumers that don't have direct innerView access).
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
        peakDisplacement = 0f
        lastMoveEvent = null
        _state.value = _state.value.copy(
            isInScrollback = true,
            gestureHint = SCROLL_GESTURE_HINT,
        )
    }

    /**
     * Called when the LAST finger lifts. Inspects the gesture's net dy, its
     * peak displacement (max excursion across all MOVE events), and the
     * VelocityTracker's y-velocity to decide whether to fire a page flip,
     * then dispatches `±mRows-1` (clamped to remaining scrollback) to the
     * inner view's [doScroll]. On the alternate buffer with no mouse
     * tracking it sends a page of cursor keys to the remote instead (see
     * [sendAltBufferPageScroll]).
     *
     * Trigger policy:
     *   - quarter-page threshold (`lineSpacing * mRows / 4`) so a slow
     *     deliberate swipe fires;
     *   - OR `|velocityY| > FLING_VELOCITY_THRESHOLD` so a quick flick with
     *     a small residual dy still fires;
     *   - peak displacement is consulted in addition to net dy so a
     *     up-and-back-again gesture still reflects the user's intent.
     *
     * Threading: UI thread only.
     */
    private fun commitGesture() {
        val initial = gestureInitialY
        val final = gestureFinalY
        val move = lastMoveEvent
        val peak = peakDisplacement
        gestureInitialY = null
        gestureFinalY = null
        peakDisplacement = 0f
        lastMoveEvent = null
        if (initial == null || final == null || move == null) {
            releaseVelocityTracker()
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
            releaseVelocityTracker()
            publishGestureHint(
                userMessage = "终端未就绪，请稍后再试",
                logDetail = "commitGesture: skipped fontLineSpacing<=0 (renderer not ready?)",
            )
            return
        }

        // Threshold: quarter of a screen in pixels — pairs with the fling
        // fallback so a small but fast flick still triggers.
        val threshold = lineSpacing * emulator.mRows / 4f

        // Snapshot velocity THEN release; the recompute is cheap, the leak
        // (forgetting to recycle) is expensive.
        val vt = velocityTracker
        var velocityY = 0f
        if (vt != null) {
            vt.computeCurrentVelocity(1_000) // px/s
            velocityY = vt.getYVelocity()
        }
        releaseVelocityTracker()

        val swipeUp = dy < -threshold || peak < -threshold
        val swipeDown = dy > threshold || peak > threshold
        val flingUp = velocityY < -FLING_VELOCITY_THRESHOLD
        val flingDown = velocityY > FLING_VELOCITY_THRESHOLD
        val pageUp: Boolean? = when {
            swipeUp || flingUp -> true
            swipeDown || flingDown -> false
            else -> null
        }
        if (pageUp == null) {
            publishGestureHint(
                userMessage = "滑动距离不够（需超过 1/4 屏）",
                logDetail = "commitGesture: skipped below threshold " +
                    "dy=$dy peak=$peak vy=$velocityY threshold=$threshold",
            )
            return
        }

        // Alt-buffer mode (vim / less / man / tmux TUI): the remote
        // full-screen program owns the screen and its own history. Termux's
        // doScroll alt-buffer branch NPEs here (mTermSession is null), so
        // we cannot scroll locally — the existing
        // AltBufferScrollCrashGuard regression suite must stay green.
        //
        // Branch on whether the remote TUI has enabled DECSET mouse
        // tracking (1000/1002/1003):
        //
        //   - mouse tracking ON (tmux `set -g mouse on`, vim `:set mouse=a`,
        //     htop after pressing m, etc.): emit a single mouse-wheel
        //     event through the SGR or legacy encoding the TUI expects.
        //     The TUI scrolls its own history — the only path that actually
        //     moves the TUI's view instead of poking the foreground program.
        //
        //   - mouse tracking OFF (default tmux on most servers, htop before
        //     mouse toggle, scripts that don't enable DECSET 1000/1002/1003):
        //     the TUI ignores wheel events, so fall back to the xterm
        //     alternateScroll behaviour — batch (mRows-1) cursor keys to
        //     the remote. This is the pre-existing path; we keep it for
        //     compatibility but tag the banner so the user knows the keys
        //     went to whatever the foreground program is (a shell in a
        //     tmux pane will navigate shell history, vim command-mode will
        //     move the cursor).
        if (emulator.isAlternateBufferActive) {
            if (emulator.isMouseTrackingActive) {
                sendAltBufferMouseWheel(pageUp)
                _state.value = ScrollbackState()
                publishGestureHint(
                    userMessage = if (pageUp) "↑ 已向上滚动" else "↓ 已向下滚动",
                    logDetail = "commitGesture: alt-buffer mouse-wheel scroll up=$pageUp rows=${emulator.mRows}",
                )
            } else {
                sendAltBufferPageScroll(pageUp)
                _state.value = ScrollbackState()
                publishGestureHint(
                    userMessage = if (pageUp)
                        "↑ 方向键已发送（远端未启用鼠标模式）"
                    else
                        "↓ 方向键已发送（远端未启用鼠标模式）",
                    logDetail = "commitGesture: alt-buffer cursor-key fallback up=$pageUp rows=${emulator.mRows} (mouse-tracking off)",
                )
            }
            return
        }

        // Main buffer: clamp the page amount to actual remaining scrollback
        // so the banner hint reflects reality (Termux's doScroll silently
        // caps further than the scrollback if amount > remaining, but the
        // banner used to lie about it). mTopRow is non-positive: 0 = live
        // view, -n = n rows scrolled back.
        val pageSize = emulator.mRows
        val currentScrollback = -readInnerTopRow() // rows already scrolled back
        val transcriptRows = readActiveTranscriptRows()
        // One-line overlap convention (matches sendAltBufferPageScroll).
        // When [transcriptRows] is 0 the reflective handles are missing
        // (post-Termux-bump fallback) — request a full page and let Termux
        // clamp inside doScroll rather than falsely telling the user we've
        // hit the top.
        val absAmount = if (transcriptRows > 0) {
            val scrollbackCapacity = (transcriptRows - pageSize).coerceAtLeast(0)
            val headroom = if (pageUp) {
                (scrollbackCapacity - currentScrollback).coerceAtLeast(0)
            } else {
                currentScrollback
            }
            minOf(pageSize - 1, headroom).coerceAtLeast(0)
        } else {
            pageSize - 1
        }
        val amount = if (pageUp) -absAmount else +absAmount

        if (amount == 0) {
            // Nothing to scroll: caller already at the boundary. Publish
            // an honest hint and auto-exit if we're already live.
            if (readInnerTopRow() == 0) {
                _state.value = ScrollbackState()
            } else {
                publishGestureHint(
                    userMessage = if (pageUp) "已到顶部" else "已在最底部",
                    logDetail = "commitGesture: clamped to zero (already at boundary) " +
                        "pageUp=$pageUp absAmount=$absAmount currentScrollback=$currentScrollback",
                )
            }
            return
        }

        com.apexplow.hanterm.logging.AppLog.d(
            "ScrollbackController",
            "commitGesture: doScroll amount=$amount dy=$dy peak=$peak " +
                "vy=$velocityY threshold=$threshold absAmount=$absAmount",
        )
        invokeDoScroll(move, amount)
        val pageHint = when {
            pageUp && absAmount >= pageSize - 1 -> "↑ 已向上翻一页"
            pageUp -> "↑ 已向上翻 $absAmount 行"
            absAmount >= pageSize - 1 -> "↓ 已向下翻一页"
            else -> "↓ 已向下翻 $absAmount 行"
        }
        // Auto-exit if the page scroll brought us back to the live view.
        if (readInnerTopRow() == 0) {
            _state.value = ScrollbackState()
        } else {
            _state.value = _state.value.copy(gestureHint = pageHint)
        }
    }

    /**
     * Read the total number of rows in the main transcript (scrollback +
     * live area). Returns 0 (treated as "no scrollback at all") if the
     * reflective handle on `TerminalEmulator.mMainBuffer` or on
     * `TerminalBuffer.getActiveTranscriptRows()` is missing — e.g. after a
     * Termux bump that renames either. Callers fall back to the legacy
     * `±mRows`-and-let-Termux-clamp behaviour in that case.
     */
    private fun readActiveTranscriptRows(): Int {
        val bufferField = mainBufferField ?: return 0
        val method = activeTranscriptRowsMethod ?: return 0
        return runCatching {
            val buffer = bufferField.get(emulator)
            (method.invoke(buffer) as? Int) ?: 0
        }.getOrDefault(0)
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
            com.apexplow.hanterm.logging.AppLog.w(
                "ScrollbackController", "alt-buffer cursor-key send failed", it,
            )
        }
    }

    /**
     * Emit a single mouse-wheel event for a remote TUI that has enabled
     * DECSET mouse tracking (1000/1002/1003). Honours DECSET 1006 (SGR
     * encoding) the same way [TerminalEmulator.sendMouseEvent] does in
     * v0.118.0; falls back to the legacy xterm encoding otherwise.
     *
     * We assemble the bytes ourselves rather than going through
     * [TerminalEmulator.sendMouseEvent] because this project's
     * `TerminalEmulator.mSession` points at `TerminalView.transcriptOutput`,
     * which currently only invalidates and counts lines — it does not
     * forward emulator-originated writes to the SSH endpoint. Re-routing
     * the session output is a separate fix; the scrollback controller
     * already has direct access to `sendToRemote` and the xterm mouse
     * protocol is stable enough to inline.
     *
     * Coordinates are pinned to the centre of the live view — xterm
     * convention is that wheel events carry no positional meaning for
     * scrolling, and the user's gesture centroid already lines up with
     * the visible content. One wheel event per swipe matches the common
     * desktop-terminal behaviour; the TUI decides how many lines to
     * scroll (vim: 3, tmux: 1 page, less: configurable).
     */
    private fun sendAltBufferMouseWheel(pageUp: Boolean) {
        val button = if (pageUp) TerminalEmulator.MOUSE_WHEELUP_BUTTON
            else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
        val col = (emulator.mColumns / 2).coerceIn(1, emulator.mColumns)
        val row = (emulator.mRows / 2).coerceIn(1, emulator.mRows)
        val sgr = runCatching {
            isDecsetInternalBitSetMethod.invoke(emulator, DECSET_BIT_SGR_MOUSE) as Boolean
        }.getOrDefault(false)
        val seq: String = if (sgr) {
            "\u001b[<${button};${col};${row}M"
        } else {
            // Legacy xterm: button / col / row each +32, packed into 3 bytes
            // after ESC [ M. Matches TerminalEmulator v0.118.0 sendMouseEvent
            // non-SGR branch.
            "\u001b[M" + (button + 32).toChar() + (col + 32).toChar() + (row + 32).toChar()
        }
        runCatching { sendToRemote(seq.toByteArray(Charsets.UTF_8)) }.onFailure {
            com.apexplow.hanterm.logging.AppLog.w(
                "ScrollbackController", "alt-buffer mouse-wheel send failed", it,
            )
        }
    }

    private fun invokeDoScroll(move: MotionEvent, amount: Int) {
        runCatching {
            doScrollMethod.invoke(innerView, move, amount)
            innerView.postInvalidateOnAnimation()
        }.onFailure {
            com.apexplow.hanterm.logging.AppLog.w(
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
        com.apexplow.hanterm.logging.AppLog.d("ScrollbackController", logDetail)
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
        /**
         * Compatibility shim — the nested `ScrollbackController.TouchDecision`
         * sealed type that pre-dated Sprint 4 is now a top-level [TouchDecision]
         * in `terminal/TouchDecision.kt`. External references to
         * `ScrollbackController.TouchDecision.PassThrough` resolve to the
         * top-level data object via these aliases.
         */
        @JvmField
        val PassThrough: TouchDecision = TouchDecision.PassThrough
        @JvmField
        val Consumed: TouchDecision = TouchDecision.Consumed

        /** How long a hint stays visible when not in scrollback mode. */
        const val TRANSIENT_HINT_MS = 4_000L
        internal const val SCROLL_GESTURE_HINT = "滑动超过 1/4 屏后抬起"

        /**
         * Minimum |velocityY| at finger-up (px/s, derived from a 1000 ms
         * VelocityTracker window) that counts as a fling even when the
         * spatial threshold isn't crossed. A 16-px MOVE 16 ms apart yields
         * ~1000 px/s; this threshold sits 50 % above that to keep
         * deliberate-but-fast swipes firing while filtering finger-fumbles.
         */
        private const val FLING_VELOCITY_THRESHOLD = 1_500f

        /**
         * DECSET 1006 (SGR mouse encoding) bit index — matches the
         * `DECSET_BIT_MOUSE_PROTOCOL_SGR` constant inside
         * TerminalEmulator v0.118.0. Pinned here because the source
         * constant is package-private; if a Termux bump renumbers it,
         * [sendAltBufferMouseWheel] silently falls back to legacy
         * encoding and this constant + test assertions must move together.
         */
        private const val DECSET_BIT_SGR_MOUSE = 512
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
        peakDisplacement = 0f
        lastMoveEvent = null
        lastDownEvent?.recycle()
        lastDownEvent = null
        releaseVelocityTracker()
        hintClearRunnable?.let { view.removeCallbacks(it) }
        hintClearRunnable = null
        runCatching {
            innerTopRowField.setInt(innerView, 0)
            innerView.postInvalidateOnAnimation()
        }.onFailure {
            com.apexplow.hanterm.logging.AppLog.w(
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

    /**
     * Obtain a fresh VelocityTracker and seed it with the cached
     * [lastDownEvent] (if any) followed by the [armingEvent] that crossed
     * the slop / second-finger threshold. Recycles [lastDownEvent] after the
     * tracker has copied it, and clears the field so a subsequent ACTION_UP
     * doesn't double-feed.
     */
    private fun seedVelocityTracker(armingEvent: MotionEvent) {
        val cached = lastDownEvent
        velocityTracker = VelocityTracker.obtain().also { vt ->
            if (cached != null) {
                vt.addMovement(cached)
                cached.recycle()
            }
            vt.addMovement(armingEvent)
        }
        lastDownEvent = null
    }

    /**
     * Idempotent recycle; safe to call from every exit path including ones
     * that may already have nulled the tracker. Always null after recycle
     * to make double-recycle impossible.
     */
    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}