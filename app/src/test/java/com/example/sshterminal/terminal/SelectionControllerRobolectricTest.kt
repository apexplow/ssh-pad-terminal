package com.example.sshterminal.terminal

import android.content.ClipboardManager
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric integration tests for SelectionController. Pins that the
 * controller's interactions with the real Android framework are wired
 * correctly:
 *   - ClipboardManager.setPrimaryClip round-trips: a second lookup reads
 *     the same label + text back
 *   - Toast text is the exact "已复制 N 字符" format
 *
 * Companion [SelectionControllerTest] covers pure state-machine and
 * clipboard-null branches without the Robolectric runtime overhead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SelectionControllerRobolectricTest {

    private lateinit var context: Context
    private lateinit var view: View
    private lateinit var clipboard: ClipboardManager
    private lateinit var ime: InputMethodManager
    private lateinit var toastLog: MutableList<CharSequence>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Inflate the view into a FrameLayout so windowToken is populated
        // by Robolectric's shadow window machinery.
        val parent = FrameLayout(context)
        view = View(context)
        parent.addView(view)
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, 800, 600)

        clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ime = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        toastLog = mutableListOf()
    }

    private fun newController() = SelectionController(
        view = view,
        clipboard = clipboard,
        ime = ime,
        toaster = { msg -> toastLog.add(msg) },
    )

    @Test
    fun enter_attachedView_makesActive() {
        val controller = newController()
        controller.enter(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 100f, 0))
        assertTrue("isActive must flip to true", controller.isActive)
    }

    @Test
    fun copyToClipboard_persistsToSystemClipboard() {
        val controller = newController()
        controller.copyToClipboard("build error: line 42")

        val clip = clipboard.primaryClip
        assertNotNull("primary clip must be set", clip)
        assertEquals("ssh-term", clip?.description?.label.toString())
        assertEquals("build error: line 42", clip?.getItemAt(0)?.coerceToText(context).toString())
    }

    @Test
    fun copyToClipboard_toastsCharCount() {
        val controller = newController()
        controller.copyToClipboard("你")  // 1 UTF-16 code unit; UTF-8 = 3 bytes

        assertEquals(1, toastLog.size)
        assertEquals("已复制 1 字符", toastLog.single().toString())
    }
}
