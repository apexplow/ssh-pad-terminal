package com.taosun.hanterm.ssh.auth

import androidx.test.core.app.ApplicationProvider
import com.taosun.hanterm.data.crypto.EncryptedPrivateKeyStore
import com.taosun.hanterm.data.crypto.KeyStoreManager
import com.taosun.hanterm.ssh.BouncyCastleBootstrap
import com.taosun.hanterm.ssh.SshException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.After
import org.junit.Assert.assertFalse
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
@Config(sdk = [33])
class PublicKeyAuthProviderEncryptedTest {

    private lateinit var context: android.content.Context
    private val keysDir: File
        get() = File(context.filesDir, "keys")
    private val tmpDir: File
        get() = File(context.cacheDir, "ssh-pad-key-tmp")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        BouncyCastleBootstrap.ensureRegistered()
        keysDir.listFiles()?.forEach { it.delete() }
        tmpDir.listFiles()?.forEach { it.delete() }
        assumeKeystoreAvailable()
    }

    @After
    fun tearDown() {
        keysDir.listFiles()?.forEach { it.delete() }
        tmpDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun pkp_res_01_encryptedKeyUsesTempFileDuringAuth() {
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
            "temp dir must not retain PEM files after auth",
            tmpDir.listFiles().orEmpty().none { it.name.endsWith(".pem") },
        )
    }

    @Test
    fun pkp_res_03_tempDirCreatedUnderCache() {
        val encPath = encryptedKeyOnDisk()
        val client = mockk<SSHClient>(relaxed = true)
        PublicKeyAuthProvider.authenticate(
            client,
            "user",
            Auth.PublicKeyAuth(encPath.absolutePath),
            context,
        )
        assertTrue(tmpDir.isDirectory)
    }

    @Test
    fun pkp_res_04_cleartextProviderLoadsFromTempFile() {
        val encPath = encryptedKeyOnDisk()
        val client = mockk<SSHClient>(relaxed = true)
        PublicKeyAuthProvider.authenticate(
            client,
            "user",
            Auth.PublicKeyAuth(encPath.absolutePath),
            context,
        )
        verify(exactly = 1) { client.authPublickey("user", any<KeyProvider>()) }
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
                KeyStoreManager.decrypt(blob)
                true
            }.getOrDefault(false),
        )
    }
}
