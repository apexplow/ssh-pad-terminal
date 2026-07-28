package com.apexplow.hanterm.terminal

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalViewClient

/**
 * Thin wrapper around Termux's `com.termux.view.TerminalView`.
 *
 * This class owns the inner view's construction, focus policy, font-size
 * idempotency, and the reflection helpers needed to extract selected text
 * without invoking Termux's null `mTermSession` path. Everything else
 * (scrollback, selection controller, IME routing) lives in [TerminalView].
 */
internal class TermuxViewBridge(
    context: Context,
    attrs: AttributeSet?,
    private val client: TerminalViewClient,
) {

    companion object {
        // Matches the value the constructor hard-codes when it pre-initialises
        // mRenderer. Tracked separately so setTextSize's idempotency guard starts
        // out comparing against the right baseline.
        const val DEFAULT_TEXT_SIZE = 14

        /**
         * Menu item ids Termux's `TextSelectionCursorController$1.onCreateActionMode`
         * hands to `Menu.add(group, id, order, title)`. Pinned to
         * `terminal-view:v0.118.0` (decompiled from the cached AAR). Termux
         * never publishes these constants — they live as `iconst_1/2/3`
         * literals inside the bytecode tableswitch in `onActionItemClicked`.
         * If a future Termux version renumbers them, our Copy/Paste intercept
         * degrades to "delegate through to the broken Termux path" inside the
         * try-catch in [TerminalView.SafeTextSelectionActionModeCallback] — the
         * process no longer crashes, the menu just stops working.
         */
        const val TERMUX_SELECTION_MENU_COPY = 1
        const val TERMUX_SELECTION_MENU_PASTE = 2
        const val TERMUX_SELECTION_MENU_MORE = 3

        /**
         * Reflection accessors into Termux's `TextSelectionCursorController`
         * to read the selection bounds it stores on enter. `mSelX1/Y1/X2/Y2`
         * are the four ints Termux passes to `mEmulator.getSelectedText(...)`
         * just before it dereferences the (null) `mTermSession`. We need them
         * so our wrapper can do the same extraction without invoking the
         * Termux path that NPEs.
         *
         * Same caveat as the menu ids: pinned to `terminal-view:v0.118.0`,
         * failures wrapped in `runCatching` in [TerminalView.SafeTextSelectionActionModeCallback].
         */
        private val selectionBoundsFields: List<java.lang.reflect.Field> by lazy {
            val cls = Class.forName(
                "com.termux.view.textselection.TextSelectionCursorController",
            )
            listOf("mSelX1", "mSelY1", "mSelX2", "mSelY2").map { name ->
                cls.getDeclaredField(name).apply { isAccessible = true }
            }
        }

        private val textSelectionControllerField: java.lang.reflect.Field by lazy {
            Class.forName("com.termux.view.TerminalView").getDeclaredField(
                "mTextSelectionCursorController",
            ).apply { isAccessible = true }
        }
    }

    val view: com.termux.view.TerminalView =
        com.termux.view.TerminalView(context, attrs).also { child ->
            child.isFocusable = false
            child.isFocusableInTouchMode = false
            // setTextSize initialises mRenderer (TerminalRenderer). Without
            // this call mRenderer stays null and onDraw crashes with an NPE.
            child.setTextSize(DEFAULT_TEXT_SIZE)
            // We bypass attachSession(), which normally sets mClient.
            child.setTerminalViewClient(client)
        }

    private var currentTextSize: Int = DEFAULT_TEXT_SIZE

    /**
     * Change the rendered font size. Idempotent: repeated calls with the same
     * size are no-ops so volume-key autorepeat / Compose recompositions don't
     * rebuild the renderer.
     */
    fun setTextSize(size: Int) {
        if (size == currentTextSize) return
        currentTextSize = size
        view.setTextSize(size)
    }

    /** Temporarily make the inner view focusable so Termux selection wins [requestFocus]. */
    fun enableFocusForSelection() {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
    }

    /** Restore the wrapper as the IME focus target after selection ends. */
    fun disableFocusAfterSelection(requestFocusOnWrapper: () -> Unit) {
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        requestFocusOnWrapper()
    }

    /**
     * Read Termux's private `mSelX1/Y1/X2/Y2` off the inner view's
     * `mTextSelectionCursorController` and reproduce Termux's selection-text
     * extraction (`mEmulator.getSelectedText(...).trim()`) ourselves.
     *
     * Returns `null` on any failure (Robolectric shadow missing, future Termux
     * renames the fields, the emulator is briefly torn down during a
     * configuration change, etc.).
     */
    fun extractSelectedTextSafely(): String? = runCatching {
        val controller = textSelectionControllerField.get(view) ?: return@runCatching null
        val fields = selectionBoundsFields
        val x1 = fields[0].getInt(controller)
        val y1 = fields[1].getInt(controller)
        val x2 = fields[2].getInt(controller)
        val y2 = fields[3].getInt(controller)
        val emulator = view.mEmulator ?: return@runCatching null
        emulator.getSelectedText(x1, y1, x2, y2)?.trim()
    }.onFailure {
        com.apexplow.hanterm.logging.AppLog.w(
            "TerminalView",
            "extractSelectedTextSafely reflection failed; Copy will be a no-op",
            it,
        )
    }.getOrNull()

    /**
     * Re-measure the inner Termux view with the wrapper's actual size when the
     * two have diverged. `com.termux.view.TerminalView`'s `onMeasure` reads the
     * emulator's grid dimensions (initialised to 80×24) to compute its own
     * desired size, ignoring `MATCH_PARENT` layout params. The workaround forces
     * a re-measure with the wrapper's actual size so the next layout pass reports
     * real pixel dimensions to the PTY resize tracker.
     */
    fun remeasureToFitParent(parentWidth: Int, parentHeight: Int) {
        if (view.width != parentWidth || view.height != parentHeight) {
            view.measure(
                MeasureSpec.makeMeasureSpec(parentWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(parentHeight, MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, parentWidth, parentHeight)
        }
    }

    /** Start Termux text selection mode, temporarily enabling inner-view focus. */
    fun startTextSelectionMode(event: MotionEvent?) {
        view.startTextSelectionMode(event)
    }

    val isSelectingText: Boolean get() = view.isSelectingText

    fun stopTextSelectionMode() {
        view.stopTextSelectionMode()
    }

    /** Post a call that will be executed on the view's message queue. */
    fun postInvalidateOnAnimation() = view.postInvalidateOnAnimation()

    fun requestLayout() = view.requestLayout()

    fun invalidate() = view.invalidate()

    /**
     * Register a layout-change listener on the inner view. Exposed so the host
     * can wire the PTY resize tracker after both objects exist.
     */
    fun addOnLayoutChangeListener(listener: android.view.View.OnLayoutChangeListener) {
        view.addOnLayoutChangeListener(listener)
    }

    /**
     * Returns the live [TerminalEmulator] backing the inner view, or `null`
     * when construction failed / the field was cleared.
     *
     * **Must read [com.termux.view.TerminalView.mEmulator] directly** — HanTerm
     * never attaches a Termux [com.termux.terminal.TerminalSession] (its
     * constructor forks a local shell via JNI). `getCurrentSession()` is
     * therefore always null here; Module 19 originally used that path and the
     * tmux drawer permanently showed "terminal emulator unavailable". The same
     * `mEmulator` field is what [TerminalPane]'s IO loop and
     * [extractSelectedTextSafely] already use.
     *
     * Used by the terminal IO/rendering integration and focused regression
     * tests; control-plane queries no longer scrape this screen buffer.
     */
    fun currentEmulator(): TerminalEmulator? = view.mEmulator
}
