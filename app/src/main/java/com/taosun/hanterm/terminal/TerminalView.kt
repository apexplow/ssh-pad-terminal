package com.taosun.hanterm.terminal

import android.content.Context
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.taosun.hanterm.logging.AppLog
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
            }
            if (scrollbackController.state.value.isInScrollback) {
                scrollbackController.onTranscriptWrite(len, emulator.mColumns)
            }
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            ShellIntegrationState.parseTitle(newTitle)?.let { state ->
                shellIntegrationListener?.invoke(state)
            }
        }
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
    private var shellIntegrationListener: ((ShellIntegrationState) -> Unit)? = null

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
        when (scrollbackController.onTouchEvent(ev)) {
            ScrollbackController.TouchDecision.Consumed -> return true
            ScrollbackController.TouchDecision.PassThrough -> { /* fall through */ }
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

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean =
            delegate.onCreateActionMode(mode, menu)

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
            delegate.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
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
    }

    fun bindEndpoint(endpoint: TerminalEndpoint) {
        this.endpoint = endpoint
        imeKeyRouter.bindEndpoint(endpoint, this, imm)
    }

    fun setComposingHintListener(listener: (String?) -> Unit) {
        composingHintListener = listener
    }

    fun setShellIntegrationListener(listener: ((ShellIntegrationState) -> Unit)?) {
        shellIntegrationListener = listener
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
