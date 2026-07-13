package com.example.sshterminal.terminal.zmodem

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.OutputStream

/**
 * Writes a received file into the public Downloads collection via MediaStore.
 *
 * minSdk 29: [MediaStore.Downloads] + `IS_PENDING` needs no storage permission
 * for app-created entries.
 */
class MediaStoreDownloadSink(
    context: Context,
) : TransferSink {
    private val appContext = context.applicationContext
    private var uri: Uri? = null
    private var stream: OutputStream? = null

    override fun begin(fileName: String): OutputStream {
        abort()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, guessMime(fileName))
            put(MediaStore.Downloads.IS_PENDING, 1)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Downloads.RELATIVE_PATH, "Download")
            }
        }
        val resolver = appContext.contentResolver
        val created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed for $fileName")
        uri = created
        val out = resolver.openOutputStream(created)
            ?: run {
                resolver.delete(created, null, null)
                uri = null
                error("MediaStore openOutputStream failed for $fileName")
            }
        stream = out
        return out
    }

    override fun commit() {
        val target = uri ?: return
        try {
            stream?.flush()
            stream?.close()
        } catch (_: Throwable) {
        }
        stream = null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        appContext.contentResolver.update(target, values, null, null)
        uri = null
    }

    override fun abort() {
        try {
            stream?.close()
        } catch (_: Throwable) {
        }
        stream = null
        val target = uri
        uri = null
        if (target != null) {
            runCatching { appContext.contentResolver.delete(target, null, null) }
        }
    }

    private fun guessMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".apk") -> "application/vnd.android.package-archive"
            lower.endsWith(".txt") || lower.endsWith(".md") -> "text/plain"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".sh") -> "text/x-shellscript"
            else -> "application/octet-stream"
        }
    }
}
