package com.example.zuzuvpn.delivery

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Zuzu configuration-delivery protocol v1, exactly as specified by the Control Plane in
 * docs/crypto/config-delivery-v1.md (backend reference: apps/api/src/customers/device-crypto.ts and
 * config-envelope.ts). Nothing here may be changed independently of that specification; the backend
 * test vectors (app/src/test/resources/config-delivery-test-vectors.json) pin every value.
 */
object ProtocolV1 {
    const val VERSION = 1

    /** Hybrid encryption suites: database name (sent at enrollment) and wire name (in envelopes and signed messages). */
    enum class Suite(val id: String, val wireName: String) {
        /** API 31+: Keystore ECDH P-256, HKDF-SHA256, AES-256-GCM. */
        ECDH_P256("ECDH_P256_HKDF_SHA256_A256GCM", "ZCP1-ECDH-P256-HKDF-SHA256-A256GCM"),

        /** API 24–30: Keystore RSA-3072, OAEP (SHA-1, MGF1-SHA-1), AES-256-GCM. */
        RSA3072("RSA3072_OAEP_SHA1_A256GCM", "ZCP1-RSA3072-OAEP-SHA1-A256GCM"),
        ;

        companion object {
            fun fromWire(name: String): Suite? = entries.firstOrNull { it.wireName == name }
        }
    }

    private const val INFO_LABEL = "zuzu-config/v1"

    // ---- key ids --------------------------------------------------------------------------------

    /** base64url(SHA-256(DER SubjectPublicKeyInfo)), first 22 characters. */
    fun keyId(spki: ByteArray): String = Base64Url.encode(sha256(spki)).substring(0, 22)

    // ---- canonical signed messages (UTF-8, "\n"-separated, no trailing newline) ---------------------

    fun enrollmentMessage(customerId: String, challenge: String, signingKid: String, encryptionKid: String, suite: Suite): String =
        listOf("ZUZU-ENROLL-V1", customerId, challenge, signingKid, encryptionKid, suite.wireName).joinToString("\n")

    fun rotationMessage(deviceId: String, signingKid: String, encryptionKid: String, suite: Suite): String =
        listOf("ZUZU-ROTATE-V1", deviceId, signingKid, encryptionKid, suite.wireName).joinToString("\n")

    /**
     * Signed request: [path] is the exact request path including "/api/v1" and the query string;
     * [body] is the exact bytes sent (empty for no body).
     */
    fun requestMessage(method: String, path: String, timestamp: String, nonce: String, body: ByteArray): String =
        listOf("ZUZU-SIG-V1", method.uppercase(), path, timestamp, nonce, Base64Url.encode(sha256(body))).joinToString("\n")

    // ---- envelope key derivation and AAD ----------------------------------------------------------

    /** "zuzu-config/v1" 0x00 kid 0x00 deviceId 0x00 serverId 0x00 nonce */
    fun hkdfInfo(kid: String, deviceId: String, serverId: String, nonce: String): ByteArray =
        listOf(INFO_LABEL, kid, deviceId, serverId, nonce).joinToString("\u0000").toByteArray(Charsets.UTF_8)

    /** salt = SHA-256(epkSpki ‖ deviceSpki) */
    fun hkdfSalt(epkSpki: ByteArray, deviceSpki: ByteArray): ByteArray = sha256(epkSpki + deviceSpki)

    /** UTF-8 of v.suite.kid.deviceId.serverId.iat.exp.<epk|ek>.nonce */
    fun aad(v: Int, suiteWire: String, kid: String, deviceId: String, serverId: String, iat: Long, exp: Long, keyMaterial: String, nonce: String): ByteArray =
        listOf(v.toString(), suiteWire, kid, deviceId, serverId, iat.toString(), exp.toString(), keyMaterial, nonce)
            .joinToString(".").toByteArray(Charsets.UTF_8)

    // ---- primitives -------------------------------------------------------------------------------

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** HKDF-SHA256 (RFC 5869) with HMAC from the platform JCA. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        prk.fill(0)
        previous.fill(0)
        return out
    }
}

/**
 * Strict base64url without padding (RFC 4648 §5), matching Node's "base64url". Implemented here
 * because java.util.Base64 needs API 26 and android.util.Base64 is unavailable in JVM tests.
 */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val INDEX = IntArray(128) { -1 }.also { table -> ALPHABET.forEachIndexed { i, c -> table[c.code] = i } }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].toInt() and 0xff shl 16) or (data[i + 1].toInt() and 0xff shl 8) or (data[i + 2].toInt() and 0xff)
            sb.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63]).append(ALPHABET[n ushr 6 and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = data[i].toInt() and 0xff shl 16
                sb.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
            }
            2 -> {
                val n = (data[i].toInt() and 0xff shl 16) or (data[i + 1].toInt() and 0xff shl 8)
                sb.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63]).append(ALPHABET[n ushr 6 and 63])
            }
        }
        return sb.toString()
    }

    /** Decodes canonical base64url; returns null for any other input (padding, wrong alphabet, non-canonical bits). */
    fun decode(text: String): ByteArray? {
        if (text.length % 4 == 1) return null
        val out = ByteArray(text.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var o = 0
        for (c in text) {
            val v = if (c.code < 128) INDEX[c.code] else -1
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = (buffer ushr bits and 0xff).toByte()
            }
        }
        if (bits > 0 && buffer and ((1 shl bits) - 1) != 0) return null // non-canonical trailing bits
        return out
    }
}
