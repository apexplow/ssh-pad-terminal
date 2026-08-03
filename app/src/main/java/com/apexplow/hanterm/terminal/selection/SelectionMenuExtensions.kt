package com.apexplow.hanterm.terminal.selection

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Menu
import android.view.MenuItem
import com.apexplow.hanterm.logging.AppLog

/**
 * Menu items appended to Termux's floating text-selection toolbar.
 *
 * Termux's [com.termux.view.TerminalView] already inflates its own items
 * (Copy / Paste / More) inside its `TextSelectionCursorController$1`
 * callback. Stock Termux only renders Copy and Paste as icons — the
 * rest collapse into a single "More" overflow that is empty by
 * default, so on tablets tapping "More" does nothing.
 *
 * This file promotes two useful items to direct toolbar buttons
 * ([MenuItem.SHOW_AS_ACTION_ALWAYS]) so they live next to Copy /
 * Paste instead of being buried in a "More" cascade the user has to
 * tap twice:
 *
 *  - **Share** — `ACTION_SEND` the selected text. Useful for piping
 *    paths / errors / selections into a chat, ticket, or note.
 *  - **Search web** — `ACTION_VIEW` a Google search for the selection.
 *    Always present; the user's first reflex on a stray string is
 *    usually "let me look this up".
 *
 * **Why we hook the wrapper, not Termux's internals.** The
 * `SafeTextSelectionActionModeCallback` in `TerminalView` already
 * wraps Termux's `Callback2` so we can intercept the menu lifecycle
 * without forking the JitPack library. We append our items to the
 * [Menu] Termux gave us, then dispatch clicks ourselves before the
 * `else -> delegate.onActionItemClicked(...)` branch falls through.
 *
 * **Threading.** Android's [Menu] / [Intent] APIs are Main-thread only,
 * matching `SafeTextSelectionActionModeCallback`'s contract.
 */
internal object SelectionMenuItemIds {
    // High ids to avoid colliding with Termux's (1/2/3) or future
    // Android-generated R.id values. Negative space is reserved for
    // Android system menus.
    const val SHARE: Int = 0x7A10_0001
    const val SEARCH_WEB: Int = 0x7A10_0003
}

/**
 * Inputs the menu-extension hooks need. Bundled into a data class so
 * the call site in `TerminalView.SafeTextSelectionActionModeCallback`
 * stays a single argument and tests can construct fixtures without
 * reflection.
 */
internal data class SelectionMenuConfig(
    val context: Context,
    val selectedText: String,
)

/**
 * Append the extension items to [menu]. No-op when [config.selectedText]
 * is blank — opening a share sheet or search on whitespace is a worse
 * failure than missing the items.
 *
 * Idempotent in the sense that re-calling it on the same [menu] after a
 * `clear()` is safe (the previous ids are gone). If called twice on an
 * unchanged menu it would duplicate items; callers should only invoke
 * this from `onCreateActionMode` / `onPrepareActionMode`, not both in
 * the same ActionMode lifecycle.
 */
internal fun addSelectionMenuExtensions(
    menu: Menu,
    config: SelectionMenuConfig,
) {
    val text = config.selectedText.trim()
    if (text.isEmpty()) return

    // Dedupe in case `onCreateActionMode` + `onPrepareActionMode` both
    // fire (Termux rebuilds the menu on prepare in some versions).
    // Removing by id is a no-op when the item is absent.
    menu.removeItem(SelectionMenuItemIds.SHARE)
    menu.removeItem(SelectionMenuItemIds.SEARCH_WEB)

    menu.add(
        Menu.NONE,
        SelectionMenuItemIds.SHARE,
        /* order = */ 100,
        "Share",
    ).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

    menu.add(
        Menu.NONE,
        SelectionMenuItemIds.SEARCH_WEB,
        /* order = */ 102,
        "Search web",
    ).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
}

/**
 * Dispatch a click on one of our extension items. Returns `true` when
 * the [itemId] is one of ours AND the intent fired successfully;
 * `false` otherwise so the caller can fall through to Termux's handler.
 *
 * `false` is also returned on dispatch failure (no app to handle the
 * intent) — the caller will then end up running Termux's
 * `delegate.onActionItemClicked` which is harmless because the id is
 * not Termux's either.
 */
internal fun handleSelectionMenuItemClick(
    itemId: Int,
    config: SelectionMenuConfig,
): Boolean = when (itemId) {
    SelectionMenuItemIds.SHARE -> dispatchShare(config)
    SelectionMenuItemIds.SEARCH_WEB -> dispatchSearchWeb(config)
    else -> false
}

// --- intent dispatch ----------------------------------------------------

private fun dispatchShare(config: SelectionMenuConfig): Boolean {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, config.selectedText)
    }
    val chooser = Intent.createChooser(intent, "Share text").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return startSafely(config.context, chooser, "Share")
}

private fun dispatchSearchWeb(config: SelectionMenuConfig): Boolean {
    val encoded = Uri.encode(config.selectedText)
    val uri = Uri.parse("https://www.google.com/search?q=$encoded")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return startSafely(config.context, intent, "SearchWeb")
}

/**
 * Wrap `startActivity` so an absent handler doesn't crash the toolbar.
 * Returns `true` when the intent was dispatched (or queued by the
 * chooser), `false` when no app handles it. Logs at [AppLog.d] so the
 * user can see in `filesDir/app.log` whether the failure is "no
 * browser installed" vs "selection text was empty when item was clicked".
 */
private fun startSafely(context: Context, intent: Intent, tag: String): Boolean = try {
    context.startActivity(intent)
    AppLog.d("SelectionMenu", "$tag dispatched: action=${intent.action}")
    true
} catch (t: Throwable) {
    AppLog.w("SelectionMenu", "$tag dispatch failed: ${t.javaClass.simpleName}", t)
    false
}