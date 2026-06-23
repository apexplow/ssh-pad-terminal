package com.example.sshterminal.ssh.auth

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordAuthenticationProvider

/**
 * Wraps an SSHJ [PasswordAuthenticationProvider] so [SshClient] can stay
 * ignorant of which auth strategy is in play.
 *
 * SSHJ's auth API is method-based: callers add `AuthPassword`, `AuthPublickey`,
 * etc. to the client, and the driver picks the first that succeeds. Returning
 * an [PasswordAuthenticationProvider] (the lower-level interface) rather than
 * constructing the `AuthPassword` directly here makes the auth strategy
 * swappable without touching [SshClient].
 *
 * The password is intentionally not zeroed after use — it's a `String` and
 * Kotlin gives us no portable way to mutate it in place. The encrypted blob
 * stays in [com.example.sshterminal.data.prefs.AppPreferences]; the plaintext
 * only ever lives in this short-lived object on the connect path.
 */
object PasswordAuthProvider : SshAuthProvider {
    override fun authenticate(
        client: SSHClient,
        username: String,
        auth: Auth.PasswordAuth,
    ) {
        client.auth(
            username,
            PasswordAuthenticationProvider(AuthPassword(username, auth.password)),
        )
    }
}
