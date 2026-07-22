package com.taosun.hanterm.ssh.security

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * File-backed TOFU store for SSH host fingerprints (Module 11 / KHS-ST-01..06).
 *
 * ## File format
 *
 * One row per line, tab-separated:
 * `<host>\t<port>\t<keyType>\t<fingerprintBase64>`
 *
 * The first line is a `#`-prefixed comment marker (future-proofing — we don't
 * currently interpret the rest of the line as `known_hosts` syntax). Blank lines
 * are ignored.
 *
 * ## Atomic writes (KHS-ST-04)
 *
 * Every [put] / [delete] goes through [AtomicFile.startWrite] → write content →
 * [AtomicFile.finishWrite] or [AtomicFile.failWrite]. The OS-level guarantee is
 * that a process crash (or kernel panic, or battery pull) never leaves a
 * half-written file on disk — the rename either succeeds or doesn't happen.
 *
 * ## Defensive parsing (KHS-ST-06)
 *
 * Any malformed row — missing field, bad Base64, wrong column count — is treated
 * as "no record" rather than throwing. A corrupt store is functionally equivalent
 * to a fresh store, and the next connect re-enrolls the host. This trades
 * forensic completeness for availability of the connect path.
 *
 * ## NOT in scope
 *
 * This is **not** a full `known_hosts` parser. We do not handle:
 *  - hashed hostnames (`|1|base64|salt|...`)
 *  - key-cert bindings (`@cert-authority`, `@revoked`)
 *  - multiple key types per (host, port) row
 *  - port-as-suffix notation (`[host]:port`)
 * TOFU only. Sprint 3+ expands this if we ever support multi-host.
 *
 * ## Thread safety
 *
 * Each instance carries a [Mutex] guarding the AtomicFile handle. Concurrent
 * [get] / [put] / [delete] from different threads serialize correctly; no
 * snapshot isolation though — a reader sees whichever rows happen to be on
 * disk at the moment of its read.
 */
class KnownHostsStore(context: Context) {

    private val file: File = File(context.filesDir, FILE_NAME)
    private val atomic: AtomicFile = AtomicFile(file)
    private val mutex = Mutex()

    /**
     * Returns the stored fingerprint for `(host, port)`, or `null` when there
     * is no record (first-use path) or when the row is malformed (KHS-ST-06).
     *
     * Never throws on a malformed row. On a missing or unreadable file
     * returns `null` so the caller can proceed down the TOFU enroll branch
     * without a special-case.
     */
    suspend fun get(host: String, port: Int): HostFingerprint? = mutex.withLock {
        val rows = readRows() ?: return@withLock null
        rows.firstNotNullOfOrNull { row ->
            if (row.host == host && row.port == port) row.fingerprint else null
        }
    }

    /**
     * Records the fingerprint for `(host, port)`, atomically overwriting any
     * existing row (KHS-ST-03). The write fsyncs before returning (KHS-ST-04).
     */
    suspend fun put(host: String, port: Int, fingerprint: HostFingerprint) =
        mutex.withLock {
            val existing = readRows().orEmpty().toMutableList()
            existing.removeAll { it.host == host && it.port == port }
            existing.add(Row(host, port, fingerprint))
            atomicWriteAll(existing)
        }

    /**
     * Removes the row for `(host, port)`, if any (SC-FH-02). A second call for
     * the same `(host, port)` is a no-op (SC-FH-03).
     */
    suspend fun delete(host: String, port: Int) {
        mutex.withLock {
            val existing = readRows().orEmpty().toMutableList()
            val removed = existing.removeAll { it.host == host && it.port == port }
            if (removed) atomicWriteAll(existing)
        }
    }

    /**
     * Lightweight probe used by [com.taosun.hanterm.ssh.SshClient]
     * to verify the store is readable before opening a TCP connection
     * (SC-KHV-02). The result is intentionally discarded — we only care
     * whether [get] completes without throwing. Returns the probe outcome
     * for callers that want to inspect the result.
     */
    suspend fun probe(): Throwable? = mutex.withLock {
        try {
            readRows()
            null
        } catch (e: Throwable) {
            e
        }
    }

    private fun readRows(): List<Row>? {
        if (!file.exists()) return emptyList()
        return try {
            atomic.openRead().use { input ->
                input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.filter { it.isNotBlank() && !it.startsWith("#") }
                        .mapNotNull { parseRow(it) }
                        .toList()
                }
            }
        } catch (e: FileNotFoundException) {
            // Lost the race against another process / a wipe between
            // exists() and openRead(). Treat as empty.
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "known_hosts read failed; treating as empty", e)
            null
        }
    }

    private fun atomicWriteAll(rows: List<Row>) {
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write("# ssh-pad-terminal TOFU store - do not edit by hand")
                w.newLine()
                for (row in rows) {
                    w.write(formatRow(row))
                    w.newLine()
                }
            }
            atomic.finishWrite(stream)
            stream = null
        } finally {
            if (stream != null) atomic.failWrite(stream)
        }
    }

    private fun formatRow(row: Row): String =
        "${row.host}\t${row.port}\t${row.fingerprint.keyType}\t${row.fingerprint.fingerprintBase64}"

    /**
     * Parse one line. Returns null on any malformed input — defensive on purpose
     * (KHS-ST-06): a corrupt row should not poison the rest of the file.
     */
    private fun parseRow(line: String): Row? {
        val parts = line.split('\t')
        if (parts.size != 4) return null
        val host = parts[0].trim()
        val port = parts[1].trim().toIntOrNull() ?: return null
        val keyType = parts[2].trim()
        val fp = parts[3].trim()
        if (host.isEmpty() || port !in 1..65535 || keyType.isEmpty() || fp.isEmpty()) {
            return null
        }
        // Validate the Base64 round-trip without retaining the bytes.
        // An empty decoded result means the input was either empty or
        // not Base64 — reject either way.
        val decoded = runCatching {
            android.util.Base64.decode(fp, android.util.Base64.DEFAULT)
        }.getOrNull() ?: return null
        if (decoded.isEmpty()) return null
        return Row(host, port, HostFingerprint(keyType, fp))
    }

    private data class Row(
        val host: String,
        val port: Int,
        val fingerprint: HostFingerprint,
    )

    companion object {
        private const val TAG = "KnownHostsStore"
        const val FILE_NAME = "known_hosts"
    }
}