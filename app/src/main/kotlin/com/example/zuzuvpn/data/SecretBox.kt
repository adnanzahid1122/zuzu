package com.example.zuzuvpn.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption for data at rest. [open] throws if the data or key is unusable. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    /** @throws MissingKeyException when the key no longer exists; other exceptions for corrupt data. */
    fun open(sealed: ByteArray): ByteArray
}

class MissingKeyException : Exception("encryption key not available")

/**
 * AES-256-GCM with a non-exportable key held in the Android Keystore (hardware-backed where the
 * device supports it). Output: [iv:12][ciphertext + 16-byte tag].
 */
class KeystoreSecretBox(private val alias: String = DEFAULT_ALIAS) : SecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        val iv = cipher.iv
        check(iv.size == IV_LENGTH)
        return iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_LENGTH + TAG_BYTES) { "too short" }
        val key = existingKey() ?: throw MissingKeyException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, sealed, 0, IV_LENGTH))
        return cipher.doFinal(sealed, IV_LENGTH, sealed.size - IV_LENGTH)
    }

    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generateKey()
    }

    private companion object {
        // Same alias as the first (single-profile) store, so an existing file stays readable.
        const val DEFAULT_ALIAS = "zuzu.shadowsocks.profile.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BYTES = 16
    }
}
