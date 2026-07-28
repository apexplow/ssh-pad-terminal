package com.apexplow.hanterm.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.apexplow.hanterm.logging.AppLog

/**
 * Owns the terminal text-selection lifecycle on the pad SSH client.
 *
 * Responsibilities:
 *   1. Enter/exit selection mode (idempotent). On enter with a non-null event,
 *      hide the soft keyboard so the IME does not steal half the screen.
 *   2. Persist extracted text from the Termux ActionMode toolbar's Copy
 *      action to the system clipboard. Never throws; failures are logged
 *      via [AppLog] and the controller returns false so the caller can decide
 *      whether to dismiss the toolbar (we always do — clean teardown beats a
 *      stuck overlay).
 *
 * Wiring is owned by [TerminalView]; this class is pure logic + system
 * services so it is testable with mockk or Robolectric in isolation.
 */
class SelectionController(
    private val view: View,
    private val clipboard: ClipboardManager?,
    private val ime: InputMethodManager,
    private val toaster: (CharSequence) -> Unit = { msg ->
        Toast.makeText(view.context, msg, Toast.LENGTH_SHORT).show()
    },
) {

    /** True between enter() and exit(). */
    var isActive: Boolean = false
        private set

    /**
     * Enter selection mode. Idempotent. If [event] is non-null (the long-press
     * path) and the view is attached, hide the IME. The [TerminalViewClient.copyModeChanged]
     * callback may invoke enter() with a null event to keep the state in sync;
     * the hide is skipped there because the IME is already hidden by the
     * long-press path.
     */
    fun enter(event: MotionEvent?) {
        if (isActive) return
        isActive = true
        if (event != null && view.windowToken != null) {
            runCatching { ime.hideSoftInputFromWindow(view.windowToken, 0) }
                .onFailure {
                    AppLog.w("SelectionController", "hideSoftInputFromWindow failed", it)
                }
        }
    }

    /** Leave selection mode. Idempotent. Does not re-show the IME. */
    fun exit() {
        isActive = false
    }

    /**
     * Persist [text] to the system clipboard with label `ssh-term` and show a
     * Toast. Returns false (no-op) when the text is null or empty, or when
     * the system clipboard is unavailable. Never throws.
     */
    fun copyToClipboard(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val cb = clipboard ?: run {
            AppLog.w("SelectionController", "ClipboardManager unavailable; copy skipped")
            return false
        }
        return runCatching {
            cb.setPrimaryClip(ClipData.newPlainText("ssh-term", text))
            runCatching { toaster("已复制 ${text.length} 字符") }
                .onFailure { AppLog.w("SelectionController", "toast failed", it) }
            true
        }.onFailure {
            AppLog.w("SelectionController", "clipboard write failed", it)
        }.getOrDefault(false)
    }
}
