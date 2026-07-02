package com.example.sshterminal.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-logic tests for SelectionController. Uses mockk for the three Android
 * framework dependencies (View / ClipboardManager / InputMethodManager) so
 * Robolectric's shadow overhead is not required for state-machine coverage.
 *
 * Companion [SelectionControllerRobolectricTest] covers the real-Android paths
 * (actual hideSoftInputFromWindow call, Toast surface, ClipboardManager
 * round-trip).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SelectionControllerTest {

    private lateinit var view: View
    private lateinit var clipboard: ClipboardManager
    private lateinit var ime: InputMethodManager
    private lateinit var toastLog: MutableList<CharSequence>
    private lateinit var controller: SelectionController

    @Before
    fun setUp() {
        view = mockk(relaxed = true)
        // View.windowToken is non-null by default in Android but mockk returns
        // null for unstubbed object-typed properties. Stub it explicitly so
        // the production `if (event != null && view.windowToken != null)`
        // branch fires the IME hide.
        every { view.windowToken } returns mockk<IBinder>(relaxed = true)
        clipboard = mockk(relaxed = true)
        ime = mockk(relaxed = true)
        toastLog = mutableListOf()
        controller = SelectionController(
            view = view,
            clipboard = clipboard,
            ime = ime,
            toaster = { msg -> toastLog.add(msg) },
        )
    }

    @Test
    fun isActive_isFalseInitially() {
        assertFalse(controller.isActive)
    }

    @Test
    fun enter_withEvent_setsActiveAndHidesIme() {
        controller.enter(mockk<MotionEvent>(relaxed = true))

        assertTrue(controller.isActive)
        verify { ime.hideSoftInputFromWindow(any(), 0) }
    }

    @Test
    fun enter_withNullEvent_setsActiveButDoesNotHideIme() {
        controller.enter(event = null)

        assertTrue(controller.isActive)
        verify(exactly = 0) { ime.hideSoftInputFromWindow(any(), any()) }
    }

    @Test
    fun enter_whenAlreadyActive_isNoOp() {
        controller.enter(mockk<MotionEvent>(relaxed = true))
        controller.enter(mockk<MotionEvent>(relaxed = true))

        verify(exactly = 1) { ime.hideSoftInputFromWindow(any(), 0) }
    }

    @Test
    fun enter_withWindowTokenNull_skipsImeHide() {
        every { view.windowToken } returns null
        controller.enter(mockk<MotionEvent>(relaxed = true))

        assertTrue(controller.isActive)
        verify(exactly = 0) { ime.hideSoftInputFromWindow(any(), any()) }
    }

    @Test
    fun exit_setsInactive() {
        controller.enter(mockk<MotionEvent>(relaxed = true))
        controller.exit()

        assertFalse(controller.isActive)
    }

    @Test
    fun exit_whenAlreadyInactive_isNoOp() {
        controller.exit()

        assertFalse(controller.isActive)
    }

    @Test
    fun copyToClipboard_nullText_returnsFalseNoOp() {
        val ok = controller.copyToClipboard(null)

        assertFalse(ok)
        verify(exactly = 0) { clipboard.setPrimaryClip(any()) }
        assertTrue(toastLog.isEmpty())
    }

    @Test
    fun copyToClipboard_emptyText_returnsFalseNoOp() {
        val ok = controller.copyToClipboard("")

        assertFalse(ok)
        verify(exactly = 0) { clipboard.setPrimaryClip(any()) }
        assertTrue(toastLog.isEmpty())
    }

    @Test
    fun copyToClipboard_validText_writesClipAndToasts() {
        val ok = controller.copyToClipboard("hello world")

        assertTrue(ok)
        verify { clipboard.setPrimaryClip(any()) }
        assertEquals(1, toastLog.size)
        // Spec: Toast text is `已复制 ${text.length} 字符`. UTF-16 code units.
        assertEquals("已复制 11 字符", toastLog.single().toString())
    }

    @Test
    fun copyToClipboard_clipboardNull_returnsFalseAndDoesNotToast() {
        val c = SelectionController(
            view = view,
            clipboard = null,
            ime = ime,
            toaster = { msg -> toastLog.add(msg) },
        )

        val ok = c.copyToClipboard("hello")

        assertFalse(ok)
        assertTrue("clipboard-null path must NOT toast (avoid misleading UX)", toastLog.isEmpty())
    }
}
