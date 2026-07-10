package com.example.sshterminal.terminal

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the 2026-07-10 NPE crash:
 *
 *   java.lang.NullPointerException: Parameter specified as non-null is null:
 *     method com.example.sshterminal.terminal.TerminalView$termuxViewClient$1.onKeyDown,
 *     parameter session
 *       at com.example.sshterminal.terminal.TerminalView$termuxViewClient$1.onKeyDown(Unknown Source:7)
 *       at com.termux.view.TerminalView.onKeyDown(TerminalView.java:707)
 *
 * Root cause: `com.termux.view.TerminalView.onKeyDown` (terminal-view:v0.118.0,
 * TerminalView.java:707) loads its internal `mTermSession` field and passes it as
 * the third argument to `TerminalViewClient.onKeyDown(int, KeyEvent, TerminalSession)`
 * without a null check. This project deliberately leaves `mTermSession` unset on
 * the inner view — see the `emulator` constructor in [TerminalView], where we
 * wire `mEmulator` via reflection and skip `TerminalSession` entirely because its
 * constructor would invoke JNI to fork a local shell. The Kotlin null-check on
 * `session: TerminalSession` then throws `Unknown Source:7`.
 *
 * The fix is to declare the callback's `session` parameter as `TerminalSession?`
 * so the Kotlin compiler omits the null-check. The JVM-level type stays
 * `TerminalSession` (confirmed by `javap`), so the override still satisfies the
 * upstream interface signature.
 *
 * Two callbacks share the bug:
 *   1. `onKeyDown(int, KeyEvent, TerminalSession)` — invoked at TerminalView.java:707
 *   2. `onCodePoint(int, boolean, TerminalSession)` — invoked at a similar site
 *      that also passes `mTermSession` un-checked.
 *
 * This test pins both. The wrapper's own [TerminalView.onKeyDown] owns input
 * routing end-to-end, so both client callbacks return `false` ("not consumed")
 * in production — that contract is the second assertion below.
 *
 * `mTermSession == null` is a deliberate design choice (see `isAltBufferScrollCrashPath`
 * kdoc for the matching NPE on the scroll path). If a future Termux version adds
 * a null-check before invoking these callbacks, the test still passes — it
 * exercises our override, not Termux's call site.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TerminalViewClientNullSessionTest {

    private lateinit var context: Context
    private lateinit var view: TerminalView
    private lateinit var client: TerminalViewClient

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
        // Grab the private termuxViewClient field via reflection — it's an
        // anonymous object inside TerminalView, not exposed publicly. The
        // exact declaration is at TerminalView.kt ~137.
        val field = TerminalView::class.java.getDeclaredField("termuxViewClient")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        client = field.get(view) as TerminalViewClient
    }

    @Test
    fun onKeyDown_acceptsNullSession_withoutThrowing() {
        // The exact shape of the KeyEvent doesn't matter — Termux's call site
        // constructs the throwable before any of its members are read. A
        // simple ACTION_DOWN is enough to exercise the parameter binding.
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        val result = client.onKeyDown(KeyEvent.KEYCODE_A, event, null as TerminalSession?)
        assertFalse("client.onKeyDown must return false (input is owned by the wrapper)", result)
    }

    @Test
    fun onCodePoint_acceptsNullSession_withoutThrowing() {
        // Same crash class, different callback. onCodePoint is invoked by
        // Termux when it has decoded a unicode codepoint that bypassed the
        // normal KeyEvent path (e.g. multi-byte IME sequences).
        val result = client.onCodePoint(0x41 /* 'A' */, false, null as TerminalSession?)
        assertFalse("client.onCodePoint must return false (input is owned by the wrapper)", result)
    }
}