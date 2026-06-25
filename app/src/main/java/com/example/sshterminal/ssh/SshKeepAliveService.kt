package com.example.sshterminal.ssh

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.sshterminal.MainActivity
import com.example.sshterminal.R
import com.example.sshterminal.logging.AppLog

/**
 * Started (non-bound) foreground service that keeps the SSH session alive
 * when the user backgrounds the app.
 *
 * ## Why this exists
 *
 * sshj's 30-second SSH-level keepalive ([SshConfig.SSH_KEEPALIVE_INTERVAL_SECONDS])
 * keeps the TCP/SSH layer alive — but the OS can still kill the *process*
 * under memory pressure when the app is not perceptible. A foreground service
 * promotes the process into Android's "perceptible" priority bucket, which
 * is one of the last to be reaped. The trade-off is a persistent low-importance
 * notification while a session is live; the user has been warned about the
 * backgrounding behavior by the architecture they chose (don't disconnect).
 *
 * ## Lifecycle
 *
 *  - [start] (companion) is called from [SshClient.connect] on the success path.
 *  - [stop] (companion) is called from [SshClient.disconnect] in every teardown
 *    path — user-driven (back-press double-tap, snackbar action, Disconnect
 *    button) and remote-driven (`onSessionClosed` from the IO loop).
 *  - The service does NOT own the [SshClient]. We deliberately keep the
 *    dependency direction one-way (SshClient → Service) so the service can be
 *    reused for any future long-running task without modification.
 *
 * ## API 34 / dataSync caveats
 *
 *  - `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)` is
 *    the **3-arg overload**; the 2-arg overload throws
 *    `MissingForegroundServiceTypeException` on API 34+. The type passed at
 *    runtime MUST match the manifest's `android:foregroundServiceType="dataSync"`
 *    — a typo crashes the process.
 *  - [onStartCommand] calls [startForeground] **synchronously** on the first
 *    line. If the service is started via `Context.startForegroundService` and
 *    `startForeground` is not called within 5 seconds, Android throws
 *    `RemoteServiceException` and crashes the process. Do not defer the call
 *    to a coroutine or background thread.
 *  - [FOREGROUND_SERVICE_TYPE_DATA_SYNC] has a per-app ~6-hour daily
 *    wall-clock quota on API 34; after that Android may demote the service
 *    to a background process. Acceptable for Sprint 3 lab validation and
 *    typical interactive sessions. If the quota becomes a real-world problem,
 *    switch to [FOREGROUND_SERVICE_TYPE_SPECIAL_USE] (requires Play Store
 *    justification via `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" .../>`).
 *  - [START_NOT_STICKY]: if the system kills the service, the sshj transport
 *    is almost certainly dead too. Re-promoting silently would be a lie —
 *    the user has to reconnect from the UI. `START_NOT_STICKY` prevents
 *    the system from resurrecting the notification on its own.
 *
 * ## Permission
 *
 * `POST_NOTIFICATIONS` (API 33+) is a runtime permission. The service still
 * runs without it; the user just doesn't see the persistent notification.
 * SshTermApp requests it on the first [com.example.sshterminal.ui.ConnectionState.Connected]
 * transition.
 */
class SshKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Defensive default: if an external caller starts the service without
        // extras, fall back to a placeholder rather than rendering an empty
        // notification. `SshKeepAliveService.start` already guards against
        // blank summaries; this is belt-and-suspenders for adb shell am
        // startservice debugging.
        val summary = intent?.getStringExtra(EXTRA_SUMMARY)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.notification_title)
        try {
            startForeground(
                NOTIF_ID,
                buildNotification(summary),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (t: Throwable) {
            // Promotion failed (e.g. permission revoked, OOM during NotificationManager
            // build, a future platform quirk). Log and stop the service so we don't
            // sit as a zombie process. The caller (SshClient) already wrapped the
            // start in runCatching so the SSH session is unaffected.
            AppLog.e(TAG, "startForeground failed; stopping self", t)
            stopSelf()
            return START_NOT_STICKY
        }
        AppLog.i(TAG, "foreground service started: summary=\"$summary\"")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // STOP_FOREGROUND_REMOVE drops the notification immediately instead of
        // waiting for the system to sweep it. runCatching because the service
        // may already be tearing down in a state where stopForeground throws.
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }.onFailure { AppLog.w(TAG, "stopForeground failed", it) }
        AppLog.i(TAG, "foreground service destroyed")
        super.onDestroy()
    }

    private fun buildNotification(summary: String): Notification {
        // Tap → open MainActivity at the top of the back stack. SINGLE_TOP
        // avoids creating a second copy of the activity if it's already
        // running; CLEAR_TOP collapses any stale intermediate activity. The
        // PendingIntent must be immutable on API 31+ (FLAG_IMMUTABLE); the
        // request code stays 0 because we only ever have one such intent.
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ssh_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(summary)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
    }

    companion object {

        private const val TAG = "SshKeepAliveService"

        /**
         * Channel id used for both [androidx.core.app.NotificationChannelCompat]
         * creation (in [com.example.sshterminal.SshTermApplication.onCreate]) and
         * the [NotificationCompat.Builder] in this service. Must be a string
         * literal at the NotificationManager level (no R.string indirection),
         * but co-locating the constant here keeps it next to the only other
         * place it's used and lets the Application reference it without
         * hard-coding.
         */
        const val CHANNEL_ID: String = "ssh_session"

        /**
         * Notification id. Single id for the lifetime of the service; we
         * don't care about updating an existing notification in place
         * because [SshClient] stops the service on every disconnect and
         * starts a fresh one on reconnect, which assigns a new Notification
         * object.
         */
        private const val NOTIF_ID: Int = 1

        private const val EXTRA_SUMMARY = "summary"

        /**
         * Start the foreground service. Safe to call from any thread; the
         * underlying IPC is asynchronous. [summary] is rendered as the
         * notification's content text (e.g. "tao@host:22"). Blank summaries
         * are dropped with a warning so we never publish an empty
         * notification.
         */
        fun start(context: Context, summary: String) {
            if (summary.isBlank()) {
                AppLog.w(TAG, "start skipped: summary is blank")
                return
            }
            val intent = Intent(context, SshKeepAliveService::class.java)
                .putExtra(EXTRA_SUMMARY, summary)
            // ContextCompat picks the right startForegroundService / startService
            // overload across API versions. Defensive: minSdk is 29 so the
            // startForegroundService path is always taken, but the compat
            // wrapper is the conventional choice.
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Stop the foreground service. Idempotent: `stopService` returns
         * `false` if the service isn't running, which is fine. The
         * notification is removed by [onDestroy]'s `stopForeground(REMOVE)`.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, SshKeepAliveService::class.java))
        }
    }
}
