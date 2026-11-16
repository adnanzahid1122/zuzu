package com.example.zuzuvpn.delivery

import com.example.zuzuvpn.data.controlplane.HttpResult
import com.example.zuzuvpn.data.servers.ConfigResolution
import com.example.zuzuvpn.data.servers.ConnectionConfig
import com.example.zuzuvpn.delivery.ProtocolV1.Suite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec

/**
 * Opt-in end-to-end interoperability test: the real Kotlin client against a real Control Plane API
 * (run locally on its TEST database). Software keys stand in for the Android Keystore; every other
 * code path (signing, enrollment proof, refresh, envelope decryption, error handling) is production code.
 *
 *   ZUZU_INTEROP_API=http://127.0.0.1:3000/api/v1 ZUZU_INTEROP_ADMIN_EMAIL=… ZUZU_INTEROP_ADMIN_PASSWORD=…
 *
 * Never point it at production: it creates customers, plans and servers.
 */
class LocalBackendInteropTest {
    private val root = System.getenv("ZUZU_INTEROP_API").orEmpty()

    /** Plain HTTP is allowed only here, for a local backend (java.net.http supports PATCH for admin setup calls). */
    private val client = java.net.http.HttpClient.newHttpClient()
    private val http = JsonHttp { method, url, body, headers ->
        withContext(Dispatchers.IO) {
            val builder = java.net.http.HttpRequest.newBuilder(java.net.URI(url)).header("Accept", "application/json")
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (body != null) builder.header("Content-Type", "application/json")
            builder.method(method, if (body != null) java.net.http.HttpRequest.BodyPublishers.ofByteArray(body) else java.net.http.HttpRequest.BodyPublishers.noBody())
            val response = try {
                client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            } catch (e: java.net.ConnectException) {
                throw IOException(e)
            }
            HttpResult(response.statusCode(), response.body().orEmpty())
        }
    }

    private suspend fun admin(method: String, route: String, token: String?, body: JSONObject?): JSONObject {
        val r = http.send(method, root + route, body?.toString()?.toByteArray(), token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())
        check(r.status in 200..299) { "$method $route → ${r.status}" }
        return if (r.body.isBlank()) JSONObject() else JSONObject(r.body)
    }

    private class Installation(val suite: Suite) : DeviceKeyStore {
        var keys: DeviceKeys? = null
        override fun load() = keys
        override fun create(): DeviceKeys {
            val sign = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val enc = if (suite == Suite.ECDH_P256) KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            else KeyPairGenerator.getInstance("RSA").apply { initialize(RSAKeyGenParameterSpec(3072, BigInteger.valueOf(65537))) }.generateKeyPair()
            return JcaDeviceKeys(suite, sign.private, sign.public, enc.private, enc.public).also { keys = it }
        }
        override fun delete() { keys = null }
    }

    private class Memory : SessionStorage, EnvelopeCache {
        var session: StoredSession? = null
        val envelopes = mutableMapOf<String, Pair<ConfigEnvelope, String>>()
        override fun load() = session
        override fun save(session: StoredSession) { this.session = session }
        override fun clear() { session = null; envelopes.clear() }
        override fun put(serverId: String, envelope: ConfigEnvelope, nonce: String) { envelopes[serverId] = envelope to nonce }
        override fun get(serverId: String) = envelopes[serverId]
        override fun remove(serverId: String) { envelopes.remove(serverId) }
    }

