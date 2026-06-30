package com.example.sshterminal.terminal

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView as TermuxTerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [ScrollbackController]. Uses a real [com.example.sshterminal.terminal.TerminalView]
 * (which builds a real [TerminalEmulator] and a real [TermuxTerminalView] inner view) so we can
 * read `termuxView.mTopRow` after a gesture and observe the doScroll side-effect end-to-end.
 * Pure-controller logic (state machine, threshold) is driven by real MotionEvents constructed
 * via the standard `MotionEvent.obtain` API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScrollbackControllerTest {

    private fun newController(): Triple<TerminalView, TerminalEmulator, ScrollbackController> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
        view.onCreateInputConnection(EditorInfo())
        val emulator = view.termuxView.mEmulator!!
        val controller = ScrollbackController(
            view = view,
            innerView = view.termuxView,
            emulator = emulator,
            fontLineSpacing = { 16f },
        )
        return Triple(view, emulator, controller)
    }

    @Test
    fun state_isInScrollbackFalseByDefault() {
        val (_, _, controller) = newController()
        assertFalse(controller.state.value.isInScrollback)
        assertEquals(0, controller.state.value.pendingOutputCount)
    }
}