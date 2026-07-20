package com.taosun.hanterm.terminal

import android.view.View

interface TerminalComposingView {
    val asView: View
        get() = this as View

    fun showComposingHint(text: String)
    fun hideComposingHint()
}
