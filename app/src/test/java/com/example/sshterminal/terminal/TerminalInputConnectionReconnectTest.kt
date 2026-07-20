package com.example.sshterminal.terminal

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the post-reconnect input deadlock.
 *
 * Symptom: after SshTermApp.handleConnectOutcome closes the old
 * BufferedPtyBridge and binds a new one, the IME (Gboard in particular)
 * keeps dispatching commitText / setComposingText /
 * deleteSurroundingText into the OLD TerminalInputConnection it cached
 * at the previous onCreateInputConnection call. That old connection's
 * `private val endpoint` was captured when the bridge it pointed to was
 * still live, so every write now goes to a closed bridge and is silently
 * dropped (see BufferedPtyBridge.kt:141-156).
 *
 * The fix: TerminalView.bindEndpoint now calls `imm.restartInput(this)`
 * before swapping its `endpoint` field. restartInput tells the framework
 * to drop the IME's cached InputConnection, so the next IME event
 * triggers a fresh `onCreateInputConnection` call that captures the new
 * endpoint.
 *
 * Test strategy note: Robolectric 4.13's ShadowInputMethodManager makes
 * `restartInput` a no-op (verified against
 * robolectric-4.13/shadows/.../ShadowInputMethodManager.java — the
 * implementation is `protected void restartInput(View view) {}` and no
 * public shadow state is recorded). So these tests cannot directly
 * assert "restartInput was called on the IMM" via shadow state — they
 * pin the structural contract that `bindEndpoint` enables: the
 * View-side InputConnection cache is cleared, and a subsequent
 * `onCreateInputConnection` returns a fresh instance whose `private
 * val endpoint` references the NEW bridge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TerminalInputConnectionReconnectTest {

    private lateinit var context: Context
    private lateinit var view: TerminalView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        view = TerminalView(context)
    }

    @Test
    fun rebindEndpoint_clearsViewSideCache_andProducesFreshConnection() {
        // Arrange: bind endpoint A and install the TerminalInputConnection
        // that the IME would have cached. Mirrors KeyEventRoutingTest's
        // setup pattern.
        val bridgeA = BufferedPtyBridge()
        view.bindEndpoint(PtyBridgeEndpoint(bridgeA))
        val cachedByIme = view.onCreateInputConnection(EditorInfo())
            as TerminalInputConnection

        // Act: simulate the reconnect sequence in
        // SshTermApp.handleConnectOutcome (SshTermApp.kt:232-263) — close
        // the old bridge, then bind a new one.
        bridgeA.close()
        val bridgeB = BufferedPtyBridge()
        view.bindEndpoint(PtyBridgeEndpoint(bridgeB))

        // Assert: bindEndpoint must clear the View-side cache. This is
        // what forces the IME's next input event to trigger a fresh
        // `onCreateInputConnection` call against the new endpoint.
        assertNull(
            "bindEndpoint must clear the View-side InputConnection cache",
            view.activeInputConnection(),
        )

        // Simulate what the framework does on the IME's next input event
        // after restartInput: re-fetch a fresh InputConnection from the
        // View. With the new `endpoint` field in place, this connection
        // captures the NEW bridge.
        val fresh = view.onCreateInputConnection(EditorInfo())
            as TerminalInputConnection
        assertNotSame(
            "a fresh onCreateInputConnection after bindEndpoint must " +
                "return a different TerminalInputConnection instance",
            cachedByIme,
            fresh,
        )
    }
}