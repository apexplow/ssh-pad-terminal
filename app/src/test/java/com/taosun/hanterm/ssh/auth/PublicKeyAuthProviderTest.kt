package com.taosun.hanterm.ssh.auth

import com.taosun.hanterm.ssh.BouncyCastleBootstrap
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.StringWriter
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.EdECPrivateKey
import java.security.interfaces.RSAPrivateKey
import java.util.Base64

/**
 * Verifies the [PublicKeyAuthProvider] PEM-loading path with both key types
 * v1.0 supports: RSA and Ed25519.
 *
 * Per the Sprint 2 spec: "用 BouncyCastle 的 Ed25519/RSA test vectors,
 * 不要连真 SSH". We don't connect to anything here — we generate a fresh
 * key pair in-process, write it as a PKCS#8 PEM file to a temp dir, and
 * round-trip it through [PublicKeyAuthProvider.loadKeyProvider] (the same
 * call SSHJ makes at auth time when `client.authPublickey(user, kp)` runs).
 *
 * The Ed25519 case is the interesting one: it requires a JCE provider that
 * supports EdECPrivateKeySpec. Android's bundled "BC" on API 29 (~1.62) is
 * too old. [BouncyCastleBootstrap] must register the bundled BC *before*
 * the KeyPairGenerator is instantiated.
 */
class PublicKeyAuthProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun ensureBouncyCastleRegistered() {
        // BouncyCastleBootstrap is idempotent — safe to call multiple times.
        BouncyCastleBootstrap.ensureRegistered()
    }

    @Test
    fun test_loadKeyProvider_rsaPem_producesMatchingPublicKey() {
        val keyPair = generateKeyPair("RSA", 2048)
        val pemFile = writePkcs8Pem(keyPair.private)

        val provider = PublicKeyAuthProvider.loadKeyProvider(pemFile.absolutePath)

        assertNotNull("loaded KeyProvider must not be null", provider)
        // getPublic/getPrivate are the JavaBean-style accessors; using them
        // explicitly avoids the `private` keyword collision in Kotlin
        // property syntax.
        val loadedPublic = provider.public
        assertNotNull("loaded RSA public key must not be null", loadedPublic)
        assertEquals(
            "loaded public key must match the one we wrote",
            keyPair.public,
            loadedPublic,
        )
    }

    @Test
    fun test_loadKeyProvider_ed25519Pem_producesMatchingPublicKey() {
        val keyPair = generateKeyPair("Ed25519", -1)
        val pemFile = writeOpenSshPem(keyPair.private)

        val provider = PublicKeyAuthProvider.loadKeyProvider(pemFile.absolutePath)

        assertNotNull("loaded KeyProvider must not be null", provider)
        val loadedPublic = provider.public
        assertNotNull("loaded Ed25519 public key must not be null", loadedPublic)
        assertEquals(
            "loaded public key must match the Ed25519 pair we wrote",
            keyPair.public,
            loadedPublic,
        )
    }

    @Test
    fun test_loadedEd25519Key_hasEdEcPrivateKeyType() {
        // Sanity: BC's Ed25519 producer must give us a key tagged as
        // EdECPrivateKey — that's the type SSHJ's "ssh-ed25519" kex asks
        // for. If the system provider were used (no Ed25519) we'd see a
        // different concrete class and SSHJ would reject it during auth.
        val keyPair = generateKeyPair("Ed25519", -1)
        val pemFile = writeOpenSshPem(keyPair.private)

        val provider = PublicKeyAuthProvider.loadKeyProvider(pemFile.absolutePath)
        val loadedPrivate = provider.private as? EdECPrivateKey

        assertNotNull(
            "expected the loaded Ed25519 private key to implement EdECPrivateKey",
            loadedPrivate,
        )
    }

    @Test
    fun test_loadedRsaKey_hasRsaPrivateKeyType() {
        val keyPair = generateKeyPair("RSA", 2048)
        val pemFile = writePkcs8Pem(keyPair.private)

        val provider = PublicKeyAuthProvider.loadKeyProvider(pemFile.absolutePath)
        val loadedPrivate = provider.private as? RSAPrivateKey

        assertNotNull(
            "expected the loaded RSA private key to implement RSAPrivateKey",
            loadedPrivate,
        )
        // 2048-bit RSA produces a 256-byte modulus byte-length. If we
        // accidentally round-tripped a 1024-bit key the bit length would
        // change. This pins the keysize contract so the key generation
        // isn't accidentally weakened in a future refactor.
        assertEquals(2048, loadedPrivate?.modulus?.bitLength() ?: 0)
    }

    @Test
    fun test_loadKeyProvider_missingFile_throws() {
        val missing = java.io.File(tempFolder.newFolder("nope"), "absent.pem")

        var caught: Throwable? = null
        try {
            PublicKeyAuthProvider.loadKeyProvider(missing.absolutePath)
        } catch (t: Throwable) {
            caught = t
        }

        assertTrue(
            "expected an error when key file doesn't exist; got $caught",
            caught is IllegalArgumentException || caught is java.io.IOException,
        )
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private fun generateKeyPair(algorithm: String, keySize: Int): KeyPair {
        // BouncyCastleBootstrap ran in @Before, so "Ed25519" resolves through
        // our BC provider. "RSA" works through the system SunRsaSign provider.
        val gen = if (keySize > 0) {
            KeyPairGenerator.getInstance(algorithm).apply { initialize(keySize) }
        } else {
            KeyPairGenerator.getInstance(algorithm)
        }
        return gen.generateKeyPair()
    }

    /**
     * Writes a private key as a PKCS#8 PEM file. Both RSA and Ed25519 keys
     * expose `PrivateKey.getEncoded()` in PKCS#8 format when produced by BC,
     * which is what SSHJ's PKCS8KeyFile expects.
     */
    private fun writePkcs8Pem(privateKey: PrivateKey): java.io.File {
        val pkcs8Bytes = privateKey.encoded
            ?: error("PrivateKey.getEncoded() returned null; provider doesn't support PKCS#8 export")
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs8Bytes)
        val pem = buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            appendLine(b64)
            append("-----END PRIVATE KEY-----")
        }
        return tempFolder.newFile("key_${System.nanoTime()}.pem").apply {
            writeText(pem)
        }
    }

    /**
     * Writes an Ed25519 private key in "new" OpenSSH v1 PEM format
     * (`-----BEGIN OPENSSH PRIVATE KEY-----`, OpenSSH 6.5+). SSHJ 0.40's
     * [net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile] hard-rejects
     * Ed25519 OID 1.3.101.112 with "PKCS8 Private Key Algorithm
     * [1.3.101.112] not supported" (verified against
     * PKCS8KeyFile.java:383 `getKeyAlgorithmObjectIdentifier`), so
     * Ed25519 keys must use the OpenSSH v1 envelope via
     * `OpenSSHKeyV1KeyFile`.
     *
     * BC 1.78's [JcaMiscPEMGenerator] emits `-----BEGIN PRIVATE KEY-----`
     * (PKCS#8) for `EdECPrivateKey` — that's the wrong format for this
     * path. We use BC 1.78's [org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil]
     * to get the raw OpenSSH v1 wire bytes and wrap them in a
     * [org.bouncycastle.util.io.pem.PemObject] with the
     * "OPENSSH PRIVATE KEY" type label, which is what
     * [KeyProviderUtil.detectKeyFileFormat] looks for in 0.40.
     *
     * For RSA and other algorithms, use [writePkcs8Pem] instead.
     */
    private fun writeOpenSshPem(privateKey: PrivateKey): java.io.File {
        require(privateKey is EdECPrivateKey) {
            "writeOpenSshPem is Ed25519-only; use writePkcs8Pem for other algorithms"
        }
        // Parse the PKCS#8 PrivateKeyInfo via BC's ASN.1 parser to
        // extract the 32-byte Ed25519 seed. Doing this directly is more
        // robust than offset-arithmetic on the DER bytes (the prefix
        // length varies with the public-key field encoding in RFC 8410)
        // and avoids depending on EdECPrivateKey.getSeed() which is
        // absent from some test-stub classpaths.
        val pkcs8 = privateKey.encoded
            ?: error("EdECPrivateKey.encoded returned null; provider doesn't support PKCS#8 export")
        val pki = org.bouncycastle.asn1.pkcs.PrivateKeyInfo.getInstance(pkcs8)
        val curvePrivateKey = pki.parsePrivateKey() as org.bouncycastle.asn1.ASN1OctetString
        val seedBytes: ByteArray = curvePrivateKey.octets
        require(seedBytes.size == 32) { "Ed25519 seed must be 32 bytes, got ${seedBytes.size}" }
        // Copy into a fresh ByteArray so the type is unambiguous for
        // the Ed25519PrivateKeyParameters constructor overload resolution.
        val seed: ByteArray = ByteArray(32) { i -> seedBytes[i] }
        val keyParam = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed)
        val openSshBytes = org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
            .encodePrivateKey(keyParam)
        val pemObject = org.bouncycastle.util.io.pem.PemObject("OPENSSH PRIVATE KEY", openSshBytes)
        val sw = StringWriter()
        JcaPEMWriter(sw).use { it.writeObject(pemObject) }
        return tempFolder.newFile("key_${System.nanoTime()}.pem").apply {
            writeText(sw.toString())
        }
    }
}
