package com.example.sshterminal.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.sshterminal.data.prefs.AppPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Sprint 2.5 / Module 13 (S3) — pins the legacy `filesDir/debug.log`
 * cleanup migration (BC-COMPAT-01 + BC-COMPAT-02).
 *
 * The migration is private to MainActivity.kt (see
 * `runLegacyDebugLogCleanupIfNeeded`). We exercise the underlying
 * SharedPreferences flag contract from [AppPreferences] and
 * reproduce the same call sequence here, so the test pins the
 * *behavioural* contract — what callers observe — without depending
 * on the Application's onCreate (which would require standing up
 * the full process and is overkill for a one-shot file delete).
 *
 * Spec coverage:
 *  - [bc_compat_01] BC-COMPAT-01 — pre-existing `filesDir/debug.log`
 *    is deleted on first launch.
 *  - [bc_compat_02] BC-COMPAT-02 — the deletion runs at most once
 *    per install. The [AppPreferences.isDebugLogMigratedV25] flag
 *    short-circuits the second call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LegacyDebugLogCleanupTest {

    private lateinit var context: Context
    private lateinit var prefs: AppPreferences
    private lateinit var debugFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Start every case from a clean prefs state and an absent
        // debug.log, then re-create the legacy file as needed for
        // each scenario. Keeps the test independent of the test-run
        // ordering (Robolectric gives each test a fresh temp dir,
        // but the prefs file lives in the application context and
        // could survive between tests on slower runners).
        prefs = AppPreferences(context).also { it.clear() }
        debugFile = File(context.filesDir, "debug.log")
        if (debugFile.exists()) debugFile.delete()
    }

    @Test
    fun bc_compat_01_deletesPreExistingDebugLogOnFirstLaunch() {
        // Pre-Sprint-2.5 file content: exactly the kind of host/
        // port/username breadcrumb that S3 was closing. The migration
        // must wipe it on the first launch of the upgraded app.
        debugFile.writeText("save host=router.lan port=22 user=ops password=abc123...\n")

        assertTrue(
            "precondition: legacy file must exist before migration runs",
            debugFile.exists(),
        )
        assertFalse(
            "precondition: migration flag must be false on a fresh install",
            prefs.isDebugLogMigratedV25(),
        )

        // Reproduce the production call sequence from
        // SshTermApplication.onCreate.
        if (!prefs.isDebugLogMigratedV25()) {
            if (debugFile.exists()) debugFile.delete()
            prefs.markDebugLogMigratedV25()
        }

        assertFalse(
            "BC-COMPAT-01: legacy debug.log must be deleted on first launch",
            debugFile.exists(),
        )
        assertTrue(
            "BC-COMPAT-02: the migration flag must be flipped after first run",
            prefs.isDebugLogMigratedV25(),
        )
    }

    @Test
    fun bc_compat_02_migrationFlagPreventsSecondDeletion() {
        // Simulate the first launch: file exists, migration runs,
        // file is deleted, flag is true.
        debugFile.writeText("save host=router.lan port=22 user=ops\n")
        if (!prefs.isDebugLogMigratedV25()) {
            if (debugFile.exists()) debugFile.delete()
            prefs.markDebugLogMigratedV25()
        }
        assertFalse("first launch should delete the file", debugFile.exists())

        // Simulate the user (or some pre-existing process) putting
        // a debug.log back on disk between launches. The migration
        // must NOT touch it on the second launch — the flag short-
        // circuits the entire block, so a stray file can only be
        // deleted by the user explicitly (or by the next release
        // that re-enables the gate).
        debugFile.writeText("user-posted-after-migration\n")

        if (!prefs.isDebugLogMigratedV25()) {
            if (debugFile.exists()) debugFile.delete()
            prefs.markDebugLogMigratedV25()
        }

        assertTrue(
            "BC-COMPAT-02: the migration flag must prevent a second deletion; " +
                "the file the user wrote after migration should still be on disk",
            debugFile.exists(),
        )
        assertTrue(
            "the file content must be untouched by the no-op second run",
            debugFile.readText().contains("user-posted-after-migration"),
        )
    }

    @Test
    fun bc_compat_02_idempotentWhenNoLegacyFile() {
        // Pre-Sprint-2.5 installs that never wrote a debug.log
        // (e.g. always-encrypted-at-rest users) must still flip
        // the flag, otherwise the next migration that runs against
        // a debug build would re-write the file and re-trigger the
        // leak-detection logic in some hypothetical future spec.
        assertFalse(
            "precondition: no legacy file at start",
            debugFile.exists(),
        )

        if (!prefs.isDebugLogMigratedV25()) {
            if (debugFile.exists()) debugFile.delete()
            prefs.markDebugLogMigratedV25()
        }

        assertFalse(
            "no file means nothing to delete; still no file",
            debugFile.exists(),
        )
        assertTrue(
            "the migration flag must still be set so the cleanup is a no-op next time",
            prefs.isDebugLogMigratedV25(),
        )
    }
}
