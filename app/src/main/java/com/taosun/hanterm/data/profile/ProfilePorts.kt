package com.taosun.hanterm.data.profile

/** Persistence seam for connection fields (not fontSize). */
internal interface ProfileStorePort {
    fun read(): StoredProfile
    fun write(profile: StoredProfile)
    fun clearConnectionFields()
}

/** Crypto seam wrapping Android Keystore AES-GCM. */
internal interface SecretCipherPort {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/** Imported private-key vault seam. */
internal interface PrivateKeyVaultPort {
    fun import(safeName: String, bytes: ByteArray): Result<Unit>
    fun resolveAbsolutePath(safeName: String): String?
    fun normalizeSafeName(raw: String): String
}

/** TOFU forget seam — verify/enroll stay in ssh/security. */
internal interface HostEnrollmentPort {
    suspend fun delete(host: String, port: Int)
}
