package com.apexplow.hanterm.terminal.link

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.apexplow.hanterm.logging.AppLog
import com.apexplow.hanterm.logging.LogClassification

/**
 * Public verdict of [LinkIntentLauncher.launch]. Top-level (not nested
 * inside `LinkIntentLauncher` — Kotlin sealed types force all
 * subclasses to live in the same package as the base, but the
 * `ui/LinkDialog` composable also needs to read this type, and we want
 * the `ui/` ↔ `terminal/link/` boundary to flow through one public type.
 */
sealed interface LaunchResult {
    /** `ACTION_VIEW` dispatched. Browser should now be foreground. */
    data object Ok : LaunchResult

    /**
     * URL failed re-validation — the cell that produced this URL is
     * no longer in the overlay snapshot. Dialog should treat this as
     * "URL is stale, do not open". No intent dispatched.
     */
    data object StaleUrl : LaunchResult

    /**
     * No `ACTION_VIEW` handler installed (`resolveActivity` returned
     * null, or `startActivity` threw `ActivityNotFoundException`).
     * Dialog should show a "no browser installed" hint.
     */
    data object NoBrowser : LaunchResult
}

/**
 * Pure helper — URL re-validate + `ACTION_VIEW` Intent dispatch.
 *
 * Two responsibilities:
 *  1. **T18 re-validation.** Run the URL through [LinkDetector.firstUrlIn]
 *     before dispatching. Closes the race where the user long-presses on
 *     a URL cell, the dialog renders, then between the dialog render
 *     and the user's tap on "Open", the emulator scrolls and the
 *     previously-detected URL cell is no longer present (or has been
 *     overwritten by fresh IO). Without re-validation, we'd dispatch
 *     an `ACTION_VIEW` for a URL the user no longer sees.
 *  2. **`ACTION_VIEW` dispatch.** Build the intent, check that something
 *     in the system can handle it (no `resolveActivity` → no browser
 *     installed → [LaunchResult.NoBrowser]), then start it.
 *
 * **Threading:** Main thread (called from `LinkDialog` button click).
 *
 * **Logging:** URL-related calls go through `LogClassification.ConnectionMetadata`
 * (reused from `SshClient.kt` — see T-MEDIUM-2 in `docs/TODOS.md` for
 * the future split into distinct `URL_DETECT` / `URL_OPEN` classifiers).
 * Release-mode `Drop` from the file sink.
 */
internal object LinkIntentLauncher {

    /**
     * Re-validate [url] against [LinkDetector.firstUrlIn] and dispatch
     * `ACTION_VIEW` if it still parses. Never throws — failures map to
     * [LaunchResult.NoBrowser] / [LaunchResult.StaleUrl].
     */
    fun launch(context: Context, url: String): LaunchResult {
        // T18 — re-validate the URL with the same regex that produced it.
        // The detection may have been several frames ago; the emulator
        // could have scrolled, or the IO thread could have overwritten
        // the row with fresh output. We do NOT trust the URL.
        val validated = LinkDetector.firstUrlIn(url)
        if (validated == null) {
            AppLog.d(
                "LinkIntentLauncher",
                "stale URL rejected: $url",
                classification = LogClassification.ConnectionMetadata,
            )
            return LaunchResult.StaleUrl
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(validated))
        // FLAG_ACTIVITY_NEW_TASK so we can launch from an Application/
        // service-context (the dialog's Context might be a
        // ComposeView-wrapped one; the flag keeps the system happy).
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // resolveActivity check BEFORE startActivity — Android throws
        // ActivityNotFoundException for a missing handler, but catching
        // it post-hoc is uglier than checking pre-hoc and lets us log
        // a clean diagnostic.
        val resolved = intent.resolveActivity(context.packageManager)
        if (resolved == null) {
            AppLog.w(
                "LinkIntentLauncher",
                "no ACTION_VIEW handler installed for $validated",
                classification = LogClassification.ConnectionMetadata,
            )
            return LaunchResult.NoBrowser
        }

        return try {
            context.startActivity(intent)
            AppLog.i(
                "LinkIntentLauncher",
                "dispatched: $validated",
                classification = LogClassification.ConnectionMetadata,
            )
            LaunchResult.Ok
        } catch (t: ActivityNotFoundException) {
            // Race: handler uninstalled between resolveActivity and
            // startActivity. Treat identically to NoBrowser.
            AppLog.w(
                "LinkIntentLauncher",
                "ActivityNotFoundException for $validated",
                classification = LogClassification.ConnectionMetadata,
            )
            LaunchResult.NoBrowser
        }
    }
}