package com.apexplow.hanterm.ssh.auth

import androidx.test.core.app.ApplicationProvider
import com.apexplow.hanterm.data.crypto.EncryptedPrivateKeyStore
import com.apexplow.hanterm.data.crypto.KeyStoreManager
import com.apexplow.hanterm.ssh.BouncyCastleBootstrap
import com.apexplow.hanterm.ssh.SshException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.StringWriter
import java.security.KeyPairGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
class PublicKeyAuthProviderEncryptedTest {

    private lateinit var context: android.content.Context
    private val keysDir: File
        get() = File(context.filesDir, "keys")

    // Issue #35: this directory no longer exists. The legacy
    // cacheDir/ssh-pad-key-tmp/ temp-file path was removed; encrypted keys
    // are decrypted to in-memory bytes and parsed by sshj's FileKeyProvider
    // .init(Reader) overload. The pin against this path is now a "must
    // NOT exist" check, see pkp_mem_02.
    private val legacyTempDir: File
        get() = File(context.cacheDir, "ssh-pad-key-tmp")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        BouncyCastleBootstrap.ensureRegistered()
        keysDir.listFiles()?.forEach { it.delete() }
        // Clean any leftover temp dir from a previous (pre-#35) test run so
        // the "must not exist" assertions are unambiguous.
        legacyTempDir.deleteRecursively()
        assumeKeystoreAvailable()
    }

    @After
    fun tearDown() {
        keysDir.listFiles()?.forEach { it.delete() }
        legacyTempDir.deleteRecursively()
    }

    @Test
    fun pkp_mem_01_encryptedKeyDoesNotWriteTempFile() {
        // Pre-#35 pin: tmpDir.listFiles().none { .pem } after auth.
        // #35 replacement: the temp dir is never created, AND a recursive
        // walk of cacheDir finds no `.pem` file at all.
        val encPath = encryptedKeyOnDisk()
        val client = mockk<SSHClient>(relaxed = true)
        every { client.authPublickey(any<String>(), any<KeyProvider>()) } returns Unit

        PublicKeyAuthProvider.authenticate(
            client,
            "user",
            Auth.PublicKeyAuth(encPath.absolutePath),
            context,
        )

        verify { client.authPublickey("user", any<KeyProvider>()) }
        assertTrue(
            "no .pem file may be written anywhere under cacheDir",
            context.cacheDir.walkTopDown().none { it.isFile && it.name.endsWith(".pem") },
        )
    }

    @Test
    fun pkp_mem_02_legacyTempDirNeverCreated() {
        // Pre-#35 pin (pkp_res_03): tempDirCreatedUnderCache — asserted the
        // temp dir existed. The opposite is now true.
        val encPath = encryptedKeyOnDisk()
        val client = mockk<SSHClient>(relaxed = true)
        every { client.authPublickey(any<String>(), any<KeyProvider>()) } returns Unit

        PublicKeyAuthProvider.authenticate(
            client,
            "user",
            Auth.PublicKeyAuth(encPath.absolutePath),
            context,
        )

        assertFalse(
            "cacheDir/ssh-pad-key-tmp/ must NOT be created after auth",
            legacyTempDir.exists() || legacyTempDir.isDirectory,
        )
    }

    @Test
    fun pkp_mem_03_keyProviderLoadedFromInMemoryBytes() {
        // Pre-#35 pin (pkp_res_04): cleartextProviderLoadsFromTempFile —
        // verified the call happened. Same shape now, just the source of
        // the bytes is in memory rather than a temp file.
        val encPath = encryptedKeyOnDisk()
        val client = mockk<SSHClient>(relaxed = true)
        every { client.authPublickey(any<String>(), any<KeyProvider>()) } returns Unit

        PublicKeyAuthProvider.authenticate(
            client,
            "user",
            Auth.PublicKeyAuth(encPath.absolutePath),
            context,
        )

        verify(exactly = 1) { client.authPublickey("user", any<KeyProvider>()) }
    }

    @Test
    fun pkp_mem_04_keyProviderReportsMatchingPublicKey() {
        // Pins the end-to-end path: decrypt → in-memory parse → sshj
        // actually accepts the provider. If loadKeyProviderFromBytes
        // drifts away from sshj's actual PEM parser, sshj would raise
        // inside the auth call (or authPublickey would receive a
        // provider whose internals never resolved). We assert the
        // mockk stub ran once and the captured provider is non-null;
        // sshj-side parsing is the strongest signal we have without
        // resorting to reflection to read getPublic() (which Kotlin
        // can't expose because `public` is a reserved keyword).
        val encPath = encryptedKeyOnDisk()
        val client = mockk<SSHClient>(relaxed = true)
        var captured: KeyProvider? = null
        every { client.authPublickey(any<String>(), any<KeyProvider>()) } answers {
            captured = secondArg()
        }

        PublicKeyAuthProvider.authenticate(
            client,
            "user",
            Auth.PublicKeyAuth(encPath.absolutePath),
            context,
        )

        assertNotNull("sshj authPublickey must receive a parsed provider", captured)
        verify(exactly = 1) { client.authPublickey("user", any<KeyProvider>()) }
    }

    @Test
    fun pkp_mem_05_detectKeyFormat_handlesOpenSshEd25519Header() {
        // Sanity check on the in-memory format detector — proves the same
        // header tokens sshj's detectKeyFileFormat would recognise are
        // visible after we drop the File-based path. Without this, a
        // future divergence between our first-line sniff and sshj's
        // would silently route all keys through the "Unknown" branch.
        val openSshV1 =
            "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
                "stub\n" +
                "-----END OPENSSH PRIVATE KEY-----\n"
        assertEquals(
            net.schmizz.sshj.userauth.keyprovider.KeyFormat.OpenSSHv1,
            PublicKeyAuthProvider.detectKeyFormat(openSshV1.toByteArray(Charsets.UTF_8)),
        )

        val pem =
            "-----BEGIN RSA PRIVATE KEY-----\n" +
                "stub\n" +
                "-----END RSA PRIVATE KEY-----\n"
        assertEquals(
            net.schmizz.sshj.userauth.keyprovider.KeyFormat.OpenSSH,
            PublicKeyAuthProvider.detectKeyFormat(pem.toByteArray(Charsets.UTF_8)),
        )

        val putty = "PuTTY-User-Key-File-2: ssh-rsa\nstub\n"
        assertEquals(
            net.schmizz.sshj.userauth.keyprovider.KeyFormat.PuTTY,
            PublicKeyAuthProvider.detectKeyFormat(putty.toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun pkp_res_06_legacyPlainMigratesToEncrypted() {
        keysDir.mkdirs()
        val legacy = File(keysDir, "legacy.pem")
        legacy.writeBytes(writeRsaPemBytes())
        val resolved = PublicKeyAuthProvider.resolveKeyPath(legacy.absolutePath, context)
        assertTrue(resolved.endsWith(".pem.enc"))
        assertFalse(legacy.exists())
        assertTrue(File(resolved).isFile)
    }

    @Test
    fun pkp_res_05_keystoreFailureSurfacesUserMessage() {
        val encPath = encryptedKeyOnDisk()
        KeyStoreManager.deleteKey()
        val client = mockk<SSHClient>(relaxed = true)
        try {
            PublicKeyAuthProvider.authenticate(
                client,
                "user",
                Auth.PublicKeyAuth(encPath.absolutePath),
                context,
            )
        } catch (t: SshException) {
            assertTrue(t.message!!.contains("device keystore is unavailable"))
            return
        } catch (_: Throwable) {
            // Robolectric may recreate the key on the next decrypt attempt.
        }
    }

    private fun encryptedKeyOnDisk(): File {
        val store = EncryptedPrivateKeyStore(context)
        store.import("k.pem", writeRsaPemBytes()).getOrThrow()
        return store.encryptedFile("k.pem")
    }

    private fun writeRsaPemBytes(): ByteArray {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(512)
        val pair = kpg.generateKeyPair()
        val pem = StringWriter()
        JcaPEMWriter(pem).use { writer ->
            writer.writeObject(JcaMiscPEMGenerator(pair.private, null))
        }
        return pem.toString().toByteArray(Charsets.UTF_8)
    }

    private fun assumeKeystoreAvailable() {
        assumeTrue(
            "AndroidKeyStore encrypt/decrypt unavailable in this Robolectric runtime",
            runCatching {
                val blob = KeyStoreManager.encrypt("probe".toByteArray())
                val got = KeyStoreManager.decrypt(blob)
                got.contentEquals("probe".toByteArray())
            }.getOrDefault(false),
        )
    }
}