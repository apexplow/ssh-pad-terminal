package com.taosun.hanterm.logging

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.taosun.hanterm.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Process-scoped log sink that writes to a single file in the app's private
 * files dir (`filesDir/app.log`) AND mirrors every entry to [android.util.Log]
 * so `adb logcat` stays useful.
 *
 * ## Why a custom sink
 *
 * `adb logcat` is the canonical way to read app diagnostics on Android, but
 * the typical ssh-pad-terminal user doesn't have adb access (the device is a
 * phone or a remote-control box, not a developer's workstation). For a
 * "connection timed out" report, we need the log to be readable *inside the
 * app* — copied to the clipboard and pasted into a chat, or rendered as
 * monospace text in the error overlay. A plain file in filesDir achieves
 * both with zero permissions.
 *
 * ## Why not Timber / SLF4J
 *
 * Adding a dep + an init call + a tag strategy for ~100 LoC of "write a
 * timestamped line, append stacktrace, read tail" is overkill. The 0.1.0
 * feature set only logs from three places: the SSH connect path, the
 * config-screen save path, and this logger itself.
 *
 * ## Threading
 *
 * Every public method is guarded by [lock] so concurrent writes from the
 * coroutine on `Dispatchers.IO` and the UI thread don't interleave bytes.
 * The file is opened per call (no persistent OutputStream) because the app
 * stays alive across hundreds of connect attempts and we don't want a
 * background file descriptor sitting open between them.
 *
 * ## Rotation
 *
 * When the file exceeds [MAX_BYTES] the leading bytes are dropped on the
 * next write. The truncation is best-effort: we read, slice, and rewrite the
 * whole file, which is fine for ~256 KB but would be slow for megabytes.
 * If you ever need more history, bump the cap and accept the I/O cost.
 *
 * ## Sensitive-data policy ([LogPolicy])
 *
 * Every `d/i/w/e` call now routes through a [LogPolicy] ([BuildConfigAwareLogPolicy]
 * by default) that decides per entry whether it lands in the file sink, only
 * in Logcat, or is dropped. Callers MUST pass an explicit
 * [LogClassification] for any entry that could reveal user data
 * (`Input` / `CredentialMetadata` / `ConnectionMetadata`); the defaults
 * ([LogClassification.Diagnostic] for `d`/`i`, [LogClassification.Error] for
 * `w`/`e`) are for non-sensitive diagnostics only. See GitHub issue #13.
 *
 * ## Pre-`init` behaviour
 *
 * If a call happens before [init], the entry is dropped silently — logging
 * must never crash the app. [policy] is initialised at object-construction
 * to a release-mode `BuildConfigAwareLogPolicy` (drops everything) so a
 * pre-`init` log can't accidentally reach the file sink either.
 */
object AppLog {

    /** Log filename in [Context.getFilesDir]. Stays out of caches/ so `clear app data` wipes it. */
    const val FILE_NAME: String = "app.log"

    /** Max file size before we drop the leading bytes on the next write. */
    const val MAX_BYTES: Int = 256 * 1024

    private const val DEFAULT_TAIL_BYTES: Int = 16 * 1024

    private val lock = Any()

    /**
     * Thread-safe timestamp formatter. Replaced `SimpleDateFormat` (Issue #37)
     * because the latter is explicitly documented as not thread-safe and the
     * old instance was shared across every log call site — see the class-level
     * kdoc section on Threading for the bug class. `DateTimeFormatter` is part
     * of `java.time` (desugared / native since API 26; we are API 36+) and is
     * safe to share across threads by contract.
     */
    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    /**
     * Policy consulted before every write. Default is a release-mode
     * `BuildConfigAwareLogPolicy(false)` so a pre-[init] log drops
     * everything — the safe failure mode. [init] replaces this with the
     * build-type-aware default; tests substitute via the second overload.
     */
    @Volatile
    private var policy: LogPolicy = BuildConfigAwareLogPolicy(isDebug = false)

    /**
     * Wire the sink to the app's filesDir. MUST be called from
     * `Application.onCreate` before any other module logs. If a call
     * happens before [init], the entry is dropped silently — logging must
     * never crash the app.
     */
    fun init(context: Context) = init(context, BuildConfigAwareLogPolicy(BuildConfig.DEBUG))

    /**
     * Wire the sink to the app's filesDir with an explicit [policy]. The
     * policy parameter exists primarily for tests; production callers should
     * use the single-arg [init] overload so the build-type default is used.
     * Idempotent: calling twice with the same arguments keeps the existing
     * file handle and policy.
     */
    fun init(context: Context, policy: LogPolicy) {
        synchronized(lock) {
            // Use applicationContext so we don't pin an Activity for the
            // lifetime of the process.
            val baseDir = context.applicationContext.filesDir
            logFile = File(baseDir, FILE_NAME)
            this.policy = policy
        }
    }

    /**
     * Debug-level entry for gesture / routing diagnostics. Defaults to
     * [LogClassification.Diagnostic]; callers logging sensitive data MUST
     * pass an explicit `classification = LogClassification.Input`.
     */
    fun d(
        tag: String,
        message: String,
        classification: LogClassification = LogClassification.Diagnostic,
    ) = writeLine(LogLevel.D, tag, message, null, classification)

