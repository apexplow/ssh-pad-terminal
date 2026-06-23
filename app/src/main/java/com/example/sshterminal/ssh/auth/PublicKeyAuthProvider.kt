package com.example.sshterminal.ssh.auth

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.keyprovider.KeyPairUtils

/**
 * Loads a PEM-encoded private key (RSA or Ed25519) and registers it with the
 * SSHJ client.
 *
 * Key formats we accept:
 *  - PKCS#8 PEM ("BEGIN PRIVATE KEY … END PRIVATE KEY")
 *  - OpenSSH "new" PEM ("BEGIN OPENSSH PRIVATE KEY …")
 *  - RSA / DSA in PKCS#1 PEM (RSA auto-converted by BC)
 *
 * The path passed in must point at a *plaintext* PEM file on disk. v1.0 does
 * NOT decrypt the PEM through [com.example.sshterminal.data.crypto.KeyStoreManager]:
 * the decision in Sprint 1.5 was to keep the file plaintext on disk and rely on
 * the Android sandbox (`filesDir/`) for confidentiality. The Keystore-managed
 * AES key is reserved for the password slot (see [com.example.sshterminal.data.prefs.AppPreferences]).
 *
 * [SSH_ANDROID_PITFALL]: SSHJ's [KeyPairUtils.loadKeyPair] internally asks
 * `KeyFactory.getInstance("RSA")` / `getInstance("Ed25519")`. Both lookups
 * must succeed — the system `BC` provider on API 29 is BouncyCastle 1.62,
 * which lacks Ed25519. We register a modern BC explicitly in
 * [com.example.sshterminal.ssh.SshClient].
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
        val keyPair = KeyPairUtils.loadKeyPair(auth.privateKeyPath)
        client.authPublicKey(username, keyPair)
    }
}
