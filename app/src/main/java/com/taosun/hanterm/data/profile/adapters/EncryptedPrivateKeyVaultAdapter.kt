package com.taosun.hanterm.data.profile.adapters

import android.content.Context
import com.taosun.hanterm.data.crypto.EncryptedPrivateKeyStore
import com.taosun.hanterm.data.profile.PrivateKeyVaultPort

/**
 * Production [PrivateKeyVaultPort]. Does **not** write prefs — the profile
 * module owns privateKeyName persistence via [com.taosun.hanterm.data.profile.ProfileStorePort].
 */
internal class EncryptedPrivateKeyVaultAdapter(
    context: Context,
) : PrivateKeyVaultPort {

    private val store = EncryptedPrivateKeyStore(context, prefs = null)

    override fun import(safeName: String, bytes: ByteArray): Result<Unit> =
        store.import(safeName, bytes)

    override fun resolveAbsolutePath(safeName: String): String? =
        store.resolveKeyFile(safeName)?.absolutePath

    override fun normalizeSafeName(raw: String): String =
        EncryptedPrivateKeyStore.normalizeSafeName(raw)
}
