package com.apexplow.hanterm.data.profile.adapters

import com.apexplow.hanterm.data.crypto.KeyStoreManager
import com.apexplow.hanterm.data.profile.SecretCipherPort

/** Production [SecretCipherPort] delegating to [KeyStoreManager]. */
internal class AndroidKeystoreCipherAdapter : SecretCipherPort {
    override fun encrypt(plaintext: ByteArray): ByteArray = KeyStoreManager.encrypt(plaintext)
    override fun decrypt(ciphertext: ByteArray): ByteArray = KeyStoreManager.decrypt(ciphertext)
}
