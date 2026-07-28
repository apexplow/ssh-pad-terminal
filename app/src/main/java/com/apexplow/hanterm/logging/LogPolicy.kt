package com.apexplow.hanterm.logging

/**
 * Centralized policy that decides, per log entry, whether it may be written to
 * the persistent file sink (`filesDir/app.log`), only to Logcat, or dropped.
 *
 * ## Why this exists
 *
 * Before [#13], every `AppLog.{d,i,w,e}` call wrote to the persistent file
 * sink. In release builds that leaked:
 *  - IME composing text and committed Chinese characters
 *  - Physical-key key codes and `unicodeChar`
 *  - Host / port / username / `username@host:port` connect metadata
 *  - Password-derived fingerprints and private-key names
 *
 * Any user with `adb` access could pull the file and reconstruct typed input
 * or enumerate the user's servers. Existing `BuildConfig.DEBUG` gates were
 * scattered and incomplete (e.g. `ConfigActions.kt:67` ran `AppLog.i` *before*
 * the debug check, so connection metadata still reached the file).
 *
 * ## How it is used
 *
 * Every log entry carries a [LogClassification] chosen at the call site.
 * [AppLog] builds a [LogEntry], passes it to [classify], and routes the result:
 *  - [LogDestination.Drop] — neither file nor Logcat
 *  - [LogDestination.LogcatOnly] — Logcat only; never the file sink
 *  - [LogDestination.File] — both file sink and Logcat (preserves today's
 *    "Copy logs" UX for non-sensitive diagnostics)
 *
 * The default production policy is [BuildConfigAwareLogPolicy]. Callers must
 * supply a classification explicitly for any entry that could reveal user
 * data; the `d/i/w/e` defaults ([LogClassification.Diagnostic] / [Error]) are
 * for non-sensitive diagnostics only.
 *
 * ## Test seam
 *
 * This file is pure Kotlin — no Android, no `BuildConfig` reference — so
 * [BuildConfigAwareLogPolicy] is unit-testable as plain JUnit by passing
 * `isDebug = true / false` directly. The `:app` `BuildConfig.DEBUG` is read
 * once, in `AppLog.init`'s default-arg expression.
 *
 * See GitHub issue #13.
 */

/** What kind of data a log entry carries. Visible at the call site. */
enum class LogClassification {
    /** User-typed text: composing pinyin, committed 汉字, key codes, `unicodeChar`. */
    Input,

    /** Credential-derived content: password fingerprints, private-key names. */
    CredentialMetadata,

    /** Server / connection identifiers: host, port, username, `user@host:port`. */
    ConnectionMetadata,

    /** Operational diagnostics — state-machine transitions, keepalive mechanics, transport errors. */
    Diagnostic,

    /** Security-relevant events: known-hosts TOFU prompts, host-key rejections. */
    Security,

    /** Errors that need to survive into the file sink for bug reports. */
    Error,
}

/** Where the entry is allowed to land after [LogPolicy.classify]. */
enum class LogDestination {
    /** Drop: do not write to file, do not mirror to Logcat. */
    Drop,

    /** Logcat only: skip the file sink, mirror to `android.util.Log`. */
    LogcatOnly,

    /** File: write to `filesDir/app.log` AND mirror to Logcat. */
    File,
}

/**
 * Mirror of `android.util.Log`'s level. Callers pick
 * [com.apexplow.hanterm.logging.AppLog]'s `d/i/w/e` method rather than passing
 * a [LogLevel] directly; the enum exists so [LogEntry] can carry the level
 * the [LogPolicy] saw, not because production code needs to construct one.
 */
enum class LogLevel { D, I, W, E }

/**
 * A single log entry as the [LogPolicy] sees it. `throwable` is captured so
 * [Error] entries still carry the stack trace that the file sink renders.
 */
data class LogEntry(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val classification: LogClassification,
    val throwable: Throwable? = null,
)

/**
 * The auditable seam. Implementations decide per entry. There is exactly one
 * production implementation, [BuildConfigAwareLogPolicy]; tests substitute a
 * recording policy to assert that sensitive entries never reach the file
 * under release conditions.
 */
interface LogPolicy {
    fun classify(entry: LogEntry): LogDestination
}

/**
 * The production policy. Sensitive classifications (`Input`,
 * `CredentialMetadata`, `ConnectionMetadata`) are dropped in release and
 * mirrored only to Logcat in debug; everything else is filed in both build
 * types so error/warning logs survive for bug reports.
 *
 * The `isDebug` boolean is taken as a constructor parameter (not read from
 * `BuildConfig` here) so this class is pure-Kotlin testable. `AppLog.init`'s
 * default-arg expression is the single place that consults `BuildConfig.DEBUG`.
 */
class BuildConfigAwareLogPolicy(
    private val isDebug: Boolean,
) : LogPolicy {

    override fun classify(entry: LogEntry): LogDestination = when (entry.classification) {
        // Sensitive in release; preserved for `adb logcat` debugging in dev.
        LogClassification.Input,
        LogClassification.CredentialMetadata,
        LogClassification.ConnectionMetadata ->
            if (isDebug) LogDestination.LogcatOnly else LogDestination.Drop

        // Diagnostics + security + errors always reach the file so
        // "Copy logs" stays useful for bug reports (Issue #13 User Story 7
        // + User Story 10).
        LogClassification.Diagnostic,
        LogClassification.Security,
        LogClassification.Error ->
            LogDestination.File
    }
}