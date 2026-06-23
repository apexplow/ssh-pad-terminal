package com.example.sshterminal.ssh.auth

import net.schmizz.sshj.SSHClient

/**
 * Wraps SSHJ's password-auth convenience call so [SshClient] can stay
 * ignorant of which auth strategy is in play.
 *
 * SSHJ exposes two relevant APIs:
 *  - `client.authPassword(user, password: String)` — synchronous, throws on
 *    failure. We use this one because the password is in memory only briefly
 *    and we don't need keyboard-interactive fallback for v1.0.
 *  - `client.auth(user, AuthPassword(...))` — async/retry-capable, but
 *    requires us to wire a `PasswordFinder` (an interface with no constructor)
 *    for what is just a static string.
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
        auth: Auth,
    ) {
        require(auth is Auth.PasswordAuth) {
            "PasswordAuthProvider requires Auth.PasswordAuth, got ${auth::class.simpleName}"
        }
        client.authPassword(username, auth.password)
    }
}
