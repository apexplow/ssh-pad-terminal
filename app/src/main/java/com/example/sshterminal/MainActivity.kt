package com.example.sshterminal

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.sshterminal.ui.SshTermApp
import net.schmizz.sshj.common.SSHException
import java.io.File
import java.net.SocketException

/**
 * Centralised crash-capture so the user can read the stack trace on next launch
 * even if they can't reach adb. The whole app's process install handler is
 * routed through [installCrashHandler] which:
 *  1. Writes the full stack trace to `filesDir/crash.log` (overwrites any
 *     previous content — only the most recent crash is kept).
 *  2. Delegates to the system default handler so Android still shows its
 *     "App has stopped" dialog.
 *
 * On next launch, MainActivity reads `filesDir/crash.log` and surfaces it in
 * the ConfigScreen "Last crash" block, so the user can see the trace
 * without needing adb.
 */
class CrashHandler private constructor(
    private val logFile: File,
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        if (!isHandledTransportAbort(t, e)) {
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                logFile.writeText(
                    "[${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] " +
                        "${t.name} (id=${t.id})\n$sw\n"
                )
            } catch (writeErr: Throwable) {
                Log.e(TAG, "could not write crash log", writeErr)
            }
        }
        delegate?.uncaughtException(t, e)
    }

    /**
     * sshj's internal `Reader` thread re-throws on socket abort (TCP RST,
     * broken pipe) and the throwable escapes into the JVM's default
     * uncaughtExceptionHandler. The Android default doesn't terminate the
     * process for non-main threads, so this is a *log-spam* bug rather than
     * a real crash — and the [com.example.sshterminal.ssh.SshSession.readInto]
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

        fun install(context: Context) {
            val logFile = File(context.filesDir, "crash.log")
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(logFile, previous))
        }

        /**
         * Returns the contents of the most recent crash log, or null if no
         * crash has been recorded since the user last cleared app data. The
         * text includes the timestamp, thread name, and full Java stack
         * trace.
         */
        fun readLastCrash(context: Context): String? {
            val logFile = File(context.filesDir, "crash.log")
            return if (logFile.exists() && logFile.length() > 0) logFile.readText() else null
        }

        /** Clears the crash log; called by the "Dismiss" button. */
        fun clearLastCrash(context: Context) {
            File(context.filesDir, "crash.log").delete()
        }
    }
}

class SshTermApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install before any activity code runs so we catch early-init crashes
        // (manifest inflation, theme resolution, Compose composable setup).
        CrashHandler.install(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SshTermApp() }
    }
}