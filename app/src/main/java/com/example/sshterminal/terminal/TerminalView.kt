package com.example.sshterminal.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View.MeasureSpec
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch
import kotlin.math.max

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), TerminalComposingView {

    private companion object {
        // Matches the value the constructor hard-codes when it pre-initialises
        // mRenderer (see below). Tracked separately so setTextSize's
        // idempotency guard starts out comparing against the right baseline.
        const val DEFAULT_TEXT_SIZE = 14
    }

    private var endpoint: TerminalEndpoint = TerminalEndpoint {}
    private var inputConnection: TerminalInputConnection? = null
    private var composingHintListener: ((String?) -> Unit)? = null
    private var ptyResizeListener: ((cols: Int, rows: Int, widthPx: Int, heightPx: Int) -> Unit)? = null

    /**
     * Owns the text-selection lifecycle. Wired from
     * [termuxViewClient.onLongPress] (enter), [termuxViewClient.copyModeChanged]
     * (enter/exit), and [transcriptOutput.onCopyTextToClipboard] (clipboard
     * write + selection teardown). See SelectionController kdoc.
     */
    /**
     * Owns the two-finger page-by-page scrollback gesture. Wired from
     * this view's dispatchTouchEvent (intercept multi-touch before it
     * reaches the inner Termux view) and from the transcriptOutput.write
     * override (count pending lines while scrolled back). Lazy because
     * the constructor params (termuxView, emulator) are not available
     * until later in the init order. See
     * docs/superpowers/specs/2026-06-30-gesture-scrollback-design.md.
     */
    private val scrollbackController: ScrollbackController by lazy {
        ScrollbackController(
            view = this,
            innerView = termuxView,
            emulator = emulator,
            fontLineSpacing = { termuxView.mRenderer?.getFontLineSpacing()?.toFloat() ?: 0f },
        )
    }

    private val selectionController: SelectionController = SelectionController(
        view = this,
        clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager,
        ime = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager,
    )

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
        override fun copyModeChanged(copyMode: Boolean) {
            if (copyMode) {
                selectionController.enter(event = null)
            } else {
                selectionController.exit()
                // Termux selection mode ends — restore wrapper focus for IME.
                restoreInnerViewNonFocusable()
            }
        }
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: android.view.MotionEvent): Boolean {
            selectionController.enter(event)
            // Termux startTextSelectionMode() bails out when requestFocus()
            // fails. The inner view is deliberately non-focusable so IME
            // InputConnection stays on this wrapper — temporarily re-enable
            // focus only for the selection session.
            enableInnerViewForSelection()
            termuxView.startTextSelectionMode(event)
            if (!termuxView.isSelectingText) {
                restoreInnerViewNonFocusable()
            }
            return true
        }
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

    /**
     * Termux [startTextSelectionMode] requires the inner view to win
     * [View.requestFocus]. IME [onCreateInputConnection] lives on this
     * wrapper, so the inner view stays non-focusable by default.
     */
    private fun enableInnerViewForSelection() {
        termuxView.isFocusable = true
        termuxView.isFocusableInTouchMode = true
    }

    private fun restoreInnerViewNonFocusable() {
        termuxView.isFocusable = false
        termuxView.isFocusableInTouchMode = false
        requestFocus()
    }

    val termuxView: com.termux.view.TerminalView =
        com.termux.view.TerminalView(context, attrs).also { child ->
            child.isFocusable = false
            child.isFocusableInTouchMode = false
            // setTextSize initialises mRenderer (TerminalRenderer). Without
            // this call mRenderer stays null and onDraw crashes with an NPE.
            child.setTextSize(DEFAULT_TEXT_SIZE)
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
            // While the user is scrolled back, count lines that arrived
            // during the read so the banner can show "▼ N 行新输出".
            // onTranscriptWrite is thread-safe (it uses MutableStateFlow.update)
            // so we can call it from the IO thread directly.
            if (scrollbackController.state.value.isInScrollback) {
                scrollbackController.onTranscriptWrite(len, emulator.mColumns)
            }
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {
            // Always dismiss selection mode on the Copy action. The framework
            // only surfaces Copy on a non-empty selection, so empty/null is
            // theoretical — but if it does fire, dismissing is cleaner than
            // letting a stale toolbar linger. Clipboard failures are surfaced
            // via AppLog.warn inside SelectionController.
            selectionController.copyToClipboard(text)
            termuxView.stopTextSelectionMode()
        }
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

    /**
     * Re-measure the inner Termux view with the wrapper's actual size
     * when the two have diverged. `com.termux.view.TerminalView`'s
     * `onMeasure` reads the emulator's grid dimensions (initialised to
     * 80×24 in the constructor above) to compute its own desired size,
     * ignoring the `MATCH_PARENT` layout params set on line 91. On the
     * first measure pass it therefore reports a small intrinsic size
     * (~640×336 at the default 14pt font), and [super.onLayout] places
     * it in the wrapper's top-left corner. The [addOnLayoutChangeListener]
     * installed in the constructor then fires with that small size,
     * [reportPtyResize] shrinks the emulator to match, and the terminal
     * is locked into a ~1/4-screen block on a tablet — until a
     * configuration change (e.g. rotation) triggers a fresh layout pass
     * that happens to read the right size.
     *
     * We can't fix the inner view's `onMeasure` (CLAUDE.md forbids
     * touching `com.termux:terminal-view`) and the synchronous
     * [requestLayout] in [onAttachedToWindow] runs *before* the wrapper
     * itself is measured, so it never lands in the right place. The
     * reliable workaround is to detect the mismatch immediately after
     * [super.onLayout] and force a re-measure with the wrapper's actual
     * size. The next [addOnLayoutChangeListener] fire then carries the
     * real pixel dimensions, [reportPtyResize] computes the correct
     * cols/rows, and the emulator resizes to fill the wrapper. The
     * re-measure is idempotent — when the inner view is already the
     * right size, the inner `if` is false and the pass is a no-op — so
     * subsequent layouts (rotation, font-size change, keyboard show/hide)
     * stay on the normal [addOnLayoutChangeListener] path.
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (termuxView.width != width || termuxView.height != height) {
            termuxView.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
            termuxView.layout(0, 0, width, height)
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

        // Drag-on-alternate-buffer crash guard. Termux's inner TerminalView
        // exposes three scroll paths inside doScroll():
        //   1. mouse tracking active  → sendMouseEvent() → safe (no mTermSession)
        //   2. alternate buffer active → handleKeyCode(KEYCODE_DPAD_UP/DOWN)
        //   3. normal scrollback       → mutate mTopRow   → safe
        //
        // Branch 2 dereferences `mTermSession.getEmulator()` to look up
        // cursor-key translation, but `mTermSession` is *deliberately null*
        // in this project — see the constructor comment above where we wire
        // the emulator into the inner view via `mEmulator` reflection and
        // skip TerminalSession entirely (TerminalSession would fork a local
        // shell via JNI). The first time the user scrolled inside a remote
        // TUI (vim, less, htop, mc, tmux TUI mode, fzf, …) the inner view
        // NPE'd on session.getEmulator() and crashed the entire process
        // (see stack trace from 2026-06-25 13:11:20, id=2).
        //
        // We can't fix the inner view (CLAUDE.md forbids modifying
        // com.termux:terminal-view internals) and we can't construct a
        // TerminalSession because its constructor would invoke a local
        // shell — neither is the right answer. The legitimate use of
        // scroll-in-alt-buffer-mode is "send scroll keys to the remote
        // TUI" (e.g., `:set mouse=a` makes vim handle its own scroll via
        // mouse events routed through branch 1, which IS safe), so when
        // branch 2 would fire the TUI isn't asking for keystrokes anyway.
        // Silently swallowing the gesture is the correct behaviour.
        //
        // We attach the listener to the *inner* view (not this wrapper) so
        // single-finger taps still reach the inner view's onSingleTapUp →
        // focus request, and we only return true on ACTION_MOVE — leaving
        // ACTION_DOWN false lets the inner view's GestureDetector
        // initialise normally so we don't disturb the rest of its state
        // machine (its onUp callback, which resets mScrollRemainder and
        // fires the mouse-up code, still runs on ACTION_UP because we
        // don't consume that).
        termuxView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> isAltBufferScrollCrashPath
                // Consume UP/CANCEL only if we're mid-gesture so the inner
                // view's GestureDetector doesn't see a half-pair (DOWN but
                // no MOVE) that could leave its state machine stuck.
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> false
                else -> false
            }
        }
    }

    /**
     * True iff a drag gesture would route through Termux's
     * `doScroll → handleKeyCode` alternate-buffer branch and crash on
     * the null `mTermSession`. Live read (not cached) because the remote
     * can enter / exit the alt-buffer at any time via DECSET 1049.
     *
     * `internal` so the regression tests in `AltBufferScrollCrashGuardTest`
     * can pin the predicate; it must not leak beyond the module.
     */
    internal val isAltBufferScrollCrashPath: Boolean
        get() = emulator.isAlternateBufferActive && !emulator.isMouseTrackingActive

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            requestFocus()
        }
        // Single- and two-finger gestures are owned by the scrollback controller.
        // We consult it before super so the inner Termux view never sees
        // multi-touch events (avoids its doScroll alt-buffer crash branch,
        // and avoids contaminating its single-finger gesture detector state).
        // Single-finger vertical swipes that exceed touchSlop also route here
        // so sliding does not accidentally trigger long-press text selection.
        when (scrollbackController.onTouchEvent(ev)) {
            ScrollbackController.TouchDecision.Consumed -> return true
            ScrollbackController.TouchDecision.PassThrough -> { /* fall through */ }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        // Mouse-wheel / trackpad scroll hits the same doScroll →
        // handleKeyCode crash path as touch gestures — see the kdoc in
        // init {} above for the full root-cause analysis. Compose's
        // pointerInteropFilter only covers touch events; generic motion
        // events from a Bluetooth mouse land here instead.
        if (ev.actionMasked == MotionEvent.ACTION_SCROLL
            && isAltBufferScrollCrashPath
        ) {
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    fun bindEndpoint(endpoint: TerminalEndpoint) {
        this.endpoint = endpoint
        inputConnection = null
    }

    fun setComposingHintListener(listener: (String?) -> Unit) {
        composingHintListener = listener
    }

    /**
     * Jump back to the live view (one screenful at a time, via the
     * inner view's doScroll) and clear the pending-output count. Wired
     * to the Compose banner's "回到底部" tap.
     */
    fun scrollToBottom() {
        scrollbackController.scrollToBottom()
        termuxView.postInvalidateOnAnimation()
    }

    /** Read-only view of the controller's scrollback state. */
    val isInScrollback: Boolean
        get() = scrollbackController.state.value.isInScrollback

    /**
     * The scrollback state as a StateFlow, for the Compose banner to
     * collectAsState. Exposed here so the caller owns the coroutine
     * lifetime (LaunchedEffect cancellation tears it down on dispose).
     */
    val scrollbackState: kotlinx.coroutines.flow.StateFlow<ScrollbackController.ScrollbackState>
        get() = scrollbackController.state

    /**
     * Subscribe a listener to the scrollback state. Fires once with the
     * current state on registration (so the caller doesn't have to handle
     * the initial null). The listener is called from a coroutine on
     * Dispatchers.Main — Compose's collectAsState in the caller side
     * subscribes and forwards.
     *
     * The listener is stored as a single nullable field, mirroring the
     * setPtyResizeListener pattern. A new call replaces the previous
     * listener; null detaches. The coroutine that drives the listener
     * lives in the caller's LaunchedEffect, not here, so it is torn
     * down automatically when the caller leaves composition.
     */
    private var scrollbackListener: ((ScrollbackController.ScrollbackState) -> Unit)? = null

    fun setScrollbackListener(listener: ((ScrollbackController.ScrollbackState) -> Unit)?) {
        scrollbackListener = listener
        if (listener != null) {
            listener(scrollbackController.state.value)
        }
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
        // `force = true` because a layout pass before this registration
        // may have already populated `lastResizeCols/Rows` (with listener
        // still null and the SIGWINCH dropped); without `force`, the
        // debounce check in [reportPtyResize] would drop this fire too,
        // leaving the SSH PTY at SshConfig's 80×24 default. The only
        // symptom at runtime is the tmux status bar landing in the middle
        // of the screen until something else (e.g. IME show) triggers a
        // fresh layout pass with a different size. See GEARS_SPEC.md
        // TV-PTY-02 and the `force` parameter's kdoc for the full
        // root-cause analysis.
        if (listener != null) reportPtyResize(termuxView.width, termuxView.height, force = true)
    }

    /**
     * Tracks the last font size passed to [setTextSize] so repeated calls
     * with the same value are a no-op. The volume-button handler fires this
     * on every key event (including autorepeat) and the Compose `update`
     * block re-invokes it on every recomposition; without this guard we'd
     * rebuild the Termux renderer and re-run the resize listener — and
     * queue another SIGWINCH on the SSH write executor — for no visible
     * change. The flood of redundant SIGWINCHs is what surfaced as the
     * "connection disconnected" overlay on a remote that closed the
     * channel after too many window-change requests in a row.
     */
    private var currentTextSize: Int = DEFAULT_TEXT_SIZE

    /**
     * Change the rendered font size. After the Termux view swaps in a new
     * TerminalRenderer with the requested metrics, we re-run
     * [reportPtyResize] so the emulator grid reflows and the pty resize
     * listener (driving the active SSH session's SIGWINCH) is invoked with
     * the new (cols, rows).
     *
     * Idempotent: calling with the same size as the current renderer is a
     * no-op, so neither the Termux renderer nor the SSH resize listener is
     * touched. This is the fix for the volume-button "connection
     * disconnected" overlay: a held volume key fires onKeyDown many times
     * per second and previously each call rebuilt the renderer and queued
     * a SIGWINCH; some servers (dropbear, busybox) close the channel when
     * the window-change rate is too high, and the resulting socket abort
     * surfaced through SshSession.readInto → onSessionClosed.
     *
     * We have to do the resize ourselves: Termux's own `updateSize()` is a
     * no-op when `mTermSession == null` (verified from the cached
     * `terminal-view:v0.118.0` AAR), and this project deliberately keeps
     * `mTermSession` null — see the constructor at lines 164-166 where we
     * wire the emulator directly and skip TerminalSession. Compose's
     * `OnLayoutChangeListener` does NOT fire when only font metrics change
     * (no view size change), so the existing resize listener attached in
     * the constructor would not pick this up on its own.
     */
    fun setTextSize(size: Int) {
        if (size == currentTextSize) return
        currentTextSize = size
        termuxView.setTextSize(size)
        reportPtyResize(termuxView.width, termuxView.height)
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

    /**
     * Pre-IME hook. Runs in ViewRootImpl's PreImeStage BEFORE the IME is given
     * the event — see `ViewRootImpl.ViewPreImeInputStage` and the dispatch
     * chain documented in the class kdoc above.
     *
     * Why this exists: Gboard / Google Pinyin consume Ctrl+Shift+V for their
     * own input-mode switch before it ever reaches `onKeyDown`. Without this
     * hook, hardware-keyboard paste silently no-ops on any connected IME.
     *
     * We only intercept the [KeyResolution.Paste] verdict here. Every other
     * event falls through to the IME (which may consume language-switch
     * shortcuts as designed) and then to [onKeyDown] for the KeyMapper
     * verdict. The onKeyDown `Paste` branch is kept as a fallback for the
     * no-IME case but is dead code in the normal IME-connected flow because
     * PreImeStage finishes the event before onKeyDown runs.
     */
    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (KeyMapper.resolve(event.keyCode, event) is KeyResolution.Paste) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                pasteFromClipboard()
            }
            // Consume both DOWN and UP so the IME never sees either half of
            // the chord — otherwise the UP leaks into the IME pipeline and
            // some IMEs use it to flip a sticky state.
            return true
        }
        return super.dispatchKeyEventPreIme(event)
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
            // Ctrl+Shift+V (paste) still works mid-composition — finishing the
            // composition is the user's responsibility, but the paste should
            // not be silently swallowed by the IME gate. Tested in
            // test_ctrlShiftV_whileComposing_stillPastesFromClipboard.
            if (verdict is KeyResolution.Paste) {
                pasteFromClipboard()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_ENTER) return false
            return false
        }

        if (event.isPrintingKey && !event.isCtrlPressed && !event.isAltPressed) return false

        when (val verdict = KeyMapper.resolve(keyCode, event)) {
            is KeyResolution.Send -> {
                endpoint.write(verdict.bytes)
                return true
            }
            KeyResolution.Paste -> {
                pasteFromClipboard()
                return true
            }
            KeyResolution.Swallow -> return true
            KeyResolution.Ignore -> return false
        }
    }

    /**
     * Reads the system clipboard's primary clip and writes its text contents
     * to the bound [TerminalEndpoint] as UTF-8 bytes.
     *
     * Wired up to the `KeyResolution.Paste` verdict (Ctrl+Shift+V on a
     * hardware keyboard). The intent mirrors what every desktop terminal does
     * on the same chord: the user is on a hardware keyboard, they press the
     * familiar paste shortcut, and the bytes that were last copied on the
     * device land in the remote shell. No IME involvement, no fragment
     * routing — the terminal view is the editor of record.
     *
     * Empty / non-text clips are a no-op (but the event is still consumed by
     * the caller so we don't double-fire). The clipboard lookup itself can
     * never throw because we only call [ClipboardManager.getPrimaryClip]
     * after [ClipboardManager.hasPrimaryClip] reports true.
     */
    private fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        if (!clipboard.hasPrimaryClip()) return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context) ?: return
        if (text.isEmpty()) return
        endpoint.write(text.toString().toByteArray(Charsets.UTF_8))
    }

    override fun showComposingHint(text: String) {
        composingHintListener?.invoke(text)
    }

    override fun hideComposingHint() {
        composingHintListener?.invoke(null)
    }

    private fun reportPtyResize(widthPx: Int, heightPx: Int, force: Boolean = false) {
        if (widthPx <= 0 || heightPx <= 0) return
        val renderer = termuxView.mRenderer ?: return
        val emulator = termuxView.mEmulator ?: return

        // updateSize() is a no-op without mTermSession, so resize the emulator
        // ourselves from the measured view dimensions (same math as Termux).
        val fontWidth = renderer.getFontWidth()
        val fontLineSpacing = renderer.getFontLineSpacing()
        // Defensive guard: in production the renderer always has valid metrics
        // (setTextSize initialises them in the constructor), but if a future
        // code path somehow produces a renderer with zero metrics — or a
        // test rig (Robolectric) can't load a real font and the metrics
        // shadow to 0 — falling through to the divide would throw and
        // propagate out of the OnLayoutChangeListener into the layout pass.
        // Skipping the resize is safe: the next layout pass with valid
        // metrics will pick up the work, and the wrapper-level re-measure
        // added in [onLayout] keeps the inner view pinned to the wrapper
        // size in the meantime.
        if (fontWidth <= 0 || fontLineSpacing <= 0) return
        val newColumns = max(4, (widthPx / fontWidth).toInt())
        val newRows = max(4, heightPx / fontLineSpacing)
        if (newColumns != emulator.mColumns || newRows != emulator.mRows) {
            emulator.resize(newColumns, newRows)
            termuxView.postInvalidateOnAnimation()
        }

        val cols = emulator.mColumns
        val rows = emulator.mRows
        if (cols <= 0 || rows <= 0) return
        // `force = true` is only used by [setPtyResizeListener]'s initial fire.
        // Background: the constructor installs an OnLayoutChangeListener that
        // calls reportPtyResize on the inner view's first layout. At that point
        // `ptyResizeListener` is still null (the LaunchedEffect in TerminalPane
        // registers it later), so the SIGWINCH is dropped on the floor — but
        // `lastResizeCols/Rows` are still written. When the LaunchedEffect
        // eventually calls setPtyResizeListener, the fire-once side effect
        // hits this debounce check and gets dropped too. Result: the SSH PTY
        // stays at SshConfig.DEFAULT_PTY_COLS/ROWS (80×24) forever, tmux
        // renders at 80×24 inside a much larger visible grid, and the status
        // bar lands in the middle of the screen. `force = true` makes the
        // registration fire protocol-mandatory: it always reaches the
        // freshly-bound session, even if a previous layout pass already
        // populated `lastResizeCols/Rows`. All other call sites keep
        // `force = false` (default), so SIGWINCH-spam protection is unchanged
        // for OnLayoutChangeListener / setTextSize / font-size changes.
        // See GEARS_SPEC.md TV-PTY-02 for the spec rationale.
        if (!force && cols == lastResizeCols && rows == lastResizeRows) return
        lastResizeCols = cols
        lastResizeRows = rows
        ptyResizeListener?.invoke(cols, rows, widthPx, heightPx)
    }
}
