package com.taosun.hanterm.terminal

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the 2026-07-13 crash:
 *
 *   java.lang.NullPointerException: Attempt to invoke virtual method
 *     'com.termux.terminal.TerminalEmulator
 *      com.termux.terminal.TerminalSession.getEmulator()'
 *     on a null object reference
 *       at com.termux.view.TerminalView.handleKeyCode(TerminalView.java:842)
 *       at com.termux.view.TerminalView.onKeyDown(TerminalView.java:729)
 *
 * Context: commit c73322b made [TerminalViewClient.onKeyDown] accept a null
 * `TerminalSession` (Termux passes its unset `mTermSession`). That stopped the
 * 2026-07-10 Kotlin null-check crash, but left the client returning `false`
 * ("not consumed"). Termux then continues into `handleKeyCode`, which
 * unconditionally calls `mTermSession.getEmulator()` and NPEs.
 *
 * Production trigger: text selection temporarily makes the inner Termux view
 * focusable (`enableInnerViewForSelection`). A subsequent hardware / post-IME
 * key is delivered by `ViewGroup.dispatchKeyEvent` to the focused child, so
 * Termux's `onKeyDown` runs instead of the wrapper's KeyMapper path.
 *
 * Guard (two layers):
 *   1. Wrapper [TerminalView.dispatchKeyEvent] always handles keys itself
 *      (no focused-child hop).
 *   2. Client `onKeyDown` returns `true` so any leftover direct call still
 *      stops before `handleKeyCode`.
 *
 * These tests pin:
 *   - `handleKeyCode` still NPEs on null session (upstream path live);
 *   - direct `termuxView.onKeyDown` no longer crashes (client consumes);
 *   - wrapper `dispatchKeyEvent` with a focused inner view routes via KeyMapper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
class TermuxViewKeyDownNullSessionCrashGuardTest {

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
    fun handleKeyCode_stillNpesOnNullSession() {
        // Documents that terminal-view:v0.118.0 still crashes inside
        // handleKeyCode when mTermSession is null. Driven via reflection
        // because our client.onKeyDown now consumes before that path.
        val handleKeyCode = com.termux.view.TerminalView::class.java
            .getDeclaredMethod(
                "handleKeyCode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .apply { isAccessible = true }
        try {
            handleKeyCode.invoke(view.termuxView, KeyEvent.KEYCODE_DPAD_UP, 0)
            fail(
                "handleKeyCode did NOT NPE — production crash path isn't " +
                    "reproducible from this AAR anymore",
            )
        } catch (t: java.lang.reflect.InvocationTargetException) {
            val cause = t.targetException
            assertTrue(
                "expected NPE from mTermSession.getEmulator(), got " +
                    "${cause.javaClass.simpleName}: ${cause.message}",
                cause is NullPointerException,
            )
        }
    }

    @Test
    fun innerView_onKeyDown_consumedByClient_doesNotThrow() {
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)
        val handled = view.termuxView.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, event)
        assertTrue(
            "client must consume so Termux never reaches handleKeyCode",
            handled,
        )
        assertArrayEquals(
            "inner onKeyDown must not write via KeyMapper (wrapper owns routing)",
            ByteArray(0),
            endpoint.bytesWritten(),
        )
    }

    @Test
    fun wrapper_dispatchKeyEvent_whenInnerFocused_routesSendWithoutCrash() {
        // Reproduce the production focus shape: selection makes the inner
        // view focusable and focused, so ViewGroup would normally deliver
        // keys there first.
        attachToWindow(view)
        view.termuxView.isFocusable = true
        view.termuxView.isFocusableInTouchMode = true
        assertTrue(
            "precondition: inner view must take focus (selection path)",
            view.termuxView.requestFocus(),
        )
        assertTrue(view.termuxView.isFocused)
        assertFalse("wrapper must NOT be the focused leaf", view.isFocused)

        // Bare DPAD_UP → Send ESC[A via KeyMapper.
        val up = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)
        val handled = view.dispatchKeyEvent(up)

        assertTrue("wrapper must consume DPAD_UP via KeyMapper", handled)
        assertArrayEquals(
            "key must reach SSH through the wrapper, not die in Termux handleKeyCode",
            "\u001B[A".toByteArray(Charsets.UTF_8),
            endpoint.bytesWritten(),
        )
    }

    private fun attachToWindow(child: TerminalView) {
        // requestFocus() only sticks when the view is in a windowed hierarchy.
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val container = FrameLayout(activity.get())
        container.addView(child, FrameLayout.LayoutParams(1080, 1920))
        activity.get().setContentView(container)
        child.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY),
        )
        child.layout(0, 0, 1080, 1920)
    }
}
