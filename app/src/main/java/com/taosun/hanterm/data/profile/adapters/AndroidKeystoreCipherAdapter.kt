package com.taosun.hanterm.data.profile.adapters

import com.taosun.hanterm.data.crypto.KeyStoreManager
import com.taosun.hanterm.data.profile.SecretCipherPort

/** Production [SecretCipherPort] delegating to [KeyStoreManager]. */
internal class AndroidKeystoreCipherAdapter : SecretCipherPort {
    override fun encrypt(plaintext: ByteArray): ByteArray = KeyStoreManager.encrypt(plaintext)
    override fun decrypt(ciphertext: ByteArray): ByteArray = KeyStoreManager.decrypt(ciphertext)
}
