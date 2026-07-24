package com.taosun.hanterm.terminal

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * After a remote TUI enters the alternate screen, Gboard may keep the
 * pre-TUI [TerminalInputConnection]. The first switch into Chinese pinyin
 * then buffers commits until enough language toggles force an IME restart
 * (pending 汉字 flush in one burst).
 *
 * [TerminalView.onDisplayUpdated] must clear the View-side IC cache on the
 * rising edge of [com.termux.terminal.TerminalEmulator.isAlternateBufferActive]
 * so the next IME event rebuilds against a fresh connection. Robolectric's
 * IMM makes `restartInput` a no-op, so we pin the structural contract
 * (cache cleared / not cleared) the same way
 * [TerminalInputConnectionReconnectTest] does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TerminalViewAltBufferImeRefreshTest {

    private lateinit var context: Context
    private lateinit var view: TerminalView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        view = TerminalView(context)
        view.bindEndpoint(PtyBridgeEndpoint(BufferedPtyBridge()))
    }

    @Test
    fun altBufferEnter_clearsCachedInputConnection() {
        view.onCreateInputConnection(EditorInfo())
        assertNotNull(view.activeInputConnection())

        appendAnsi("\u001B[?1049h")
        view.onDisplayUpdated()

        assertNull(
            "alt-buffer enter must clear the View-side InputConnection cache",
            view.activeInputConnection(),
        )
    }

    @Test
    fun stayingInAltBuffer_doesNotClearAgain() {
        appendAnsi("\u001B[?1049h")
        view.onDisplayUpdated()
        view.onCreateInputConnection(EditorInfo())
        assertNotNull(view.activeInputConnection())

        // More TUI redraws while still on the alternate screen.
        appendAnsi("hello")
        view.onDisplayUpdated()

        assertNotNull(
            "subsequent onDisplayUpdated while still in alt-buffer must not " +
                "drop a freshly installed InputConnection",
            view.activeInputConnection(),
        )
    }

    @Test
    fun leaveAndReenterAltBuffer_clearsAgain() {
        appendAnsi("\u001B[?1049h")
        view.onDisplayUpdated()
        view.onCreateInputConnection(EditorInfo())

        appendAnsi("\u001B[?1049l")
        view.onDisplayUpdated()
        assertNotNull(
            "leaving alt-buffer alone must not clear the IC",
            view.activeInputConnection(),
        )

        appendAnsi("\u001B[?1049h")
        view.onDisplayUpdated()
        assertNull(
            "re-entering alt-buffer must clear the IC again",
            view.activeInputConnection(),
        )
    }

    private fun appendAnsi(csi: String) {
        val emu = view.currentEmulator()!!
        val bytes = csi.toByteArray(Charsets.UTF_8)
        emu.append(bytes, bytes.size)
    }
}
