package com.example.zuzuvpn.delivery

import com.example.zuzuvpn.delivery.ProtocolV1.Suite
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * This installation's two device keys. Private-key operations happen through the JCA so that, on a
 * device, they run inside the Android Keystore (the private keys never leave it). JVM tests use the
 * same class with software keys (e.g. the TEST-ONLY keys of the backend vectors).
 */
interface DeviceKeys {
    val suite: Suite
    val signingSpki: ByteArray
    val encryptionSpki: ByteArray
    val signingKid: String get() = ProtocolV1.keyId(signingSpki)
    val encryptionKid: String get() = ProtocolV1.keyId(encryptionSpki)

    /** SHA256withECDSA, DER-encoded. */
    fun sign(message: ByteArray): ByteArray

    /** Suite ECDH_P256: ECDH with the ephemeral public key (DER SPKI); returns the 32-byte x-coordinate. */
    fun agree(peerSpki: ByteArray): ByteArray

    /** Suite RSA3072: RSA-OAEP (SHA-1, MGF1-SHA-1) decryption of the wrapped CEK. */
    fun unwrap(wrappedKey: ByteArray): ByteArray
}

class JcaDeviceKeys(
    override val suite: Suite,
    private val signingPrivate: PrivateKey,
    signingPublic: PublicKey,
    private val encryptionPrivate: PrivateKey,
    encryptionPublic: PublicKey,
    /** "AndroidKeyStore" for Keystore keys (ECDH needs the Keystore provider explicitly); null = default providers. */
    private val keyAgreementProvider: String? = null,
) : DeviceKeys {
    override val signingSpki: ByteArray = signingPublic.encoded
    override val encryptionSpki: ByteArray = encryptionPublic.encoded

    override fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(signingPrivate)
            update(message)
            sign()
        }

    override fun agree(peerSpki: ByteArray): ByteArray {
        check(suite == Suite.ECDH_P256) { "not an ECDH key" }
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerSpki))
        val agreement = keyAgreementProvider?.let { KeyAgreement.getInstance("ECDH", it) } ?: KeyAgreement.getInstance("ECDH")
        agreement.init(encryptionPrivate)
        agreement.doPhase(peer, true)
        return agreement.generateSecret()
    }

    override fun unwrap(wrappedKey: ByteArray): ByteArray {
        check(suite == Suite.RSA3072) { "not an RSA key" }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionPrivate, OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        return cipher.doFinal(wrappedKey)
    }
}

/** Creates, loads and destroys this installation's device keys. */
interface DeviceKeyStore {
    /**
     * The existing keys, or null if this installation has none (an incomplete or unusable set is
     * deleted). Throws [java.security.GeneralSecurityException] if the Keystore itself fails (keys kept).
     */
    fun load(): DeviceKeys?

    /** Generates a fresh pair, replacing any existing keys. */
    fun create(): DeviceKeys

    /** Deletes both keys (logout, revoked device, reinstall-like reset). */
    fun delete()
}
