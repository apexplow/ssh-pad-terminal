package com.example.sshterminal.terminal

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import kotlin.math.max

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), TerminalComposingView {

    private var endpoint: TerminalEndpoint = TerminalEndpoint {}
    private var inputConnection: TerminalInputConnection? = null
    private var composingHintListener: ((String?) -> Unit)? = null
    private var ptyResizeListener: ((cols: Int, rows: Int, widthPx: Int, heightPx: Int) -> Unit)? = null

    /**
     * Tracks the last PTY dimensions we reported so we only fire the resize
     * listener when something actually changed. OnGlobalLayoutListener fires
     * for many unrelated reasons (keyboard insets, IME show/hide, scroll
     * bounds) and we don't want to spam the SSH channel with no-op SIGWINCHs.
     */
    private var lastResizeCols = 0
    private var lastResizeRows = 0

    /**
     * Minimal [TerminalViewClient] so Termux's inner view doesn't NPE on tap.
     * IME and hardware keys are handled by this wrapper; the inner view is
     * display-only aside from scroll/selection gestures.
     *
     * Declared before [termuxView] so [setTerminalViewClient] runs with a
     * fully-initialised client (Termux calls mClient from touch/scroll paths).
     */
    private val termuxViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float) = scale
        override fun onSingleTapUp(e: android.view.MotionEvent) {
            requestFocus()
        }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = false
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = false
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: android.view.MotionEvent) = false
        override fun readControlKey() = false
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    val termuxView: com.termux.view.TerminalView =
        com.termux.view.TerminalView(context, attrs).also { child ->
            child.isFocusable = false
            child.isFocusableInTouchMode = false
            // setTextSize initialises mRenderer (TerminalRenderer). Without
            // this call mRenderer stays null and onDraw crashes with an NPE.
            child.setTextSize(14)
            // We bypass attachSession(), which normally sets mClient.
            child.setTerminalViewClient(termuxViewClient)
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            child.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                reportPtyResize(v.width, v.height)
            }
        }

    /**
     * Adapter that lets us feed bytes FROM the SSH channel INTO the emulator's
     * transcript. Termux's TerminalSession was overkill for our use case
     * (it forks a local shell via JNI), so we wire a custom TerminalOutput
     * straight to the emulator instead.
     *
     * Flow: SSH bytes → session.readInto → onReceive(bytes) → emulator.write
     * (so they end up in the visible transcript and termuxView invalidates).
     *
     * Declared AFTER [termuxView] because its callbacks reference termuxView
     * — Kotlin val initialisation runs top-to-bottom and would NPE if we
     * referenced termuxView before it was constructed.
     */
    private val transcriptOutput = object : TerminalOutput() {
        override fun write(bytes: ByteArray, offset: Int, len: Int) {
            // The emulator already updated its internal transcript; we just
            // need the View to redraw.
            termuxView.postInvalidateOnAnimation()
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {
            termuxView.postInvalidateOnAnimation()
        }
    }

    /**
     * The TerminalEmulator instance — Termux's terminal state machine.
     * Declared AFTER [transcriptOutput] (which it captures) and AFTER
     * [termuxView] (which [transcriptOutput] references).
     */
    private val emulator: TerminalEmulator = try {
        TerminalEmulator(
            /* transcriptOutput = */ transcriptOutput,
            /* cols = */ 80,
            /* rows = */ 24,
            /* transcriptRows = */ null,  // use Termux's default
            /* client = */ object : TerminalSessionClient {
                // We don't actually need a real session client because we never
                // attach a TerminalSession, but the constructor requires one.
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
        // If TerminalEmulator construction fails for any reason (JNI binding
        // missing, etc.), fall back to a dummy object so the app at least
        // launches and we can show a friendly error to the user. We log the
        // full stack to app-private storage so we can read it back via
        // 'adb pull /data/data/.../files/crash.log'.
        writeCrashLog("TerminalEmulator construction failed", t)
        // Return a stand-in emulator — but TerminalEmulator is final with a
        // private constructor, so we can't actually create one. Throw the
        // exception upward and let the user's app process die; we've done
        // what we can to surface the diagnostic.
        throw t
    }.also { e ->
        // Bypass TerminalSession entirely (which would try to fork a local
        // shell via JNI and crash the app). Instead, hand the emulator to
        // the Termux view via the public mEmulator field.
        termuxView.mEmulator = e
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            // Now that the view has been added to the window, we can call
            // updateSize on the Termux view which forces it to recompute
            // cols/rows from its actual measured dimensions and assign
            // them to the emulator. Without this, the emulator's cols/rows
            // stay at our initial 80x24, and on tablets with a 200-col
            // wide display the prompt wraps awkwardly.
            termuxView.requestLayout()
            termuxView.invalidate()
        } catch (t: Throwable) {
            writeCrashLog("onAttachedToWindow", t)
        }
    }

    private fun writeCrashLog(prefix: String, t: Throwable) {
        try {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val logFile = java.io.File(context.filesDir, "crash.log")
            logFile.appendText("[$prefix]\n${sw}\n")
        } catch (_: Throwable) {
            // Last-ditch: swallow.
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            requestFocus()
        }
        return super.dispatchTouchEvent(ev)
    }

    fun bindEndpoint(endpoint: TerminalEndpoint) {
        this.endpoint = endpoint
        inputConnection = null
    }

    fun setComposingHintListener(listener: (String?) -> Unit) {
        composingHintListener = listener
    }

    /**
     * Registers a callback fired whenever the visible terminal grid dimensions
     * change (rotation, split-screen, multi-window, soft-keyboard show/hide).
     *
     * The callback receives `(cols, rows, widthPx, heightPx)`. Pixel dimensions
     * are 0 if the view hasn't been laid out yet — call sites should treat 0
     * as "unspecified" and forward only the cell dimensions to the remote.
     *
     * Added in Sprint 2 to drive SIGWINCH. Does NOT touch any IME logic
     * (onKeyDown / onCreateInputConnection / InputConnection callbacks are
     * untouched), so the Sprint 1.5 IME chain remains stable.
     */
    fun setPtyResizeListener(listener: ((cols: Int, rows: Int, widthPx: Int, heightPx: Int) -> Unit)?) {
        ptyResizeListener = listener
        // Fire once on registration so a freshly-bound session gets the
        // current size rather than waiting for the next layout pass.
        if (listener != null) reportPtyResize(termuxView.width, termuxView.height)
    }

    fun activeInputConnection(): TerminalInputConnection? = inputConnection

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_NORMAL or
            // Keep NO_SUGGESTIONS off so IMEs show candidate UI for Chinese / Japanese / Korean.
            // TYPE_TEXT_FLAG_NO_SUGGESTIONS was previously set because we treated this view
            // as a "no-op text field"; that suppressed IME composing entirely, which broke
            // Chinese input. Per implementation_plan.md §"输入链路设计", this view is a
            // full IME target and MUST allow composing.
            InputType.TYPE_TEXT_FLAG_MULTI_LINE   // optional but harmless on API 29+
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return TerminalInputConnection(this, endpoint).also { inputConnection = it }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val connection = inputConnection
        // While composing, the IME owns the input pipeline. Two exceptions:
        //  - DEL/ENTER still need to be consumed here so the IME doesn't see them
        //    twice (we return false to let the IME handle, but we still claim the
        //    physical event to avoid leaking it via an alternate path).
        //  - Ctrl+Space / Shift+Space / KEYCODE_LANGUAGE_SWITCH must be swallowed
        //    even mid-composition so they never reach the SSH channel.
        if (connection?.isComposing() == true) {
            val verdict = KeyMapper.resolve(keyCode, event)
            if (verdict is KeyResolution.Swallow) return true
            if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_ENTER) return false
            return false
        }

        if (event.isPrintingKey && !event.isCtrlPressed && !event.isAltPressed) return false

        when (val verdict = KeyMapper.resolve(keyCode, event)) {
            is KeyResolution.Send -> {
                endpoint.write(verdict.bytes)
                return true
            }
            KeyResolution.Swallow -> return true
            KeyResolution.Ignore -> return false
        }
    }

    override fun showComposingHint(text: String) {
        composingHintListener?.invoke(text)
    }

    override fun hideComposingHint() {
        composingHintListener?.invoke(null)
    }

    private fun reportPtyResize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val renderer = termuxView.mRenderer ?: return
        val emulator = termuxView.mEmulator ?: return

        // updateSize() is a no-op without mTermSession, so resize the emulator
        // ourselves from the measured view dimensions (same math as Termux).
        val fontWidth = renderer.getFontWidth()
        val fontLineSpacing = renderer.getFontLineSpacing()
        val newColumns = max(4, (widthPx / fontWidth).toInt())
        val newRows = max(4, heightPx / fontLineSpacing)
        if (newColumns != emulator.mColumns || newRows != emulator.mRows) {
            emulator.resize(newColumns, newRows)
            termuxView.postInvalidateOnAnimation()
        }

        val cols = emulator.mColumns
        val rows = emulator.mRows
        if (cols <= 0 || rows <= 0) return
        if (cols == lastResizeCols && rows == lastResizeRows) return
        lastResizeCols = cols
        lastResizeRows = rows
        ptyResizeListener?.invoke(cols, rows, widthPx, heightPx)
    }
}
