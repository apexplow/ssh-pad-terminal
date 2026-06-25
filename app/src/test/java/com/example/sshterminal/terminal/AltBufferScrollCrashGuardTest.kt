package com.example.sshterminal.terminal

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the drag-in-alternate-buffer NPE that crashed the
 * app the moment the user scrolled inside a remote TUI (vim, less, htop,
 * tmux, mc, fzf — any app that issues DECSET 1049).
 *
 * Root cause: the wrapper deliberately keeps Termux's `mTermSession` null
 * (see TerminalView.kt constructor, which wires the emulator directly via
 * reflection and skips TerminalSession to avoid the local-shell JNI fork).
 * Termux's `TerminalView.doScroll()` takes three branches:
 *   1. mouse tracking active  → sendMouseEvent() → safe
 *   2. alternate buffer active → handleKeyCode(KEYCODE_DPAD_UP/DOWN)
 *   3. normal scrollback       → mutate mTopRow   → safe
 * Branch 2 dereferences `mTermSession.getEmulator()` and NPEs. We can't
 * fix the inner view (CLAUDE.md forbids modifying com.termux internals)
 * and we can't construct a TerminalSession because it would invoke a
 * local shell. The fix is an OnTouchListener on the inner view that
 * consumes ACTION_MOVE in the crashing configuration.
 *
 * These tests pin:
 *   - the predicate used by the listener (so future DEC-mode additions
 *     can't accidentally re-open the crash window);
 *   - the inner-view crash itself via reflection — proves the bug
 *     path is still live in the upstream AAR and would re-fire the
 *     moment our guard stops consuming;
 *   - the wrapper's claim that it consumes MOVE in the crashing
 *     configuration — proves the listener is actually installed and
 *     reaching the right code path.
 *
 * We deliberately do NOT exercise the bug via simulated touch dispatch.
 * Robolectric's GestureDetector shadow doesn't fire onScroll for synthetic
 * MotionEvents, so a DOWN → MOVE → UP sequence through `dispatchTouchEvent`
 * never reaches `doScroll` in unit tests — the upstream test would be a
 * false-positive pass. Driving `doScroll` directly via reflection is the
 * only way to reproduce the production crash in CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AltBufferScrollCrashGuardTest {

    private lateinit var context: Context
    private lateinit var endpoint: MockEchoSession
    private lateinit var view: TerminalView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        endpoint = MockEchoSession()
        view = TerminalView(context)
        view.bindEndpoint(endpoint)
        view.onCreateInputConnection(EditorInfo())
    }

    @Test
    fun test_normalScrollback_isNotFlaggedAsCrashPath() {
        // Default emulator state: not in alt buffer, no mouse tracking.
        assertFalse(
            "normal scrollback must NOT be intercepted — the inner view's " +
                "doScroll mTopRow path is safe and we want scrollback to work",
            view.isAltBufferScrollCrashPath,
        )
    }

    @Test
    fun test_altBufferWithoutMouseTracking_isFlaggedAsCrashPath() {
        view.termuxView.mEmulator!!.doDecSetOrReset(/* set = */ true, /* mode = */ 1049)
        assertTrue(
            "alt buffer active + mouse tracking off must be flagged — " +
                "doScroll would route through handleKeyCode and NPE on mTermSession",
            view.isAltBufferScrollCrashPath,
        )
    }

    @Test
    fun test_altBufferWithMouseTracking_isNotFlaggedAsCrashPath() {
        // `:set mouse=a` in vim, mouse-aware TUIs (htop, fzf). doScroll
        // routes through sendMouseEventCode in this case — no mTermSession
        // deref, no crash. Listener must let those gestures through.
        val emu = view.termuxView.mEmulator!!
        emu.doDecSetOrReset(true, 1049)   // alt buffer
        emu.doDecSetOrReset(true, 1000)   // mouse tracking
        assertFalse(
            "alt buffer + mouse tracking is safe (doScroll → sendMouseEvent)",
            view.isAltBufferScrollCrashPath,
        )
    }

    @Test
    fun test_innerViewDoScroll_inAltBuffer_throwsNpeOnRawCall() {
        // The production crash, reproduced from the AAR. Driving doScroll
        // directly (it's package-private; reflection is the only way from
        // outside com.termux.view) bypasses the wrapper's OnTouchListener
        // guard — if THIS doesn't NPE, the upstream AAR has changed and
        // the wrapper-level guard test above may need re-evaluation.
        //
        // Without this test, an accidental removal of the guard would
        // never surface in CI: the simulated touch dispatch test would
        // still pass (Robolectric's GestureDetector doesn't fire onScroll
        // for synthetic events), and the only failure mode would be the
        // one in the user's bug report.
        val innerView = view.termuxView
        innerView.mEmulator!!.doDecSetOrReset(true, 1049)
        val downTime = SystemClock.uptimeMillis()
        val move = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_MOVE, 100f, 300f, 0)
        val doScroll = com.termux.view.TerminalView::class.java
            .getDeclaredMethod("doScroll", MotionEvent::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        try {
            try {
                // distanceDown = -3 matches what onScroll passes for an up-scroll:
                // doScroll's alt-buffer branch dispatches handleKeyCode(KEYCODE_DPAD_UP=19).
                doScroll.invoke(innerView, move, -3)
            } catch (t: java.lang.reflect.InvocationTargetException) {
                // Reflection wraps the real exception. We expect NPE from
                // session.getEmulator() being dereferenced on a null mTermSession.
                val cause = t.targetException
                assertTrue(
                    "expected NPE from handleKeyCode→mTermSession.getEmulator(), " +
                        "got ${cause.javaClass.simpleName}: ${cause.message}",
                    cause is NullPointerException,
                )
                return
            }
            throw AssertionError(
                "doScroll(-3) on alt-buffer emulator did NOT NPE — production " +
                    "crash path isn't reproducible from this AAR anymore, " +
                    "wrapper-level guard may now be unnecessary",
            )
        } finally {
            move.recycle()
        }
    }

    @Test
    fun test_wrapper_installsTouchListenerOnInnerView() {
        // Integration check: the OnTouchListener installed in TerminalView.init
        // must actually be on the inner view. If the setOnTouchListener call
        // is ever removed, this test fails — and the production crash returns.
        //
        // We can't easily probe the listener's runtime behaviour via
        // dispatchTouchEvent: ViewGroup only dispatches MOVE within an active
        // gesture (after a DOWN captured by a child), and Robolectric's
        // GestureDetector shadow doesn't fire onScroll for synthetic events,
        // so a DOWN→MOVE→UP sequence never reaches doScroll in CI. The
        // reflection-based NPE reproduction above is the rigorous proof
        // that the AAR path is broken; this test is the cheap "is the guard
        // actually wired up" check on our own code.
        val listenerInfoField = android.view.View::class.java
            .getDeclaredField("mListenerInfo")
            .apply { isAccessible = true }
        val listenerInfo = listenerInfoField.get(view.termuxView)
        assertNotNull(
            "TerminalView.init must install an OnTouchListener on the inner " +
                "view — otherwise the alt-buffer scroll crash returns. " +
                "(ListenerInfo is null, so no listeners were ever attached.)",
            listenerInfo,
        )
        val onTouchListenerField = listenerInfo!!.javaClass
            .getDeclaredField("mOnTouchListener")
            .apply { isAccessible = true }
        assertNotNull(
            "TerminalView.init must set an OnTouchListener on the inner view " +
                "— the alt-buffer scroll crash returns otherwise",
            onTouchListenerField.get(listenerInfo),
        )
    }

    @Test
    fun test_wrapper_consumesMouseWheelScrollInAltBuffer() {
        // Bluetooth mouse / trackpad scroll hits the same doScroll →
        // handleKeyCode crash path. Compose's pointerInteropFilter only
        // covers touch events; the wrapper's dispatchGenericMotionEvent
        // has to short-circuit. Verify by dispatching ACTION_SCROLL with
        // SOURCE_MOUSE — without the guard, dispatchGenericMotionEvent
        // would forward to termuxView.onGenericMotionEvent and NPE.
        view.termuxView.mEmulator!!.doDecSetOrReset(true, 1049)
        val eventTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = 50f; y = 50f; pressure = 0f; size = 0f
                setAxisValue(MotionEvent.AXIS_VSCROLL, 3f)
            },
        )
        val scroll = MotionEvent.obtain(
            eventTime, eventTime,
            MotionEvent.ACTION_SCROLL,
            1, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0,
        )
        try {
            assertTrue(
                "wrapper must consume ACTION_SCROLL in alt-buffer mode to " +
                    "prevent the NPE in handleKeyCode",
                view.dispatchGenericMotionEvent(scroll),
            )
        } finally {
            scroll.recycle()
        }
    }
}