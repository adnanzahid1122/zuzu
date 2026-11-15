package com.zuzuvpn.shadowsocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShadowsocksProfileTest {
    // 16 zero bytes. A placeholder for tests, not a real key.
    private val key16 = "AAAAAAAAAAAAAAAAAAAAAA=="
    private val method = ShadowsocksProfile.METHOD_2022_BLAKE3_AES_128_GCM

    private fun profile(host: String = "vpn.example.com", port: Int = 8388, key: String = key16, m: String = method) =
        ShadowsocksProfile(name = "t", host = host, port = port, password = key, method = m)

    @Test fun validProfileHasNoProblems() {
        assertEquals(emptyList<ProfileProblem>(), ShadowsocksProfileValidator.validate(profile()))
        assertEquals(emptyList<ProfileProblem>(), ShadowsocksProfileValidator.validate(profile(host = "203.0.113.7")))
        assertEquals(emptyList<ProfileProblem>(), ShadowsocksProfileValidator.validate(profile(host = "2001:db8::1")))
    }

    @Test fun rejectsWrongKeyLength() {
        // 32 bytes is an aes-256 key, not valid for aes-128.
        val key32 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        assertEquals(listOf(ProfileProblem.WrongKeyLength), ShadowsocksProfileValidator.validate(profile(key = key32)))
    }

    @Test fun rejectsNonBase64Key() {
        assertEquals(listOf(ProfileProblem.InvalidKeyEncoding), ShadowsocksProfileValidator.validate(profile(key = "not a key!")))
    }

    @Test fun acceptsIdentityChain() {
        assertTrue(ShadowsocksProfileValidator.validate(profile(key = "$key16:$key16")).isEmpty())
    }

    @Test fun rejectsBadHostPortAndMethod() {
        val problems = ShadowsocksProfileValidator.validate(profile(host = "999.1.1.1", port = 70000, m = "aes-256-gcm"))
        assertTrue(ProfileProblem.InvalidHost in problems)
        assertTrue(ProfileProblem.InvalidPort in problems)
        assertTrue(ProfileProblem.UnsupportedMethod in problems)
    }

    @Test fun toStringRedactsKey() {
        assertFalse(profile().toString().contains(key16))
    }

    @Test fun parsesSip002PercentEncoded() {
        val p = ShadowsocksUri.parse("ss://$method:AAAAAAAAAAAAAAAAAAAAAA%3D%3D@vpn.example.com:8388#My%20Server")
        assertEquals("vpn.example.com", p.host)
        assertEquals(8388, p.port)
        assertEquals(method, p.method)
        assertEquals(key16, p.password)
        assertEquals("My Server", p.name)
    }

    @Test fun keepsPlusInPercentEncodedKey() {
        val p = ShadowsocksUri.parse("ss://$method:ab+c/d==@h.example:1")
        assertEquals("ab+c/d==", p.password)
    }

    @Test fun parsesSip002Base64UserInfoAndIpv6() {
        val userInfo = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("$method:$key16".toByteArray())
        val p = ShadowsocksUri.parse("ss://$userInfo@[2001:db8::1]:443/?#")
        assertEquals("2001:db8::1", p.host)
        assertEquals(443, p.port)
        assertEquals(key16, p.password)
    }

    @Test fun parsesLegacyForm() {
        val all = java.util.Base64.getEncoder().encodeToString("$method:$key16@203.0.113.7:8388".toByteArray())
        val p = ShadowsocksUri.parse("ss://$all#legacy")
        assertEquals("203.0.113.7", p.host)
        assertEquals(key16, p.password)
        assertEquals("legacy", p.name)
    }

    @Test fun rejectsPluginsAndGarbage() {
        expectReason(ShadowsocksUriException.Reason.PluginNotSupported) {
            ShadowsocksUri.parse("ss://$method:x@h.example:1/?plugin=obfs-local%3Bobfs%3Dhttp")
        }
        expectReason(ShadowsocksUriException.Reason.NotShadowsocksUri) { ShadowsocksUri.parse("vmess://abc") }
        expectReason(ShadowsocksUriException.Reason.InvalidPort) { ShadowsocksUri.parse("ss://$method:x@h.example:0") }
    }

    @Test fun ssConfigEscapesAndSelectsMode() {
        val json = SsConfigBuilder.build(profile().copy(udpEnabled = false), "203.0.113.7", 40000)
        assertTrue(json.contains("\"mode\":\"tcp_only\""))
        assertTrue(json.contains("\"local_address\":\"127.0.0.1\""))
        assertEquals("\"a\\\"b\\\\c\"", SsConfigBuilder.jsonString("a\"b\\c"))
    }

    @Test fun hevConfigUsesMapDnsOnlyWithoutUdp() {
        assertFalse(HevConfigBuilder.build(1080, "u1", "p1", udpEnabled = true).contains("mapdns"))
        assertTrue(HevConfigBuilder.build(1080, "u1", "p1", udpEnabled = false).contains("mapdns"))
    }

    // ---- Key format: must match shadowsocks-rust (STANDARD alphabet, padding optional/canonical) ----

    private fun keyProblems(key: String) = ShadowsocksProfileValidator.validate(profile(key = key))

    // 16 bytes of 0xFB: exercises '+' and '/' in standard base64.
    private val keyStdSymbols = "+/v7+/v7+/v7+/v7+/v7+w=="

    @Test fun acceptsStandardAlphabetSymbols() {
        assertEquals(emptyList<ProfileProblem>(), keyProblems(keyStdSymbols))
    }

    @Test fun acceptsUnpaddedKey() {
        assertEquals(emptyList<ProfileProblem>(), keyProblems("AAAAAAAAAAAAAAAAAAAAAA"))
    }

    @Test fun acceptsSurroundingWhitespace() {
        // Trimmed identically by the validator and the config builder.
        assertEquals(emptyList<ProfileProblem>(), keyProblems("  $key16\n"))
    }

    @Test fun rejectsUrlSafeAlphabet() {
        // Same bytes as keyStdSymbols in base64url; the native decoder rejects '-' and '_'.
        assertEquals(listOf(ProfileProblem.InvalidKeyEncoding), keyProblems("-_v7-_v7-_v7-_v7-_v7-w=="))
    }

    @Test fun rejectsInvalidPadding() {
        assertEquals(listOf(ProfileProblem.InvalidKeyEncoding), keyProblems("AAAAAAAAAAAAAAAAAAAAAA=")) // needs ==
        assertEquals(listOf(ProfileProblem.InvalidKeyEncoding), keyProblems("AAAAAAAAAAAAAAAAAAAAAA===")) // too much
        assertEquals(listOf(ProfileProblem.InvalidKeyEncoding), keyProblems("AAAAAAAAAAAAAAAAAAAAAAAA=")) // none due
        assertEquals(listOf(ProfileProblem.InvalidKeyEncoding), keyProblems("AAAAAAAAAAA=AAAAAAAAAA==")) // inside
    }

    @Test fun rejectsMalformedKeys() {
        assertEquals(listOf(ProfileProblem.InvalidKeyEncoding), keyProblems("AAAAAAAAAAA AAAAAAAAAAA==")) // inner space
        assertEquals(listOf(ProfileProblem.WrongKeyLength), keyProblems("AAAAAAAAAAAAAAAAAAAAAAA")) // 23 chars = 17 bytes, unpadded
        assertEquals(listOf(ProfileProblem.InvalidKeyEncoding), keyProblems("AAAAAAAAAAAAAAAAAAAAA")) // len % 4 == 1
        assertEquals(listOf(ProfileProblem.InvalidKeyEncoding), keyProblems("AAAAAAAAAAAAAAAAAAAAAB==")) // trailing bits
        assertEquals(listOf(ProfileProblem.InvalidKeyEncoding), keyProblems("AAAAAAAAAAAAAAAAAAAAA*=="))
    }

    @Test fun rejectsWrongKeyLengths() {
        assertEquals(listOf(ProfileProblem.WrongKeyLength), keyProblems("AAAAAAAAAAAAAAAAAAAA")) // 15 bytes
        assertEquals(listOf(ProfileProblem.WrongKeyLength), keyProblems("AAAAAAAAAAAAAAAAAAAAAAA=")) // 17 bytes
    }

    @Test fun validatesEveryPartOfIdentityChain() {
        assertTrue(keyProblems("$key16:$keyStdSymbols:$key16").isEmpty())
        assertEquals(listOf(ProfileProblem.WrongKeyLength), keyProblems("$key16:AAAAAAAAAAAAAAAAAAAA"))
        assertEquals(listOf(ProfileProblem.InvalidKeyEncoding), keyProblems("$key16:-_v7-_v7-_v7-_v7-_v7-w=="))
        assertEquals(listOf(ProfileProblem.InvalidKeyEncoding), keyProblems("$key16:"))
        assertEquals(listOf(ProfileProblem.InvalidKeyEncoding), keyProblems(":$key16"))
    }

    @Test fun lenientDecoderAcceptsUrlSafeAlphabet() {
        // ss:// link parts may be base64url; raw keys may not (see rejectsUrlSafeAlphabet).
        val expected = ByteArray(16) { 0xFB.toByte() }
        org.junit.Assert.assertArrayEquals(expected, Base64Codec.decodeLenient("-_v7-_v7-_v7-_v7-_v7-w"))
        org.junit.Assert.assertArrayEquals(expected, Base64Codec.decodeLenient("+/v7+/v7+/v7+/v7+/v7+w=="))
        assertEquals(null, Base64Codec.decodeKey("-_v7-_v7-_v7-_v7-_v7-w"))
        assertEquals(16, Base64Key.length(keyStdSymbols))
        assertEquals(null, Base64Key.length("-_v7-_v7-_v7-_v7-_v7-w=="))
    }

    @Test fun uriWithoutHostIsReportedAsMissingHost() {
        expectReason(ShadowsocksUriException.Reason.MissingHost) { ShadowsocksUri.parse("ss://$method:$key16@:8388") }
        expectReason(ShadowsocksUriException.Reason.MissingHost) { ShadowsocksUri.parse("ss://$method:$key16@[]:8388") }
    }

    // ---- chacha20-ietf-poly1305 (classic AEAD, password-based) ----

    private val chacha = ShadowsocksProfile.METHOD_CHACHA20_IETF_POLY1305
    // Placeholder password exercising characters that matter: ':', '@', '+', '/', '=', quote, spaces.
    private val classicPassword = " pass:w@rd+/= \"x\" "

    private fun classic(password: String = classicPassword) =
        ShadowsocksProfile(name = "t", host = "203.0.113.9", port = 443, password = password, method = chacha)

    @Test fun chachaIsSupportedAlongside2022() {
        assertEquals(listOf(ShadowsocksProfile.METHOD_2022_BLAKE3_AES_128_GCM, chacha), ShadowsocksProfile.SUPPORTED_METHODS)
        assertTrue(ShadowsocksProfileValidator.validate(classic()).isEmpty())
        assertTrue(ShadowsocksProfileValidator.validate(profile()).isEmpty()) // 2022 still valid
    }

    @Test fun chachaPasswordIsFreeFormAndKeptVerbatim() {
        // Not base64 and not 16 bytes: irrelevant for a password-based cipher.
        assertTrue(ShadowsocksProfileValidator.validate(classic("short")).isEmpty())
        assertEquals(classicPassword, classic().effectivePassword)
        // 2022 keys are still trimmed.
        assertEquals(key16, profile(key = "  $key16 ").effectivePassword)
    }

    @Test fun chachaPasswordProblems() {
        assertEquals(listOf(ProfileProblem.EmptyKey), ShadowsocksProfileValidator.validate(classic("")))
        assertEquals(listOf(ProfileProblem.EmptyKey), ShadowsocksProfileValidator.validate(classic("   ")))
        assertEquals(listOf(ProfileProblem.InvalidPasswordCharacters), ShadowsocksProfileValidator.validate(classic("secret\n")))
    }

    @Test fun otherClassicCiphersStayUnsupported() {
        listOf("aes-256-gcm", "aes-128-gcm", "chacha20-poly1305", "2022-blake3-chacha20-poly1305", "rc4-md5").forEach {
            assertTrue(it, ProfileProblem.UnsupportedMethod in ShadowsocksProfileValidator.validate(classic().copy(method = it)))
        }
    }

    @Test fun configBuilderPassesChachaPasswordExactly() {
        val json = SsConfigBuilder.build(classic(), "203.0.113.9", 40000)
        assertTrue(json.contains("\"method\":\"chacha20-ietf-poly1305\""))
        assertTrue(json.contains("\"password\":\" pass:w@rd+/= \\\"x\\\" \""))
        assertTrue(json.contains("\"mode\":\"tcp_and_udp\""))
        // 2022 key is trimmed in the config.
        assertTrue(SsConfigBuilder.build(profile(key = " $key16 "), "203.0.113.9", 1).contains("\"password\":\"$key16\""))
    }

    @Test fun parsesSip002Base64UserInfoForChacha() {
        // The common SIP002 form for classic ciphers: base64url("method:password"), no padding.
        val userInfo = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("$chacha:p@ss:w+rd/=".toByteArray())
        val p = ShadowsocksUri.parse("ss://$userInfo@203.0.113.9:443#Test%20server")
        assertEquals(chacha, p.method)
        assertEquals("p@ss:w+rd/=", p.password)
        assertEquals("203.0.113.9", p.host)
        assertEquals(443, p.port)
        assertEquals("Test server", p.name)
        assertTrue(ShadowsocksProfileValidator.validate(p).isEmpty())
    }

    @Test fun parsesPercentEncodedAndLegacyChacha() {
        val p1 = ShadowsocksUri.parse("ss://CHACHA20-IETF-POLY1305:p%40ss%3Aw+rd@h.example:443")
        assertEquals(chacha, p1.method) // method is case-normalised
        assertEquals("p@ss:w+rd", p1.password)
        val legacy = java.util.Base64.getEncoder().encodeToString("$chacha:p@ss:w@rd@203.0.113.9:8443".toByteArray())
        val p2 = ShadowsocksUri.parse("ss://$legacy")
        assertEquals("p@ss:w@rd", p2.password) // '@' inside the password survives
        assertEquals("203.0.113.9", p2.host)
        assertEquals(8443, p2.port)
    }

    @Test fun chachaProfileStringsDoNotLeakPassword() {
        assertFalse(classic("TopSecretPw").toString().contains("TopSecretPw"))
    }

    // ---- Secrets never appear in messages ----

    @Test fun uriErrorsDoNotContainTheKey() {
        val secret = "SECRETKEYabc%zz"
        try {
            ShadowsocksUri.parse("ss://$method:$secret@h.example:8388")
            fail("expected failure")
        } catch (e: ShadowsocksUriException) {
            assertEquals(ShadowsocksUriException.Reason.Malformed, e.reason)
            assertFalse(e.message.orEmpty().contains("SECRET"))
            assertEquals(null, e.cause)
        }
    }

    @Test fun profileStringFormsDoNotContainTheKey() {
        val p = profile(key = keyStdSymbols)
        assertFalse(p.toString().contains(keyStdSymbols))
        assertFalse("$p".contains("+/v7"))
    }

    private fun expectReason(reason: ShadowsocksUriException.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: ShadowsocksUriException) {
            assertEquals(reason, e.reason)
        }
    }
}
