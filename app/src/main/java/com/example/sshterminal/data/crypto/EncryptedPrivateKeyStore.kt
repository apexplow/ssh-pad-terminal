package com.example.sshterminal.data.crypto

import android.content.Context
import com.example.sshterminal.data.prefs.AppPreferences
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * Encrypts imported PEM private keys at rest under `filesDir/keys/` (Module 12).
 *
 * Plaintext PEM bytes exist only transiently during [import]; on disk the file is
 * a self-contained `KeyStoreManager.encrypt` payload (IV ‖ ciphertext ‖ GCM tag).
 */
class EncryptedPrivateKeyStore(
    context: Context,
    private val prefs: AppPreferences? = null,
) {
    private val appContext = context.applicationContext

    private val keysDir: File
        get() = File(appContext.filesDir, "keys").also { it.mkdirs() }

    /** Module 12 / PKR-FMT-01: `filesDir/keys/<safeName>.pem.enc`. */
    fun encryptedFile(safeName: String): File =
        File(keysDir, "${normalizeSafeName(safeName)}.pem.enc")

    /** Sprint 1.5 legacy plaintext layout: `filesDir/keys/<safeName>`. */
    fun legacyPlainFile(safeName: String): File =
        File(keysDir, normalizeSafeName(safeName))

    /** Returns the encrypted or legacy plaintext key file, or null if neither exists. */
    fun resolveKeyFile(safeName: String): File? {
        val enc = encryptedFile(safeName)
        if (enc.isFile) return enc
        val legacy = legacyPlainFile(safeName)
        if (legacy.isFile) return legacy
        return null
    }

    /**
     * SAF import entry (EPKS-IM-01): encrypt [sourceBytes] and persist under
     * [encryptedFile]. Zeros the in-memory copy when done (EPKS-IM-04).
     */
    fun import(safeName: String, sourceBytes: ByteArray): Result<Unit> = runCatching {
        val plain = sourceBytes.copyOf()
        try {
            val encrypted = KeyStoreManager.encrypt(plain)
            plain.fill(0)
            encryptedFile(safeName).writeBytes(encrypted)
            prefs?.privateKeyName = normalizeSafeName(safeName)
        } finally {
            plain.fill(0)
            sourceBytes.fill(0)
        }
    }

    /**
     * File-based import (EPKS-IM-02..03): reads [sourceFile], encrypts, and
     * best-effort secure-deletes [sourceFile] when it lives under [keysDir].
     */
    fun import(safeName: String, sourceFile: File): Result<Unit> {
        val bytes = sourceFile.readBytes()
        val result = import(safeName, bytes)
        if (result.isSuccess && sourceFile.parentFile?.absolutePath == keysDir.absolutePath) {
            secureDeleteBestEffort(sourceFile)
        }
        return result
    }

    /**
     * One-time legacy migration (PKR-FMT-04 / PKP-RES-06): encrypt a plaintext
     * `.pem`, write `.pem.enc`, delete the plaintext file.
     */
    fun migrateLegacyPlaintextIfNeeded(safeName: String): File? {
        val legacy = legacyPlainFile(safeName)
        if (!legacy.isFile) return null
        val enc = encryptedFile(safeName)
        if (enc.isFile) return enc
        val plain = legacy.readBytes()
        try {
            enc.writeBytes(KeyStoreManager.encrypt(plain))
        } finally {
            plain.fill(0)
        }
        secureDeleteBestEffort(legacy)
        return enc
    }

    companion object {
        fun normalizeSafeName(name: String): String =
            name.trim().let { if (it.endsWith(".pem")) it else "$it.pem" }

        /**
         * Best-effort secure delete (EPKS-IM-02..03). Flash wear-leveling may
         * leave recoverable copies on eMMC/UFS; this is defense in depth only.
         * Does not retry on [IOException] mid-overwrite.
         */
        fun secureDeleteBestEffort(file: File) {
            if (!file.isFile) return
            runCatching {
                val len = file.length().coerceAtLeast(1)
                file.outputStream().use { out ->
                    val buf = ByteArray(4096)
                    SecureRandom().nextBytes(buf)
                    var remaining = len
                    while (remaining > 0) {
                        val n = minOf(remaining, buf.size.toLong()).toInt()
                        out.write(buf, 0, n)
                        remaining -= n
                    }
                }
            }
            file.delete()
        }
    }
}
