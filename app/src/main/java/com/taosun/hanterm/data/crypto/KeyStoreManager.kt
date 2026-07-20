package com.taosun.hanterm.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM wrapper around Android Keystore.
 *
 * Per `implementation_plan.md` §"决策 3": a hybrid scheme where the private-key file
 * lives in `filesDir/keys/` (encrypted PEM, see [privateKeyName]) and the wrapping AES key lives in the
 * AndroidKeyStore (hardware-backed when available).
 *
 * Threat model: this defends against *other ordinary apps* reading the private key
 * (Android sandbox + Keystore). It does **not** defend against a rooted device,
 * `adb backup` migration (mitigated by `android:allowBackup="false"`), or a debugger
 * attached to the process.
 */
object KeyStoreManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ssh_key_encryption_key"
    private const val AES_KEY_SIZE_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * Returns the persistent AES key, creating it on first use.
     *
     * The key is restricted to encrypt/decrypt, GCM block mode, no padding — matching
     * the [TRANSFORMATION] used by [encrypt]/[decrypt].
     */
    fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                .apply { init(spec) }
                .generateKey()
        }
        return (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    /**
     * Encrypts [plaintext] under the managed key. Returns a self-contained payload
     * (12-byte IV || ciphertext+tag) so callers can persist a single ByteArray.
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        check(iv.size == GCM_IV_BYTES) { "unexpected IV size: ${iv.size}" }
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /**
     * Decrypts a payload produced by [encrypt].
     */
    fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > GCM_IV_BYTES) { "payload too short to contain an IV" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** Deletes the managed key. Intended for "forget this device" flows. */
    fun deleteKey() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
    }
}