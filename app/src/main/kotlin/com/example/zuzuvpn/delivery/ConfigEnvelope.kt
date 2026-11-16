package com.example.zuzuvpn.delivery

import com.example.zuzuvpn.delivery.ProtocolV1.Suite
import com.zuzuvpn.shadowsocks.ShadowsocksProfile
import com.zuzuvpn.shadowsocks.ShadowsocksProfileValidator
import org.json.JSONException
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** An encrypted configuration response (docs/crypto/config-delivery-v1.md §3). */
data class ConfigEnvelope(
    val v: Int,
    val suite: String,
    val kid: String,
    val deviceId: String,
    val serverId: String,
    val iat: Long,
    val exp: Long,
    val epk: String?,
    val ek: String?,
    val iv: String,
    val ct: String,
) {
    fun toJson(): String = JSONObject().apply {
        put("v", v); put("suite", suite); put("kid", kid); put("deviceId", deviceId); put("serverId", serverId)
        put("iat", iat); put("exp", exp); epk?.let { put("epk", it) }; ek?.let { put("ek", it) }; put("iv", iv); put("ct", ct)
    }.toString()

    companion object {
        /** Parses the JSON shape only; all cryptographic and semantic checks happen in [EnvelopeOpener]. */
        fun parse(json: String): ConfigEnvelope = try {
            val o = JSONObject(json)
            fun str(k: String) = (o.opt(k) as? String) ?: throw EnvelopeException(EnvelopeFailure.Malformed)
            fun optStr(k: String) = if (o.has(k)) (o.opt(k) as? String ?: throw EnvelopeException(EnvelopeFailure.Malformed)) else null
            fun long(k: String): Long = when (val n = o.opt(k)) {
                is Int -> n.toLong()
                is Long -> n
                else -> throw EnvelopeException(EnvelopeFailure.Malformed)
            }
            ConfigEnvelope(
                v = (o.opt("v") as? Int) ?: throw EnvelopeException(EnvelopeFailure.Malformed),
                suite = str("suite"), kid = str("kid"), deviceId = str("deviceId"), serverId = str("serverId"),
                iat = long("iat"), exp = long("exp"), epk = optStr("epk"), ek = optStr("ek"), iv = str("iv"), ct = str("ct"),
            )
        } catch (e: JSONException) {
            throw EnvelopeException(EnvelopeFailure.Malformed)
        }
    }
}

enum class EnvelopeFailure {
    Malformed,
    UnsupportedVersion,
    UnsupportedSuite,
    /** Envelope suite differs from the suite of this device's key (never accepted: no downgrade). */
    SuiteMismatch,
    /** kid is not this device's encryption key. */
    WrongKey,
    WrongDevice,
    WrongServer,
    /** Authentication failed: tampered data, wrong key, or not the response to this request's nonce. */
    DecryptionFailed,
    NonceMismatch,
    Expired,
    InvalidPayload,
}

class EnvelopeException(val failure: EnvelopeFailure) : Exception(failure.name)

/** What this device expects for the response to one pending request. */
data class PendingRequest(val serverId: String, val deviceId: String, val nonce: String)

/** Decrypted payload. Only ever held in memory; [toString] redacts secrets. */
sealed interface ConfigPayload {
    val serverId: String

    data class Shadowsocks(override val serverId: String, val server: String, val port: Int, val method: String, val password: String, val udp: Boolean) : ConfigPayload {
        fun toProfile(name: String) = ShadowsocksProfile(name = name, host = server, port = port, password = password, method = method, udpEnabled = udp)
        override fun toString() = "ConfigPayload.Shadowsocks(serverId=$serverId, method=$method, udp=$udp, <redacted>)"
    }
}

/**
 * Opens an envelope with this device's keys, in the order of config-delivery-v1.md §3:
 *  1. version, suite (must equal the suite of this device's key), kid, deviceId, serverId;
 *  2. exactly one of epk/ek, 12-byte iv, ct ≥ 16 bytes;
 *  3. derive/unwrap the CEK, AES-256-GCM decrypt with the AAD rebuilt from **this request's** nonce;
 *  4. payload nonce equals the pending nonce, protocol and fields valid, exp > now − 120 s.
 * Any failure rejects the envelope; nothing is retried with other parameters.
 */
object EnvelopeOpener {
    const val CLOCK_SKEW_SECONDS = 120L

    fun open(envelope: ConfigEnvelope, keys: DeviceKeys, pending: PendingRequest, nowSeconds: Long): ConfigPayload {
        val plaintext = decrypt(envelope, keys, pending)
        try {
            val payload = parsePayload(plaintext, pending)
            if (envelope.exp <= nowSeconds - CLOCK_SKEW_SECONDS) fail(EnvelopeFailure.Expired)
            return payload
        } finally {
            plaintext.fill(0)
        }
    }

