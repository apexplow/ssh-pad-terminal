package com.example.sshterminal.ssh

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.sshterminal.MainActivity
import com.example.sshterminal.R
import com.example.sshterminal.logging.AppLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Started (non-bound) foreground service that keeps the SSH session alive
 * when the user backgrounds the app.
 *
 * ## Why this exists
 *
 * sshj's Heartbeater keeps the TCP/SSH layer warm — but the OS can still
 * kill the *process* under memory pressure when the app is not perceptible.
 * A foreground service promotes the process into Android's "perceptible"
 * priority bucket. The trade-off is a persistent low-importance notification
 * while a session is live.
 *
 * ## SSH keepalive nudge loop (BG-KA-05)
 *
 * Device logs (2026-07-11) showed `Handler.postDelayed` deferring the 4th
 * nudge from the expected T+15 s to T+25 s despite a held
 * [PowerManager.PARTIAL_WAKE_LOCK] — OEM / Doze deferred the Looper. The
 * ~15 s TX gap let Tailscale / sshd ClientAlive RST the socket.
 *
 * The loop is therefore a plain [Thread] that `Thread.sleep`s under the
 * wake lock (not `Handler.postDelayed`). Sleep with a held partial wake
 * lock is wall-clock accurate *when the process is not OEM-frozen*.
 *
 * ## Battery optimization (BG-KA-06)
 *
 * Device log 2026-07-11 19:30: after nudge #4 the sleep-loop itself was
 * frozen for ~40 s despite FGS + PARTIAL_WAKE_LOCK + specialUse — classic
 * OEM "battery saver" process freeze. [SshTermApp] prompts
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` on first Connected. Without that
 * exemption, no in-process keepalive strategy can keep Tailscale SSH up.
 *
 * ## FGS type: specialUse
 *
 * `dataSync` is quota-limited and more aggressively deferred on API 34+
 * OEM builds. Persistent interactive SSH is a textbook `specialUse` case
 * (declared via [PROPERTY_SPECIAL_USE_FGS_SUBTYPE] in the manifest).
 *
 * ## Lifecycle
 *
 *  - [start] from [SshClient.connect] on success.
 *  - [stop] from [SshClient.disconnect] on every teardown path.
 *  - The service does NOT own the [SshClient].
 */
class SshKeepAliveService : Service() {

    private var nudgeThread: Thread? = null
    private val nudgeStop = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private val nudgeCount = AtomicInteger(0)
    @Volatile private var lastNudgeElapsedRealtimeMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val summary = intent?.getStringExtra(EXTRA_SUMMARY)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.notification_title)
        try {
            startForeground(
                NOTIF_ID,
                buildNotification(summary),
                foregroundServiceType(),
            )
        } catch (t: Throwable) {
            AppLog.e(TAG, "startForeground failed; stopping self", t)
            stopSelf()
            return START_NOT_STICKY
        }
        AppLog.i(TAG, "foreground service started: summary=\"$summary\"")
        logPowerState("onStartCommand")
        acquireSessionWakeLock()
        startSshKeepaliveNudgeLoop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSshKeepaliveNudgeLoop()
        releaseSessionWakeLock()
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }.onFailure { AppLog.w(TAG, "stopForeground failed", it) }
        AppLog.i(TAG, "foreground service destroyed")
        super.onDestroy()
    }

    private fun startSshKeepaliveNudgeLoop() {
        stopSshKeepaliveNudgeLoop()
        nudgeStop.set(false)
        nudgeCount.set(0)
        lastNudgeElapsedRealtimeMs = 0L
        val intervalMs =
            SshConfig.FGS_SSH_KEEPALIVE_NUDGE_SECONDS.toLong() * 1000L
        val thread = Thread({
            // Non-daemon: a daemon keepalive thread is fair game for OEM
            // freezers once the UI thread goes idle in the background
            // (BG-KA-06). Keep it a normal thread owned by the FGS process.
            AppLog.i(TAG, "SSH keepalive sleep-loop started interval=${intervalMs}ms")
            while (!nudgeStop.get()) {
                val tickStart = SystemClock.elapsedRealtime()
                sendOneNudge(tickStart)
                val elapsed = SystemClock.elapsedRealtime() - tickStart
                val sleepMs = (intervalMs - elapsed).coerceAtLeast(0L)
                if (nudgeStop.get()) break
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    break
                }
                val wokeAt = SystemClock.elapsedRealtime()
                val sleptFor = wokeAt - tickStart - elapsed
                // Wall sleep should be ≈ intervalMs. Anything ≥ 2× means the
                // OEM froze us despite FGS + WakeLock — log so the next
                // device report can prove battery-opt is still on.
                if (sleptFor >= intervalMs * 2) {
                    AppLog.w(
                        TAG,
                        "SSH keepalive sleep deferred: slept ${sleptFor}ms " +
                            "(expected ~${intervalMs}ms) — OEM likely freezing " +
                            "the process; check battery optimization exemption",
                    )
                    logPowerState("afterDeferredSleep")
                }
            }
            AppLog.i(TAG, "SSH keepalive sleep-loop stopped after ${nudgeCount.get()} nudges")
        }, "SshKeepAlive-nudge")
        thread.isDaemon = false
        thread.priority = Thread.NORM_PRIORITY
        nudgeThread = thread
        thread.start()
    }

    private fun sendOneNudge(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val n = nudgeCount.incrementAndGet()
        val prev = lastNudgeElapsedRealtimeMs
        lastNudgeElapsedRealtimeMs = nowElapsedMs
        if (prev > 0L) {
            val gap = nowElapsedMs - prev
            val expected =
                SshConfig.FGS_SSH_KEEPALIVE_NUDGE_SECONDS.toLong() * 1000L
            if (gap >= expected * 2) {
                AppLog.w(
                    TAG,
                    "SSH keepalive nudge #$n gap=${gap}ms (expected ~${expected}ms)",
                )
            }
        }
        when {
            !SshClient.hasKeepAliveNudge() ->
                AppLog.w(TAG, "SSH keepalive nudge #$n skipped (callback not registered)")
            SshClient.nudgeTransportKeepAlive() ->
                AppLog.i(TAG, "SSH keepalive nudge #$n ok")
            else ->
                AppLog.w(TAG, "SSH keepalive nudge #$n send failed (see SshClient log)")
        }
    }

    private fun stopSshKeepaliveNudgeLoop() {
        nudgeStop.set(true)
        nudgeThread?.interrupt()
        nudgeThread = null
    }

    private fun acquireSessionWakeLock() {
        releaseSessionWakeLock()
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SshTerm::SessionWakeLock",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            AppLog.i(TAG, "PARTIAL_WAKE_LOCK acquired")
        }.onFailure {
            AppLog.w(TAG, "PARTIAL_WAKE_LOCK acquire failed", it)
        }
    }

    private fun releaseSessionWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun logPowerState(where: String) {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
            val idle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.isDeviceIdleMode
            } else {
                false
            }
            AppLog.i(
                TAG,
                "power[$where]: ignoringBatteryOpt=$ignoring deviceIdle=$idle " +
                    "powerSave=${pm.isPowerSaveMode}",
            )
        }.onFailure {
            AppLog.w(TAG, "power[$where]: probe failed", it)
        }
    }

    private fun buildNotification(summary: String): Notification {
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
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setShowWhen(false)
            .build()
    }

    companion object {

        private const val TAG = "SshKeepAliveService"

        const val CHANNEL_ID: String = "ssh_session_v2"

        private const val NOTIF_ID: Int = 1

        private const val EXTRA_SUMMARY = "summary"

        /**
         * API 34+ uses `specialUse` (no dataSync quota / less OEM deferral).
         * Older APIs fall back to `dataSync`, which is the only type that
         * existed for this kind of work before specialUse.
         */
        private fun foregroundServiceType(): Int =
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }

        fun start(context: Context, summary: String) {
            if (summary.isBlank()) {
                AppLog.w(TAG, "start skipped: summary is blank")
                return
            }
            val intent = Intent(context, SshKeepAliveService::class.java)
                .putExtra(EXTRA_SUMMARY, summary)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SshKeepAliveService::class.java))
        }
    }
}
