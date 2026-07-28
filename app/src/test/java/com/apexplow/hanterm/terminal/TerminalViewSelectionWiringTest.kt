package com.apexplow.hanterm.terminal

import android.content.ClipboardManager
import android.content.Context
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.widget.FrameLayout
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wires the three SelectionController integration sites in TerminalView:
 *
 *   1. termuxViewClient.onLongPress(event)
 *        → selectionController.enter(event)
 *
 *   2. termuxViewClient.copyModeChanged(true / false)
 *        → controller.enter(null) | controller.exit()
 *
 *   3. transcriptOutput.onCopyTextToClipboard(text)
 *        → controller.copyToClipboard(text)   (always)
 *        → clipboard receives the text (verify by reading back)
 *        → no clipboard write when text is empty
 *
 * Each site is exercised via reflection on TerminalView's private fields
 * (same pattern as AltBufferScrollCrashGuardTest). We assert observable
 * side-effects (clipboard contents, isActive state), not Termux internals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
class TerminalViewSelectionWiringTest {

    private lateinit var context: Context
    private lateinit var view: TerminalView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
        view.onCreateInputConnection(EditorInfo())
    }

    @Test
    fun onLongPress_startsTermuxSelectionMode() {
        attachToWindow(view)

        val event = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_DOWN, 100f, 100f, 0,
        )
        try {
            invokeOnLongPress(event)
            assertTrue(
                "startTextSelectionMode must enter Termux copy mode — " +
                    "inner view needs temporary focus (see enableInnerViewForSelection)",
                view.termuxView.isSelectingText,
            )
        } finally {
            event.recycle()
        }
    }

    @Test
    fun copyModeChanged_false_restoresWrapperFocus() {
        attachToWindow(view)
        val event = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_DOWN, 100f, 100f, 0,
        )
        try {
            invokeOnLongPress(event)
            assertTrue(view.termuxView.isFocusable)

            invokeCopyModeChanged(false)

            assertFalse(view.termuxView.isFocusable)
            assertFalse(view.termuxView.isFocusableInTouchMode)
            assertTrue(view.hasFocus())
        } finally {
            event.recycle()
        }
    }

    @Test
    fun onLongPress_entersController() {
        val event = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_DOWN, 100f, 100f, 0,
        )

        invokeOnLongPress(event)

        val controller = controllerField.get(view) as SelectionController
        assertTrue(
            "selectionController.isActive must flip on long-press",
            controller.isActive,
        )
    }

    @Test
    fun copyModeChanged_false_exitsController() {
        val controller = controllerField.get(view) as SelectionController
        controller.enter(event = mockk(relaxed = true))
        assertTrue(controller.isActive)

        invokeCopyModeChanged(false)

        assertFalse(controller.isActive)
    }

    @Test
    fun copyModeChanged_true_keepsControllerActive() {
        invokeCopyModeChanged(true)

        val controller = controllerField.get(view) as SelectionController
        assertTrue(controller.isActive)
    }

    @Test
    fun onCopyTextToClipboard_validText_writesClip() {
        invokeOnCopyTextToClipboard("compile error: missing semicolon")

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        assertNotNull(clip)
        assertEquals("ssh-term", clip?.description?.label.toString())
        assertEquals(
            "compile error: missing semicolon",
            clip?.getItemAt(0)?.coerceToText(context).toString(),
        )
    }

    @Test
    fun onCopyTextToClipboard_emptyText_doesNotWriteClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()

        invokeOnCopyTextToClipboard("")

        assertFalse(
            "empty copy must not write the clipboard",
            clipboard.hasPrimaryClip(),
        )
    }

    // --- reflection helpers ------------------------------------------------

    private val controllerField by lazy {
        TerminalView::class.java.getDeclaredField("selectionController").apply {
            isAccessible = true
        }
    }

    private fun attachToWindow(view: TerminalView) {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val container = FrameLayout(activity.get())
        container.addView(
            view,
            FrameLayout.LayoutParams(1080, 1920),
        )
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 1920)
    }

    private fun invokeOnLongPress(event: MotionEvent) {
        val client = clientField()
        client::class.java.getMethod("onLongPress", MotionEvent::class.java)
            .invoke(client, event)
    }

    private fun invokeCopyModeChanged(copyMode: Boolean) {
        val client = clientField()
        client::class.java.getMethod("copyModeChanged", Boolean::class.javaPrimitiveType)
            .invoke(client, copyMode)
    }

    private fun invokeOnCopyTextToClipboard(text: String) {
        val output = transcriptOutputField()
        output::class.java.getMethod("onCopyTextToClipboard", String::class.java)
            .invoke(output, text)
    }

    private fun clientField(): Any {
        val f = TerminalView::class.java.getDeclaredField("termuxViewClient").apply {
            isAccessible = true
        }
        return f.get(view)!!
    }

    private fun transcriptOutputField(): Any {
        val f = TerminalView::class.java.getDeclaredField("transcriptOutput").apply {
            isAccessible = true
        }
        return f.get(view)!!
    }
}
