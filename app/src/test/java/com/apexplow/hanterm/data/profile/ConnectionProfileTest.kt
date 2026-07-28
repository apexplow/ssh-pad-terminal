package com.apexplow.hanterm.data.profile

import com.apexplow.hanterm.ssh.auth.Auth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionProfileTest {

    private lateinit var store: InMemoryProfileStore
    private lateinit var cipher: RoundTripFakeCipher
    private lateinit var keys: InMemoryPrivateKeyVault
    private lateinit var hosts: RecordingHostEnrollment
    private lateinit var profile: ConnectionProfile

    @Before
    fun setUp() {
        store = InMemoryProfileStore()
        cipher = RoundTripFakeCipher()
        keys = InMemoryPrivateKeyVault()
        hosts = RecordingHostEnrollment()
        profile = DefaultConnectionProfile(store, cipher, keys, hosts)
    }

    @Test
    fun load_neverFillsPassword_evenWhenBlobPresent() {
        store.seed(StoredProfile(host = "h", username = "u", passwordBlob = byteArrayOf(1, 2, 3)))
        val snap = profile.load()
        assertEquals("", snap.draft.password)
        assertTrue(snap.hasStoredPassword)
        assertEquals("h", snap.draft.host)
    }

    @Test
    fun save_emptyPassword_keepsBlob() {
        store.seed(StoredProfile(host = "old", username = "u", passwordBlob = byteArrayOf(9, 8, 7)))
        val outcome = profile.save(
            ConnectionDraft("new", "22", "ops", password = "", privateKeyName = ""),
        )
        assertEquals("new", store.read().host)
        assertArrayEquals(byteArrayOf(9, 8, 7), store.read().passwordBlob)
        assertTrue(outcome.hasStoredPassword)
        assertEquals("", outcome.draftForUi.password)
    }

    @Test
    fun save_nonEmptyPassword_replacesBlob() {
        store.seed(StoredProfile(passwordBlob = byteArrayOf(1)))
        profile.save(
            ConnectionDraft("h", "22", "u", password = "secret", privateKeyName = ""),
        )
        val blob = store.read().passwordBlob
        assertTrue(blob != null && blob.isNotEmpty())
        assertFalse(blob!!.contentEquals(byteArrayOf(1)))
        assertEquals("secret", String(cipher.decrypt(blob)))
    }

    @Test
    fun prepareConnect_emptyPassword_keepsBlob_andAuthFromBlob() = runBlocking {
        val blob = cipher.encrypt("stored-pw".toByteArray())
        store.seed(StoredProfile(host = "h", username = "u", passwordBlob = blob))
        val prepared = profile.prepareConnect(
            ConnectionDraft("h", "2222", "u", password = "", privateKeyName = ""),
        ).getOrThrow()
        assertEquals(2222, prepared.port)
        assertArrayEquals(blob, store.read().passwordBlob)
        val auth = prepared.auth as Auth.PasswordAuth
        assertEquals("stored-pw", String(auth.password))
    }

    @Test
    fun prepareConnect_nonEmptyPassword_authFromDraft_noDecryptNeeded() = runBlocking {
        store.seed(StoredProfile(host = "h", username = "u", passwordBlob = byteArrayOf(1)))
        val decryptsBefore = cipher.decryptCount
        val prepared = profile.prepareConnect(
            ConnectionDraft("h", "22", "u", password = "typed", privateKeyName = ""),
        ).getOrThrow()
        assertEquals(decryptsBefore, cipher.decryptCount)
        val auth = prepared.auth as Auth.PasswordAuth
        assertEquals("typed", String(auth.password))
        assertEquals("typed", String(cipher.decrypt(store.read().passwordBlob!!)))
    }

    @Test
    fun prepareConnect_prefersPublicKey() = runBlocking {
        keys.files["id.pem"] = "/tmp/id.pem"
        store.seed(StoredProfile(host = "h", username = "u", passwordBlob = byteArrayOf(1)))
        val prepared = profile.prepareConnect(
            ConnectionDraft("h", "22", "u", password = "x", privateKeyName = "id.pem"),
        ).getOrThrow()
        assertTrue(prepared.auth is Auth.PublicKeyAuth)
        assertEquals("/tmp/id.pem", (prepared.auth as Auth.PublicKeyAuth).privateKeyPath)
    }

    @Test
    fun clearStoredPassword_wipesBlobOnly() {
        store.seed(
            StoredProfile(host = "h", username = "u", privateKeyName = "k.pem", passwordBlob = byteArrayOf(1)),
        )
        profile.clearStoredPassword()
        assertNull(store.read().passwordBlob)
        assertEquals("h", store.read().host)
        assertEquals("k.pem", store.read().privateKeyName)
    }

    @Test
    fun clearAll_clearsConnectionFields() {
        store.seed(StoredProfile(host = "h", username = "u", passwordBlob = byteArrayOf(1)))
        store.fontSizePreserved = 18
        val draft = profile.clearAll()
        assertEquals("", store.read().host)
        assertNull(store.read().passwordBlob)
        assertEquals(18, store.fontSizePreserved)
        assertEquals("", draft.host)
        assertEquals("22", draft.port)
    }

    @Test
    fun importKey_updatesPrivateKeyName() {
        val name = profile.importKey("my_key", "PEM".toByteArray()).getOrThrow()
        assertEquals("my_key.pem", name)
        assertEquals("my_key.pem", store.read().privateKeyName)
        assertTrue(keys.files.containsKey("my_key.pem"))
    }

    @Test
    fun forgetHost_recordsHostAndPort() = runBlocking {
        profile.forgetHost("example.com", 2222)
        assertEquals(listOf("example.com" to 2222), hosts.deletions)
    }

    // ── fakes ──────────────────────────────────────────────────────────

    private class InMemoryProfileStore : ProfileStorePort {
        private var current = StoredProfile()
        var fontSizePreserved: Int = 14

        fun seed(profile: StoredProfile) {
            current = profile
        }

        override fun read(): StoredProfile = current

        override fun write(profile: StoredProfile) {
            current = profile
        }

        override fun clearConnectionFields() {
            current = StoredProfile()
        }
    }

    private class RoundTripFakeCipher : SecretCipherPort {
        var decryptCount: Int = 0
            private set

        override fun encrypt(plaintext: ByteArray): ByteArray =
            // trivial reversible transform
            plaintext.map { (it + 1).toByte() }.toByteArray()

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            decryptCount++
            return ciphertext.map { (it - 1).toByte() }.toByteArray()
        }
    }

    private class InMemoryPrivateKeyVault : PrivateKeyVaultPort {
        val files = mutableMapOf<String, String>()

        override fun import(safeName: String, bytes: ByteArray): Result<Unit> = runCatching {
            files[safeName] = "/mem/$safeName"
            bytes.fill(0)
        }

        override fun resolveAbsolutePath(safeName: String): String? = files[safeName]

        override fun normalizeSafeName(raw: String): String =
            raw.trim().let { if (it.endsWith(".pem")) it else "$it.pem" }
    }

    private class RecordingHostEnrollment : HostEnrollmentPort {
        val deletions = mutableListOf<Pair<String, Int>>()

        override suspend fun delete(host: String, port: Int) {
            deletions += host to port
        }
    }
}
