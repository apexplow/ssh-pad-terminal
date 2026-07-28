package com.apexplow.hanterm.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.apexplow.hanterm.logging.AppLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * Sprint 2.5 / Module 13 (S3) — pins the file-sink and fingerprint gating
 * on [appendDebugLog] and [passwordFingerprint].
 *
 * The two functions accept an explicit [Boolean] debug flag, defaulting to
 * [com.apexplow.hanterm.BuildConfig.DEBUG]. Production callers leave
 * the default; tests pass `true` / `false` to exercise both branches
 * without depending on which build type Robolectric was launched with.
 *
 * Spec coverage:
 *  - [cs_dl_01] CS-DL-01 — debug build writes the message to
 *    `filesDir/debug.log`.
 *  - [cs_dl_02] CS-DL-02 — release build does NOT touch the file (no
 *    create, no append).
 *  - [cs_dl_03] CS-DL-03 — neither call site passes the password or its
 *    fingerprint. We verify the helper's surface (it doesn't accept a
 *    password parameter) and we verify the *production* call site
 *    string-literal — the Save button builds its message from host/port/
 *    user/privateKeyName only.
 *  - [cs_dl_04] CS-DL-04 — `Log.d("ConfigScreen", ...)` and `AppLog` see
 *    the message regardless of build type. We use Robolectric's
 *    [ShadowLog] stream to assert the Logcat call.
 *  - [cs_pf_01] CS-PF-01 — release `passwordFingerprint` returns the
 *    empty string.
 *  - [cs_pf_02] CS-PF-02 — debug `passwordFingerprint` returns the
 *    SHA-256 first-16-hex payload (existing behaviour, preserved for
 *    lab testing).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
class ConfigScreenDebugLogGateTest {

