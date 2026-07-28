package com.apexplow.hanterm.data.profile

import com.apexplow.hanterm.ssh.auth.Auth
import java.nio.ByteBuffer
import java.nio.CharBuffer

/**
 * Default [ConnectionProfile] implementation. Orchestrates store / cipher /
 * vault / host-enrollment ports; UI never sees those adapters.
 *
 * Construct via [ConnectionProfiles.create] (or tests with fakes).
 */
internal class DefaultConnectionProfile(
    private val store: ProfileStorePort,
    private val cipher: SecretCipherPort,
    private val keys: PrivateKeyVaultPort,
    private val hosts: HostEnrollmentPort,
) : ConnectionProfile {

    override fun load(): ProfileSnapshot {
        val stored = store.read()
        return ProfileSnapshot(
            draft = ConnectionDraft(
                host = stored.host,
                port = stored.port.toString(),
                username = stored.username,
                password = "",
                privateKeyName = stored.privateKeyName,
            ),
            hasStoredPassword = stored.hasPasswordBlob(),
        )
    }

    override fun save(draft: ConnectionDraft): SaveOutcome {
        val cur = store.read()
        val merged = mergeFields(cur, draft, replacePasswordIfPresent = true)
        store.write(merged)
        return SaveOutcome(
            draftForUi = draft.copy(password = ""),
            hasStoredPassword = merged.hasPasswordBlob(),
        )
    }

    override suspend fun prepareConnect(draft: ConnectionDraft): Result<ConnectPrepared> =
        runCatching {
            val cur = store.read()
            val merged = mergeFields(cur, draft, replacePasswordIfPresent = true)
            store.write(merged)
            val auth = materializeAuth(draft, merged)
            ConnectPrepared(
                host = merged.host,
                port = merged.port,
                username = merged.username,
                auth = auth,
            )
        }

    override fun clearStoredPassword() {
        val cur = store.read()
        store.write(cur.copy(passwordBlob = null))
    }

    override fun clearAll(): ConnectionDraft {
        store.clearConnectionFields()
        return blankDraft()
    }

    override fun importKey(displayName: String, bytes: ByteArray): Result<String> =
        runCatching {
            val safe = keys.normalizeSafeName(displayName)
            keys.import(safe, bytes).getOrThrow()
            val cur = store.read()
            store.write(cur.copy(privateKeyName = safe))
            safe
        }

    override suspend fun forgetHost(host: String, port: Int) {
        hosts.delete(host.trim(), port)
    }

    /**
     * @param replacePasswordIfPresent when true and draft.password is non-empty,
     *   encrypt and replace the blob; when draft.password is empty, KEEP blob.
     */
    private fun mergeFields(
        cur: StoredProfile,
        draft: ConnectionDraft,
        replacePasswordIfPresent: Boolean,
    ): StoredProfile {
        val keyName = draft.privateKeyName.trim().let { raw ->
            if (raw.isBlank()) "" else keys.normalizeSafeName(raw)
        }
        val blob = when {
            replacePasswordIfPresent && draft.password.isNotEmpty() -> {
                val plain = draft.password.toByteArray(Charsets.UTF_8)
                try {
                    cipher.encrypt(plain)
                } finally {
                    plain.fill(0)
                }
            }
            else -> cur.passwordBlob
        }
        return StoredProfile(
            host = draft.host.trim(),
            port = draft.port.toIntOrNull() ?: StoredProfile.DEFAULT_PORT,
            username = draft.username.trim(),
            privateKeyName = keyName,
            passwordBlob = blob,
        )
    }

    private fun materializeAuth(draft: ConnectionDraft, stored: StoredProfile): Auth {
        require(stored.host.isNotBlank() && stored.username.isNotBlank()) {
            "Missing host, username, or password/key. Fill in the form and tap Connect."
        }
        val keyName = stored.privateKeyName
        if (keyName.isNotBlank()) {
            val path = keys.resolveAbsolutePath(keyName)
                ?: error("private key not found for $keyName")
            return Auth.PublicKeyAuth(path)
        }
        if (draft.password.isNotEmpty()) {
            return Auth.PasswordAuth(draft.password.toCharArray())
        }
        val blob = stored.passwordBlob
            ?: error("Missing host, username, or password/key. Fill in the form and tap Connect.")
        return decryptToPasswordAuth(blob)
    }

    private fun decryptToPasswordAuth(blob: ByteArray): Auth.PasswordAuth {
        val plainBytes = cipher.decrypt(blob)
        val decoded: CharBuffer = Charsets.UTF_8.decode(ByteBuffer.wrap(plainBytes))
        return try {
            val chars = CharArray(decoded.remaining()).also { decoded.get(it) }
            Auth.PasswordAuth(chars)
        } finally {
            try {
                decoded.clear()
                while (decoded.hasRemaining()) {
                    decoded.put('\u0000')
                }
            } catch (_: Throwable) {
                // Buffer may be read-only.
            }
            plainBytes.fill(0)
        }
    }

    companion object {
        fun blankDraft(): ConnectionDraft = ConnectionDraft(
            host = "",
            port = StoredProfile.DEFAULT_PORT.toString(),
            username = "",
            password = "",
            privateKeyName = "",
        )
    }
}
