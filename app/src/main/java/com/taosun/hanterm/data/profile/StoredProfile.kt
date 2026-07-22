package com.taosun.hanterm.data.profile

/**
 * Persistence snapshot for connection fields. [passwordBlob] null means no
 * usable stored password (including the empty-sentinel case).
 */
data class StoredProfile(
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val username: String = "",
    val privateKeyName: String = "",
    val passwordBlob: ByteArray? = null,
) {
    fun hasPasswordBlob(): Boolean = passwordBlob != null && passwordBlob.isNotEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredProfile) return false
        return host == other.host &&
            port == other.port &&
            username == other.username &&
            privateKeyName == other.privateKeyName &&
            passwordBlob.contentEquals(other.passwordBlob)
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + username.hashCode()
        result = 31 * result + privateKeyName.hashCode()
        result = 31 * result + (passwordBlob?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        const val DEFAULT_PORT: Int = 22
    }
}
