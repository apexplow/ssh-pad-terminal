package com.example.sshterminal.ssh.auth

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
 * Key formats we accept:
 *  - PKCS#8 PEM (`BEGIN PRIVATE KEY … END PRIVATE KEY`)
 *  - "Classic" OpenSSH PEM (`BEGIN RSA/EC/DSA PRIVATE KEY …`) — handled by
 *    [OpenSSHKeyFile].
 *  - "New" OpenSSH PEM (`BEGIN OPENSSH PRIVATE KEY …`, OpenSSH 6.5+) —
 *    handled by [OpenSSHKeyV1KeyFile] from the `com.hierynomus.sshj.*`
 *    package (it's the only provider that knows the v1 envelope).
 *  - PuTTY `.ppk` files — handled by [PuTTYKeyFile].
 *
 * Format detection uses [KeyProviderUtil.detectKeyFileFormat] which reads
 * just enough of the file to identify it (no key material leaves the JVM).
 *
 * The path passed in must point at a *plaintext* key file on disk. v1.0
 * does NOT decrypt the key through [com.example.sshterminal.data.crypto.KeyStoreManager]:
 * the decision in Sprint 1.5 was to keep the file plaintext on disk and rely
 * on the Android sandbox (`filesDir/`) for confidentiality. The Keystore-
 * managed AES key is reserved for the password slot (see
 * [com.example.sshterminal.data.prefs.AppPreferences]).
 *
 * [SSH_ANDROID_PITFALL]: SSHJ's key providers internally ask
 * `KeyFactory.getInstance("RSA")` / `getInstance("Ed25519")`. Both lookups
 * must succeed — the system `BC` provider on API 29 is BouncyCastle 1.62,
 * which lacks Ed25519. We register a modern BC explicitly in
 * [com.example.sshterminal.ssh.SshClient] via [com.example.sshterminal.ssh.BouncyCastleBootstrap].
 */
object PublicKeyAuthProvider : SshAuthProvider {
    override fun authenticate(
        client: SSHClient,
        username: String,
        auth: Auth,
    ) {
        require(auth is Auth.PublicKeyAuth) {
            "PublicKeyAuthProvider requires Auth.PublicKeyAuth, got ${auth::class.simpleName}"
        }
        val keyProvider = loadKeyProvider(auth.privateKeyPath)
        client.authPublickey(username, keyProvider)
    }

    /**
     * Auto-detects the key format and constructs the matching provider.
     * Centralised so a test can call this directly with a temp PEM file
     * (see `PublicKeyAuthProviderTest`) without going through the SSH
     * auth driver.
     */
    internal fun loadKeyProvider(path: String): KeyProvider {
        val keyFile = File(path)
        require(keyFile.isFile) { "private key file not found: $path" }
        val format = KeyProviderUtil.detectKeyFileFormat(keyFile)
        val provider: KeyProvider = when (format) {
            KeyFormat.PKCS8 -> PKCS8KeyFile()
            KeyFormat.OpenSSH -> OpenSSHKeyFile()
            KeyFormat.OpenSSHv1 -> OpenSSHKeyV1KeyFile()
            KeyFormat.PuTTY -> PuTTYKeyFile()
            KeyFormat.Unknown -> error("Unknown / unsupported key format for $path")
        }
        // init(File) lives on the FileKeyProvider sub-interface; the
        // parent KeyProvider doesn't know about files. Casting is the
        // documented seam — every concrete provider we construct above
        // implements FileKeyProvider.
        val fileProvider = provider as net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
        // Encrypted key files (passphrase-protected) are out of scope for v1.0;
        // an unencrypted file works without a PasswordFinder.
        fileProvider.init(keyFile)
        return provider
    }
}
