package com.apexplow.hanterm.ssh.security

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
 * hashes for the v0 legacy branch). Two fake keys with the same
 * `getAlgorithm` and `toString` are "the same key" from the verifier's
 * perspective.
 *
 * ## Why a [FakeFingerprint]? (#16)
 *
 * As of #16, the verifier delegates fingerprinting to an injected
 * [HostKeyFingerprint] module (production = [CanonicalHostKeyFingerprint]).
 * The TOFU-state-machine assertions below are key-agnostic — what we
 * care about is "given a stored row, what does the verifier decide?",
 * not "what bytes does sshj's KeyType emit?" The real-crypto path is
 * pinned by [HostKeyFingerprintTest] using real BC-generated keypairs;
 * here we use [FakeFingerprint] to keep the TOFU logic decoupled from
 * BouncyCastle / sshj details.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
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

    /** Convenience for "current-format" v1 test rows. */
    private fun v1(keyType: String, fp: String) = HostFingerprint(keyType, fp, algorithmVersion = 1)

    /** Default fake fingerprint — same algo on both sides of the v0/v1 boundary. */
    private val fakeFingerprint: HostKeyFingerprint = FakeFingerprint()

    // ---- KHV-VF-01: implements HostKeyVerifier ----

    @Test
    fun khv_vf_01_implementsHostKeyVerifier() {
        // Compile-time check: KnownHostsVerifier is assignable to the
        // sshj interface. No body needed.
        val v: net.schmizz.sshj.transport.verification.HostKeyVerifier =
            KnownHostsVerifier(store, host, port, fingerprint = fakeFingerprint)
        assertNotNull(v)
    }

    // ---- KHV-VF-02: first-use enrolls + returns true ----

    @Test
    fun khv_vf_02_firstUseEnrollsAndReturnsTrue() {
        val v = KnownHostsVerifier(store, host, port, fingerprint = fakeFingerprint)
        val key = fakeKey("ssh-ed25519", "first-use-bytes")
        assertTrue("first-use must accept the key", v.verify(host, port, key))
        // The store must now contain the enrolled row.
        val fp = runBlocking { store.get(host, port) }
        assertNotNull("first-use must write to the store", fp)
        assertEquals("ssh-ed25519", fp!!.keyType)
        assertEquals(
            "enrolled row must be v1 (current format)",
            1,
            fp.algorithmVersion,
        )
    }

    @Test
    fun khv_vf_02b_firstUseEnrollPreservesFingerprintAcrossReconstruction() {
        // The verifier is meant to be reconstructed per-connect (so the
        // store survives process restart). Confirm the row written by
        // one verifier instance is visible to a fresh instance.
        val first = KnownHostsVerifier(store, host, port, fingerprint = fakeFingerprint)
        val key = fakeKey("ssh-ed25519", "stable-fingerprint")
        first.verify(host, port, key)
        val second = KnownHostsVerifier(store, host, port, fingerprint = fakeFingerprint)
        // A new verifier seeing the same key from a fresh process state
        // must accept (match) — proves the on-disk row is durable.
        assertTrue(second.verify(host, port, key))
    }

    // ---- KHV-VF-03: matching record returns true, store unchanged ----

    @Test
    fun khv_vf_03_matchReturnsTrueAndStoreUntouched() {
        val wire = "AAAABBBB"
        val fp = v1("ssh-ed25519", fingerprintBase64(wire))
        runBlocking {
            store.put(host, port, fp)
        }
        val v = KnownHostsVerifier(store, host, port, fingerprint = fakeFingerprint)
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
            store.put(host, port, v1("ssh-ed25519", fingerprintBase64(oldWire)))
        }
        val v = KnownHostsVerifier(store, host, port, fingerprint = fakeFingerprint)
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
            store.put(host, port, v1("ssh-rsa", fingerprintBase64(wire)))
        }
        val v = KnownHostsVerifier(store, host, port, fingerprint = fakeFingerprint)
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
            store.put(host, port, v1("ssh-ed25519", fingerprintBase64(expectedWire)))
        }
        val v = KnownHostsVerifier(store, host, port, fingerprint = fakeFingerprint)
        val mismatch = fakeKey("ssh-ed25519", "rotated-bytes")
        assertFalse(v.verify(host, port, mismatch))
    }

    // ---- Defensive: null algorithm returns false (mirrors CanonicalHostKeyFingerprint fail-closed) ----

    @Test
    fun khv_returnsFalseWhenAlgorithmIsNull() {
        // Real CanonicalHostKeyFingerprint would call KeyType.fromKey() with
        // a null algorithm → UNKNOWN → IllegalArgumentException → caught
        // upstream and returns false. FakeFingerprint mirrors that behavior
        // (throws on null algorithm) so the test pins the same contract.
        val v = KnownHostsVerifier(store, host, port, fingerprint = fakeFingerprint)
        val key = fakeKey(null, "anything")
        assertFalse(v.verify(host, port, key))
    }

    // ---- KHV-UX-02: interactive prompt gates first-use + mismatch decisions ----

    @Test
    fun khv_ux_02_firstUseWithPromptDeclinedDoesNotEnrollOrAccept() {
        val prompt = StubPrompt(answer = false)
        val v = KnownHostsVerifier(store, host, port, prompt = prompt, fingerprint = fakeFingerprint)
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
        val v = KnownHostsVerifier(store, host, port, prompt = prompt, fingerprint = fakeFingerprint)
        val key = fakeKey("ssh-ed25519", "first-use-bytes")
        assertTrue("approved first-use prompt must accept", v.verify(host, port, key))
        val fp = runBlocking { store.get(host, port) }
        assertNotNull("approval must enroll the fingerprint", fp)
        assertEquals("ssh-ed25519", fp!!.keyType)
    }

    @Test
    fun khv_ux_02c_mismatchWithPromptDeclinedLeavesStoreUntouched() {
        val oldWire = "old-fp-bytes"
        runBlocking { store.put(host, port, v1("ssh-ed25519", fingerprintBase64(oldWire))) }
        val prompt = StubPrompt(answer = false)
        val v = KnownHostsVerifier(store, host, port, prompt = prompt, fingerprint = fakeFingerprint)
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
        runBlocking { store.put(host, port, v1("ssh-ed25519", fingerprintBase64(oldWire))) }
        val prompt = StubPrompt(answer = true)
        val v = KnownHostsVerifier(store, host, port, prompt = prompt, fingerprint = fakeFingerprint)
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
        runBlocking { store.put(host, port, v1("ssh-ed25519", fingerprintBase64(wire))) }
        val prompt = StubPrompt(answer = false) // would fail the test if ever consulted
        val v = KnownHostsVerifier(store, host, port, prompt = prompt, fingerprint = fakeFingerprint)
        assertTrue(v.verify(host, port, fakeKey("ssh-ed25519", wire)))
        assertEquals("a matching record must never consult the prompt", 0, prompt.callCount)
    }

    // ---- v0 → v1 auto-migration (#16) ----

    @Test
    fun khv_v0_legacyRowSameKeyAutoMigratesToV1() {
        // Pre-populate a v0 (pre-#16) row. The fingerprint was computed
        // the old way (toString-hash), but in this test the fake key's
        // toString() == the wire bytes, so legacyFingerprint(wire) ==
        // fingerprintBase64(wire) == FakeFingerprint.compute(key). That
        // coincidence is what makes the v0→v1 auto-migrate work: the
        // verifier recomputes the LEGACY fingerprint from the current
        // key and compares against the stored v0 value; if they match,
        // it knows it's the same key and rewrites the row as v1.
        val wire = "stable-fingerprint"
        val legacyRow = HostFingerprint(
            keyType = "ssh-ed25519",
            fingerprintBase64 = fingerprintBase64(wire),
            algorithmVersion = 0,
        )
        runBlocking { store.put(host, port, legacyRow) }

        val v = KnownHostsVerifier(store, host, port, fingerprint = fakeFingerprint)
        val key = fakeKey("ssh-ed25519", wire)
        assertTrue(
            "v0 row matching the current key must auto-migrate (no prompt) and accept",
            v.verify(host, port, key),
        )

        // The row must have been rewritten in place as v1.
        val fetched = runBlocking { store.get(host, port) }
        assertNotNull(fetched)
        assertEquals(
            "v0 row must have been rewritten with algorithmVersion = 1",
            1,
            fetched!!.algorithmVersion,
        )
        assertEquals("ssh-ed25519", fetched.keyType)
        assertEquals(
            "fingerprint and keyType are unchanged on auto-migrate",
            fingerprintBase64(wire),
            fetched.fingerprintBase64,
        )
    }

    @Test
    fun khv_v0_legacyRowDifferentKeyPromptsAsMismatch() {
        // Pre-populate a v0 row for a different key. The verifier must
        // NOT auto-migrate (the v0 fingerprint won't match) — it must
        // fall through to the existing mismatch path. With a declining
        // prompt, the store stays untouched and the connection is refused.
        val legacyRow = HostFingerprint(
            keyType = "ssh-ed25519",
            fingerprintBase64 = fingerprintBase64("old-bytes"),
            algorithmVersion = 0,
        )
        runBlocking { store.put(host, port, legacyRow) }

        val prompt = StubPrompt(answer = false)
        val v = KnownHostsVerifier(
            store = store,
            host = host,
            port = port,
            prompt = prompt,
            fingerprint = fakeFingerprint,
        )
        val newKey = fakeKey("ssh-ed25519", "new-bytes")
        assertFalse(
            "v0 row with mismatching key + declined prompt must refuse",
            v.verify(host, port, newKey),
        )
        val fetched = runBlocking { store.get(host, port) }
        assertEquals(
            "store must keep the OLD v0 fingerprint on declined mismatch",
            0,
            fetched?.algorithmVersion,
        )
        assertEquals(
            fingerprintBase64("old-bytes"),
            fetched?.fingerprintBase64,
        )
        // The prompt was consulted (with the v0 row as previousFingerprint).
        assertEquals(1, prompt.callCount)
        assertEquals(
            "mismatch prompt must carry the stored v0 fingerprint as previous",
            fingerprintBase64("old-bytes"),
            prompt.lastRequest?.previousFingerprint?.fingerprintBase64,
        )
    }

    @Test
    fun khv_unknownKeyTypeRefuses() {
        // The fingerprint module throws (mimicking CanonicalHostKeyFingerprint
        // on an UNKNOWN KeyType). The verifier must catch and return false
        // (fail-closed) without writing the store.
        val throwingFingerprint = FakeFingerprint(throwing = true)
        val v = KnownHostsVerifier(store, host, port, fingerprint = throwingFingerprint)
        val key = fakeKey("ssh-ed25519", "any-bytes")
        assertFalse(
            "UNKNOWN key type (fingerprint throws) must refuse the connection",
            v.verify(host, port, key),
        )
        val fetched = runBlocking { store.get(host, port) }
        assertNull("UNKNOWN key must not enroll anything", fetched)
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
            store.put(host, port, v1("ssh-ed25519", "fp"))
        }
        val v = KnownHostsVerifier(store, host, port, fingerprint = fakeFingerprint)
        assertEquals(listOf("ssh-ed25519"), v.findExistingAlgorithms(host, port))
    }

    @Test
    fun findExistingAlgorithms_emptyForUnknownHost() {
        val v = KnownHostsVerifier(store, host, port, fingerprint = fakeFingerprint)
        assertEquals(emptyList<String>(), v.findExistingAlgorithms(host, port))
    }

    // ---- Test fixtures ----

    private fun fingerprintBase64(wireString: String): String =
        sha256Base64(wireString.toByteArray(Charsets.UTF_8))

    private fun sha256Base64(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return Base64.getEncoder().encodeToString(md.digest(bytes))
    }

    private fun fakeKey(algorithm: String?, wireString: String): PublicKey =
        object : PublicKey {
            override fun getAlgorithm(): String? = algorithm
            override fun getFormat(): String = "fake"
            override fun getEncoded(): ByteArray = wireString.toByteArray()
            override fun toString(): String = wireString
        }

    /**
     * Test-only [HostKeyFingerprint] for [KnownHostsVerifier] TOFU tests.
     * Mirrors the real [CanonicalHostKeyFingerprint]'s contract closely
     * enough to keep the verifier's logic pin-able without involving
     * BouncyCastle / sshj's KeyType:
     *
     *  - `compute(key)` returns a [FingerprintResult] with the key's
     *    algorithm name, a SHA-256/Base64 hash of `key.toString()`, and
     *    `algorithmVersion = 1` (matching the production module).
     *  - Throws [IllegalArgumentException] on a null algorithm — the
     *    same fail-closed contract the production module enforces via
     *    `KeyType.UNKNOWN` + `require`.
     *  - When constructed with `throwing = true`, throws unconditionally
     *    — used by the `khv_unknownKeyTypeRefuses` test to verify the
     *    verifier's exception handling.
     */
    private class FakeFingerprint(
        override val currentVersion: Int = 1,
        private val throwing: Boolean = false,
    ) : HostKeyFingerprint {
        override fun compute(publicKey: PublicKey): FingerprintResult {
            if (throwing) {
                throw IllegalArgumentException("FakeFingerprint: configured to throw")
            }
            val algorithm = publicKey.algorithm
                ?: throw IllegalArgumentException("FakeFingerprint: null algorithm (mirrors CanonicalHostKeyFingerprint UNKNOWN fail-closed)")
            val fingerprintBase64 = sha256Base64(publicKey.toString().toByteArray(Charsets.UTF_8))
            return FingerprintResult(
                keyType = algorithm,
                fingerprintBase64 = fingerprintBase64,
                algorithmVersion = currentVersion,
            )
        }

        private fun sha256Base64(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            return Base64.getEncoder().encodeToString(md.digest(bytes))
        }
    }
}
