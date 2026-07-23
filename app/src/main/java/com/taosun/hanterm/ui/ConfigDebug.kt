package com.taosun.hanterm.ui

import android.content.Context
import android.util.Log
import com.taosun.hanterm.BuildConfig
import com.taosun.hanterm.logging.AppLog
import com.taosun.hanterm.logging.LogClassification
import java.io.File
import java.security.MessageDigest

/**
 * Debug-build helpers for the configuration form. Extracted from
 * [ConfigActions] so the [ConnectionDraftEditor] (Issue #18) can depend on
 * them without pulling in the actions composable.
 *
 * ## Why a separate file
 *
 * - [passwordFingerprint] / [appendDebugLog] are top-level `internal` functions
 *   pinned by `ConfigScreenDebugLogGateTest` (CS-DL-01..04, CS-PF-01..02,
 *   CS-PK-01..02). Keeping them as top-level functions preserves that test
 *   contract: the test pins the *functions*, not the editor.
 * - [DebugLogSink] is the [ConnectionDraftEditor] seam so the editor can run
 *   in pure JUnit without touching `android.util.Log`, `AppLog`, or
 *   `Context.filesDir`. Production wires [AndroidDebugLogSink]; tests wire
 *   [RecordingDebugLogSink].
 */

/**
 * Compute a debug-build fingerprint of [password] suitable for side-by-side
 * comparison with `sha256sum` on a real host. **Empty string in release** —
 * the legacy convention that the release build produces no sensitive
 * content. Sprint 2.5 / S3 (CS-PF-01 + CS-PF-02): gated by [isDebug].
 */
internal fun passwordFingerprint(
    password: String,
    isDebug: Boolean = BuildConfig.DEBUG,
): String {
    if (!isDebug) return ""
    if (password.isEmpty()) return "(empty, length=0)"
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(password.toByteArray(Charsets.UTF_8))
    val hex = bytes.joinToString("") { "%02x".format(it) }
    val first = password.first()
    val firstByteHex = "0x%02x".format(first.code)
    val firstRepr = if (first.isLetterOrDigit() || first in "!@#\$%^&*()-_=+[]{};:,.<>?/ ") {
        "'$first'"
    } else {
        "(non-printable $firstByteHex)"
    }
    return "len=${password.length} sha256[0..16]=${hex.take(16)} firstByte=$firstByteHex $firstRepr"
}

/**
 * Mirror the legacy debug-only logger: `Log.d` mirror + [AppLog] entry under
 * [LogClassification.ConnectionMetadata] (LogcatOnly in debug, Drop in
 * release, per Issue #13) + (debug-only) append to `filesDir/debug.log`.
 *
 * [privateKeyName], if non-blank, is logged as a separate entry classified
 * [LogClassification.CredentialMetadata] so the file sink can drop it in
 * release even when the base [message] reaches the sink under
 * [LogClassification.ConnectionMetadata]. Issue #13 implementation decision.
 */
internal fun appendDebugLog(
    context: Context,
    message: String,
    isDebug: Boolean = BuildConfig.DEBUG,
    privateKeyName: String = "",
) {
    Log.d("ConfigScreen", message)
    AppLog.i(
        "ConfigScreen",
        message,
        classification = LogClassification.ConnectionMetadata,
    )
    if (privateKeyName.isNotBlank()) {
        AppLog.i(
            "ConfigScreen",
            "save privateKey=$privateKeyName",
            classification = LogClassification.CredentialMetadata,
        )
    }
    if (!isDebug) return
    val debugFile = File(context.filesDir, "debug.log")
    runCatching {
        debugFile.appendText(message + "\n", Charsets.UTF_8)
    }
}

/**
 * Production seam for [ConnectionDraftEditor] so the editor never touches
 * `android.util.Log`, [AppLog], or [Context.filesDir] directly. Tests pass
 * [RecordingDebugLogSink]; production passes [AndroidDebugLogSink].
 */
internal interface DebugLogSink {
    /**
     * Mirrors the legacy [appendDebugLog] behavior: `Log.d` + [AppLog] under
     * [LogClassification.ConnectionMetadata] + (debug-only) `debug.log` file
     * write. [privateKeyName] is logged as a separate [LogClassification.CredentialMetadata]
     * entry.
     */
    fun append(message: String, privateKeyName: String = "")

    /**
     * Writes an [AppLog] entry under [LogClassification.CredentialMetadata].
     * LogcatOnly in debug, Drop in release (Issue #13). Use for credential-
     * derived content that must NEVER reach the file sink.
     */
    fun logCredential(message: String)

    /** Returns the SHA-256 first-16-hex debug fingerprint; empty string in release. */
    fun fingerprint(password: String): String
}

/** Production [DebugLogSink] that delegates to the file-backed helpers. */
internal class AndroidDebugLogSink(
    private val context: Context,
    private val isDebug: Boolean = BuildConfig.DEBUG,
) : DebugLogSink {
    override fun append(message: String, privateKeyName: String) {
        appendDebugLog(context, message, isDebug = isDebug, privateKeyName = privateKeyName)
    }

    override fun logCredential(message: String) {
        AppLog.i(
            "ConfigScreen",
            message,
            classification = LogClassification.CredentialMetadata,
        )
    }

    override fun fingerprint(password: String) = passwordFingerprint(password, isDebug = isDebug)
}