    /**
     * Convenience for a free-form message. Mirrors [android.util.Log.i] but
     * also writes to the file sink (subject to the [policy]). Defaults to
     * [LogClassification.Diagnostic]; callers logging sensitive data MUST
     * pass an explicit `classification = LogClassification.ConnectionMetadata`.
     */
    fun i(
        tag: String,
        message: String,
        classification: LogClassification = LogClassification.Diagnostic,
    ) = writeLine(LogLevel.I, tag, message, null, classification)

    /**
     * Warning-level entry. Used for "defensive guard tripped", "degraded
     * path taken", "tolerated non-fatal failure". [throwable] is rendered
     * as `<Type>: <msg>` plus a full stacktrace when present, mirroring
     * [e] but at WARN level. Defaults to [LogClassification.Error].
     */
    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        classification: LogClassification = LogClassification.Error,
    ) = writeLine(LogLevel.W, tag, message, throwable, classification)

    /** Error-level entry. [throwable] is rendered as `<Type>: <msg>` plus a full stacktrace.
     *  Defaults to [LogClassification.Error]. */
    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        classification: LogClassification = LogClassification.Error,
    ) = writeLine(LogLevel.E, tag, message, throwable, classification)

    /**
     * Read the last [maxBytes] of the log file as a single String. Older
     * lines are dropped; the return is prefixed with `"…\n"` to make the
     * truncation obvious to whoever reads it. Returns an empty string if
     * [init] has not run or the file is empty.
     */
    fun readTail(maxBytes: Int = DEFAULT_TAIL_BYTES): String = synchronized(lock) {
        val file = logFile ?: return ""
        if (!file.exists() || file.length() == 0L) return ""
        val text = file.readText(Charsets.UTF_8)
        if (text.length <= maxBytes) text else "…\n" + text.takeLast(maxBytes)
    }

    /**
     * Wipe the log file. Called from the "Clear logs" button (or before
     * tests). Idempotent — deleting a missing file is fine.
     */
    fun clear() = synchronized(lock) {
        logFile?.takeIf { it.exists() }?.delete()
    }

    /**
     * Reset [policy] to the safe release-default `BuildConfigAwareLogPolicy(false)`.
     * Test-only seam — production code never calls this. Lets test suites
     * start each case from a known policy regardless of what a previous test
     * installed via the two-arg [init] overload.
     */
    @VisibleForTesting
    fun resetPolicyForTests() = synchronized(lock) {
        policy = BuildConfigAwareLogPolicy(isDebug = false)
    }

    // -- internals ------------------------------------------------------------

    private fun writeLine(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
        classification: LogClassification,
    ) {
        // Classify FIRST so a Drop entry short-circuits before the
        // SimpleDateFormat allocation and any file I/O. Per Issue #13 this
        // is the audit seam — every entry flows through it.
        val entry = LogEntry(level, tag, message, classification, throwable)
        val destination = policy.classify(entry)
        if (destination == LogDestination.Drop) return

        // Format off the lock so we never hold the monitor while doing I/O
        // for the timestamp formatter (the DateTimeFormatter is the heaviest
        // part of this call). DateTimeFormatter is thread-safe so concurrent
        // .format() callers (IO dispatcher + UI thread) don't corrupt the
        // shared instance — replaces the SimpleDateFormat path tracked by
        // Issue #37.
        val timestamp = timeFormat.format(LocalTime.now())
        val line = formatLine(timestamp, level, tag, message, throwable)

        if (destination == LogDestination.File) {
            synchronized(lock) {
                val file = logFile ?: return
                try {
                    file.appendText(line, Charsets.UTF_8)
                    // Rotate on size. Best-effort: if the rotation itself throws
                    // (disk full, permissions) we drop the entry rather than
                    // surfacing the error to the caller.
                    if (file.length() > MAX_BYTES) {
                        val keep = file.readText(Charsets.UTF_8).takeLast(MAX_BYTES)
                        file.writeText(keep, Charsets.UTF_8)
                    }
                } catch (_: Throwable) {
                    // Never let logging kill the caller.
                }
            }
        }

        // Mirror to Logcat AFTER the file write so a logcat failure (rare)
        // doesn't block the file sink. Both File and LogcatOnly destinations
        // mirror to Logcat — that's the whole point of LogcatOnly.
        when (level) {
            LogLevel.D -> android.util.Log.d(tag, message)
            LogLevel.I -> android.util.Log.i(tag, message)
            LogLevel.W -> {
                if (throwable != null) android.util.Log.w(tag, message, throwable)
                else android.util.Log.w(tag, message)
            }
            LogLevel.E -> {
                if (throwable != null) android.util.Log.e(tag, message, throwable)
                else android.util.Log.e(tag, message)
            }
        }
    }

    private fun formatLine(
        timestamp: String,
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ): String = buildString {
        append('[').append(timestamp).append("] ")
        append(level.name).append('/').append(tag).append(": ")
        append(message)
        if (throwable != null) {
            append(" | ").append(throwable.javaClass.name)
            throwable.message?.takeIf { it.isNotEmpty() }?.let { append(": ").append(it) }
            append('\n')
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            // Indent every stacktrace line by two spaces so the multi-line
            // appendText below doesn't trip the line-oriented readers (and
            // human eyes can scan it).
            sw.toString().lineSequence().forEach { line ->
                append("  ").append(line).append('\n')
            }
        } else {
            append('\n')
        }
    }
}