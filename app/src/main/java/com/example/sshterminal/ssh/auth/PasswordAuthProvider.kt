package com.example.sshterminal.ssh.auth

import android.util.Log
import com.example.sshterminal.BuildConfig
import net.schmizz.sshj.SSHClient

/**
 * Wraps SSHJ's password-auth convenience call so [SshClient] can stay
 * ignorant of which auth strategy is in play.
 *
 * Sprint 2.5 / S4 (PAP-LG-01..03): diagnostic logging is gated by
 * [BuildConfig.DEBUG] — release builds emit no Log.* and skip sha256Hex.
 */
object PasswordAuthProvider : SshAuthProvider {
    private const val TAG = "SshAuth"

    override fun authenticate(
        client: SSHClient,
        username: String,
        auth: Auth,
    ) {
        authenticate(client, username, auth, BuildConfig.DEBUG)
    }

    internal fun authenticate(
        client: SSHClient,
        username: String,
        auth: Auth,
        isDebug: Boolean,
    ) {
        require(auth is Auth.PasswordAuth) {
            "PasswordAuthProvider requires Auth.PasswordAuth, got ${auth::class.simpleName}"
        }
        if (isDebug) {
            Log.i(
                TAG,
                "password auth: user=$username length=${auth.password.length} " +
                    "sha256=${sha256Hex(auth.password)} " +
                    "firstByte=${auth.password.firstOrNull()?.code?.toString(16) ?: "null"}",
            )
        }
        client.authPassword(username, auth.password)
    }

    private fun sha256Hex(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16) + "..."
    }
}