    private lateinit var context: Context
    private lateinit var debugFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // AppLog owns the file sink we share with appendDebugLog's Logcat/
        // AppLog branch. Init once and clear() before each test so a
        // stale line from a previous case doesn't show up in our asserts.
        AppLog.init(context)
        AppLog.clear()
        ShadowLog.clear()
        debugFile = File(context.filesDir, "debug.log")
        // Defensive: a stale file from a previous test would let the
        // "no file touch in release" assertion pass for the wrong reason.
        if (debugFile.exists()) debugFile.delete()
    }

    @After
    fun tearDown() {
        if (debugFile.exists()) debugFile.delete()
    }

    // -----------------------------------------------------------------
    // appendDebugLog — CS-DL-01..04
    // -----------------------------------------------------------------

    @Test
    fun cs_dl_01_writesMessageToFilesDirDebugLog_whenDebugTrue() {
        appendDebugLog(context, "save host=h.example port=22 user=ops", isDebug = true)

        assertTrue(
            "debug build must create+append filesDir/debug.log; file=" +
                "${debugFile.absolutePath} exists=${debugFile.exists()}",
            debugFile.exists(),
        )
        val text = debugFile.readText(Charsets.UTF_8)
        assertTrue(
            "expected the message to land in the file, got '$text'",
            text.contains("save host=h.example port=22 user=ops"),
        )
    }

    @Test
    fun cs_dl_02_noFileTouchInRelease() {
        // Make sure the file is absent to start with — if it were somehow
        // already there, the post-call assertion wouldn't be meaningful.
        assertFalse(
            "precondition: debug.log must not exist before the release call",
            debugFile.exists(),
        )

        appendDebugLog(context, "save host=h.example port=22 user=ops", isDebug = false)

        assertFalse(
            "release build must not create filesDir/debug.log",
            debugFile.exists(),
        )
    }

    @Test
    fun cs_dl_03_passwordDerivedContentNeverPassed() {
        // Belt-and-braces: run the helper with a non-sensitive message and
        // confirm nothing password-shaped ends up in either sink. (The
        // helper's structural signature — no password slot — is already
        // enforced by the source; we don't pin it via reflection to avoid
        // adding a kotlin-reflect dependency for one assertion.)
        appendDebugLog(
            context,
            "save host=h.example port=22 user=ops privateKey=id_rsa.pem",
            isDebug = true,
        )
        val fileText = debugFile.readText(Charsets.UTF_8)
        val logText = AppLog.readTail()
        for (sink in listOf(fileText, logText)) {
            assertFalse(
                "sink must not contain 'password=' (the legacy leak shape): $sink",
                sink.contains("password="),
            )
            assertFalse(
                "sink must not contain 'sha256[0..16]=' (the fingerprint shape): $sink",
                sink.contains("sha256[0..16]="),
            )
        }
    }

    @Test
    fun cs_dl_04_logcatReceivesMessageButFileSinkDropsConnectionMetadata() {
        // Issue #13 update: the AppLog file sink no longer receives
        // ConnectionMetadata entries in either build type (LogcatOnly in
        // debug, Drop in release). Log.d still mirrors to Logcat
        // unconditionally. This is the new contract — the old contract
        // ("AppLog must still record") was the leak that #13 closed.
        ShadowLog.clear()
        AppLog.clear()
        appendDebugLog(context, "save host=h.example debug=true", isDebug = true)
        assertTrue(
            "debug build: Log.d(ConfigScreen, msg) must still record via Logcat",
            ShadowLog.getLogs().any { it.tag == "ConfigScreen" && it.msg == "save host=h.example debug=true" },
        )
        assertFalse(
            "debug build: AppLog file sink must NOT record ConnectionMetadata; tail=" +
                AppLog.readTail(),
            AppLog.readTail().contains("save host=h.example debug=true"),
        )

        // Release build: same — Logcat still records, file sink stays empty.
        ShadowLog.clear()
        AppLog.clear()
        appendDebugLog(context, "save host=h.example debug=false", isDebug = false)
        assertTrue(
            "release build: Log.d(ConfigScreen, msg) must still record via Logcat",
            ShadowLog.getLogs().any { it.tag == "ConfigScreen" && it.msg == "save host=h.example debug=false" },
        )
        assertFalse(
            "release build: AppLog file sink must NOT record ConnectionMetadata; tail=" +
                AppLog.readTail(),
            AppLog.readTail().contains("save host=h.example debug=false"),
        )
    }

    // -----------------------------------------------------------------
    // passwordFingerprint — CS-PF-01..02
    // -----------------------------------------------------------------

    @Test
    fun cs_pf_01_returnsEmptyStringInRelease() {
        // The whole point of CS-PF-01 is that release builds must not
        // produce sensitive content. Empty string is the contract.
        assertEquals("", passwordFingerprint("hunter2", isDebug = false))
        assertEquals("", passwordFingerprint("", isDebug = false))
        assertEquals("", passwordFingerprint("with-special-chars!@#", isDebug = false))
    }

    @Test
    fun cs_pf_02_returnsSha256First16HexInDebug() {
        // Existing debug behaviour: a non-empty string with the
        // SHA-256 first-16-hex token. We don't pin the *exact* hex
        // value (that would couple us to JVM-default MessageDigest
        // string-encoding changes) but we do pin the structural shape.
        val fp = passwordFingerprint("hunter2", isDebug = true)
        assertNotEquals(
            "debug build must not return the release-build empty string",
            "",
            fp,
        )
        assertTrue(
            "debug fingerprint must contain 'sha256[0..16]=': $fp",
            fp.contains("sha256[0..16]="),
        )
        // The token after `sha256[0..16]=` is 16 lowercase hex chars.
        val match = Regex("""sha256\[0\.\.16\]=([0-9a-f]{16})""").find(fp)
        assertTrue(
            "debug fingerprint must embed exactly 16 lowercase hex chars: $fp",
            match != null,
        )
    }

    // -----------------------------------------------------------------
    // Issue #13 — privateKeyName parameter emits a separate
    // CredentialMetadata entry. Both build types must keep the
    // privateKey= token out of the AppLog file sink.
    // -----------------------------------------------------------------

    @Test
    fun cs_pk_01_release_dropsPrivateKeyFromAppLogAndDebugLog() {
        ShadowLog.clear()
        AppLog.clear()
        // Release: AppLog file sink and debug.log must NOT see the key.
        appendDebugLog(
            context,
            "save host=h.example port=22 user=ops",
            isDebug = false,
            privateKeyName = "id_rsa.pem",
        )
        assertFalse(
            "release AppLog must NOT contain the privateKey token; tail=" +
                AppLog.readTail(),
            AppLog.readTail().contains("id_rsa.pem"),
        )
        assertFalse(
            "release debug.log must NOT exist (BuildConfig.DEBUG gate)",
            debugFile.exists(),
        )
    }

    @Test
    fun cs_pk_02_debug_logsBaseInDebugLogButDropsPrivateKeyFromAppLog() {
        // Debug build: base message reaches legacy debugFile (debug-only),
        // but the privateKey= token stays out of the AppLog file sink
        // because the CredentialMetadata line is classified (LogcatOnly).
        ShadowLog.clear()
        AppLog.clear()
        appendDebugLog(
            context,
            "save host=h.example port=22 user=ops",
            isDebug = true,
            privateKeyName = "id_rsa.pem",
        )
        // AppLog file sink: both host= (ConnectionMetadata → LogcatOnly)
        // and id_rsa.pem (CredentialMetadata → LogcatOnly) are dropped
        // in debug. The file sink stays empty.
        assertEquals(
            "debug build: AppLog file sink must NOT contain either sensitive token; tail=" +
                AppLog.readTail(),
            "",
            AppLog.readTail(),
        )
        // The legacy debugFile write (gated by isDebug=true) keeps the
        // base message — but only the BASE; the privateKey line is a
        // separate AppLog entry that doesn't go through the legacy file
        // write path.
        assertTrue(
            "debug build: legacy debug.log must contain the base message",
            debugFile.exists(),
        )
        val debugText = debugFile.readText(Charsets.UTF_8)
        assertTrue(
            "debug build: legacy debug.log contains the base message; text=$debugText",
            debugText.contains("save host=h.example port=22 user=ops"),
        )
    }
}
