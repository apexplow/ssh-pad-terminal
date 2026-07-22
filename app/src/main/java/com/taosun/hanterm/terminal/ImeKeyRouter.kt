package com.taosun.hanterm.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * Owns the IME → terminal key routing pipeline.
 *
 * This class holds the current [TerminalEndpoint], the cached
 * [TerminalInputConnection], and the [InputDispatcher] that owns the
 * routing state machine. After issue #14 it is a thin plumbing layer:
 * the composing-state gate, the digit-flush logic, and the Ctrl/Alt
 * Send-during-composing finish rule all live in [InputDispatcher];
 * this class only translates between Android [KeyEvent] / IME
 * callbacks and [InputDispatcher.dispatch] / [DispatchResult]
 * application. See `docs/ARCHITECTURE.md` §7 for the full invariants.
 *
 * The [InputDispatcher] instance is shared with [TerminalInputConnection]
 * so the composing flag and digit-flush tracker stay coherent across the
 * View.onKeyDown path and the BaseInputConnection callback path.
 */
internal class ImeKeyRouter(
    private val context: Context,
) {

    private var endpoint: TerminalEndpoint = TerminalEndpoint {}
    private var inputConnection: TerminalInputConnection? = null

    /**
     * Sole owner of the routing state machine. One instance per router
     * — shared with the cached [TerminalInputConnection] so the
     * composing flag and digit-flush tracker are coherent across both
     * surfaces (View.onKeyDown and InputConnection callbacks). See
     * issue #14 and `docs/ARCHITECTURE.md` §7.
     */
    private val dispatcher: InputDispatcher = InputDispatcher()

    /**
     * Binds a new endpoint and drops the IME's cached state for [view].
     *
     * This method tells the [InputMethodManager] to restart input on [view],
     * which forces the IME to discard its old [TerminalInputConnection]. It
     * then updates the internal endpoint and clears the cached connection so
     * the next IME event creates a fresh [TerminalInputConnection] pointing at
     * the new endpoint. Call this whenever the terminal session changes (e.g.
     * after a reconnect or when swapping transports).
     */
    fun bindEndpoint(
        endpoint: TerminalEndpoint,
        view: android.view.View,
        imm: InputMethodManager?,
    ) {
        refreshInput(view, imm)
        this.endpoint = endpoint
    }

    /**
     * Drop the IME's cached [TerminalInputConnection] and ask
     * [InputMethodManager] to rebuild one.
     *
     * Used on reconnect ([bindEndpoint]) and when a remote TUI enters the
     * alternate screen — Gboard otherwise keeps a stale connection, so the
     * first switch into Chinese pinyin after `cursor-agent` (etc.) buffers
     * commits until the user mashes the language toggle enough times to
     * force an IME restart (then every pending 汉字 lands in one burst).
     */
    fun refreshInput(
        view: android.view.View,
        imm: InputMethodManager?,
    ) {
        // The cached IC's finishComposingText() handles hint hide + IME
        // state cleanup + dispatching ImeFinishComposing to the
        // dispatcher (idempotent). dispatcher.reset() is a
        // belt-and-suspenders wipe in case the IC was already nulled
        // externally without finishComposingText being called first.
        inputConnection?.takeIf { dispatcher.isComposing() }?.finishComposingText()
        dispatcher.reset()
        inputConnection = null
        imm?.restartInput(view)
    }

    fun onCreateInputConnection(
        outAttrs: EditorInfo,
        view: TerminalComposingView,
    ): InputConnection {
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_NORMAL or
            // Keep NO_SUGGESTIONS off so IMEs show candidate UI for CJK.
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return TerminalInputConnection(view, endpoint, dispatcher).also { inputConnection = it }
    }

    /**
     * Dispatch the key event through the View's standard key-dispatch path so
     * that [onKeyDown] runs on this router.
     */
    fun dispatchKeyEvent(event: KeyEvent, host: android.view.View): Boolean =
        event.dispatch(host, host.keyDispatcherState, host)

    /**
     * Pre-IME hook. Intercepts Ctrl+Shift+V before the IME can consume it.
     *
     * MUST stay [KeyMapper]-direct — pre-IME time has no composing
     * context (the IME hasn't seen the event yet) so consulting the
     * dispatcher would be unreliable. The current [KeyMapper]-based
     * `is Paste` check is the right seam: it identifies the chord
     * before the framework routes it anywhere.
     */
    fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (KeyMapper.resolve(event) is KeyResolution.Paste) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                pasteFromClipboard()
            }
            // Consume both DOWN and UP so the IME never sees either half.
            return true
        }
        return false
    }

    fun activeInputConnection(): TerminalInputConnection? = inputConnection

    /**
     * Single physical-key dispatch. Translates the [KeyEvent] into
     * [InputEvent.Key], asks the [InputDispatcher] for the verdict,
     * and applies it. The composing-state gate, digit-flush logic,
     * and "swallow beats composing" rules all live in the dispatcher —
     * this method is platform plumbing only.
     */
    fun onKeyDown(@Suppress("UNUSED_PARAMETER") keyCode: Int, event: KeyEvent): Boolean =
        when (val result = dispatcher.dispatch(InputEvent.Key(event))) {
            is DispatchResult.Send -> {
                endpoint.write(result.bytes)
                true
            }
            is DispatchResult.FinishComposingThenSend -> {
                // The dispatcher has already cleared its own composing
                // state. Calling finishComposingText on the cached IC
                // hides the composing hint + signals the IME that the
                // pinyin session is over (which the dispatcher cannot
                // do — that's a BaseInputConnection concern). The
                // dispatcher dispatch inside finishComposingText is
                // idempotent (dispatcher.composing was already false).
                inputConnection?.finishComposingText()
                endpoint.write(result.bytes)
                true
            }
            DispatchResult.Paste -> {
                pasteFromClipboard()
                true
            }
            DispatchResult.Swallow -> true
            DispatchResult.Ignore -> false
        }

    /**
     * Reads the system clipboard's primary clip and writes its text contents to
     * the bound [TerminalEndpoint] as UTF-8 bytes.
     */
    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        if (!clipboard.hasPrimaryClip()) return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context) ?: return
        if (text.isEmpty()) return
        endpoint.write(text.toString().toByteArray(Charsets.UTF_8))
    }
}