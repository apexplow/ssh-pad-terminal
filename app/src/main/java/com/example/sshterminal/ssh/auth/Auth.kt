package com.example.sshterminal.ssh.auth

/**
 * Authentication strategies the SSH client supports in v1.0.
 *
 * Modelled as a sealed class so the [com.example.sshterminal.ssh.SshClient]
 * `when` over the auth variant is exhaustive — adding a new auth type
 * (e.g. keyboard-interactive) forces a compile error at every call site.
 *
 * Per `implementation_plan.md` §"模块划分与边界", the v1.0 surface is:
 *  - password (read out of the Keystore-encrypted blob in [com.example.sshterminal.data.prefs.AppPreferences])
 *  - public-key (PEM file imported via SAF, stored under `filesDir/keys/`)
 *
 * Neither carries the username — that flows through [com.example.sshterminal.ssh.SshClient.connect]
 * as a separate parameter so the same [Auth] instance can be retried against a
 * different user without rebuilding the credential object.
 */
sealed class Auth {
    /** Plain-text password. The caller is responsible for decrypting it first. */
    data class PasswordAuth(val password: String) : Auth()

    /**
     * Path on the local filesystem to a PEM-encoded private key.
     *
     * The path is absolute (resolved by the caller from
     * [com.example.sshterminal.data.prefs.AppPreferences.privateKeyName] +
     * `Context.filesDir/keys/`). SSHJ 0.40's
     * [PublicKeyAuthProvider.loadKeyProvider] reads it directly via
     * [net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil.detectKeyFileFormat]
     * and the corresponding `KeyFile` subclass; we don't pre-parse it.
     */
    data class PublicKeyAuth(val privateKeyPath: String) : Auth()
}
