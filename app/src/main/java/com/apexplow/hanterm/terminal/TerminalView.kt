package com.apexplow.hanterm.terminal

import android.content.Context
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.apexplow.hanterm.logging.AppLog
import com.apexplow.hanterm.terminal.selection.SelectionMenuConfig
import com.apexplow.hanterm.terminal.selection.addSelectionMenuExtensions
import com.apexplow.hanterm.terminal.selection.handleSelectionMenuItemClick
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch

/**
 * HanTerm terminal widget.
 *
 * The class is now a coordinator that delegates to three helpers:
 * - [TermuxViewBridge] — the inner `com.termux.view.TerminalView`, font size,
 *   selection-mode focus dance, and reflection-based selected-text extraction.
 * - [ImeKeyRouter] — InputConnection cache, composing-gate, and hardware-key
 *   routing (see `implementation_plan.md` §"KeyEvent 路由规则表").
 * - [PtyResizeTracker] — debounced (cols, rows, widthPx, heightPx) reporting.
 *
 * Remaining responsibilities (kept here because they cross helpers):
 * transcript output, emulator construction, scrollback/selection controllers,
 * crash guards, and the floating action-mode intercept for Termux Copy/Paste.
 */
open class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : FrameLayout(context, attrs), TerminalComposingView {

    private val imm: InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private val imeKeyRouter = ImeKeyRouter(context)

    private var endpoint: TerminalEndpoint = TerminalEndpoint {}

    private val scrollbackController: ScrollbackController by lazy {
        ScrollbackController(
            view = this,
            innerView = termuxView,
            emulator = emulator,
            fontLineSpacing = { termuxView.mRenderer?.getFontLineSpacing()?.toFloat() ?: 0f },
            sendToRemote = { bytes -> endpoint.write(bytes) },
        )
    }

    /**
     * Sprint 4 T17 — IO-thread→Main torn-write guard. Stamped whenever
     * bytes from the sshj reader enter the emulator (see
     * [transcriptOutput.write]). Read by
     * [com.apexplow.hanterm.terminal.link.LinkOverlay.refresh] to skip
     * a refresh inside 16 ms of an IO append.
     *
     * `@Volatile` provides the happens-before edge the Main-thread read
     * needs without an `AtomicLong`. Single writer (IO thread via
     * `endpoint.write`); single reader (Main via the overlay).
     */
    @Volatile
    private var lastWriteUptimeMs: Long = 0L

    /**
     * Sprint 4 Step 9 — link long-press gesture. Initialized lazily after
     * [linkOverlay] so the order in [gestureConsumers] is deterministic
     * (scrollback first, link second).
     */
    private val linkOverlay: com.apexplow.hanterm.terminal.link.LinkOverlay by lazy {
        com.apexplow.hanterm.terminal.link.LinkOverlay(
            emulatorSource = { emulator.takeIf { termuxView.mRenderer != null } },
            topRowSource = { scrollbackController.readInnerTopRow() },
            lastWriteUptimeMsSource = { lastWriteUptimeMs },
        )
    }

    /**
     * Hook for Step 11 ([com.apexplow.hanterm.terminal.link.LinkDialog])
     * — set via [setLinkTapListener]. Stored separately from the
     * gesture construction so the Compose-side wiring can register
     * before the overlay actually fires.
     *
     * **2026-08-02.** Single-tap UX was too aggressive — bare taps
     * were stealing the click from normal terminal character input.
     * Now the gesture is Ctrl+tap (browser "open in new tab"
     * convention; the HanTerm shell is keyboard-only so a hardware
     * Ctrl is always in reach). Public API stays a "set listener"
     * callback so Compose wiring doesn't change.
     */
    private var linkTapListener: ((String) -> Unit)? = null

    fun setLinkTapListener(listener: (String) -> Unit) {
        linkTapListener = listener
    }

