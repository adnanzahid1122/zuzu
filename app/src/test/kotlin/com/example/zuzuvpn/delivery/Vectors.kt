package com.example.zuzuvpn.delivery

import com.example.zuzuvpn.delivery.ProtocolV1.Suite
import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** The Control Plane's interoperability vectors (docs/crypto/test-vectors.json, copied verbatim). ALL KEYS ARE TEST-ONLY. */
object Vectors {
    val doc: JSONObject by lazy {
        JSONObject(Vectors::class.java.classLoader!!.getResource("config-delivery-test-vectors.json")!!.readText())
    }

    fun b(s: String): ByteArray = Base64Url.decode(s) ?: error("bad base64url in vectors")

    fun envelope(name: String): JSONObject {
        val list = doc.getJSONArray("envelopes")
        return (0 until list.length()).map { list.getJSONObject(it) }.first { it.getString("name") == name }
    }

    val envelopeNames: List<String> get() = doc.getJSONArray("envelopes").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }

    /** Device keys of a vector (software JCA keys standing in for Keystore keys). */
    fun keysOf(vector: JSONObject): JcaDeviceKeys {
        val suite = Suite.fromWire(vector.getString("suite"))!!
        val algorithm = if (suite == Suite.ECDH_P256) "EC" else "RSA"
        val device = vector.getJSONObject("device")
        val signing = doc.getJSONObject("signatures").getJSONObject("signingKey")
        val ec = KeyFactory.getInstance("EC")
        return JcaDeviceKeys(
            suite = suite,
            signingPrivate = ec.generatePrivate(PKCS8EncodedKeySpec(b(signing.getString("privateKeyPkcs8")))),
            signingPublic = ec.generatePublic(X509EncodedKeySpec(b(signing.getString("publicKeySpki")))),
            encryptionPrivate = KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(b(device.getString("privateKeyPkcs8")))),
            encryptionPublic = KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(b(device.getString("publicKeySpki")))),
        )
    }

    fun envelopeOf(vector: JSONObject): ConfigEnvelope = ConfigEnvelope.parse(vector.getJSONObject("envelope").toString())

    fun pendingOf(vector: JSONObject, nonce: String = vector.getJSONObject("request").getString("nonce")) =
        PendingRequest(serverId = vector.getJSONObject("request").getString("serverId"), deviceId = vector.getJSONObject("device").getString("deviceId"), nonce = nonce)
}