    @Test fun `kotlin client interoperates with the real backend for both suites`() = runBlocking {
        assumeTrue("set ZUZU_INTEROP_API (+ admin credentials) to run", root.isNotEmpty())
        val run = System.currentTimeMillis().toString(36)
        val adminToken = admin("POST", "/admin/auth/login", null, JSONObject().put("email", System.getenv("ZUZU_INTEROP_ADMIN_EMAIL")).put("password", System.getenv("ZUZU_INTEROP_ADMIN_PASSWORD"))).getString("accessToken")
        val countries = admin("GET", "/admin/countries?search=NL", adminToken, null).getJSONArray("items")
        val countryId = if (countries.length() > 0) countries.getJSONObject(0).getString("id") else admin("POST", "/admin/countries", adminToken, JSONObject().put("name", "Netherlands").put("isoCode", "NL")).getString("id")
        val ssPassword = "interop-test-only-$run"
        val serverId = admin("POST", "/admin/servers", adminToken, JSONObject().put("name", "Interop $run").put("countryId", countryId).put("protocol", "SHADOWSOCKS")
            .put("shadowsocks", JSONObject().put("serverAddress", "198.51.100.77").put("serverPort", 8443).put("method", "chacha20-ietf-poly1305").put("password", ssPassword).put("supportsUdp", true))).getString("id")
        admin("PATCH", "/admin/servers/$serverId/status", adminToken, JSONObject().put("status", "ACTIVE"))
        val planId = admin("POST", "/admin/plans", adminToken, JSONObject().put("code", "interop-$run").put("name", "Interop").put("maxDevices", 5).put("premiumAccess", false)).getString("id")

        val email = "interop-$run@example.test"
        val password = "interop-password-$run"
        val api = AppApi(root, http)
        fun install(suite: Suite): Triple<AccountManager, SecureConfigProvider, Installation> {
            val keys = Installation(suite)
            val mem = Memory()
            val manager = AccountManager(api, keys, mem, mem, DeviceInfo("Interop ${suite.name}", "JVM", "test"))
            return Triple(manager, SecureConfigProvider(manager, api, mem), keys)
        }

        val (phoneA, configsA, keysA) = install(Suite.ECDH_P256)
        val (phoneB, configsB, keysB) = install(Suite.RSA3072)

        // Register (A) and sign in (B): automatic enrollment with proof of possession, both suites.
        assertEquals(SignInOutcome.Success, phoneA.signIn(email, password, register = true))
        assertEquals(SignInOutcome.Success, phoneB.signIn(email, password, register = false))
        assertEquals(Suite.ECDH_P256, keysA.keys!!.suite)
        assertEquals(Suite.RSA3072, keysB.keys!!.suite)

        // No subscription: a free server is still delivered (subscriptions only gate premium servers).
        assertTrue(configsA.resolve(serverId, "Interop") is ConfigResolution.Ready)
        val customerId = admin("GET", "/admin/customers?search=$email", adminToken, null).getJSONArray("items").getJSONObject(0).getString("id")
        admin("POST", "/admin/customers/$customerId/subscriptions", adminToken, JSONObject().put("planId", planId))

        // Encrypted delivery, decrypted by the Kotlin opener, for both suites.
        for ((label, provider) in listOf("ECDH" to configsA, "RSA" to configsB)) {
            val ready = provider.resolve(serverId, "Interop") as ConfigResolution.Ready
            val profile = (ready.config as ConnectionConfig.Shadowsocks).profile
            assertEquals(label, "198.51.100.77", profile.host)
            assertEquals(label, 8443, profile.port)
            assertEquals(label, ssPassword, profile.password)
        }

        // Signed refresh accepted by the server (rotation), then delivery still works.
        phoneA.refreshNow()
        phoneB.refreshNow()
        assertTrue(configsA.resolve(serverId, "Interop") is ConfigResolution.Ready)

        // Admin revokes phone B: its next request ends the session and deletes its keys.
        val devices = admin("GET", "/admin/customers/$customerId", adminToken, null).getJSONArray("devices")
        val bId = (0 until devices.length()).map { devices.getJSONObject(it) }.first { it.optString("suite") == Suite.RSA3072.id }.getString("id")
        admin("POST", "/admin/customers/$customerId/devices/$bId/revoke", adminToken, null)
        assertEquals(ConfigResolution.SignedOut, configsB.resolve(serverId, "Interop"))
        assertEquals(AccountState.SignedOut(SignOutReason.DeviceRevoked), phoneB.state.value)
        assertEquals(null, keysB.keys)

        // Admin disables the account: phone A is signed out as AccountDisabled.
        admin("PATCH", "/admin/customers/$customerId", adminToken, JSONObject().put("status", "DISABLED"))
        configsA.resolve(serverId, "Interop")
        assertTrue(phoneA.state.value is AccountState.SignedOut)
        val reason = (phoneA.state.value as AccountState.SignedOut).reason
        assertTrue("reason $reason", reason == SignOutReason.AccountDisabled || reason == SignOutReason.DeviceRevoked)
        println("INTEROP OK: ECDH + RSA enrollment, delivery, decryption, signed refresh, revocation, disable")
    }

