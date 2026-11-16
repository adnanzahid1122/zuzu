package com.example.zuzuvpn.delivery

import com.example.zuzuvpn.delivery.ProtocolV1.Suite
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/** Client-side verification rules of config-delivery-v1.md §3, for both suites. */
class EnvelopeOpenerTest {
    private val ecdh = Vectors.envelope("ecdh-shadowsocks")
    private val rsa = Vectors.envelope("rsa-shadowsocks")

    private fun expect(failure: EnvelopeFailure, block: () -> Unit) {
        try {
            block()
            fail("expected $failure")
        } catch (e: EnvelopeException) {
            assertEquals(failure, e.failure)
        }
    }

    private fun open(vector: org.json.JSONObject, env: ConfigEnvelope = Vectors.envelopeOf(vector), keys: DeviceKeys = Vectors.keysOf(vector), pending: PendingRequest = Vectors.pendingOf(vector), now: Long = env.iat) =
        EnvelopeOpener.open(env, keys, pending, now)

    /** Keys that claim the vector's identity but hold a different private key (a different device). */
    private fun impostor(vector: org.json.JSONObject): DeviceKeys {
        val real = Vectors.keysOf(vector)
        val other = if (real.suite == Suite.ECDH_P256) KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        else KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val wrong = JcaDeviceKeys(real.suite, other.private, other.public, other.private, other.public)
        return object : DeviceKeys by wrong {
            override val encryptionSpki = real.encryptionSpki
            override val encryptionKid = real.encryptionKid
        }
    }

    @Test fun `valid envelopes open for both suites`() {
        listOf(ecdh, rsa).forEach { open(it) }
    }

    @Test fun `another device's key cannot decrypt`() {
        expect(EnvelopeFailure.DecryptionFailed) { open(ecdh, keys = impostor(ecdh)) }
        expect(EnvelopeFailure.DecryptionFailed) { open(rsa, keys = impostor(rsa)) }
    }

    @Test fun `tampered ciphertext is rejected`() {
        for (v in listOf(ecdh, rsa)) {
            val env = Vectors.envelopeOf(v)
            val ct = Vectors.b(env.ct).also { it[3] = (it[3].toInt() xor 1).toByte() }
            expect(EnvelopeFailure.DecryptionFailed) { open(v, env.copy(ct = Base64Url.encode(ct))) }
        }
    }

    @Test fun `tampered authenticated metadata is rejected`() {
        val env = Vectors.envelopeOf(ecdh)
        expect(EnvelopeFailure.DecryptionFailed) { open(ecdh, env.copy(exp = env.exp + 1)) }
        expect(EnvelopeFailure.DecryptionFailed) { open(ecdh, env.copy(iat = env.iat - 1)) }
        val other = "00000000-0000-4000-8000-000000000000"
        expect(EnvelopeFailure.DecryptionFailed) { open(ecdh, env.copy(serverId = other), pending = Vectors.pendingOf(ecdh).copy(serverId = other)) }
        expect(EnvelopeFailure.WrongServer) { open(ecdh, env.copy(serverId = other)) }
        expect(EnvelopeFailure.WrongDevice) { open(ecdh, env.copy(deviceId = other)) }
    }

    @Test fun `a response for another request nonce is rejected (replay)`() {
        expect(EnvelopeFailure.DecryptionFailed) { open(ecdh, pending = Vectors.pendingOf(ecdh, nonce = "b3RoZXItbm9uY2Utb3RoZXI")) }
        expect(EnvelopeFailure.DecryptionFailed) { open(rsa, pending = Vectors.pendingOf(rsa, nonce = "b3RoZXItbm9uY2Utb3RoZXI")) }
    }

    @Test fun `expiry is enforced with 120 s clock skew`() {
        val env = Vectors.envelopeOf(ecdh)
        open(ecdh, now = env.exp + 119)
        expect(EnvelopeFailure.Expired) { open(ecdh, now = env.exp + 120) }
        expect(EnvelopeFailure.Expired) { open(rsa, now = Vectors.envelopeOf(rsa).exp + 3600) }
    }

    @Test fun `incorrect key identifier is rejected before any decryption`() {
        expect(EnvelopeFailure.WrongKey) { open(ecdh, Vectors.envelopeOf(ecdh).copy(kid = "AAAAAAAAAAAAAAAAAAAAAA")) }
    }

    @Test fun `unsupported version and suite, and suite downgrade, are rejected`() {
        val env = Vectors.envelopeOf(ecdh)
        expect(EnvelopeFailure.UnsupportedVersion) { open(ecdh, env.copy(v = 2)) }
        expect(EnvelopeFailure.UnsupportedSuite) { open(ecdh, env.copy(suite = "ZCP2-SOMETHING")) }
        // An RSA envelope is never accepted by an ECDH-key device (and vice versa).
        expect(EnvelopeFailure.SuiteMismatch) { open(ecdh, Vectors.envelopeOf(rsa).copy(kid = env.kid, deviceId = env.deviceId)) }
        expect(EnvelopeFailure.SuiteMismatch) { open(rsa, Vectors.envelopeOf(ecdh)) }
    }

    @Test fun `malformed key fields and sizes are rejected`() {
        val env = Vectors.envelopeOf(ecdh)
        expect(EnvelopeFailure.Malformed) { open(ecdh, env.copy(epk = null)) }
        expect(EnvelopeFailure.Malformed) { open(ecdh, env.copy(ek = "AAAA")) }
        expect(EnvelopeFailure.Malformed) { open(ecdh, env.copy(iv = "AAAA")) }
        expect(EnvelopeFailure.Malformed) { open(ecdh, env.copy(ct = "not base64!")) }
        expect(EnvelopeFailure.Malformed) { ConfigEnvelope.parse("{\"v\":1}") }
        expect(EnvelopeFailure.Malformed) { ConfigEnvelope.parse("not json") }
    }
}
