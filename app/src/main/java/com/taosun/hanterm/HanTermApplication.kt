package com.taosun.hanterm

import android.app.Application
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.taosun.hanterm.data.prefs.AppPreferences
import com.taosun.hanterm.data.profile.ConnectionProfile
import com.taosun.hanterm.data.profile.ConnectionProfiles
import com.taosun.hanterm.logging.AppLog
import com.taosun.hanterm.ssh.ConnectionRuntime
import com.taosun.hanterm.ssh.SshClient
import com.taosun.hanterm.ssh.SshConnector
import com.taosun.hanterm.ssh.SshKeepAliveService
import com.taosun.hanterm.ssh.security.HostKeyPrompt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

class HanTermApplication : Application() {

    private val lock = Any()

    private var cachedProfile: ConnectionProfile? = null
    private var cachedConnector: SshConnector? = null
    private var cachedRuntime: ConnectionRuntime? = null
    private var hostKeyPrompt: HostKeyPrompt? = null

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
        runLegacyDebugLogCleanupIfNeeded(this)
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
                    // DEFAULT (not LOW): OEM battery savers treat LOW channels as
                    // "silent / deferrable" and freeze the owning FGS more aggressively
                    // (BG-KA-06). A visible ongoing notification keeps us perceptible.
                    NotificationManagerCompat.IMPORTANCE_DEFAULT,
                )
                    .setName(getString(R.string.notification_channel_name))
                    .setDescription(getString(R.string.notification_channel_description))
                    .setShowBadge(false)
                    .build()
            )
        }.onFailure { AppLog.e("HanTermApplication", "createNotificationChannel failed", it) }
    }

    /**
     * Process-scoped [ConnectionProfile]. One instance shared by ConfigScreen
     * and the ViewModel so Save and Connect see the same persisted picture.
     */
    fun connectionProfile(prefs: AppPreferences): ConnectionProfile = synchronized(lock) {
        cachedProfile ?: ConnectionProfiles.create(this, prefs).also { cachedProfile = it }
    }

    /**
     * Process-scoped [ConnectionRuntime]. First caller supplies the interactive
     * host-key prompt and IO dispatcher; subsequent Activity recreations reuse
     * the same runtime (and its live session) without a degraded re-attach path.
     */
    fun connectionRuntime(
        prompt: HostKeyPrompt,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ConnectionRuntime = synchronized(lock) {
        cachedRuntime?.let { return it }
        hostKeyPrompt = prompt
        val client = SshClient(context = this, hostKeyPrompt = prompt)
        cachedConnector = client
        ConnectionRuntime(
            context = this,
            connector = client,
            ioDispatcher = ioDispatcher,
        ).also { cachedRuntime = it }
    }

    /** Test seam: replace the process-scoped runtime (e.g. with a fake connector). */
    fun replaceConnectionRuntimeForTests(runtime: ConnectionRuntime) = synchronized(lock) {
        cachedRuntime = runtime
    }

    fun clearConnectionRuntimeForTests() = synchronized(lock) {
        cachedRuntime?.dispose()
        cachedRuntime = null
        cachedConnector = null
        cachedProfile = null
        hostKeyPrompt = null
    }
}

/**
 * Sprint 2.5 / BC-COMPAT-01: one-shot deletion of the pre-2.5 `debug.log`
 * file on first launch after upgrade. Gated by [AppPreferences.isDebugLogMigratedV25].
 */
private fun runLegacyDebugLogCleanupIfNeeded(context: Context) {
    val prefs = AppPreferences(context)
    if (prefs.isDebugLogMigratedV25()) return
    val legacy = File(context.filesDir, "debug.log")
    runCatching { if (legacy.exists()) legacy.delete() }
    prefs.markDebugLogMigratedV25()
}
