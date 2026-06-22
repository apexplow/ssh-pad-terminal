package com.example.sshterminal.data.prefs

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

/**
 * Robolectric tests for [AppPreferences].
 *
 * Scope (Sprint 1.5 §4): verify the SharedPreferences-backed read/write/clear contract
 * and the `hasUsableCredentials` boolean logic. We intentionally do NOT exercise
 * Keystore-backed password round-trip here — Robolectric's AndroidKeyStore is a stub
 * and behaves unreliably; that coverage is reserved for the manual "device" matrix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
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
        prefs.password = "hunter2"
        prefs.privateKeyName = "id_ed25519.pem"

        prefs.clear()

        val reloaded = AppPreferences(context)
        assertEquals("", reloaded.host)
        assertEquals(AppPreferences.DEFAULT_PORT, reloaded.port)
        assertEquals("", reloaded.username)
        assertEquals("", reloaded.password)
        assertEquals("", reloaded.privateKeyName)
    }

    @Test
    fun test_hasUsableCredentials_returnsTrueWhenPasswordSet() {
        prefs.host = "router.lan"
        prefs.port = 22
        prefs.username = "ops"
        prefs.password = "hunter2"

        assertTrue(prefs.hasUsableCredentials())
    }

    @Test
    fun test_hasUsableCredentials_returnsTrueWhenPrivateKeySet() {
        prefs.host = "router.lan"
        prefs.port = 22
        prefs.username = "ops"
        // No password — only a private key on disk.
        prefs.privateKeyName = "id_ed25519.pem"

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
}
