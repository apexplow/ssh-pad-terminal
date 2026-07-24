package com.taosun.hanterm.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robolectric tests for [AppPreferences].
 *
 * Scope (Sprint 1.5 §4): verify the SharedPreferences-backed read/write/clear contract
 * and the `hasUsableCredentials` boolean logic. We intentionally do NOT exercise
 * Keystore-backed password round-trip here — Robolectric's AndroidKeyStore is a stub
 * and behaves unreliably; that coverage is reserved for the manual "device" matrix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppPreferencesTest {

    private lateinit var context: Context
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Each test starts from a clean store so cases don't bleed into each other.
        prefs = AppPreferences(context).also { it.clear() }
    }

    @Test
    fun test_saveAndLoadRoundTrip_hostPortUsername() {
        prefs.host = "router.lan"
        prefs.port = 2222
        prefs.username = "ops"

        // Re-instantiate to confirm values survive process death (persisted to disk).
        val reloaded = AppPreferences(context)
        assertEquals("router.lan", reloaded.host)
        assertEquals(2222, reloaded.port)
        assertEquals("ops", reloaded.username)
    }

    @Test
    fun test_clear_wipesAllFields() {
        prefs.host = "router.lan"
        prefs.port = 2222
        prefs.username = "ops"
        prefs.privateKeyName = "id_ed25519.pem"
        prefs.fontSize = 22

        prefs.clear()

        val reloaded = AppPreferences(context)
        assertEquals("", reloaded.host)
        assertEquals(AppPreferences.DEFAULT_PORT, reloaded.port)
        assertEquals("", reloaded.username)
        assertEquals("", reloaded.privateKeyName)
        assertEquals(AppPreferences.DEFAULT_FONT_SIZE, reloaded.fontSize)
    }

    @Test
    fun test_hasUsableCredentials_returnsTrueWhenPasswordSet() {
        prefs.host = "router.lan"
        prefs.port = 22
        prefs.username = "ops"
        // Production writes via Plan C (encrypted blob), NOT the legacy plain
        // `password` slot — see ConnectionProfile.save / prepareConnect and the kdoc on
        // hasUsableCredentials. Using the right writer here is what keeps the
        // test in lockstep with what the UI actually does.
        prefs.setEncryptedPassword(byteArrayOf(1, 2, 3, 4))

        assertTrue(prefs.hasUsableCredentials())
    }

    @Test
    fun test_hasUsableCredentials_returnsTrueWhenPrivateKeySet() {
        prefs.host = "router.lan"
        prefs.port = 22
        prefs.username = "ops"
        prefs.privateKeyName = "id_ed25519.pem"
        File(context.filesDir, "keys").mkdirs()
        File(context.filesDir, "keys/id_ed25519.pem").writeText("pem")

        assertTrue(prefs.hasUsableCredentials())
    }

    @Test
    fun ap_pkn_02_returnsFalseWhenKeyNameSetButFileMissing() {
        prefs.host = "router.lan"
        prefs.port = 22
        prefs.username = "ops"
        prefs.privateKeyName = "missing.pem"

        assertFalse(prefs.hasUsableCredentials())
    }

    @Test
    fun ap_pkn_03_returnsTrueWhenEncryptedKeyExists() {
        prefs.host = "router.lan"
        prefs.port = 22
        prefs.username = "ops"
        prefs.privateKeyName = "id_rsa.pem"
        File(context.filesDir, "keys").mkdirs()
        File(context.filesDir, "keys/id_rsa.pem.pem.enc").writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(prefs.hasUsableCredentials())
    }

    @Test
    fun test_hasUsableCredentials_returnsFalseWhenBothBlank() {
        prefs.host = "router.lan"
        prefs.port = 22
        prefs.username = "ops"
        // No password and no key.
        assertFalse(prefs.hasUsableCredentials())
    }

    @Test
    fun test_saveAndLoadRoundTrip_privateKeyName() {
        prefs.privateKeyName = "id_ed25519.pem"

        val reloaded = AppPreferences(context)
        assertEquals("id_ed25519.pem", reloaded.privateKeyName)
    }

    @Test
    fun test_getEncryptedPassword_returnsNullWhenNotSet() {
        // Plan C surface: getEncryptedPassword() returns null for the empty store
        // (not an empty byte array) so callers can distinguish "no password" from
        // "password that's the empty string" — which we never want to persist anyway.
        assertNull(prefs.getEncryptedPassword())
    }

    @Test
    fun test_getEncryptedPassword_returnsNullForEmptyBlob() {
        // The UI writes an empty blob when the user clears the password field.
        // Reading it back must return null (not a zero-length array), so callers
        // can use a simple null check instead of trying to decrypt a blob that
        // wouldn't even contain an IV. Regression test for the Sprint 1.5 bugfix.
        prefs.setEncryptedPassword(ByteArray(0))
        assertNull(prefs.getEncryptedPassword())
    }

    @Test
    fun test_saveAndLoadRoundTrip_fontSize() {
        prefs.fontSize = 22

        val reloaded = AppPreferences(context)
        assertEquals(22, reloaded.fontSize)
    }

    @Test
    fun test_fontSize_defaultsToFourteen() {
        // No setter call — a fresh store should return the compile-time default.
        assertEquals(AppPreferences.DEFAULT_FONT_SIZE, prefs.fontSize)
    }

    @Test
    fun test_fontSize_clampsOutOfRangeValues() {
        // Bypass the public setter and write a junk value straight into the
        // SharedPreferences XML, simulating a corrupted store or a manual edit.
        // The getter must clamp so setTextSize() never sees an out-of-range arg.
        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(AppPreferences.KEY_FONT_SIZE, 9999).commit()
        assertEquals(AppPreferences.MAX_FONT_SIZE, AppPreferences(context).fontSize)

        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(AppPreferences.KEY_FONT_SIZE, 1).commit()
        assertEquals(AppPreferences.MIN_FONT_SIZE, AppPreferences(context).fontSize)
    }

    @Test
    fun legacyPassword_isScrubbedOnConstruction() {
        // Issue #34 — simulate an upgrade user: a pre-Plan-C build left
        // `KEY_PASSWORD` sitting on disk in plaintext. The next
        // AppPreferences construction must remove it; a second construction
        // must be a no-op (idempotent scrub). We write the legacy key by
        // name directly via SharedPreferences because the public `password`
        // property was removed in #34 — this is the only path that can
        // place plaintext into the store.
        val rawPrefs = context.getSharedPreferences(
            AppPreferences.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        rawPrefs.edit()
            .putString(AppPreferences.KEY_PASSWORD, "hunter2-upgrade")
            .commit()

        // First construction scrubs the leftover.
        AppPreferences(context)
        assertFalse(
            "legacy KEY_PASSWORD must be removed on first construction",
            context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                .contains(AppPreferences.KEY_PASSWORD),
        )

        // Second construction is a no-op (init is idempotent).
        AppPreferences(context)
        assertFalse(
            "scrub must be idempotent on subsequent constructions",
            context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                .contains(AppPreferences.KEY_PASSWORD),
        )
    }
}
