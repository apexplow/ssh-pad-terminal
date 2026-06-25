package com.example.sshterminal

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.example.sshterminal.data.prefs.AppPreferences
import com.example.sshterminal.logging.AppLog
import com.example.sshterminal.ssh.SshKeepAliveService
import com.example.sshterminal.terminal.FontSizeController
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
        // Wire the process-scoped log sink so SshClient (and any other
        // module that doesn't hold a Context) can record diagnostics that
        // the UI can later read and copy. Idempotent; safe to call before
        // any Activity code runs.
        AppLog.init(this)
        // Create the SSH foreground-service notification channel. This MUST
        // happen before SshKeepAliveService.onStartCommand runs, since the
        // NotificationCompat.Builder references the channel id. Done here
        // (rather than in the service's onCreate) so the channel is a
        // manifest-level identity that exists regardless of whether the user
        // has ever connected — useful if we later expose channel toggles in
        // app settings. createNotificationChannel is idempotent; cheap IPC,
        // no-op on the second and subsequent cold starts.
        //
        // runCatching guards an OEM-quirk IPC failure (rare, but seen on
        // locked-down devices with broken NotificationManagerService).
        // Letting it throw would crash the process before any UI shows —
        // graceful degradation: the channel gets created on the next cold
        // start, the foreground service falls back to whatever behaviour
        // the system gives a missing channel (typically a no-op notification).
        runCatching {
            NotificationManagerCompat.from(this).createNotificationChannel(
                NotificationChannelCompat.Builder(
                    SshKeepAliveService.CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_LOW,
                )
                    .setName(getString(R.string.notification_channel_name))
                    .setDescription(getString(R.string.notification_channel_description))
                    .setShowBadge(false)
                    .build()
            )
        }.onFailure { AppLog.e("SshTermApplication", "createNotificationChannel failed", it) }
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Seed the font-size controller from persisted prefs BEFORE Compose
        // runs, so the first frame already shows the user's chosen size.
        // AppPreferences' fontSize getter clamps to [MIN, MAX] so a corrupted
        // store can never reach the renderer.
        FontSizeController.state.value = AppPreferences(this).fontSize
        setContent { SshTermApp() }
    }

    /**
     * Volume up / down steps the terminal font size. Returning `true`
     * consumes the event so the system does NOT also adjust media volume
     * and does NOT pop the media-volume slider — a held volume key fires
     * many ACTION_DOWN events with `repeatCount > 0`; we step on every one
     * so holding the key ramps the size quickly, which matches the user's
     * mental model of "the bigger I press, the more it changes".
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val current = FontSizeController.state.value
        val newSize: Int? = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP ->
                (current + AppPreferences.FONT_SIZE_STEP)
                    .coerceAtMost(AppPreferences.MAX_FONT_SIZE)
            KeyEvent.KEYCODE_VOLUME_DOWN ->
                (current - AppPreferences.FONT_SIZE_STEP)
                    .coerceAtLeast(AppPreferences.MIN_FONT_SIZE)
            else -> null
        }
        if (newSize != null) {
            FontSizeController.state.value = newSize
            // Persist so the choice survives process death. SshTermApp reads
            // the same SharedPreferences on next launch (via its own
            // AppPreferences instance) and MainActivity re-seeds the
            // controller from it in onCreate.
            AppPreferences(this).fontSize = newSize
            // The snackbar lives in Compose (mounted in SshTermApp's
            // Scaffold). Push the message through the controller's channel
            // and let the LaunchedEffect there render it.
            FontSizeController.showMessage("Font size: $newSize")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}