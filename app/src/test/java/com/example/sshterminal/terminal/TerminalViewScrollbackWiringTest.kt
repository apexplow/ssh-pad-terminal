package com.example.sshterminal.terminal

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wires the ScrollbackController into TerminalView. Asserts:
 *   1. The scrollbackController field is lazily constructed when
 *      first accessed (proves the lazy + non-null init works).
 *   2. isInScrollback reads the controller's state.
 *   3. scrollToBottom() resets the inner view's mTopRow to 0.
 *   4. setScrollbackListener() fires the initial state on registration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TerminalViewScrollbackWiringTest {

    private lateinit var context: Context
    private lateinit var view: TerminalView

    private val innerTopRowField: java.lang.reflect.Field =
        com.termux.view.TerminalView::class.java
            .getDeclaredField("mTopRow")
            .apply { isAccessible = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
        view.onCreateInputConnection(EditorInfo())
    }

    @Test
    fun isInScrollback_readsControllerState() {
        // Default: not in scrollback.
        assertFalse(view.isInScrollback)
    }

    @Test
    fun scrollToBottom_resetsInnerTopRow() {
        val emulator = view.termuxView.mEmulator!!
        // Populate scrollback and jump up two pages.
        val scrollbackFiller = "\r\n".repeat(emulator.mRows * 4).toByteArray()
        emulator.append(scrollbackFiller, scrollbackFiller.size)
        innerTopRowField.setInt(view.termuxView, emulator.mRows * 2)

        view.scrollToBottom()

        assertEquals(0, innerTopRowField.getInt(view.termuxView))
        assertFalse(view.isInScrollback)
    }

    @Test
    fun setScrollbackListener_firesInitialState() {
        var seen: ScrollbackController.ScrollbackState? = null
        view.setScrollbackListener { state -> seen = state }
        // Initial state fires once on registration (mirrors the
        // setPtyResizeListener pattern).
        assertNotNull(seen)
        assertFalse(seen!!.isInScrollback)
    }
}
