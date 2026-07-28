package com.apexplow.hanterm.terminal.zmodem

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Streaming destination for a single ZMODEM receive.
 *
 * Production: [MediaStoreDownloadSink]. Tests: [InMemoryTransferSink].
 */
interface TransferSink {
    /** Open the destination for [fileName] (already sanitized). */
    fun begin(fileName: String): OutputStream

    /** Finalize a successful transfer (e.g. clear IS_PENDING). */
    fun commit()

    /** Discard a partial transfer. Safe to call if [begin] never ran. */
    fun abort()
}

/** Test sink that keeps bytes in memory. */
class InMemoryTransferSink : TransferSink {
    private val buffer = ByteArrayOutputStream()
    var begunName: String? = null
        private set
    var committed: Boolean = false
        private set
    var aborted: Boolean = false
        private set

    val bytes: ByteArray
        get() = buffer.toByteArray()

    override fun begin(fileName: String): OutputStream {
        begunName = fileName
        buffer.reset()
        committed = false
        aborted = false
        return buffer
    }

    override fun commit() {
        committed = true
    }

    override fun abort() {
        aborted = true
        buffer.reset()
    }
}
