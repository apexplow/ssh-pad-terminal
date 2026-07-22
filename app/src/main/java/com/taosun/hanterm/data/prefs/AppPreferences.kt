package com.taosun.hanterm.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.taosun.hanterm.data.crypto.EncryptedPrivateKeyStore

/**
 * Single-host SharedPreferences store, per `implementation_plan.md` §"模块划分与边界".
 *
 * v1.0 intentionally stores only one host (the "active" connection target).
 * Multi-host management is explicitly out-of-scope for Sprint 1 and lands in Sprint 3.
 *
 * Values are read lazily and exposed as mutable properties backed by the underlying
 * [SharedPreferences] editor, so callers can either mutate-and-save explicitly or
 * rely on [commit]/[apply] semantics.
 */
class AppPreferences(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_HOST, value).apply()
        }

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) {
            prefs.edit().putInt(KEY_PORT, value).apply()
        }

    /**
     * Terminal font size in pixels (passed straight to Termux's [com.termux.view.TerminalView.setTextSize]).
     * Read is clamped to `[MIN_FONT_SIZE, MAX_FONT_SIZE]` so a manually-edited prefs file can't crash
     * the renderer. Write is unclamped — callers (currently MainActivity) are responsible for stepping
     * the value into range before assigning.
     */
    var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
            .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        set(value) {
            prefs.edit().putInt(KEY_FONT_SIZE, value).apply()
        }

    var username: String
        get() = prefs.getString(KEY_USERNAME, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_USERNAME, value).apply()
        }

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_PASSWORD, value).apply()
        }

    /**
     * Filename (under `filesDir/keys/`) of the imported private key, or empty
     * when no key is selected. The encrypted blob itself is managed by
     * [com.taosun.hanterm.data.crypto.KeyStoreManager].
     */
    var privateKeyName: String
        get() = prefs.getString(KEY_PRIVATE_KEY_NAME, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_PRIVATE_KEY_NAME, value).apply()
        }

    // ---------------------------------------------------------------------
    // Plan C (Sprint 1.5 §3): explicit Keystore-backed password slot.
    //
    // The plain [password] String getter/setter above is kept for backward
    // compatibility (older callers, tests, and `clear()` semantics), but the
    // The UI layer writes via ConnectionProfile (data/profile); callers no longer
    // encrypt at the ConfigScreen call site. Preferred flow:
    //   profile.save(draft) / profile.prepareConnect(draft)
    // This slot remains the on-disk home for the AES-GCM blob.
    // ---------------------------------------------------------------------

    /** Stores a password blob already encrypted by `KeyStoreManager.encrypt`. */
    fun setEncryptedPassword(ciphertext: ByteArray) {
        prefs.edit().putString(KEY_ENCRYPTED_PASSWORD, encodeBytes(ciphertext)).apply()
    }

    /**
     * Returns the encrypted password blob, or `null` if no password has been
     * saved or the saved value is an explicit "empty" sentinel. The caller is
     * expected to pass the result to `KeyStoreManager.decrypt`.
     *
     * Treats a zero-length blob as null so callers don't have to handle the
     * "saved empty" sentinel themselves — that case would otherwise round-trip
     * through Keystore.decrypt and throw on the IV-length precondition. The UI
     * writes an empty blob when the user clears the password field; this makes
     * that path safe.
     */
    fun getEncryptedPassword(): ByteArray? {
        val encoded = prefs.getString(KEY_ENCRYPTED_PASSWORD, null) ?: return null
        val decoded = decodeBytes(encoded)
        return if (decoded.isEmpty()) null else decoded
    }

    /**
     * True iff all required connection fields are present and non-empty.
     *
     * Note: the password check uses [getEncryptedPassword] (the Plan C Keystore-backed
     * slot), NOT [password] (the legacy plain-text slot). Sprint 1.5's
     * ConfigScreen always writes via setEncryptedPassword, so the plain-text
     * slot is always empty in production — checking it would always return false
     * and the user would see "missing host/username/credential" despite having
     * configured everything correctly. The legacy `password` field is only
     * consulted by tests.
     */
    fun hasUsableCredentials(): Boolean =
        host.isNotBlank() && username.isNotBlank() &&
            (getEncryptedPassword() != null || hasPrivateKeyOnDisk())

    private fun hasPrivateKeyOnDisk(): Boolean {
        if (privateKeyName.isBlank()) return false
        return EncryptedPrivateKeyStore(appContext).resolveKeyFile(privateKeyName) != null
    }

    /**
     * Clears connection fields and the encrypted-password slot only.
     * Preserves [fontSize] and migration flags — unlike [clear], which wipes
     * the entire SharedPreferences file.
     */
    fun clearConnectionFields() {
        prefs.edit()
            .remove(KEY_HOST)
            .remove(KEY_PORT)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_PRIVATE_KEY_NAME)
            .remove(KEY_ENCRYPTED_PASSWORD)
            .apply()
    }

    /** Wipes the entire prefs file (including fontSize). The Keystore key is left untouched. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    // Sprint 2.5 / Module 13 (BC-COMPAT-02)
    fun isDebugLogMigratedV25(): Boolean =
        prefs.getBoolean(KEY_DEBUG_LOG_MIGRATED_V25, false)

    fun markDebugLogMigratedV25() {
        prefs.edit().putBoolean(KEY_DEBUG_LOG_MIGRATED_V25, true).apply()
    }

    private fun encodeBytes(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun decodeBytes(encoded: String): ByteArray =
        android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)

    companion object {
        const val PREFS_NAME = "hanterm_prefs"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_PRIVATE_KEY_NAME = "private_key_name"
        const val KEY_ENCRYPTED_PASSWORD = "encrypted_password"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_DEBUG_LOG_MIGRATED_V25 = "debug_log_migrated_v2_5"
        const val DEFAULT_PORT = 22
        const val DEFAULT_FONT_SIZE = 14
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 32
        const val FONT_SIZE_STEP = 2
    }
}