package com.taosun.hanterm.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Reads a SAF [uri] into display-name + bytes for [com.taosun.hanterm.data.profile.ConnectionProfile.importKey].
 * Does not encrypt or touch prefs — that is the profile module's job.
 */
internal fun readPrivateKeyFromUri(context: Context, uri: Uri): Pair<String, ByteArray> {
    val resolver = context.contentResolver
    val displayName = queryDisplayName(resolver, uri) ?: "imported_key.pem"
    val bytes = resolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "could not open $uri" }
        input.readBytes()
    }
    return displayName to bytes
}

private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
    return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}
