package com.taosun.hanterm.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.taosun.hanterm.data.crypto.EncryptedPrivateKeyStore
import com.taosun.hanterm.data.prefs.AppPreferences

/**
 * Reads the SAF [uri], encrypts the PEM via [EncryptedPrivateKeyStore], and
 * returns the stored safeName.
 */
internal fun importPrivateKey(context: Context, uri: Uri, prefs: AppPreferences): String {
    val resolver = context.contentResolver
    val displayName = queryDisplayName(resolver, uri) ?: "imported_key.pem"
    val safeName = sanitizeFileName(displayName).let { if (it.endsWith(".pem")) it else "$it.pem" }
    val bytes = resolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "could not open $uri" }
        input.readBytes()
    }
    EncryptedPrivateKeyStore(context, prefs)
        .import(safeName, bytes)
        .getOrThrow()
    return safeName
}

private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
    return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}

private fun sanitizeFileName(raw: String): String =
    raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
