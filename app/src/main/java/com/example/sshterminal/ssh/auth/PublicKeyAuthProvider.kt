package com.example.sshterminal.ssh.auth

import android.content.Context
import android.util.Log
import com.example.sshterminal.BuildConfig
import com.example.sshterminal.data.crypto.EncryptedPrivateKeyStore
import com.example.sshterminal.data.crypto.KeyStoreManager
import com.example.sshterminal.ssh.SshException
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import java.io.File

/**
 * Loads a PEM-encoded private key (RSA or Ed25519) and registers it with the
 * SSHJ client.
 *
 * Sprint 2.5 / Module 12: encrypted keys (`.pem.enc`) are decrypted to a
 * short-lived temp file under `cacheDir/ssh-pad-key-tmp/` for the duration of
 * the auth call; legacy plaintext `.pem` files are auto-migrated on first auth.
 */
object PublicKeyAuthProvider : SshAuthProvider {
    private const val TEMP_KEY_DIR = "ssh-pad-key-tmp"

    override fun authenticate(
        client: SSHClient,
        username: String,
        auth: Auth,
    ) {
        error(
            "PublicKeyAuthProvider requires application Context for encrypted keys; " +
                "call authenticate(client, username, auth, appContext) from SshClient",
        )
    }

    fun authenticate(
        client: SSHClient,
        username: String,
        auth: Auth,
        appContext: Context,
    ) {
        require(auth is Auth.PublicKeyAuth) {
            "PublicKeyAuthProvider requires Auth.PublicKeyAuth, got ${auth::class.simpleName}"
        }
        val resolvedPath = resolveKeyPath(auth.privateKeyPath, appContext)
        if (resolvedPath.endsWith(".pem.enc")) {
            val tmpDir = tempKeyDir(appContext)
            val tmp = File.createTempFile("key-", ".pem", tmpDir).apply {
                setReadable(true, true)
                setWritable(true, true)
                setExecutable(false, false)
            }
            try {
                val cleartext = decryptKeyPayload(File(resolvedPath))
                try {
                    tmp.writeBytes(cleartext)
                } finally {
                    cleartext.fill(0)
                }
                val keyProvider = loadKeyProvider(tmp.absolutePath)
                client.authPublickey(username, keyProvider)
            } finally {
                EncryptedPrivateKeyStore.secureDeleteBestEffort(tmp)
            }
        } else {
            val keyProvider = loadKeyProvider(resolvedPath)
            client.authPublickey(username, keyProvider)
        }
    }

    internal fun resolveKeyPath(privateKeyPath: String, appContext: Context): String {
        val file = File(privateKeyPath)
        if (file.name.endsWith(".pem.enc")) {
            return file.absolutePath
        }
        val store = EncryptedPrivateKeyStore(appContext)
        val migrated = store.migrateLegacyPlaintextIfNeeded(file.name)
        return migrated?.absolutePath ?: file.absolutePath
    }

    private fun tempKeyDir(appContext: Context): File =
        File(appContext.cacheDir, TEMP_KEY_DIR).apply {
            mkdirs()
            setReadable(true, true)
            setWritable(true, true)
            setExecutable(true, true)
        }

    private fun decryptKeyPayload(encryptedFile: File): ByteArray {
        return try {
            KeyStoreManager.decrypt(encryptedFile.readBytes())
        } catch (t: Throwable) {
            throw SshException(
                "Cannot decrypt private key: device keystore is unavailable. " +
                    "Re-import the key after unlocking the device once.",
                t,
            )
        }
    }

    internal fun loadKeyProvider(path: String): KeyProvider {
        val keyFile = File(path)
        require(keyFile.isFile) { "private key file not found: $path" }
        val format = KeyProviderUtil.detectKeyFileFormat(keyFile)
        if (BuildConfig.DEBUG) {
            Log.d("SshKeyAuth", "loadKeyProvider format=$format")
        }
        // `when` over a Java enum emits "Enum argument can be null in Java,
        // but exhaustive when contains no null branch" in Kotlin 1.9.24 even
        // when the enum is exhaustively listed. The if-else chain sidesteps
        // the warning entirely; the final `else` still catches
        // KeyFormat.Unknown AND any future sshj enum value we don't
        // recognise.
        val provider: KeyProvider = if (format == KeyFormat.PKCS8) {
            PKCS8KeyFile()
        } else if (format == KeyFormat.OpenSSH) {
            OpenSSHKeyFile()
        } else if (format == KeyFormat.OpenSSHv1) {
            OpenSSHKeyV1KeyFile()
        } else if (format == KeyFormat.PuTTY) {
            PuTTYKeyFile()
        } else {
            error("Unknown / unsupported key format for $path")
        }
        val fileProvider = provider as net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
        fileProvider.init(keyFile)
        return provider
    }
}
