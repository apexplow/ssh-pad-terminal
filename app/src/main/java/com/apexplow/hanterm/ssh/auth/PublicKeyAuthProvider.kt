package com.apexplow.hanterm.ssh.auth

import android.content.Context
import android.util.Log
import com.apexplow.hanterm.BuildConfig
import com.apexplow.hanterm.data.crypto.EncryptedPrivateKeyStore
import com.apexplow.hanterm.data.crypto.KeyStoreManager
import com.apexplow.hanterm.ssh.SshException
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import java.io.File
import java.io.StringReader

/**
 * Loads a PEM-encoded private key (RSA or Ed25519) and registers it with the
 * SSHJ client.
 *
 * ## On-disk layout
 *
 * Encrypted keys (`.pem.enc`) are decrypted on demand to **in-memory** bytes
 * (AES-GCM via Android Keystore) and parsed by sshj's
 * [FileKeyProvider.init] overload that takes a [StringReader]. No plaintext
 * PEM is ever written to disk; the legacy `cacheDir/ssh-pad-key-tmp/`
 * directory that the pre-#35 implementation used for a temp file has been
 * removed, so an OOM-kill / force-stop can no longer leak a plaintext copy.
 * Plaintext `.pem` files (the pre-Plan-C vault, auto-migrated on first auth)
 * are still read straight off disk — those are plaintext at rest by design
 * until [EncryptedPrivateKeyStore.migrateLegacyPlaintextIfNeeded] rewrites
 * them as `.pem.enc`.
 *
 * ## Format detection
 *
 * SSHJ's public [KeyProviderUtil.detectKeyFileFormat] takes a [File], so we
 * replicate the same first-line sniffing in [detectKeyFormat] on the
 * in-memory bytes. The logic mirrors sshj 0.40's implementation verbatim
 * (PEM armoring header + the `PuTTY-User-Key-File-2:` sentinel); any
 * divergence should be a deliberate one.
 */
object PublicKeyAuthProvider : SshAuthProvider {

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
            // Issue #35: decrypt into memory only. sshj's FileKeyProvider.init
            // overload that takes a Reader parses from the bytes we hand it;
            // we never touch a temp file.
            val cleartext = decryptKeyPayload(File(resolvedPath))
            try {
                val keyProvider = loadKeyProviderFromBytes(cleartext)
                client.authPublickey(username, keyProvider)
            } finally {
                cleartext.fill(0)
            }
        } else {
            // Legacy plaintext `.pem` on disk — file-based path is correct
            // here because the plaintext is already at rest by definition.
            // migrateLegacyPlaintextIfNeeded has already rewritten this file
            // as `.pem.enc` (and zeroed / deleted the plaintext) when the
            // user upgraded from a pre-Plan-C build, so we don't expect to
            // hit this branch often.
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

    /**
     * Replicates [KeyProviderUtil.detectKeyFileFormat] on in-memory bytes.
     *
     * sshj's public API only takes a [File], so when we want to skip the
     * temp-file round-trip we have to do the detection ourselves. The logic
     * mirrors sshj 0.40:
     *  - `PuTTY-User-Key-File-…` sentinel → PuTTY
     *  - PEM armoring with `OPENSSH PRIVATE KEY` token → OpenSSHv1
     *  - PEM armoring with `ENCRYPTED` token → PKCS8 (encrypted)
     *  - PEM armoring with `PRIVATE KEY` token → OpenSSH (legacy PEM)
     *  - anything else → Unknown (caller surfaces an error)
     *
     * Only the first line is consulted; that's all sshj looks at. Reading
     * more would risk pulling in wrapped headers (PuTTY has none) and is
     * unnecessary.
     */
    internal fun detectKeyFormat(cleartext: ByteArray): KeyFormat {
        val head = String(
            cleartext,
            0,
            minOf(cleartext.size, 256),
            Charsets.UTF_8,
        )
        val firstLine = head.lineSequence().firstOrNull()?.trim()
            ?: return KeyFormat.Unknown
        return when {
            firstLine.startsWith("PuTTY-User-Key-File") -> KeyFormat.PuTTY
            firstLine.startsWith("---- BEGIN") || firstLine.startsWith("-----BEGIN") -> {
                when {
                    firstLine.contains("OPENSSH PRIVATE KEY") -> KeyFormat.OpenSSHv1
                    firstLine.contains("ENCRYPTED") -> KeyFormat.PKCS8
                    firstLine.contains("PRIVATE KEY") -> KeyFormat.OpenSSH
                    else -> KeyFormat.Unknown
                }
            }
            else -> KeyFormat.Unknown
        }
    }

    /**
     * Loads a [KeyProvider] directly from decrypted PEM bytes — no temp
     * file, no cache-dir write. The caller is responsible for zeroing
     * [cleartext] once auth is finished.
     */
    internal fun loadKeyProviderFromBytes(cleartext: ByteArray): KeyProvider {
        val format = detectKeyFormat(cleartext)
        if (BuildConfig.DEBUG) {
            Log.d("SshKeyAuth", "loadKeyProviderFromBytes format=$format")
        }
        val provider = providerFor(format, source = "<in-memory>")
        (provider as FileKeyProvider).init(
            StringReader(String(cleartext, Charsets.UTF_8)),
        )
        return provider
    }

    /**
     * File-based path for the legacy plaintext `.pem` on-disk layout
     * (pre-Plan-C vault). New keys use [loadKeyProviderFromBytes].
     */
    internal fun loadKeyProvider(path: String): KeyProvider {
        val keyFile = File(path)
        require(keyFile.isFile) { "private key file not found: $path" }
        val format = KeyProviderUtil.detectKeyFileFormat(keyFile)
        if (BuildConfig.DEBUG) {
            Log.d("SshKeyAuth", "loadKeyProvider format=$format")
        }
        val provider = providerFor(format, source = path)
        (provider as FileKeyProvider).init(keyFile)
        return provider
    }

    /**
     * `when` over a Java enum emits "Enum argument can be null in Java,
     * but exhaustive when contains no null branch" in Kotlin 1.9.24 even
     * when the enum is exhaustively listed. The if-else chain sidesteps
     * the warning; the final `else` still catches [KeyFormat.Unknown] AND
     * any future sshj enum value we don't recognise.
     */
    private fun providerFor(format: KeyFormat, source: String): KeyProvider =
        if (format == KeyFormat.PKCS8) {
            PKCS8KeyFile()
        } else if (format == KeyFormat.OpenSSH) {
            OpenSSHKeyFile()
        } else if (format == KeyFormat.OpenSSHv1) {
            OpenSSHKeyV1KeyFile()
        } else if (format == KeyFormat.PuTTY) {
            PuTTYKeyFile()
        } else {
            error("Unknown / unsupported key format for $source")
        }
}