package com.apexplow.hanterm.data.crypto

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
 *
 * ## User-authentication decision (Issue #39, `docs/COMPLIANCE_NOTES.md` §4)
 *
 * The AES master key is **deliberately not** gated behind
 * `KeyGenParameterSpec.setUserAuthenticationRequired(true)`. Calling that with
 * a non-zero timeout would require a fresh biometric / device-credential prompt
 * every time `decrypt()` runs — i.e. every SSH connect attempt, every time the
 * foreground service wakes the keepalive thread, and (most painfully) every
 * `SshKeepAliveService` nudge after the user has stepped away from the device
 * for a few minutes. That breaks the "long-lived session" core scenario of an
 * SSH client and offers no real security gain:
 *
 *  - The actual threats (other apps reading files, `adb backup` exfiltration,
 *    shared-device snooping of the unattended phone) are already covered by
 *    Android sandbox + Keystore hardware binding + `allowBackup="false"` —
 *    none of which require user authentication.
 *  - A biometric prompt gates who unlocks **right now**, not who else has
 *    unlocked in the last few hours. It does not retroactively authenticate
 *    ciphertext that was decrypted while the user was present.
 *  - An attacker who already holds the unlocked phone is past the threat
 *    boundary the Keystore is supposed to defend against.
 *
 * If a future UX flow genuinely needs per-use auth (e.g. an "import private
 * key" action that should require explicit user presence), wire that through
 * a **separate, narrowly-scoped** `KeyGenParameterSpec` — not by flipping the
 * master key. Do not change this builder without an explicit decision issue.
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
     *
     * The builder deliberately omits `setUserAuthenticationRequired(true)` — see the
     * class-level kdoc "User-authentication decision" section above. The
     * `.setUserAuthenticationRequired(false)` call below is therefore a no-op at the
     * Keystore level (false is the platform default) but is made explicit here so the
     * decision is auditable in code: grep for `setUserAuthenticationRequired` and the
     * reviewer is forced to read this comment.
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
                // Explicit "no per-use auth required". See kdoc above (Issue #39).
                .setUserAuthenticationRequired(false)
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