    /** Steps 1–3: header checks and authenticated decryption. Returns the payload bytes (caller zeroes them). */
    fun decrypt(envelope: ConfigEnvelope, keys: DeviceKeys, pending: PendingRequest): ByteArray {
        if (envelope.v != ProtocolV1.VERSION) fail(EnvelopeFailure.UnsupportedVersion)
        val suite = Suite.fromWire(envelope.suite) ?: fail(EnvelopeFailure.UnsupportedSuite)
        if (suite != keys.suite) fail(EnvelopeFailure.SuiteMismatch)
        if (envelope.kid != keys.encryptionKid) fail(EnvelopeFailure.WrongKey)
        if (envelope.deviceId != pending.deviceId) fail(EnvelopeFailure.WrongDevice)
        if (envelope.serverId != pending.serverId) fail(EnvelopeFailure.WrongServer)

        val keyMaterial = when (suite) {
            Suite.ECDH_P256 -> if (envelope.epk == null || envelope.ek != null) fail(EnvelopeFailure.Malformed) else envelope.epk
            Suite.RSA3072 -> if (envelope.ek == null || envelope.epk != null) fail(EnvelopeFailure.Malformed) else envelope.ek
        }
        val iv = Base64Url.decode(envelope.iv) ?: fail(EnvelopeFailure.Malformed)
        val ct = Base64Url.decode(envelope.ct) ?: fail(EnvelopeFailure.Malformed)
        val material = Base64Url.decode(keyMaterial) ?: fail(EnvelopeFailure.Malformed)
        if (iv.size != 12 || ct.size < 16) fail(EnvelopeFailure.Malformed)

        val plaintext: ByteArray
        var cek: ByteArray? = null
        try {
            cek = when (suite) {
                Suite.ECDH_P256 -> {
                    val z = keys.agree(material)
                    try {
                        ProtocolV1.hkdfSha256(
                            ikm = z,
                            salt = ProtocolV1.hkdfSalt(material, keys.encryptionSpki),
                            info = ProtocolV1.hkdfInfo(envelope.kid, envelope.deviceId, envelope.serverId, pending.nonce),
                            length = 32,
                        )
                    } finally {
                        z.fill(0)
                    }
                }
                Suite.RSA3072 -> keys.unwrap(material).also { if (it.size != 32) throw IllegalStateException() }
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(ProtocolV1.aad(envelope.v, envelope.suite, envelope.kid, envelope.deviceId, envelope.serverId, envelope.iat, envelope.exp, keyMaterial, pending.nonce))
            plaintext = cipher.doFinal(ct)
        } catch (e: Exception) {
            fail(EnvelopeFailure.DecryptionFailed)
        } finally {
            cek?.fill(0)
        }
        return plaintext
    }

    private fun parsePayload(plaintext: ByteArray, pending: PendingRequest): ConfigPayload {
        val o = try {
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (e: JSONException) {
            fail(EnvelopeFailure.InvalidPayload)
        }
        if (o.opt("v") != 1) fail(EnvelopeFailure.InvalidPayload)
        if (o.opt("nonce") != pending.nonce) fail(EnvelopeFailure.NonceMismatch)
        if (o.opt("serverId") != pending.serverId) fail(EnvelopeFailure.WrongServer)
        when (o.opt("protocol")) {
            "shadowsocks" -> {
                val ss = o.optJSONObject("shadowsocks") ?: fail(EnvelopeFailure.InvalidPayload)
                val server = ss.opt("server") as? String ?: fail(EnvelopeFailure.InvalidPayload)
                val port = ss.opt("port") as? Int ?: fail(EnvelopeFailure.InvalidPayload)
                val method = ss.opt("method") as? String ?: fail(EnvelopeFailure.InvalidPayload)
                val password = ss.opt("password") as? String ?: fail(EnvelopeFailure.InvalidPayload)
                val udp = ss.opt("udp") as? Boolean ?: fail(EnvelopeFailure.InvalidPayload)
                val payload = ConfigPayload.Shadowsocks(pending.serverId, server, port, method, password, udp)
                // Same rules the engine enforces (host, port, cipher, key format).
                if (ShadowsocksProfileValidator.validate(payload.toProfile("check")).isNotEmpty()) fail(EnvelopeFailure.InvalidPayload)
                return payload
            }
            // WireGuard delivery is decrypted by the same code path but not consumed in this stage
            // (the app requests "shadowsocks" only; the WireGuard engine is a separate milestone).
            else -> fail(EnvelopeFailure.InvalidPayload)
        }
    }

    private fun fail(failure: EnvelopeFailure): Nothing = throw EnvelopeException(failure)
}
