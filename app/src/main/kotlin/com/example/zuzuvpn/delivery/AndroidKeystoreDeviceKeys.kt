package com.example.zuzuvpn.delivery

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.example.zuzuvpn.delivery.ProtocolV1.Suite
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec
import java.math.BigInteger

/**
 * Device keys in the Android Keystore (non-exportable; StrongBox when available for EC keys, TEE otherwise).
 *
 * Suite selection (docs/crypto/config-delivery-v1.md §1): API >= 31 and a P-256 PURPOSE_AGREE_KEY key can be
 * generated → ECDH_P256; otherwise RSA3072. This is the documented rule, not a silent downgrade: the
 * chosen suite is fixed per key and registered with the server, which never changes it.
 *
 * Keys are not bound to user authentication, so changing the screen lock does not invalidate them.
 * Uninstalling the app or clearing its data deletes them (the server then sees a new device).
 */
class AndroidKeystoreDeviceKeys(context: Context) : DeviceKeyStore {
    private val strongBox = Build.VERSION.SDK_INT >= 28 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun load(): DeviceKeys? = try {
        val ks = keyStore()
        val signing = ks.getEntry(SIGN_ALIAS, null) as? KeyStore.PrivateKeyEntry
        val encryption = ks.getEntry(ENCRYPT_ALIAS, null) as? KeyStore.PrivateKeyEntry
        when {
            signing == null && encryption == null -> null
            signing == null || encryption == null -> {
                Log.w(TAG, "Incomplete device key set; discarding")
                delete()
                null
            }
            else -> {
                val suite = when (encryption.certificate.publicKey.algorithm) {
                    "EC" -> Suite.ECDH_P256
                    "RSA" -> Suite.RSA3072
                    else -> null
                }
                if (suite == null) {
                    delete()
                    null
                } else {
                    JcaDeviceKeys(
                        suite = suite,
                        signingPrivate = signing.privateKey,
                        signingPublic = signing.certificate.publicKey,
                        encryptionPrivate = encryption.privateKey,
                        encryptionPublic = encryption.certificate.publicKey,
                        keyAgreementProvider = PROVIDER,
                    )
                }
            }
        }
    } catch (e: Exception) {
        // A Keystore error may be transient: report it without deleting the keys (the device identity).
        // Never include e.message: keep logs free of key details.
        Log.w(TAG, "Device keys could not be loaded (${e.javaClass.simpleName})")
        throw java.security.KeyStoreException("device keys unavailable")
    }

    override fun create(): DeviceKeys {
        delete()
        generateEc(SIGN_ALIAS, KeyProperties.PURPOSE_SIGN) { setDigests(KeyProperties.DIGEST_SHA256) }
        val ecdh = Build.VERSION.SDK_INT >= 31 && try {
            generateEc(ENCRYPT_ALIAS, PURPOSE_AGREE_KEY) {}
            true
        } catch (e: Exception) {
            Log.w(TAG, "ECDH key generation unavailable (${e.javaClass.simpleName}); using RSA-3072 suite")
            false
        }
        if (!ecdh) generateRsa()
        return load() ?: error("device keys could not be created")
    }

    override fun delete() {
        runCatching {
            val ks = keyStore()
            ks.deleteEntry(SIGN_ALIAS)
            ks.deleteEntry(ENCRYPT_ALIAS)
        }
    }

    private fun generateEc(alias: String, purposes: Int, extra: KeyGenParameterSpec.Builder.() -> Unit) {
        fun build(strong: Boolean) = KeyGenParameterSpec.Builder(alias, purposes)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .apply(extra)
            .apply { if (strong && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        if (strongBox) {
            try {
                generator.initialize(build(true))
                generator.generateKeyPair()
                return
            } catch (e: Exception) {
                Log.i(TAG, "StrongBox unavailable for this key (${e.javaClass.simpleName}); using TEE")
            }
        }
        generator.initialize(build(false))
        generator.generateKeyPair()
    }

    /** RSA-3072, e = 65537, OAEP with SHA-1 (MGF1-SHA-1 is the only MGF1 digest before API 34). StrongBox does not support 3072-bit RSA. */
    private fun generateRsa() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER)
        generator.initialize(
            KeyGenParameterSpec.Builder(ENCRYPT_ALIAS, KeyProperties.PURPOSE_DECRYPT)
                .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(3072, BigInteger.valueOf(65537)))
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
                .build(),
        )
        generator.generateKeyPair()
    }


    private companion object {
        const val TAG = "ZuzuDeviceKeys"
        const val PROVIDER = "AndroidKeyStore"
        const val SIGN_ALIAS = "zuzu.device.sign.v1"
        const val ENCRYPT_ALIAS = "zuzu.device.encrypt.v1"

        /** KeyProperties.PURPOSE_AGREE_KEY (API 31); literal so the class compiles against older stubs too. */
        const val PURPOSE_AGREE_KEY = 1 shl 6
    }
}
