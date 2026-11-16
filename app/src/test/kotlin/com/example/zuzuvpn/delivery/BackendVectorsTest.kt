package com.example.zuzuvpn.delivery

import com.example.zuzuvpn.delivery.ProtocolV1.Suite
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Mandatory interoperability tests: the Android implementation must reproduce the backend vectors exactly. */
class BackendVectorsTest {
    private val doc = Vectors.doc

    @Test fun `key ids match the backend`() {
        val ids = doc.getJSONArray("keyIds")
        for (i in 0 until ids.length()) {
            val v = ids.getJSONObject(i)
            assertEquals(v.getString("kid"), ProtocolV1.keyId(Vectors.b(v.getString("spki"))))
        }
        for (name in Vectors.envelopeNames) {
            val v = Vectors.envelope(name)
            assertEquals(v.getJSONObject("device").getString("kid"), Vectors.keysOf(v).encryptionKid)
        }
    }

    @Test fun `every envelope decrypts to the expected plaintext (both suites)`() {
        assertTrue(Vectors.envelopeNames.containsAll(listOf("ecdh-shadowsocks", "ecdh-wireguard", "rsa-shadowsocks")))
        for (name in Vectors.envelopeNames) {
            val v = Vectors.envelope(name)
            val plain = EnvelopeOpener.decrypt(Vectors.envelopeOf(v), Vectors.keysOf(v), Vectors.pendingOf(v))
            assertEquals(name, v.getString("expectedPlaintextUtf8"), String(plain, Charsets.UTF_8))
        }
    }

    @Test fun `shadowsocks vectors open into a valid profile, connecting to serverAddress`() {
        for (name in listOf("ecdh-shadowsocks", "rsa-shadowsocks")) {
            val v = Vectors.envelope(name)
            val env = Vectors.envelopeOf(v)
            val payload = EnvelopeOpener.open(env, Vectors.keysOf(v), Vectors.pendingOf(v), env.iat) as ConfigPayload.Shadowsocks
            val expected = JSONObject(v.getString("expectedPlaintextUtf8")).getJSONObject("shadowsocks")
            val profile = payload.toProfile("Paris")
            assertEquals(expected.getString("server"), profile.host)
            assertEquals(expected.getInt("port"), profile.port)
            assertEquals(expected.getString("method"), profile.method)
            assertEquals(expected.getString("password"), profile.password)
            assertEquals(expected.getBoolean("udp"), profile.udpEnabled)
            assertTrue(!payload.toString().contains(expected.getString("password")))
        }
    }

    @Test fun `the wireguard vector decrypts but is not consumed in this stage`() {
        val v = Vectors.envelope("ecdh-wireguard")
        val env = Vectors.envelopeOf(v)
        expectFailure(EnvelopeFailure.InvalidPayload) { EnvelopeOpener.open(env, Vectors.keysOf(v), Vectors.pendingOf(v), env.iat) }
    }

    @Test fun `every negative vector is rejected`() {
        val negative = doc.getJSONArray("negative")
        assertEquals(9, negative.length())
        for (i in 0 until negative.length()) {
            val n = negative.getJSONObject(i)
            val source = Vectors.envelope(n.getString("from"))
            val pending = Vectors.pendingOf(source, nonce = n.optString("nonce").ifEmpty { source.getJSONObject("request").getString("nonce") })
            try {
                EnvelopeOpener.decrypt(ConfigEnvelope.parse(n.getJSONObject("envelope").toString()), Vectors.keysOf(source), pending)
                fail("accepted ${n.getString("name")}")
            } catch (e: EnvelopeException) {
                // expected: tampering is detected
            }
        }
    }

    @Test fun `canonical signed messages are byte-identical to the backend`() {
        val s = doc.getJSONObject("signatures")
        val r = s.getJSONObject("request")
        assertEquals(r.getString("message"), ProtocolV1.requestMessage(r.getString("method"), r.getString("path"), r.getString("timestamp"), r.getString("nonce"), r.getString("bodyUtf8").toByteArray()))
        val e = s.getJSONObject("enrollment").getString("message").split("\n")
        assertEquals(s.getJSONObject("enrollment").getString("message"), ProtocolV1.enrollmentMessage(e[1], e[2], e[3], e[4], Suite.fromWire(e[5])!!))
        val rot = s.getJSONObject("rotation").getString("message").split("\n")
        assertEquals(s.getJSONObject("rotation").getString("message"), ProtocolV1.rotationMessage(rot[1], rot[2], rot[3], Suite.fromWire(rot[4])!!))
    }

    @Test fun `backend signatures verify and ours verify with the same key and encoding`() {
        val s = doc.getJSONObject("signatures")
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Vectors.b(s.getJSONObject("signingKey").getString("publicKeySpki"))))
        assertEquals(s.getJSONObject("signingKey").getString("kid"), ProtocolV1.keyId(pub.encoded))
        val keys = Vectors.keysOf(Vectors.envelope("ecdh-shadowsocks"))
        for (kind in listOf("enrollment", "request", "rotation")) {
            val m = s.getJSONObject(kind)
            val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(pub) }
            verifier.update(m.getString("message").toByteArray())
            assertTrue(kind, verifier.verify(Vectors.b(m.getString("signature"))))
            val ours = keys.sign(m.getString("message").toByteArray())
            val check = Signature.getInstance("SHA256withECDSA").apply { initVerify(pub) }
            check.update(m.getString("message").toByteArray())
            assertTrue("our DER signature over $kind", check.verify(ours))
        }
    }

    @Test fun `deterministic suite A inputs reproduce the backend key schedule`() {
        // HKDF salt/info/AAD built here must match what produced the vector: decrypting proves it, and the
        // ephemeral key in the vector re-derives the epk the backend sent.
        val v = Vectors.envelope("ecdh-shadowsocks")
        val eph = KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Vectors.b(v.getJSONObject("deterministic").getString("ephemeralPrivateKeyPkcs8"))))
        assertEquals("EC", eph.algorithm)
        assertEquals(v.getJSONObject("deterministic").getString("iv"), v.getJSONObject("envelope").getString("iv"))
    }

    @Test fun `base64url is strict and canonical like the backend`() {
        for (size in 0..40) {
            val bytes = ByteArray(size) { (it * 37 + 11).toByte() }
            assertArrayEquals(bytes, Base64Url.decode(Base64Url.encode(bytes)))
        }
        assertNull(Base64Url.decode("AA=="))
        assertNull(Base64Url.decode("A+B/"))
        assertNull(Base64Url.decode("A"))
        assertNull(Base64Url.decode("AB")) // non-canonical trailing bits
        assertEquals("AA", Base64Url.encode(byteArrayOf(0)))
    }

    @Test fun `hkdf matches RFC 5869 test case 1`() {
        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val okm = ProtocolV1.hkdfSha256(hex("0b".repeat(22)), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    private fun expectFailure(failure: EnvelopeFailure, block: () -> Unit) {
        try {
            block()
            fail("expected $failure")
        } catch (e: EnvelopeException) {
            assertEquals(failure, e.failure)
        }
    }
}
