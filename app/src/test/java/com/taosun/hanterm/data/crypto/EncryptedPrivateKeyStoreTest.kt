package com.taosun.hanterm.data.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taosun.hanterm.data.prefs.AppPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
class EncryptedPrivateKeyStoreTest {

    private lateinit var context: Context
    private lateinit var prefs: AppPreferences
    private lateinit var store: EncryptedPrivateKeyStore

    private val keysDir: File
        get() = File(context.filesDir, "keys")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = AppPreferences(context).also { it.clear() }
        store = EncryptedPrivateKeyStore(context, prefs)
        keysDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        keysDir.listFiles()?.forEach { it.delete() }
        prefs.clear()
    }

    @Test
    fun pkr_fmt_01_encryptedFileAtSafePath() {
        assertEquals("id_rsa.pem.pem.enc", store.encryptedFile("id_rsa.pem").name)
        assertTrue(store.encryptedFile("id_rsa.pem").absolutePath.contains("/keys/"))
    }

    @Test
    fun pkr_fmt_03_privateKeyNameStoresSafeNameOnly() {
        assumeKeystoreAvailable()
        store.import("mykey.pem", TEST_PEM.toByteArray()).getOrThrow()
        assertEquals("mykey.pem", prefs.privateKeyName)
    }

    @Test
    fun pkr_fmt_02_payloadIsKeyStoreManagerBlob() {
        assumeKeystoreAvailable()
        store.import("key.pem", TEST_PEM.toByteArray()).getOrThrow()
        val payload = store.encryptedFile("key.pem").readBytes()
        assertTrue("encrypted payload must include IV+ciphertext", payload.size > 12)
        val roundTrip = String(KeyStoreManager.decrypt(payload), Charsets.UTF_8)
        assertEquals(TEST_PEM.trim(), roundTrip.trim())
    }

    @Test
    fun pkr_fmt_04_legacyPlaintextMigratesOnFirstAuth() {
        assumeKeystoreAvailable()
        keysDir.mkdirs()
        val legacy = File(keysDir, "legacy.pem")
        legacy.writeText(TEST_PEM)
        val migrated = store.migrateLegacyPlaintextIfNeeded("legacy.pem")
        assertTrue(migrated!!.isFile)
        assertFalse(legacy.exists())
        assertTrue(store.encryptedFile("legacy.pem").isFile)
    }

    @Test
    fun epks_im_01_importEncryptsAndSetsPrefs() {
        assumeKeystoreAvailable()
        store.import("imported.pem", TEST_PEM.toByteArray()).getOrThrow()
        assertEquals("imported.pem", prefs.privateKeyName)
        assertTrue(store.encryptedFile("imported.pem").isFile)
    }

    @Test
    fun epks_im_02_secureDeletesSourceInKeysDir() {
        assumeKeystoreAvailable()
        keysDir.mkdirs()
        val source = File(keysDir, "source.pem")
        source.writeText(TEST_PEM)
        store.import("source.pem", source).getOrThrow()
        assertFalse("plaintext source must be removed", source.exists())
    }

    @Test
    fun epks_im_03_secureDeleteDoesNotThrowOnMissingFile() {
        val ghost = File(keysDir, "ghost.pem")
        EncryptedPrivateKeyStore.secureDeleteBestEffort(ghost)
    }

    @Test
    fun epks_im_04_plaintextZeroedAfterImport() {
        assumeKeystoreAvailable()
        val plain = TEST_PEM.toByteArray()
        store.import("z.pem", plain).getOrThrow()
        assertTrue(plain.all { it == 0.toByte() })
    }

    private fun assumeKeystoreAvailable() {
        assumeTrue(
            "AndroidKeyStore encrypt/decrypt unavailable in this Robolectric runtime",
            runCatching {
                val blob = KeyStoreManager.encrypt("probe".toByteArray())
                KeyStoreManager.decrypt(blob)
                true
            }.getOrDefault(false),
        )
    }

    companion object {
        private val TEST_PEM = """
            -----BEGIN PRIVATE KEY-----
            MIIBVQIBADANBgkqhkiG9w0BAQEFAASCAT8wggE7AgEAAkEA1234567890
            -----END PRIVATE KEY-----
        """.trimIndent()
    }
}
