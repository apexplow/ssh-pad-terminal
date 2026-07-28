package com.apexplow.hanterm.ssh.security

import com.apexplow.hanterm.ssh.BouncyCastleBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey

/**
 * Tests for [HostKeyFingerprint] — pins the canonical-wire-bytes contract from
 * issue #16. This is the **primary seam** for the module: the production
 * implementation [CanonicalHostKeyFingerprint] is fed real BC-generated
 * Ed25519 + RSA keypairs and asserted to produce stable, SSH-named, versioned
 * fingerprints that match the format `ssh-keygen -lf -E sha256` reports.
 *
 * We deliberately do **not** shell out to `ssh-keygen` in JUnit (CLAUDE.md
 * "no external tools in app/src/test/"). Stability + determinism + the
 * JCA→SSH algorithm-name shift are the assertions; matching `ssh-keygen`
 * output is verified manually on-device before merge.
 *
 * Pattern mirrors `PublicKeyAuthProviderTest.ensureBouncyCastleRegistered()`
 * — we need BC registered for `KeyPairGenerator.getInstance("Ed25519")` to
 * resolve (Robolectric's sandbox is one place where `Security.insertProviderAt`
 * throws; the bootstrap swallows that, so a unit test still works).
 */
class HostKeyFingerprintTest {

    @Before
    fun ensureBouncyCastleRegistered() {
        BouncyCastleBootstrap.ensureRegistered()
    }

    private val fingerprint: HostKeyFingerprint = CanonicalHostKeyFingerprint()

    // ---- Determinism: same key → same fingerprint ----

    @Test
    fun ed25519_key_yieldsStableFingerprint() {
        val key = generateKeyPair("Ed25519", -1).public
        val a = fingerprint.compute(key)
        val b = fingerprint.compute(key)
        assertEquals(
            "compute() must be deterministic for the same key",
            a.fingerprintBase64,
            b.fingerprintBase64,
        )
        assertEquals("keyType must also be deterministic", a.keyType, b.keyType)
    }

    @Test
    fun rsa_key_yieldsStableFingerprint() {
        val key = generateKeyPair("RSA", 2048).public
        val a = fingerprint.compute(key)
        val b = fingerprint.compute(key)
        assertEquals(
            "compute() must be deterministic for the same RSA key",
            a.fingerprintBase64,
            b.fingerprintBase64,
        )
    }

    @Test
    fun ed25519_and_rsa_yieldDifferentFingerprints() {
        // Sanity: the module is actually keying on the wire bytes, not returning
        // a constant. Two distinct key types should never collide.
        val ed = fingerprint.compute(generateKeyPair("Ed25519", -1).public)
        val rsa = fingerprint.compute(generateKeyPair("RSA", 2048).public)
        assertNotEquals(
            "Ed25519 and RSA fingerprints must differ",
            ed.fingerprintBase64,
            rsa.fingerprintBase64,
        )
    }

    // ---- JCA → SSH algorithm-name shift ----

    @Test
    fun ed25519_keyTypeIsSshName() {
        val key = generateKeyPair("Ed25519", -1).public
        val result = fingerprint.compute(key)
        assertEquals(
            "Ed25519 keys must surface as 'ssh-ed25519', not the JCA 'Ed25519'",
            "ssh-ed25519",
            result.keyType,
        )
    }

    @Test
    fun rsa_keyTypeIsSshName() {
        val key = generateKeyPair("RSA", 2048).public
        val result = fingerprint.compute(key)
        assertEquals(
            "RSA keys must surface as 'ssh-rsa', not the JCA 'RSA'",
            "ssh-rsa",
            result.keyType,
        )
    }

    // ---- algorithmVersion is the module's currentVersion ----

    @Test
    fun algorithmVersion_isCurrent() {
        val key = generateKeyPair("Ed25519", -1).public
        val result = fingerprint.compute(key)
        assertEquals(
            "FingerprintResult.algorithmVersion must match the module's currentVersion",
            fingerprint.currentVersion,
            result.algorithmVersion,
        )
        assertEquals(
            "Sanity: currentVersion is pinned at 1 for SHA-256/wire-bytes",
            1,
            fingerprint.currentVersion,
        )
    }

    // ---- Fail-closed on UNKNOWN key types ----

    @Test
    fun unknownKeyType_throws() {
        // A PublicKey whose algorithm name sshj cannot classify as a known
        // KeyType. "DH" is not an sshj host key type (it's a KEX algorithm).
        val unknownKey = object : PublicKey {
            override fun getAlgorithm(): String = "DH"
            override fun getFormat(): String = "fake"
            override fun getEncoded(): ByteArray = byteArrayOf(1, 2, 3)
        }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            fingerprint.compute(unknownKey)
        }
        // The message should be useful for debugging — must mention the key type.
        assertTrue(
            "Exception message must reference the unsupported key type, was: ${ex.message}",
            ex.message?.contains("DH") == true,
        )
    }

    // ---- Fixture helpers ----

    private fun generateKeyPair(algorithm: String, keySize: Int): KeyPair {
        val gen = if (keySize > 0) {
            KeyPairGenerator.getInstance(algorithm).apply { initialize(keySize) }
        } else {
            KeyPairGenerator.getInstance(algorithm)
        }
        return gen.generateKeyPair()
    }
}
