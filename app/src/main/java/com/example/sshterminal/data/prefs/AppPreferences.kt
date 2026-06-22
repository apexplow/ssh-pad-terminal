package com.example.sshterminal.data.prefs

import android.content.Context
import android.content.SharedPreferences

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

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
     * [com.example.sshterminal.data.crypto.KeyStoreManager].
     */
    var privateKeyName: String
        get() = prefs.getString(KEY_PRIVATE_KEY_NAME, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_PRIVATE_KEY_NAME, value).apply()
        }

    /** True iff all required connection fields are present and non-empty. */
    fun hasUsableCredentials(): Boolean =
        host.isNotBlank() && username.isNotBlank() && (password.isNotBlank() || privateKeyName.isNotBlank())

    /** Wipes the saved host configuration. The Keystore key is left untouched. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS_NAME = "ssh_term_prefs"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_PRIVATE_KEY_NAME = "private_key_name"
        const val DEFAULT_PORT = 22
    }
}