    /** Every engine-supported cipher: API → database → encrypted envelope → Kotlin decryption → engine config. */
    @Test fun `every supported cipher survives the full delivery path unchanged`() = runBlocking {
        assumeTrue("set ZUZU_INTEROP_API (+ admin credentials) to run", root.isNotEmpty())
        val run = System.currentTimeMillis().toString(36) + "c"
        val adminToken = admin("POST", "/admin/auth/login", null, JSONObject().put("email", System.getenv("ZUZU_INTEROP_ADMIN_EMAIL")).put("password", System.getenv("ZUZU_INTEROP_ADMIN_PASSWORD"))).getString("accessToken")
        val countries = admin("GET", "/admin/countries?search=NL", adminToken, null).getJSONArray("items")
        val countryId = if (countries.length() > 0) countries.getJSONObject(0).getString("id") else admin("POST", "/admin/countries", adminToken, JSONObject().put("name", "Netherlands").put("isoCode", "NL")).getString("id")
        val planId = admin("POST", "/admin/plans", adminToken, JSONObject().put("code", "ciphers-$run").put("name", "Ciphers").put("maxDevices", 5).put("premiumAccess", false)).getString("id")
        val email = "ciphers-$run@example.test"
        val password = "ciphers-password-$run"
        val api = AppApi(root, http)
        val phones = listOf(Suite.ECDH_P256, Suite.RSA3072).map { suite ->
            val keys = Installation(suite)
            val mem = Memory()
            val manager = AccountManager(api, keys, mem, mem, DeviceInfo("Cipher ${suite.name}", "JVM", "test"))
            manager to SecureConfigProvider(manager, api, mem)
        }
        assertEquals(SignInOutcome.Success, phones[0].first.signIn(email, password, register = true))
        assertEquals(SignInOutcome.Success, phones[1].first.signIn(email, password, register = false))
        val customerId = admin("GET", "/admin/customers?search=$email", adminToken, null).getJSONArray("items").getJSONObject(0).getString("id")
        admin("POST", "/admin/customers/$customerId/subscriptions", adminToken, JSONObject().put("planId", planId))

        val random = java.security.SecureRandom()
        for (method in com.zuzuvpn.shadowsocks.ShadowsocksProfile.SUPPORTED_METHODS) {
            val keyBytes = com.zuzuvpn.shadowsocks.ShadowsocksProfile.keyLength(method)
            val credential = keyBytes?.let { java.util.Base64.getEncoder().encodeToString(ByteArray(it).also(random::nextBytes)) } ?: "pw-$run-$method"
            val serverId = admin("POST", "/admin/servers", adminToken, JSONObject().put("name", "Cipher $method").put("countryId", countryId).put("protocol", "SHADOWSOCKS")
                .put("shadowsocks", JSONObject().put("serverAddress", "198.51.100.88").put("serverPort", 8388).put("method", method).put("password", credential).put("supportsUdp", true))).getString("id")
            admin("PATCH", "/admin/servers/$serverId/status", adminToken, JSONObject().put("status", "ACTIVE"))
            for ((manager, provider) in phones) {
                val ready = provider.resolve(serverId, "Cipher") as ConfigResolution.Ready
                val profile = (ready.config as ConnectionConfig.Shadowsocks).profile
                assertEquals("$method via ${manager.currentKeys!!.suite}", method, profile.method)
                assertEquals(method, credential, profile.password)
                assertTrue(method, com.zuzuvpn.shadowsocks.ShadowsocksProfileValidator.validate(profile).isEmpty())
                val engineJson = JSONObject(com.zuzuvpn.shadowsocks.SsConfigBuilder.build(profile, "198.51.100.88", 1080))
                assertEquals(method, engineJson.getString("method"))
                assertEquals(credential, engineJson.getString("password"))
            }
        }
        println("CIPHERS OK: ${com.zuzuvpn.shadowsocks.ShadowsocksProfile.SUPPORTED_METHODS.joinToString()} via ECDH and RSA")
    }
}
