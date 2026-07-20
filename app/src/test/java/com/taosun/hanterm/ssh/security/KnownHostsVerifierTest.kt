package com.taosun.hanterm.ssh.security

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * Tests for [KnownHostsVerifier] — pins KHV-VF-01..06 against a real
 * [KnownHostsStore] but a fake sshj [PublicKey]. We don't drive sshj's
 * internal verifier pipeline; we test the contract our verifier exposes
 * to sshj.
 *
 * The fake [PublicKey] subclass overrides [PublicKey.getAlgorithm] (the
 * one method the verifier reads) and [toString] (the bytes the verifier
 * hashes). Two fake keys with the same `getAlgorithm` and `toString`
 * are "the same key" from the verifier's perspective.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnownHostsVerifierTest {

    private lateinit var store: KnownHostsStore
    private val knownHostsFile: File
        get() = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, KnownHostsStore.FILE_NAME)

    private val host = "test.example"
    private val port = 22

    @Before
    fun setUp() {
        knownHostsFile.delete()
        store = KnownHostsStore(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        knownHostsFile.delete()
    }

    // ---- KHV-VF-01: implements HostKeyVerifier ----

    @Test
    fun khv_vf_01_implementsHostKeyVerifier() {
        // Compile-time check: KnownHostsVerifier is assignable to the
        // sshj interface. No body needed.
        val v: net.schmizz.sshj.transport.verification.HostKeyVerifier =
            KnownHostsVerifier(store, host, port)
        assertNotNull(v)
    }

    // ---- KHV-VF-02: first-use enrolls + returns true ----

    @Test
    fun khv_vf_02_firstUseEnrollsAndReturnsTrue() {
        val v = KnownHostsVerifier(store, host, port)
        val key = fakeKey("ssh-ed25519", "first-use-bytes")
        assertTrue("first-use must accept the key", v.verify(host, port, key))
        // The store must now contain the enrolled row.
        val fp = runBlocking { store.get(host, port) }
        assertNotNull("first-use must write to the store", fp)
        assertEquals("ssh-ed25519", fp!!.keyType)
    }

    @Test
    fun khv_vf_02b_firstUseEnrollPreservesFingerprintAcrossReconstruction() {
        // The verifier is meant to be reconstructed per-connect (so the
        // store survives process restart). Confirm the row written by
        // one verifier instance is visible to a fresh instance.
        val first = KnownHostsVerifier(store, host, port)
        val key = fakeKey("ssh-ed25519", "stable-fingerprint")
        first.verify(host, port, key)
        val second = KnownHostsVerifier(store, host, port)
        // A new verifier seeing the same key from a fresh process state
        // must accept (match) — proves the on-disk row is durable.
        assertTrue(second.verify(host, port, key))
    }

    // ---- KHV-VF-03: matching record returns true, store unchanged ----

    @Test
    fun khv_vf_03_matchReturnsTrueAndStoreUntouched() {
        val wire = "AAAABBBB"
        val fp = HostFingerprint("ssh-ed25519", fingerprintBase64(wire))
        runBlocking {
            store.put(host, port, fp)
        }
        val v = KnownHostsVerifier(store, host, port)
        val key = fakeKey("ssh-ed25519", wire)
        assertTrue(v.verify(host, port, key))
        runBlocking {
            val fetched = store.get(host, port)
            assertEquals(fp, fetched)
        }
    }

    // ---- KHV-VF-04: mismatch returns false, store unchanged ----

    @Test
    fun khv_vf_04_fingerprintMismatchReturnsFalseAndStoreUntouched() {
        val oldWire = "old-fp-bytes"
        runBlocking {
            store.put(host, port, HostFingerprint("ssh-ed25519", fingerprintBase64(oldWire)))
        }
        val v = KnownHostsVerifier(store, host, port)
        val newKey = fakeKey("ssh-ed25519", "new-fp")
        assertFalse("fingerprint mismatch must refuse", v.verify(host, port, newKey))
        runBlocking {
            val fetched = store.get(host, port)
            assertEquals(
                "store must keep the OLD fingerprint on mismatch",
                fingerprintBase64(oldWire),
                fetched?.fingerprintBase64,
            )
        }
    }

    // ---- KHV-VF-05: key-type change treated as mismatch ----

    @Test
    fun khv_vf_05_keyTypeChangeTreatedAsMismatch() {
        val wire = "fingerprint-x"
        runBlocking {
            store.put(host, port, HostFingerprint("ssh-rsa", fingerprintBase64(wire)))
        }
        val v = KnownHostsVerifier(store, host, port)
        val rotated = fakeKey("ssh-ed25519", wire)
        assertFalse(
            "key-type change must be treated as a mismatch (no implicit re-enroll)",
            v.verify(host, port, rotated),
        )
    }

    // ---- KHV-VF-06: mismatch surfaces as sshj's OpenHostKeyVerificationException ----

    @Test
    fun khv_vf_06_returnsFalseOnMismatchSoSshjRaisesOpenHostKeyVerification() {
        val expectedWire = "expected-bytes"
        runBlocking {
            store.put(host, port, HostFingerprint("ssh-ed25519", fingerprintBase64(expectedWire)))
        }
        val v = KnownHostsVerifier(store, host, port)
        val mismatch = fakeKey("ssh-ed25519", "rotated-bytes")
        assertFalse(v.verify(host, port, mismatch))
    }

    // ---- Defensive: null algorithm returns false ----

    @Test
    fun khv_returnsFalseWhenAlgorithmIsNull() {
        val v = KnownHostsVerifier(store, host, port)
        val key = fakeKey(null, "anything")
        assertFalse(v.verify(host, port, key))
    }

    // ---- KHV-UX-02: interactive prompt gates first-use + mismatch decisions ----

    @Test
    fun khv_ux_02_firstUseWithPromptDeclinedDoesNotEnrollOrAccept() {
        val prompt = StubPrompt(answer = false)
        val v = KnownHostsVerifier(store, host, port, prompt)
        val key = fakeKey("ssh-ed25519", "first-use-bytes")
        assertFalse("declined first-use prompt must refuse the connection", v.verify(host, port, key))
        assertNull("declined first-use must not write the store", runBlocking { store.get(host, port) })
        assertEquals(1, prompt.callCount)
        assertNull(
            "first-use prompt request must carry no previous fingerprint",
            prompt.lastRequest?.previousFingerprint,
        )
    }

    @Test
    fun khv_ux_02b_firstUseWithPromptApprovedEnrollsAndAccepts() {
        val prompt = StubPrompt(answer = true)
        val v = KnownHostsVerifier(store, host, port, prompt)
        val key = fakeKey("ssh-ed25519", "first-use-bytes")
        assertTrue("approved first-use prompt must accept", v.verify(host, port, key))
        val fp = runBlocking { store.get(host, port) }
        assertNotNull("approval must enroll the fingerprint", fp)
        assertEquals("ssh-ed25519", fp!!.keyType)
    }

    @Test
    fun khv_ux_02c_mismatchWithPromptDeclinedLeavesStoreUntouched() {
        val oldWire = "old-fp-bytes"
        runBlocking { store.put(host, port, HostFingerprint("ssh-ed25519", fingerprintBase64(oldWire))) }
        val prompt = StubPrompt(answer = false)
        val v = KnownHostsVerifier(store, host, port, prompt)
        val newKey = fakeKey("ssh-ed25519", "new-fp")
        assertFalse("declined mismatch prompt must refuse", v.verify(host, port, newKey))
        val fetched = runBlocking { store.get(host, port) }
        assertEquals(
            "store must keep the OLD fingerprint when the user declines",
            fingerprintBase64(oldWire),
            fetched?.fingerprintBase64,
        )
    }

    @Test
    fun khv_ux_02d_mismatchWithPromptApprovedReEnrollsWithNewFingerprint() {
        val oldWire = "old-fp-bytes"
        runBlocking { store.put(host, port, HostFingerprint("ssh-ed25519", fingerprintBase64(oldWire))) }
        val prompt = StubPrompt(answer = true)
        val v = KnownHostsVerifier(store, host, port, prompt)
        val newKey = fakeKey("ssh-ed25519", "new-fp")
        assertTrue("approved mismatch prompt must accept and re-enroll", v.verify(host, port, newKey))
        val fetched = runBlocking { store.get(host, port) }
        assertEquals(
            "store must be overwritten with the NEW fingerprint on approval",
            fingerprintBase64("new-fp"),
            fetched?.fingerprintBase64,
        )
        assertEquals(
            "mismatch prompt request must carry the previously-trusted fingerprint",
            fingerprintBase64(oldWire),
            prompt.lastRequest?.previousFingerprint?.fingerprintBase64,
        )
    }

    @Test
    fun khv_ux_02e_matchingRecordNeverPrompts() {
        val wire = "AAAABBBB"
        runBlocking { store.put(host, port, HostFingerprint("ssh-ed25519", fingerprintBase64(wire))) }
        val prompt = StubPrompt(answer = false) // would fail the test if ever consulted
        val v = KnownHostsVerifier(store, host, port, prompt)
        assertTrue(v.verify(host, port, fakeKey("ssh-ed25519", wire)))
        assertEquals("a matching record must never consult the prompt", 0, prompt.callCount)
    }

    private class StubPrompt(private val answer: Boolean) : HostKeyPrompt {
        var callCount = 0
        var lastRequest: HostKeyPromptRequest? = null

        override suspend fun confirm(request: HostKeyPromptRequest): Boolean {
            callCount++
            lastRequest = request
            return answer
        }
    }

    // ---- findExistingAlgorithms ----

    @Test
    fun findExistingAlgorithms_returnsStoredKeyType() {
        runBlocking {
            store.put(host, port, HostFingerprint("ssh-ed25519", "fp"))
        }
        val v = KnownHostsVerifier(store, host, port)
        assertEquals(listOf("ssh-ed25519"), v.findExistingAlgorithms(host, port))
    }

    @Test
    fun findExistingAlgorithms_emptyForUnknownHost() {
        val v = KnownHostsVerifier(store, host, port)
        assertEquals(emptyList<String>(), v.findExistingAlgorithms(host, port))
    }

    private fun fingerprintBase64(wireString: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return Base64.getEncoder().encodeToString(md.digest(wireString.toByteArray(Charsets.UTF_8)))
    }

    private fun fakeKey(algorithm: String?, wireString: String): PublicKey =
        object : PublicKey {
            override fun getAlgorithm(): String? = algorithm
            override fun getFormat(): String = "fake"
            override fun getEncoded(): ByteArray = wireString.toByteArray()
            override fun toString(): String = wireString
        }
}