    private val linkGesture: com.apexplow.hanterm.terminal.link.LinkGesture by lazy {
        com.apexplow.hanterm.terminal.link.LinkGesture(
            context = context,
            view = this,
            overlay = linkOverlay,
            bridge = termuxViewBridge,
            isComposingProvider = { isComposing() },
            // Issue #linklongpress-listener-null: a real device's tap
            // can fire before Compose's `onTerminalViewChanged` has
            // installed the listener (Sprint 4 on-device bug,
            // 2026-08-01). The previous `?.invoke(url)` silently
            // dropped the URL — the user saw no LinkDialog AND no
            // Termux toolbar, and we had no signal to disambiguate
            // "listener never wired" from "overlay empty" from "dialog
            // dismiss race". Log a warning on the null path so the
            // next bug report's app.log tells us which of the three.
            onSingleTap = { url ->
                val listener = linkTapListener
                if (listener != null) {
                    listener.invoke(url)
                } else {
                    com.apexplow.hanterm.logging.AppLog.w(
                        "TerminalView",
                        "linkTapListener is null at tap — " +
                            "Compose `onTerminalViewChanged` did not install " +
                            "setLinkTapListener on this TerminalView " +
                            "(hash=${System.identityHashCode(this)})",
                    )
                }
            },
        )
    }

    /** Internal accessor for the overlay (used by [com.apexplow.hanterm.terminal.link.LinkOverlayView]). */
    internal val linkOverlayForView: com.apexplow.hanterm.terminal.link.LinkOverlay get() = linkOverlay

    /**
     * Sprint 4 T7 — gesture consumer chain consulted by [dispatchTouchEvent]
     * in order. The list is built once at construction (Sprint 4 wires
     * `LinkGesture` here in Step 9; this commit only registers
     * `ScrollbackController` and reserves a slot for the link gesture).
     */
    private val gestureConsumers: List<GestureConsumer> by lazy {
        // Order: scrollback first (multi-touch + slop-cross scroll
        // wins), link long-press second (single-finger hold on URL cell).
        // A future selection-controller or zmodem-controller would slot
        // in between — see `terminal/TouchDecision.kt` for the contract.
        buildList {
            add(scrollbackController)
            add(linkGesture)
        }
    }

    /**
     * Sprint 4 T4 — per-event gesture-suppression latch.
     *
     * Set by a [GestureConsumer] (LinkGesture, in Step 9) on its
     * consumed ACTION_DOWN to signal that subsequent inner-view gestures
     * — specifically Termux's text-selection `GestureDetector.onLongPress`
     * — should NOT fire for the rest of this touch sequence. Cleared on
     * the matching ACTION_UP / ACTION_CANCEL so unrelated touches are
     * not affected.
     *
     * Read by `dispatchTouchEvent` to short-circuit `super.dispatchTouchEvent`
     * for the suppression window — but the cleaner mechanism today is
     * for the consumer itself to call `termuxViewBridge.cancelInnerGesture()`
     * and return `TouchDecision.Consumed`, which already prevents the
     * inner GestureDetector from firing. This flag is left as a documented
     * seam for the Step 9 wiring.
     */
    private var gestureSuppressed: Boolean = false

    private val selectionController: SelectionController = SelectionController(
        view = this,
        clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager,
        ime = imm,
    )

    // Selection / focus helpers delegated to the bridge. They are declared
    // before [termuxViewClient] so the anonymous client object can call them
    // without capturing the not-yet-initialised [termuxViewBridge] property.
    private fun enableInnerViewForSelection(): Unit = termuxViewBridge.enableFocusForSelection()

    private fun restoreInnerViewNonFocusable(): Unit =
        termuxViewBridge.disableFocusAfterSelection(::requestFocus)

    private fun startTextSelectionMode(event: MotionEvent?): Unit =
        termuxViewBridge.startTextSelectionMode(event)

    private fun stopTextSelectionMode(): Unit = termuxViewBridge.stopTextSelectionMode()

    private val isInnerSelectingText: Boolean get() = termuxViewBridge.isSelectingText

