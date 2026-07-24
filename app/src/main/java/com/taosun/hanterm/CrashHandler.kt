package com.taosun.hanterm

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import net.schmizz.sshj.common.SSHException
import java.io.File
import java.net.SocketException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Centralised crash-capture so the user can read the stack trace on next launch
 * even if they can't reach adb. The whole app's process install handler is
 * routed through [install] which:
 *  1. Writes the full stack trace to a per-crash file under
 *     `filesDir/crashes/`, named `crash-<yyyyMMdd-HHmmss-SSS>.log`.
 *  2. Rotates the directory so only the [KEEP_LAST_CRASHES] most recent files
 *     survive (Issue #38 / `docs/APP_STORE_PLAN.md` §P2). Earlier crashes
 *     keep their files until evicted; this is **rotation**, not overwrite.
 *  3. Delegates to the system default handler so Android still shows its
 *     "App has stopped" dialog.
 *
 * On next launch, [readLastCrash] returns the most recent crash's text and
 * ConfigScreen surfaces it in the "Last crash" block — same UX as before,
 * just backed by a rotating directory now. The legacy single-file
 * `filesDir/crash.log` from pre-#38 builds is silently migrated on first
 * read so an in-place upgrade doesn't strand the previous crash trace.
 */
class CrashHandler private constructor(
    private val crashDir: File,
    private val keepLast: Int,
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        if (!isHandledTransportAbort(t, e)) {
            try {
                writeCrashFile(t, e)
                rotate()
            } catch (writeErr: Throwable) {
                Log.e(TAG, "could not write crash log", writeErr)
            }
        }
        delegate?.uncaughtException(t, e)
    }

    /**
     * Persist this crash under a timestamped filename and prune the directory
     * down to [keepLast] files. Split out from [uncaughtException] so tests
     * can drive it without installing a global handler.
     */
    @VisibleForTesting
    internal fun writeCrashFile(t: Thread, e: Throwable) {
        if (!crashDir.exists()) crashDir.mkdirs()
        val now = LocalDateTime.now()
        val filename = "crash-" + FILENAME_FORMATTER.format(now) + ".log"
        val file = File(crashDir, filename)
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        file.writeText(
            "[${HEADER_FORMATTER.format(now)}] ${t.name} (id=${t.threadId()})\n$sw\n",
        )
    }

    /**
     * Delete oldest crash files until at most [keepLast] remain. Newest is
     * defined by last-modified; ties are broken by filename descending
     * (FILENAME_FORMATTER contains milliseconds, so collisions are rare).
     * No-op when the directory has fewer files than [keepLast].
     */
    @VisibleForTesting
    internal fun rotate() {
        val all = listCrashFiles() ?: return
        if (all.size <= keepLast) return
        all.sortedWith(
            compareByDescending<File> { it.lastModified() }.thenByDescending { it.name },
        ).drop(keepLast).forEach { runCatching { it.delete() } }
    }

    private fun listCrashFiles(): List<File>? {
        val files = crashDir.listFiles { f ->
            f.isFile && f.name.startsWith(CRASH_FILE_PREFIX) && f.name.endsWith(CRASH_FILE_SUFFIX)
        } ?: return null
        return files.toList()
    }

    /**
     * sshj's internal `Reader` thread re-throws on socket abort (TCP RST,
     * broken pipe) and the throwable escapes into the JVM's default
     * uncaughtExceptionHandler. The Android default doesn't terminate the
     * process for non-main threads, so this is a *log-spam* bug rather than
     * a real crash — and the [com.taosun.hanterm.ssh.SshSession.readInto]
     * loop already surfaces the connection loss through a clean `Result.failure`
     * that the UI renders as "Connection closed: …". Suppress the crash-log
     * entry in that case so users don't see a confusing "Last crash" overlay
     * for an event the app already handled.
     */
    private fun isHandledTransportAbort(t: Thread, e: Throwable): Boolean {
        if (!t.name.startsWith("Reader")) return false
        // sshj chains: SSHException -> cause: SocketException, message
        // "Software caused connection abort". Match both layers so we don't
        // accidentally suppress real transport errors that aren't aborts.
        val rootCause = generateSequence<Throwable>(e) { it.cause }.lastOrNull()
        return rootCause is SocketException ||
            (e is SSHException && (e.message?.contains("abort", ignoreCase = true) == true))
    }

    companion object {
        private const val TAG = "CrashHandler"

        /** Name of the rotation directory under `filesDir`. */
        @VisibleForTesting
        const val CRASH_DIR = "crashes"

        /**
         * Maximum number of crash files retained on disk. Issue #38's
         * acceptance requires "at least 3" — we round to 3 exactly so the
         * directory size is predictable for callers / tests.
         */
        @VisibleForTesting
        const val KEEP_LAST_CRASHES = 3

        /** Filename prefix for crash logs (lets us find them again). */
        @VisibleForTesting
        const val CRASH_FILE_PREFIX = "crash-"

        /** Filename suffix for crash logs. */
        @VisibleForTesting
        const val CRASH_FILE_SUFFIX = ".log"

        /**
         * Filesystem-safe timestamp for filenames. No colons — some
         * attached-storage providers reject them.
         */
        @VisibleForTesting
        val FILENAME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.US)

        /** Human-readable timestamp used in the in-file header. */
        @VisibleForTesting
        val HEADER_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

        /** Legacy filename from pre-#38 builds; deleted once on first [install]. */
        private const val LEGACY_FILE = "crash.log"

        fun install(context: Context) {
            val crashDir = File(context.filesDir, CRASH_DIR)
            // One-shot migration: remove the legacy single-file crash.log
            // if it still exists from an older build. Its content has
            // already been seen by the user (otherwise the OS would have
            // invoked readLastCrash and cleared the legacy file); deleting
            // it now keeps the new rotation-only contract clean.
            runCatching { File(context.filesDir, LEGACY_FILE).delete() }
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(
                CrashHandler(crashDir, KEEP_LAST_CRASHES, previous),
            )
        }

        /**
         * Returns the contents of the most recent crash log, or null if no
         * crash has been recorded since the user last cleared app data or
         * since the last successful rotation. The text includes the
         * human-readable timestamp, thread name, and full Java stack trace.
         */
        fun readLastCrash(context: Context): String? {
            val crashDir = File(context.filesDir, CRASH_DIR)
            if (!crashDir.exists()) return null
            val files = crashDir.listFiles { f ->
                f.isFile && f.name.startsWith(CRASH_FILE_PREFIX) && f.name.endsWith(CRASH_FILE_SUFFIX)
            } ?: return null
            val latest = files.maxWithOrNull(
                compareBy<File> { it.lastModified() }.thenByDescending { it.name },
            ) ?: return null
            return if (latest.length() > 0) latest.readText() else null
        }

        /** Clears every rotated crash log; called by the "Dismiss" button. */
        fun clearLastCrash(context: Context) {
            val crashDir = File(context.filesDir, CRASH_DIR)
            if (!crashDir.exists()) return
            crashDir.listFiles { f ->
                f.isFile && f.name.startsWith(CRASH_FILE_PREFIX) && f.name.endsWith(CRASH_FILE_SUFFIX)
            }?.forEach { runCatching { it.delete() } }
        }

        /**
         * Test-only factory that builds a [CrashHandler] pointing at an
         * arbitrary directory. Production code calls [install] only.
         */
        @VisibleForTesting
        internal fun createForTest(
            crashDir: File,
            keepLast: Int,
            delegate: Thread.UncaughtExceptionHandler?,
        ): CrashHandler = CrashHandler(crashDir, keepLast, delegate)
    }
}
