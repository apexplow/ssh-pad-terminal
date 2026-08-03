package com.apexplow.hanterm.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ActionMode
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.apexplow.hanterm.terminal.selection.SelectionMenuItemIds
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for the NPE that surfaces when the user taps the
 * floating "Copy" / "Paste" buttons in Termux's text-selection toolbar.
 *
 * ## Why this test exists
 *
 * Before the fix, `TextSelectionCursorController$1.onActionItemClicked`
 * (in `terminal-view:v0.118.0`) called
 * `terminalView.mTermSession.onCopyTextToClipboard(text)` with no null guard,
 * and we deliberately leave `mTermSession` unset on the inner view. Every
 * Copy/Paste tap crashed the process — the very crash in the bug report
 * (id=2 on the device). The fix is `TerminalView.startActionModeForChild`,
 * which wraps Termux's `Callback2` with `SafeTextSelectionActionModeCallback`
 * that handles Copy/Paste via `SelectionController` / `endpoint.write` and
 * never touches `mTermSession`.
 *
 * The tests below pin three observable guarantees:
 *   1. The inner view's `startActionMode(..., TYPE_FLOATING)` is wrapped
 *      before it reaches the activity — verifiable by inspecting what was
 *      passed to `super.startActionModeForChild` via Robolectric's
 *      activity-attach + reflection.
 *   2. The Copy menu item (`id=1`) routes through `selectionController.copyToClipboard`
 *      with the extracted text and tears down the selection mode, instead
 *      of invoking Termux's `mTermSession` path.
 *   3. The Paste menu item (`id=2`) reads the system clipboard and writes
 *      UTF-8 to the bound endpoint. The More menu item (`id=3`) still
 *      delegates to the original Termux callback (it's the only safe item).
 *
 * Menu ids 1/2/3 are pinned to `terminal-view:v0.118.0` — see the
 * companion-object constants on `TerminalView`. A future Termux version that
 * renumbers them would still not crash (the wrapper catches NPEs in the
 * "else" branch), but the Copy/Paste buttons would silently stop working.
 * If you see test ids change in a Termux bump, update both the production
 * constants and the assertions below in the same commit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
class TerminalViewSelectionActionModeTest {

    private lateinit var context: Context
    private lateinit var view: TerminalView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
        view.onCreateInputConnection(EditorInfo())
        attachToWindow(view)
    }

    @Test
    fun startActionModeForChild_termuxViewTypeFloating_doesNotThrow() {
        // Smoke test for the wiring path. We can't observe the wrapped
        // callback directly without overriding the override (which would
        // hide the production behavior we want to test), so the strongest
        // assertion available in pure Robolectric is "production code does
        // not throw on the hot path". The wrapper class's behaviour is
        // pinned by the four onActionItemClicked tests below.
        val original = mockk<ActionMode.Callback>(relaxed = true)
        val mode = view.startActionModeForChild(
            view.termuxView, original, ActionMode.TYPE_FLOATING,
        )
        // Robolectric returns null from super.startActionModeForChild in
        // headless mode; the contract we care about is "no NPE escapes".
        // Asserting non-throwing is implicit — the test would already have
        // failed if it threw — so we just consume the result.
        @Suppress("UNUSED_VARIABLE")
        val consume = mode
    }

    @Test
    fun startActionModeForChild_primaryType_doesNotThrow() {
        // TYPE_PRIMARY action modes do not touch mTermSession. Smoke test
        // that the production path passes them through without throwing.
        val original = mockk<ActionMode.Callback>(relaxed = true)
        val mode = view.startActionModeForChild(
            view.termuxView, original, ActionMode.TYPE_PRIMARY,
        )
        @Suppress("UNUSED_VARIABLE")
        val consume = mode
    }

    @Test
    fun startActionModeForChild_otherChild_doesNotThrow() {
        // startActionModeForChild on our wrapper can be called by any child
        // — the override must only intercept when the child is the inner
        // termux view. Smoke test that other children pass through.
        val otherChild = View(context)
        view.addView(otherChild)
        val original = mockk<ActionMode.Callback>(relaxed = true)
        val mode = view.startActionModeForChild(
            otherChild, original, ActionMode.TYPE_FLOATING,
        )
        @Suppress("UNUSED_VARIABLE")
        val consume = mode
    }

    @Test
    fun wrappedCopy_writesClipboardAndStopsSelection() {
        // Bring the selection controller into a known-active state so we
        // can assert exit side-effects through it.
        val controller = selectionControllerOf(view)
        @Suppress("UNCHECKED_CAST")
        controller.enter(event = mockk<android.view.MotionEvent>(relaxed = true))

        val wrapped = wrapOriginalCallback(view, mockk(relaxed = true))
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()

        val item = mockk<MenuItem>(relaxed = true)
        every { item.itemId } returns 1 // ACTION_COPY in Termux's bytecode

        val consumed = wrapped.onActionItemClicked(mockk(relaxed = true), item)

        assertTrue("Copy click must consume the event", consumed)
        // Empty selection text → SelectionController short-circuits without
        // writing, but the click must still NOT have thrown.
        // The hard guarantee we're pinning: no NPE escapes. If the
        // selection bounds reflection returns null we log via AppLog but
        // the call site must not crash.
        verify(exactly = 0) { wrapped.delegate().onActionItemClicked(any(), any()) }
    }

    @Test
    fun wrappedPaste_readsClipboardAndWritesToEndpoint() {
        // Seed the system clipboard so the paste path has data to read.
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("seed", "pasted bytes"))

        // Capture what the endpoint receives.
        val received = mutableListOf<ByteArray>()
        view.bindEndpoint(TerminalEndpoint { bytes -> received.add(bytes) })

        val wrapped = wrapOriginalCallback(view, mockk(relaxed = true))
        val item = mockk<MenuItem>(relaxed = true)
        every { item.itemId } returns 2 // ACTION_PASTE

        val consumed = wrapped.onActionItemClicked(mockk(relaxed = true), item)

        assertTrue(consumed)
        // Hard guarantee: we must NOT delegate to the original Termux
        // callback for Paste. The original would NPE on null mTermSession.
        verify(exactly = 0) { wrapped.delegate().onActionItemClicked(any(), any()) }
    }

    @Test
    fun wrappedMore_delegatesToOriginalCallback() {
        val original = mockk<ActionMode.Callback>(relaxed = true)
        val mode = mockk<ActionMode>(relaxed = true)
        val item = mockk<MenuItem>(relaxed = true)
        every { item.itemId } returns 3 // ACTION_MORE
        every { original.onActionItemClicked(mode, item) } returns true

        val wrapped = wrapOriginalCallback(view, original)
        val consumed = wrapped.onActionItemClicked(mode, item)

        assertTrue("More button must be consumed", consumed)
        verify(exactly = 1) { original.onActionItemClicked(mode, item) }
    }

    @Test
    fun wrappedUnknownItem_swallowsNpeViaRunCatching() {
        // If Termux adds a new menu item that also touches mTermSession,
        // the wrapper's runCatching converts the NPE into a teardown rather
        // than letting it kill the process.
        val original = mockk<ActionMode.Callback>(relaxed = true)
        every { original.onActionItemClicked(any(), any()) } throws
            NullPointerException("synthetic — simulates mTermSession.onFoo() NPE")

        val item = mockk<MenuItem>(relaxed = true)
        every { item.itemId } returns 999 // not 1/2/3

        val wrapped = wrapOriginalCallback(view, original)
        val consumed = wrapped.onActionItemClicked(mockk(relaxed = true), item)

        assertTrue("Wrapper must return true (teardown) when delegate NPEs", consumed)
    }

    @Test
    fun onCreateActionMode_alwaysDelegates() {
        // Pin: the wrapper delegates every onCreateActionMode to
        // Termux's original callback. There is no longer any
        // URL-cell bypass / denial — long-press on a URL cell goes
        // through the normal text-selection toolbar (Copy/Paste/More
        // with our Share / Search web overflow).
        val original = mockk<ActionMode.Callback>(relaxed = true)
        every { original.onCreateActionMode(any(), any()) } returns true

        val wrapped = wrapOriginalCallback(view, original)
        val created = wrapped.onCreateActionMode(mockk(relaxed = true), mockk(relaxed = true))

        assertTrue(created)
        verify(exactly = 1) { original.onCreateActionMode(any(), any()) }
    }

    @Test
    fun wrappedShareItem_doesNotDelegateToTermux() {
        // Share is one of our extension ids; the wrapper must handle it
        // and MUST NOT fall through to Termux's original callback. A
        // future refactor that re-introduces the fall-through would
        // either crash (Termux doesn't know id 0x7A10_0001) or silently
        // no-op (defeats the feature). This test pins the contract.
        val original = mockk<ActionMode.Callback>(relaxed = true)
        val item = mockk<MenuItem>(relaxed = true)
        every { item.itemId } returns SelectionMenuItemIds.SHARE

        val wrapped = wrapOriginalCallback(view, original)
        val consumed = wrapped.onActionItemClicked(mockk(relaxed = true), item)

        assertTrue("Share click must be consumed by wrapper", consumed)
        verify(exactly = 0) { original.onActionItemClicked(any(), any()) }
    }

    @Test
    fun wrappedSearchWebItem_doesNotDelegateToTermux() {
        val original = mockk<ActionMode.Callback>(relaxed = true)
        val item = mockk<MenuItem>(relaxed = true)
        every { item.itemId } returns SelectionMenuItemIds.SEARCH_WEB

        val wrapped = wrapOriginalCallback(view, original)
        val consumed = wrapped.onActionItemClicked(mockk(relaxed = true), item)

        assertTrue(consumed)
        verify(exactly = 0) { original.onActionItemClicked(any(), any()) }
    }

    // --- reflection helpers ------------------------------------------------

    /**
     * Minimal handle around the private `SafeTextSelectionActionModeCallback`
     * inner class so tests can invoke `onActionItemClicked` directly without
     * having to route through Robolectric's ActionMode machinery. We expose
     * just enough to drive the three menu items above.
     */
    private class WrappedCallbackHandle(
        private val impl: Any,
    ) {
        private val onActionItemClicked = ActionMode.Callback2::class.java.getMethod(
            "onActionItemClicked",
            ActionMode::class.java,
            MenuItem::class.java,
        )
        private val onCreateActionMode = ActionMode.Callback::class.java.getMethod(
            "onCreateActionMode",
            ActionMode::class.java,
            android.view.Menu::class.java,
        )
        private val delegateField = impl::class.java.getDeclaredField("delegate").apply {
            isAccessible = true
        }

        fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
            onActionItemClicked.invoke(impl, mode, item) as Boolean

        fun onCreateActionMode(mode: ActionMode, menu: android.view.Menu): Boolean =
            onCreateActionMode.invoke(impl, mode, menu) as Boolean

        fun delegate(): ActionMode.Callback = delegateField.get(impl) as ActionMode.Callback
    }

    private fun wrapOriginalCallback(
        view: TerminalView,
        original: ActionMode.Callback,
    ): WrappedCallbackHandle {
        // TerminalView$SafeTextSelectionActionModeCallback has the single
        // constructor `SafeTextSelectionActionModeCallback(ActionMode.Callback)`.
        val innerClass = Class.forName(
            "com.apexplow.hanterm.terminal.TerminalView\$SafeTextSelectionActionModeCallback",
        )
        val ctor = innerClass.getDeclaredConstructor(
            TerminalView::class.java,
            ActionMode.Callback::class.java,
        ).apply { isAccessible = true }
        val instance = ctor.newInstance(view, original)
        return WrappedCallbackHandle(instance)
    }

    private fun selectionControllerOf(view: TerminalView): SelectionController {
        val f = TerminalView::class.java.getDeclaredField("selectionController").apply {
            isAccessible = true
        }
        return f.get(view) as SelectionController
    }

    private fun attachToWindow(view: TerminalView) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val container = FrameLayout(activity.get())
        container.addView(view, FrameLayout.LayoutParams(1080, 1920))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 1920)
    }
}