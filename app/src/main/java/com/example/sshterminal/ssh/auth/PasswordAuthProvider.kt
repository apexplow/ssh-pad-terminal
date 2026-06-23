package com.example.sshterminal.ssh.auth

import android.util.Log
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
 *
 * Debug: log a SHA-256 of the password (not the password itself) at
 * the auth call site, so `adb logcat | grep SshAuth` can confirm which
 * password is reaching SSHJ without leaking the secret to the log buffer.
 */
object PasswordAuthProvider : SshAuthProvider {
    private const val TAG = "SshAuth"

    override fun authenticate(
        client: SSHClient,
        username: String,
        auth: Auth,
    ) {
        require(auth is Auth.PasswordAuth) {
            "PasswordAuthProvider requires Auth.PasswordAuth, got ${auth::class.simpleName}"
        }
        Log.i(TAG, "password auth: user=$username length=${auth.password.length} " +
                "sha256=${sha256Hex(auth.password)} firstByte=${auth.password.firstOrNull()?.code?.toString(16) ?: "null"}")
        client.authPassword(username, auth.password)
    }

    private fun sha256Hex(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16) + "..."
    }
}
