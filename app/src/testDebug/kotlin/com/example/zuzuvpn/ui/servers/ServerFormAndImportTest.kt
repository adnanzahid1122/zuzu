package com.example.zuzuvpn.ui.servers

import com.zuzuvpn.shadowsocks.ProfileProblem
import com.zuzuvpn.shadowsocks.ShadowsocksProfile
import com.zuzuvpn.shadowsocks.ShadowsocksUriException.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ServerFormAndImportTest {
    private val method = ShadowsocksProfile.METHOD_2022_BLAKE3_AES_128_GCM
    // Placeholder 16-byte keys for tests (not real secrets): all-0xFB and all-zero.
    private val key = "+/v7+/v7+/v7+/v7+/v7+w=="
    private val key2 = "AAAAAAAAAAAAAAAAAAAAAA=="

    private fun form(host: String = "vpn.example.com", port: String = "8388", k: String = key, m: String = method, name: String = "Home") =
        ServerForm(name = name, host = host, port = port, method = m, key = k)

    private fun errors(f: ServerForm) = (f.validate() as? ServerForm.Result.Invalid)?.errors.orEmpty()

    // ---- manual entry ----

    @Test fun manualProfileIsCreatedFromValidForm() {
        val result = form(host = "  203.0.113.7 ", port = " 443 ", k = " $key ").validate()
        val profile = (result as ServerForm.Result.Valid).profile
        assertEquals("203.0.113.7", profile.host)
        assertEquals(443, profile.port)
        assertEquals(key, profile.password)
        assertEquals(method, profile.method)
        assertFalse("UDP must not be assumed", profile.udpEnabled)
    }

    @Test fun emptyNameDefaultsToHost() {
        val profile = (form(name = " ").validate() as ServerForm.Result.Valid).profile
        assertEquals("vpn.example.com", profile.name)
    }

    @Test fun invalidHostname() {
        assertEquals(ProfileProblem.InvalidHost, errors(form(host = "bad host!"))[FormField.Host])
        assertEquals(ProfileProblem.InvalidHost, errors(form(host = "300.1.1.1"))[FormField.Host])
    }

    @Test fun invalidPort() {
        listOf("", "0", "65536", "abc", "-1").forEach {
            assertEquals("port '$it'", ProfileProblem.InvalidPort, errors(form(port = it))[FormField.Port])
        }
    }

    @Test fun unsupportedCipher() {
        assertEquals(ProfileProblem.UnsupportedMethod, errors(form(m = "aes-256-cfb"))[FormField.Method])
        assertEquals(ProfileProblem.UnsupportedMethod, errors(form(m = "2022-blake3-aes-256-cfb"))[FormField.Method])
    }

    @Test fun invalidKeys() {
        assertEquals(ProfileProblem.InvalidKeyEncoding, errors(form(k = "-_v7-_v7-_v7-_v7-_v7-w=="))[FormField.Key]) // URL-safe raw key
        assertEquals(ProfileProblem.WrongKeyLength, errors(form(k = "AAAAAAAAAAAAAAAAAAAA"))[FormField.Key]) // 15 bytes
        assertEquals(ProfileProblem.InvalidKeyEncoding, errors(form(k = "AAAAAAAAAAAAAAAAAAAAAA="))[FormField.Key]) // bad padding
    }

    @Test fun missingRequiredFields() {
        val e = errors(ServerForm())
        assertEquals(ProfileProblem.EmptyHost, e[FormField.Host])
        assertEquals(ProfileProblem.InvalidPort, e[FormField.Port])
        assertEquals(ProfileProblem.EmptyKey, e[FormField.Key])
        assertFalse(FormField.Method in e) // the default cipher is valid
    }

    @Test fun formToStringRedactsKey() {
        assertFalse(form().toString().contains(key))
    }

    // ---- chacha20-ietf-poly1305 ----

    private val chacha = ShadowsocksProfile.METHOD_CHACHA20_IETF_POLY1305

    @Test fun manualChachaProfileKeepsPasswordVerbatim() {
        // Placeholder password; leading/trailing spaces and ':' are part of it.
        val f = form(m = chacha, k = " a:b+c ").copy(udpEnabled = true)
        val profile = (f.validate() as ServerForm.Result.Valid).profile
        assertEquals(chacha, profile.method)
        assertEquals(" a:b+c ", profile.password)
        assertTrue(profile.udpEnabled)
    }

    @Test fun chachaPasswordErrorsMapToTheKeyField() {
        assertEquals(ProfileProblem.EmptyKey, errors(form(m = chacha, k = ""))[FormField.Key])
        assertEquals(ProfileProblem.InvalidPasswordCharacters, errors(form(m = chacha, k = "pw\r\n"))[FormField.Key])
    }

    @Test fun switchingCipherRevalidatesTheSameSecret() {
        // A 2022 key is a valid chacha password, but a chacha password is not a valid 2022 key.
        assertTrue(form(m = chacha, k = key).validate() is ServerForm.Result.Valid)
        assertEquals(ProfileProblem.InvalidKeyEncoding, errors(form(m = method, k = "plain words"))[FormField.Key])
    }

    @Test fun importsChachaWithPasswordOnlyPreview() {
        val userInfo = Base64.getUrlEncoder().withoutPadding().encodeToString("$chacha:Placeholder:Secret42".toByteArray())
        val outcome = ServerImport.parse("ss://$userInfo@203.0.113.9:443#Example")
        val preview = (outcome as ServerImport.Outcome.Success).preview
        assertEquals(chacha, preview.profile.method)
        assertEquals("Placeholder:Secret42", preview.profile.password) // not split as a key chain
        assertTrue(preview.isPassword)
        assertEquals(0, preview.keyCount)
        // Neither half of the secret appears in any string form ("password=<redacted>" is the label).
        listOf(preview.toString(), outcome.toString()).forEach { text ->
            assertFalse(text, text.contains("Placeholder") || text.contains("Secret42"))
        }
    }

    @Test fun importDefaultsUdpOff() {
        assertFalse(ImportUiState().udpEnabled)
        assertFalse(ServerForm().udpEnabled)
    }

    // ---- ss:// import ----

    @Test fun validSip002ImportGivesSafePreview() {
        val outcome = ServerImport.parse("ss://$method:${key.replace("+", "%2B").replace("/", "%2F").replace("=", "%3D")}@vpn.example.com:8388#Office")
        val preview = (outcome as ServerImport.Outcome.Success).preview
        assertEquals("Office", preview.profile.name)
        assertEquals("vpn.example.com", preview.profile.host)
        assertEquals(8388, preview.profile.port)
        assertEquals(key, preview.profile.password)
        assertEquals(1, preview.keyCount)
        assertEquals(16, preview.keyBytes)
        assertFalse(preview.isPassword)
        assertFalse(preview.toString().contains(key))
        assertFalse(outcome.toString().contains(key))
    }

    @Test fun urlSafeBase64UserInfoIsDecoded() {
        // SIP002 user info is base64url without padding. For ASCII "method:key" the URL-safe
        // characters '-'/'_' cannot occur, so what matters here is the missing padding; the
        // '-'/'_' alphabet itself is covered by lenientDecoderAcceptsUrlSafeAlphabet in :shadowsocks.
        val userInfo = Base64.getUrlEncoder().withoutPadding().encodeToString("$method:$key".toByteArray())
        val outcome = ServerImport.parse("ss://$userInfo@[2001:db8::7]:443/#v6")
        val preview = (outcome as ServerImport.Outcome.Success).preview
        assertEquals("2001:db8::7", preview.profile.host)
        assertEquals(key, preview.profile.password)
    }

    @Test fun identityChainPreviewCountsKeys() {
        val outcome = ServerImport.parse("ss://$method:$key2%3A${key.replace("+", "%2B").replace("/", "%2F")}@h.example:1")
        val preview = (outcome as ServerImport.Outcome.Success).preview
        assertEquals(2, preview.keyCount)
    }

    @Test fun invalidImports() {
        assertEquals(ServerImport.Outcome.UriError(Reason.NotShadowsocksUri), ServerImport.parse("https://example.com"))
        assertEquals(ServerImport.Outcome.UriError(Reason.Malformed), ServerImport.parse("ss://%%%@h.example:1"))
        assertEquals(ServerImport.Outcome.UriError(Reason.PluginNotSupported), ServerImport.parse("ss://$method:$key2@h.example:1/?plugin=v2ray-plugin"))
    }

    @Test fun importsWithMissingFields() {
        assertEquals(ServerImport.Outcome.UriError(Reason.InvalidPort), ServerImport.parse("ss://$method:$key2@h.example"))
        assertEquals(ServerImport.Outcome.UriError(Reason.MissingHost), ServerImport.parse("ss://$method:$key2@:8388"))
        assertEquals(ServerImport.Outcome.UriError(Reason.MissingCredentials), ServerImport.parse("ss://$method:@h.example:8388"))
        assertEquals(ServerImport.Outcome.UriError(Reason.MissingCredentials), ServerImport.parse("ss://:$key2@h.example:8388"))
    }

    @Test fun importedKeysUseStrictValidation() {
        // Percent-encoded user info carrying a URL-safe raw key: the native engine would reject it.
        assertEquals(
            ServerImport.Outcome.InvalidProfile(ProfileProblem.InvalidKeyEncoding),
            ServerImport.parse("ss://$method:-_v7-_v7-_v7-_v7-_v7-w%3D%3D@h.example:8388"),
        )
        assertEquals(
            ServerImport.Outcome.InvalidProfile(ProfileProblem.WrongKeyLength),
            ServerImport.parse("ss://$method:AAAAAAAAAAAAAAAAAAAA@h.example:8388"),
        )
        assertEquals(
            ServerImport.Outcome.InvalidProfile(ProfileProblem.UnsupportedMethod),
            ServerImport.parse("ss://aes-256-cfb:password@h.example:8388"),
        )
    }

    @Test fun importErrorsNeverContainTheSecret() {
        val secret = "SECRETVALUE"
        listOf(
            "ss://$method:$secret%zz@h.example:1",
            "ss://$method:$secret@h.example:99999",
            "ss://$method:$secret@h.example:1",
        ).forEach { link ->
            val outcome = ServerImport.parse(link)
            assertFalse(outcome is ServerImport.Outcome.Success)
            assertFalse("outcome leaks secret: $outcome", outcome.toString().contains(secret))
        }
    }
}
