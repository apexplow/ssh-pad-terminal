package com.taosun.hanterm.ssh.auth

/**
 * Authentication strategies the SSH client supports in v1.0.
 *
 * Modelled as a sealed class so the [com.taosun.hanterm.ssh.SshClient]
 * `when` over the auth variant is exhaustive — adding a new auth type
 * (e.g. keyboard-interactive) forces a compile error at every call site.
 *
 * Per `implementation_plan.md` §"模块划分与边界", the v1.0 surface is:
 *  - password (read out of the Keystore-encrypted blob in [com.taosun.hanterm.data.prefs.AppPreferences])
 *  - public-key (PEM file imported via SAF, stored under `filesDir/keys/`)
 *
 * Neither carries the username — that flows through [com.taosun.hanterm.ssh.SshClient.connect]
 * as a separate parameter so the same [Auth] instance can be retried against a
 * different user without rebuilding the credential object.
 */
sealed class Auth {
    /**
     * Plain-text password as a mutable [CharArray] so it can be zeroed after use.
     *
     * The caller is responsible for decrypting it first. [PasswordAuthProvider]
     * clears the array in `finally` after calling sshj; the ViewModel clears the
     * intermediate ByteArray while decoding from Keystore.
     */
    class PasswordAuth(val password: CharArray) : Auth() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is PasswordAuth && password.contentEquals(other.password))

        override fun hashCode(): Int = password.contentHashCode()

        override fun toString(): String = "PasswordAuth(length=${password.size})"
    }

    /**
     * Path on the local filesystem to a PEM-encoded private key.
     *
     * The path is absolute (resolved by the caller from
     * [com.taosun.hanterm.data.prefs.AppPreferences.privateKeyName] +
     * `Context.filesDir/keys/`). SSHJ 0.40's
     * [PublicKeyAuthProvider.loadKeyProvider] reads it directly via
     * [net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil.detectKeyFileFormat]
     * and the corresponding `KeyFile` subclass; we don't pre-parse it.
     */
    data class PublicKeyAuth(val privateKeyPath: String) : Auth()
}