    private val termuxViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float) = scale
        override fun onSingleTapUp(e: MotionEvent) {
            requestFocus()
        }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = false
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = false
        override fun copyModeChanged(copyMode: Boolean) {
            if (copyMode) {
                selectionController.enter(event = null)
            } else {
                selectionController.exit()
                restoreInnerViewNonFocusable()
            }
        }
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean = true
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = true
        override fun onLongPress(event: MotionEvent): Boolean {
            selectionController.enter(event)
            enableInnerViewForSelection()
            startTextSelectionMode(event)
            if (!isInnerSelectingText) {
                restoreInnerViewNonFocusable()
            }
            return true
        }
        override fun readControlKey() = false
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private val termuxViewBridge = TermuxViewBridge(
        context = context,
        attrs = attrs,
        client = termuxViewClient,
    )

    val termuxView = termuxViewBridge.view

    private val transcriptOutput = object : TerminalOutput() {
        override fun write(bytes: ByteArray, offset: Int, len: Int) {
            termuxViewBridge.postInvalidateOnAnimation()
            if (len > 0) {
                if (offset == 0 && len == bytes.size) {
                    endpoint.write(bytes)
                } else {
                    endpoint.write(bytes.copyOfRange(offset, offset + len))
                }
                // Sprint 4 T17 — stamp the IO→Main torn-write guard
                // BEFORE the emulator copies the bytes into its
                // screen buffer, so a same-frame
                // `LinkOverlay.refresh()` call cannot read torn rows.
                // `endpoint.write` is the sshj reader thread's path
                // into the emulator (it eventually calls
                // `emulator.append`).
                lastWriteUptimeMs = android.os.SystemClock.uptimeMillis()
            }
            if (scrollbackController.state.value.isInScrollback) {
                scrollbackController.onTranscriptWrite(len, emulator.mColumns)
            }
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {
            selectionController.copyToClipboard(text)
            stopTextSelectionMode()
        }
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {
            termuxViewBridge.postInvalidateOnAnimation()
        }
    }

    private val emulator: TerminalEmulator = try {
        TerminalEmulator(
            transcriptOutput,
            80,
            24,
            null,
            object : TerminalSessionClient {
                override fun onTextChanged(session: TerminalSession) {}
                override fun onTitleChanged(session: TerminalSession) {}
                override fun onSessionFinished(session: TerminalSession) {}
                override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
                override fun onPasteTextFromClipboard(session: TerminalSession) {}
                override fun onBell(session: TerminalSession) {}
                override fun onColorsChanged(session: TerminalSession) {}
                override fun onTerminalCursorStateChange(visible: Boolean) {}
                override fun getTerminalCursorStyle(): Int = 0
                override fun logError(tag: String?, message: String?) {}
                override fun logWarn(tag: String?, message: String?) {}
                override fun logInfo(tag: String?, message: String?) {}
                override fun logDebug(tag: String?, message: String?) {}
                override fun logVerbose(tag: String?, message: String?) {}
                override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
                override fun logStackTrace(tag: String?, e: Exception?) {}
            },
        )
    } catch (t: Throwable) {
        writeCrashLog("TerminalEmulator construction failed", t)
        throw t
    }.also {
        termuxView.mEmulator = it
    }

    private val ptyResizeTracker = PtyResizeTracker(termuxView)

    private fun onTermuxSizeChanged(widthPx: Int, heightPx: Int) {
        ptyResizeTracker.onSizeChanged(widthPx, heightPx)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        addView(
            termuxView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        termuxViewBridge.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            onTermuxSizeChanged(v.width, v.height)
        }

        // Drag-on-alternate-buffer crash guard. Termux's inner TerminalView
        // exposes three scroll paths inside doScroll(); branch 2 dereferences
        // the deliberately-null mTermSession. Swallow the gesture when the
        // emulator is in the alt buffer and mouse tracking is off.
        termuxView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> isAltBufferScrollCrashPath
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> false
                else -> false
            }
        }
    }

    private var composingHintListener: ((String?) -> Unit)? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            termuxViewBridge.requestLayout()
            termuxViewBridge.invalidate()
        } catch (t: Throwable) {
            writeCrashLog("onAttachedToWindow", t)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        termuxViewBridge.remeasureToFitParent(width, height)
    }

    private fun writeCrashLog(prefix: String, t: Throwable) {
        try {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val logFile = java.io.File(context.filesDir, "crash.log")
            logFile.appendText("[$prefix]\n${sw}\n")
        } catch (_: Throwable) {
        }
    }

    internal val isAltBufferScrollCrashPath: Boolean
        get() = emulator.isAlternateBufferActive && !emulator.isMouseTrackingActive

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        imeKeyRouter.dispatchKeyEvent(event, this)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        imeKeyRouter.onKeyDown(keyCode, event)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            requestFocus()
        }
        // Sprint 4 T7 — gesture list refactor. Each registered consumer
        // gets a chance to claim the event. First `Consumed` wins; the
        // rest are skipped. If every consumer passes through, the event
        // falls through to `super` (the inner view).
        //
        // The list order matters: scrollback should claim multi-touch
        // scrolls first (so single-finger taps don't accidentally arm a
        // scroll gesture); link long-press fires only after scrollback
        // decides this is a single-finger tap-and-hold.
        for (consumer in gestureConsumers) {
            when (consumer.onTouchEvent(ev)) {
                TouchDecision.Consumed -> return true
                TouchDecision.PassThrough -> Unit
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_SCROLL && isAltBufferScrollCrashPath) {
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    open override fun startActionModeForChild(
        child: View,
        callback: ActionMode.Callback,
        type: Int,
    ): ActionMode? {
        if (child === termuxView && type == ActionMode.TYPE_FLOATING) {
            return super.startActionModeForChild(
                child,
                SafeTextSelectionActionModeCallback(callback),
                type,
            )
        }
        return super.startActionModeForChild(child, callback, type)
    }

    private inner class SafeTextSelectionActionModeCallback(
        private val delegate: ActionMode.Callback,
    ) : ActionMode.Callback2() {

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            // 2026-08-01 redesign: link long-press → link single-tap,
            // so there's no longer a race between LinkGesture and Termux's
            // own selection GestureDetector on the same DOWN. The
            // floating Copy/Paste/More toolbar is allowed to render for
            // any selection (including one that contains a URL) — long
            // press → selection → Share / Search web from the overflow
            // is the alternate path to "I want to share this URL".
            val created = delegate.onCreateActionMode(mode, menu)
            if (created) {
                // Append Share / Search web so the stock Termux toolbar's
                // overflow ("More") is no longer empty on tablets.
                // See terminal/selection/.
                appendSelectionMenuExtensions(menu)
            }
            return created
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val prepared = delegate.onPrepareActionMode(mode, menu)
            if (prepared) {
                // Termux's `onPrepareActionMode` may rebuild the menu
                // (e.g. when the selection changes). Re-append our
                // extensions so they survive that rebuild.
                appendSelectionMenuExtensions(menu)
            }
            return prepared
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            // Our extensions (Share / Open URL / Search web) take
            // priority over Termux's delegate — none of them collide
            // with the 1/2/3 ids Termux uses for Copy/Paste/More.
            val config = SelectionMenuConfig(
                context = this@TerminalView.context,
                selectedText = termuxViewBridge.extractSelectedTextSafely().orEmpty(),
            )
            if (handleSelectionMenuItemClick(item.itemId, config)) {
                stopTextSelectionMode()
                return true
            }
            return when (item.itemId) {
                TermuxViewBridge.TERMUX_SELECTION_MENU_COPY -> {
                    val text = termuxViewBridge.extractSelectedTextSafely()
                    selectionController.copyToClipboard(text)
                    stopTextSelectionMode()
                    true
                }
                TermuxViewBridge.TERMUX_SELECTION_MENU_PASTE -> {
                    imeKeyRouter.pasteFromClipboard()
                    stopTextSelectionMode()
                    true
                }
                else -> {
                    val consumed = runCatching {
                        delegate.onActionItemClicked(mode, item)
                    }.getOrElse {
                        stopTextSelectionMode()
                        true
                    }
                    consumed
                }
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            delegate.onDestroyActionMode(mode)
        }

        override fun onGetContentRect(
            mode: ActionMode,
            view: View,
            rect: android.graphics.Rect,
        ) {
            if (delegate is ActionMode.Callback2) {
                delegate.onGetContentRect(mode, view, rect)
            }
        }

        /**
         * Inject Share / Open URL / Search web items into the toolbar
         * [menu] using whatever text the Termux selection controller
         * currently has highlighted. Empty / null selection → no items
         * (no point offering Share on whitespace).
         */
        private fun appendSelectionMenuExtensions(menu: Menu) {
            val text = termuxViewBridge.extractSelectedTextSafely().orEmpty()
            addSelectionMenuExtensions(
                menu,
                SelectionMenuConfig(
                    context = this@TerminalView.context,
                    selectedText = text,
                ),
            )
        }
    }

    fun bindEndpoint(endpoint: TerminalEndpoint) {
        this.endpoint = endpoint
        imeKeyRouter.bindEndpoint(endpoint, this, imm)
        // Re-arm so the next alt-buffer enter after a reconnect still refreshes.
        lastAltBufferActive = currentEmulator()?.isAlternateBufferActive == true
    }

    /**
     * Call after remote bytes have been applied to the emulator.
     *
     * Rising edge of [TerminalEmulator.isAlternateBufferActive] (TUI enter)
     * refreshes the IME so Gboard does not keep a pre-TUI InputConnection —
     * see [ImeKeyRouter.refreshInput].
     */
    fun onDisplayUpdated() {
        val alt = currentEmulator()?.isAlternateBufferActive == true
        if (alt && !lastAltBufferActive) {
            AppLog.i("IME", "alt-buffer enter → refreshInput")
            imeKeyRouter.refreshInput(this, imm)
        }
        lastAltBufferActive = alt
    }

    /** Last-seen alt-buffer flag for [onDisplayUpdated] edge detection. */
    private var lastAltBufferActive: Boolean = false

    fun setComposingHintListener(listener: (String?) -> Unit) {
        composingHintListener = listener
    }

    override fun showComposingHint(text: String) {
        composingHintListener?.invoke(text)
    }

    override fun hideComposingHint() {
        composingHintListener?.invoke(null)
    }

    fun scrollToBottom() {
        scrollbackController.scrollToBottom()
        termuxViewBridge.postInvalidateOnAnimation()
    }

    val isInScrollback: Boolean
        get() = scrollbackController.state.value.isInScrollback

    val scrollbackState: kotlinx.coroutines.flow.StateFlow<ScrollbackController.ScrollbackState>
        get() = scrollbackController.state

    private var scrollbackListener: ((ScrollbackController.ScrollbackState) -> Unit)? = null

    fun setScrollbackListener(listener: ((ScrollbackController.ScrollbackState) -> Unit)?) {
        scrollbackListener = listener
        if (listener != null) {
            listener(scrollbackController.state.value)
        }
    }

    fun setPtyResizeListener(listener: ((cols: Int, rows: Int, widthPx: Int, heightPx: Int) -> Unit)?) {
        ptyResizeTracker.setPtyResizeListener(listener)
    }

    fun setTextSize(size: Int) {
        termuxViewBridge.setTextSize(size)
        ptyResizeTracker.onSizeChanged(termuxView.width, termuxView.height)
    }

    fun activeInputConnection(): TerminalInputConnection? = imeKeyRouter.activeInputConnection()

    /**
     * True iff the IME currently has an active composing region.
     *
     * View-layer convenience over [TerminalInputConnection.isComposing]
     * — `LinkGesture` reads this to suppress long-press while the user is
     * mid-拼音 (otherwise a long-press on a URL would steal the touch from
     * the IME mid-composition). Returns `false` when no InputConnection
     * is bound (e.g. before first focus) — no IME ⇒ nothing to suppress.
     */
    fun isComposing(): Boolean = activeInputConnection()?.isComposing() == true

    /**
     * Returns the live [TerminalEmulator] backing this view, or `null` if
     * construction failed. Delegates to [termuxViewBridge] so callers don't
     * have to know which surface owns the emulator pointer.
     *
     * Reads `mEmulator` directly — HanTerm never attaches a Termux
     * [TerminalSession] (see [TerminalViewClientNullSessionTest]).
     */
    fun currentEmulator(): TerminalEmulator? = termuxViewBridge.currentEmulator()

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection =
        imeKeyRouter.onCreateInputConnection(outAttrs, this)

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean =
        imeKeyRouter.dispatchKeyEventPreIme(event)
}
