package com.example.zuzuvpn.delivery

import com.example.zuzuvpn.data.controlplane.HttpResult
import com.example.zuzuvpn.data.servers.ConfigResolution
import com.example.zuzuvpn.data.servers.ConnectionConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

private const val ROOT = "https://api.example.test/api/v1"

/** Scripted backend: each route returns the next queued response; every request is recorded. */
private class FakeBackend : JsonHttp {
    data class Sent(val method: String, val url: String, val body: ByteArray?, val headers: Map<String, String>)

    val sent = mutableListOf<Sent>()
    val routes = mutableMapOf<String, ArrayDeque<() -> HttpResult>>()
    var offline = false

    fun on(route: String, vararg responses: () -> HttpResult) {
        routes.getOrPut(route) { ArrayDeque() }.addAll(responses)
    }

    override suspend fun send(method: String, url: String, body: ByteArray?, headers: Map<String, String>): HttpResult {
        if (offline) throw IOException("offline")
        sent += Sent(method, url, body, headers)
        val route = url.removePrefix(ROOT)
        val queue = routes[route] ?: error("unexpected $route")
        return (queue.removeFirstOrNull() ?: error("no response queued for $route"))()
    }
}

private fun ok(body: JSONObject) = { HttpResult(200, body.toString()) }
private fun err(status: Int, code: String, data: JSONObject? = null) = { HttpResult(status, JSONObject().put("error", JSONObject().put("statusCode", status).put("code", code).put("message", "m").apply { data?.let { put("data", it) } }).toString()) }

/** An enrollment token whose payload carries [jti] (the signature part is irrelevant to the client). */
private fun enrollmentToken(customerId: String, jti: String) =
    "eyJhbGciOiJIUzI1NiJ9." + Base64Url.encode(JSONObject().put("sub", customerId).put("jti", jti).put("typ", "customer-enroll").toString().toByteArray()) + ".sig"

private fun signInBody(customerId: String = "c1", jti: String = "jti-1") =
    JSONObject().put("enrollmentToken", enrollmentToken(customerId, jti)).put("expiresIn", 600).put("customer", JSONObject().put("id", customerId).put("email", "ana@example.test"))

private class MemoryStorage : SessionStorage {
    var value: StoredSession? = null
    override fun load() = value
    override fun save(session: StoredSession) { value = session }
    override fun clear() { value = null }
}

private class MemoryCache : EnvelopeCache {
    val map = mutableMapOf<String, Pair<ConfigEnvelope, String>>()
    override fun put(serverId: String, envelope: ConfigEnvelope, nonce: String) { map[serverId] = envelope to nonce }
    override fun get(serverId: String) = map[serverId]
    override fun remove(serverId: String) { map.remove(serverId) }
    override fun clear() = map.clear()
}

/** Software stand-in for the Android Keystore (here: the vector's TEST-ONLY keys). */
private class FakeKeyStore(private val make: () -> DeviceKeys) : DeviceKeyStore {
    var keys: DeviceKeys? = null
    var created = 0
    override fun load() = keys
    override fun create(): DeviceKeys = make().also { keys = it; created++ }
    override fun delete() { keys = null }
}

class AccountFlowTest {
    private val vector = Vectors.envelope("ecdh-shadowsocks")
    private val vectorDeviceId = vector.getJSONObject("device").getString("deviceId")
    private val backend = FakeBackend()
    private val storage = MemoryStorage()
    private val cache = MemoryCache()
    private val keyStore = FakeKeyStore { Vectors.keysOf(vector) }
    private var now = 1_800_000_000L
    private val api = AppApi(ROOT, backend)
    private val account = AccountManager(api, keyStore, storage, cache, DeviceInfo("Pixel 8", "Pixel 8", "1.0.0"), clock = { now })

    private fun enrolled(keys: DeviceKeys, deviceId: String = vectorDeviceId, refresh: String = "zrt_1") = JSONObject()
        .put("reattached", false).put("device", JSONObject().put("id", deviceId).put("label", "Pixel 8"))
        .put("accessToken", "access-1").put("expiresIn", 900).put("refreshToken", refresh)
        .put("signingKeyId", keys.signingKid).put("encryptionKeyId", keys.encryptionKid).put("suite", keys.suite.id)

    private fun signedIn() = runBlocking {
        backend.on("/app/auth/login", ok(signInBody()))
        backend.on("/app/devices", ok(enrolled(Vectors.keysOf(vector))))
        assertEquals(SignInOutcome.Success, account.signIn("ana@example.test", "correct horse battery", register = false))
    }

    private fun verify(spki: ByteArray, message: String, signature: String): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)))
            update(message.toByteArray())
            verify(Base64Url.decode(signature))
        }

    @Test fun `sign-in enrolls automatically with a valid proof of possession`() {
        signedIn()
        val keys = keyStore.keys!!
        val enroll = backend.sent.single { it.url.endsWith("/app/devices") }
        assertEquals("Bearer ${enrollmentToken("c1", "jti-1")}", enroll.headers["Authorization"])
        val body = JSONObject(String(enroll.body!!))
        assertEquals(Base64Url.encode(keys.signingSpki), body.getString("signingKey"))
        assertEquals("ECDH_P256_HKDF_SHA256_A256GCM", body.getString("suite"))
        val message = ProtocolV1.enrollmentMessage("c1", "jti-1", keys.signingKid, keys.encryptionKid, keys.suite)
        assertTrue(verify(keys.signingSpki, message, body.getString("signature")))
        assertEquals(AccountState.SignedIn("ana@example.test", vectorDeviceId), account.state.value)
        assertEquals("zrt_1", storage.value!!.refreshToken)
        assertFalse(storage.value.toString().contains("zrt_1"))
    }

    @Test fun `a server echoing different keys is not trusted`() = runBlocking {
        backend.on("/app/auth/login", ok(signInBody()))
        backend.on("/app/devices", ok(enrolled(Vectors.keysOf(vector)).put("encryptionKeyId", "AAAAAAAAAAAAAAAAAAAAAA")))
        assertEquals(SignInOutcome.Failed("INVALID_RESPONSE"), account.signIn("ana@example.test", "pw-pw-pw-pw-pw", false))
        assertNull(storage.value)
    }

    @Test fun `wrong credentials and network errors surface as codes`() = runBlocking {
        backend.on("/app/auth/login", err(401, "INVALID_CREDENTIALS"))
        assertEquals(SignInOutcome.Failed("INVALID_CREDENTIALS"), account.signIn("ana@example.test", "nope", false))
        backend.offline = true
        assertEquals(SignInOutcome.Failed("NETWORK"), account.signIn("ana@example.test", "nope", false))
    }

    @Test fun `device limit offers replacement with the remembered credentials`() = runBlocking {
        backend.on("/app/auth/login", ok(signInBody()), ok(signInBody(jti = "jti-2")))
        val limitData = JSONObject().put("limit", 2).put("devices", JSONArray().put(JSONObject().put("id", "old-1").put("label", "Old phone").put("model", JSONObject.NULL).put("lastSeenAt", "2026-11-01T10:00:00.000Z")))
        backend.on("/app/devices", err(409, "DEVICE_LIMIT_REACHED", limitData), ok(enrolled(Vectors.keysOf(vector))))
        val outcome = account.signIn("ana@example.test", "correct horse battery", false) as SignInOutcome.DeviceLimit
        assertEquals(2, outcome.limit)
        assertEquals("Old phone", outcome.devices.single().label)
        assertNull(outcome.devices.single().model)
        assertEquals(SignInOutcome.Success, account.replaceDevice("old-1"))
        assertEquals("old-1", JSONObject(String(backend.sent.last().body!!)).getString("replaceDeviceId"))
    }

    @Test fun `refresh is signed with the device key over the exact request`() = runBlocking {
        signedIn()
        now += 2000 // access token expired
        backend.on("/app/auth/refresh", ok(JSONObject().put("accessToken", "access-2").put("expiresIn", 900).put("refreshToken", "zrt_2")))
        backend.on("/app/me", ok(JSONObject().put("deviceId", vectorDeviceId)))
        account.authorized { token, _, _ -> api.me(token) }
        val refresh = backend.sent.single { it.url.endsWith("/app/auth/refresh") }
        val h = refresh.headers
        assertEquals(keyStore.keys!!.signingKid, h["X-Zuzu-Key-Id"])
        assertEquals(now.toString(), h["X-Zuzu-Timestamp"])
        val message = ProtocolV1.requestMessage("POST", "/api/v1/app/auth/refresh", h["X-Zuzu-Timestamp"]!!, h["X-Zuzu-Nonce"]!!, refresh.body!!)
        assertTrue(verify(keyStore.keys!!.signingSpki, message, h["X-Zuzu-Signature"]!!))
        assertEquals("zrt_2", storage.value!!.refreshToken)
        assertEquals("Bearer access-2", backend.sent.last().headers["Authorization"])
    }

    @Test fun `every signed request uses a fresh nonce (no accidental replays)`() {
        val signer = RequestSigner(Vectors.keysOf(vector), clock = { now })
        val nonces = (1..50).map { signer.headers("POST", "/api/v1/app/auth/refresh", ByteArray(0))["X-Zuzu-Nonce"]!! }.toSet()
        assertEquals(50, nonces.size)
        assertTrue(nonces.all { it.length == 24 })
    }

    @Test fun `a replayed-request rejection is reported, not treated as sign-out`() = runBlocking {
        signedIn()
        now += 2000
        backend.on("/app/auth/refresh", err(401, "SIGNATURE_REPLAYED"))
        val failure = runCatching { account.authorized { token, _, _ -> api.me(token) } }.exceptionOrNull() as ApiFailure
        assertEquals("SIGNATURE_REPLAYED", failure.code)
        assertTrue(account.state.value is AccountState.SignedIn)
        assertTrue(keyStore.keys != null)
    }

    @Test fun `revoked device deletes keys, session and cached envelopes`() = runBlocking {
        signedIn()
        cache.put("srv", Vectors.envelopeOf(vector), "n")
        backend.on("/app/me", err(401, "DEVICE_REVOKED"))
        runCatching { account.authorized { token, _, _ -> api.me(token) } }
        assertEquals(AccountState.SignedOut(SignOutReason.DeviceRevoked), account.state.value)
        assertNull(keyStore.keys)
        assertNull(storage.value)
        assertTrue(cache.map.isEmpty())
    }

    @Test fun `disabled account signs out and deletes the identity`() = runBlocking {
        signedIn()
        now += 2000
        backend.on("/app/auth/refresh", err(401, "ACCOUNT_DISABLED"))
        runCatching { account.authorized { token, _, _ -> api.me(token) } }
        assertEquals(AccountState.SignedOut(SignOutReason.AccountDisabled), account.state.value)
        assertNull(keyStore.keys)
    }

    @Test fun `expired session keeps the keys so the installation re-attaches`() = runBlocking {
        signedIn()
        now += 2000
        backend.on("/app/auth/refresh", err(401, "SESSION_EXPIRED"))
        runCatching { account.authorized { token, _, _ -> api.me(token) } }
        assertEquals(AccountState.SignedOut(SignOutReason.SessionExpired), account.state.value)
        assertTrue(keyStore.keys != null)
    }

    @Test fun `keys of a revoked device are replaced by a fresh identity at the next sign-in`() = runBlocking {
        keyStore.create() // left over from a device the server revoked
        backend.on("/app/auth/login", ok(signInBody()), ok(signInBody(jti = "jti-2")))
        backend.on("/app/devices", err(401, "DEVICE_REVOKED"), ok(enrolled(Vectors.keysOf(vector))))
        assertEquals(SignInOutcome.Success, account.signIn("ana@example.test", "correct horse battery", register = true.not()))
        assertEquals(2, keyStore.created)
        // The retry used login, never a second registration.
        assertEquals(2, backend.sent.count { it.url.endsWith("/app/auth/login") })
    }

    @Test fun `sign out tells the server (signed) and wipes local state`() = runBlocking {
        signedIn()
        backend.on("/app/auth/logout", { HttpResult(204, "") })
        account.signOut()
        val logout = backend.sent.single { it.url.endsWith("/app/auth/logout") }
        assertTrue(logout.headers.containsKey("X-Zuzu-Signature"))
        assertEquals(AccountState.SignedOut(SignOutReason.SignedOut), account.state.value)
        assertNull(keyStore.keys)
    }

    // ---- encrypted configuration through the provider ----------------------------------------------

    /** Deterministic "random" so the provider sends the vector's nonce (the vector envelope answers it). */
    private fun nonceRandom(nonce: String) = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            Base64Url.decode(nonce)!!.copyInto(bytes)
        }
    }

    private fun provider(nonce: String = vector.getJSONObject("request").getString("nonce")) =
        SecureConfigProvider(account, api, cache, clock = { now }, random = nonceRandom(nonce))

    private val serverId = vector.getJSONObject("request").getString("serverId")

    @Test fun `config is requested, decrypted in memory and handed over as a Shadowsocks profile`() = runBlocking {
        signedIn()
        backend.on("/app/servers/$serverId/config", ok(vector.getJSONObject("envelope")))
        val result = provider().resolve(serverId, "Paris") as ConfigResolution.Ready
        val profile = (result.config as ConnectionConfig.Shadowsocks).profile
        assertEquals("198.51.100.7", profile.host)
        assertEquals("Paris", profile.name)
        val request = JSONObject(String(backend.sent.last().body!!))
        assertEquals(keyStore.keys!!.encryptionKid, request.getString("kid"))
        assertEquals(JSONArray().put("shadowsocks").toString(), request.getJSONArray("protocols").toString())
        assertFalse(result.toString().contains(profile.password))
        assertTrue(cache.map.containsKey(serverId)) // still encrypted
    }

    @Test fun `a replayed or foreign envelope is a security error`() = runBlocking {
        signedIn()
        backend.on("/app/servers/$serverId/config", ok(vector.getJSONObject("envelope")))
        assertEquals(ConfigResolution.SecurityError, provider(nonce = "ZnJlc2gtbm9uY2UtZnJlc2gt").resolve(serverId, "Paris"))
        assertFalse(cache.map.containsKey(serverId))
    }

    @Test fun `authorization refusals map to user-facing reasons and drop the cache`() = runBlocking {
        signedIn()
        cache.put(serverId, Vectors.envelopeOf(vector), vector.getJSONObject("request").getString("nonce"))
        backend.on("/app/servers/$serverId/config", err(403, "SUBSCRIPTION_REQUIRED"), err(403, "ENTITLEMENT_REQUIRED"), err(404, "SERVER_NOT_AVAILABLE"), err(429, "TOO_MANY_REQUESTS"))
        assertEquals(ConfigResolution.SubscriptionRequired, provider().resolve(serverId, "Paris"))
        assertFalse(cache.map.containsKey(serverId))
        assertEquals(ConfigResolution.PremiumRequired, provider().resolve(serverId, "Paris"))
        assertEquals(ConfigResolution.Unavailable, provider().resolve(serverId, "Paris"))
        assertEquals(ConfigResolution.RateLimited, provider().resolve(serverId, "Paris"))
    }

    @Test fun `a request made before session restore waits for it (always-on start at boot)`() = runBlocking {
        storage.value = StoredSession(vectorDeviceId, "c1", "ana@example.test", "zrt_1")
        keyStore.keys = Vectors.keysOf(vector)
        backend.on("/app/auth/refresh", ok(JSONObject().put("accessToken", "access-2").put("expiresIn", 900).put("refreshToken", "zrt_2")))
        backend.on("/app/servers/$serverId/config", ok(vector.getJSONObject("envelope")))
        val pending = async { provider().resolve(serverId, "Paris") }
        kotlinx.coroutines.delay(100)
        assertEquals(AccountState.Loading, account.state.value)
        account.restore()
        assertTrue(pending.await() is ConfigResolution.Ready)
    }

    @Test fun `a Keystore error at restore keeps the device identity and session`() = runBlocking {
        storage.value = StoredSession(vectorDeviceId, "c1", "ana@example.test", "zrt_1")
        val failing = object : DeviceKeyStore {
            var deleted = false
            override fun load(): DeviceKeys? = throw java.security.KeyStoreException("unavailable")
            override fun create(): DeviceKeys = error("not expected")
            override fun delete() { deleted = true }
        }
        val manager = AccountManager(api, failing, storage, cache, DeviceInfo("x", null, null), clock = { now })
        manager.restore()
        assertEquals(AccountState.SignedOut(), manager.state.value)
        assertFalse(failing.deleted)
        assertTrue(storage.value != null)
    }

    @Test fun `token-bearing results never print their tokens`() {
        assertFalse(DeviceTokens("secret-access", 900, "zrt_secret").toString().contains("secret"))
        assertFalse(SignInResult("eyJ.secret.sig", "c1", "a@b.test", "jti").toString().contains("secret"))
    }

    @Test fun `offline reconnects use the encrypted cache until it expires`() = runBlocking {
        signedIn()
        backend.on("/app/servers/$serverId/config", ok(vector.getJSONObject("envelope")))
        provider().resolve(serverId, "Paris")
        backend.offline = true
        assertTrue(provider(nonce = "ZnJlc2gtbm9uY2UtZnJlc2gt").resolve(serverId, "Paris") is ConfigResolution.Ready)
        now = Vectors.envelopeOf(vector).exp + 3600
        assertEquals(ConfigResolution.NetworkError, provider().resolve(serverId, "Paris"))
        assertFalse(cache.map.containsKey(serverId))
    }

    @Test fun `signed-out and revoked states never fetch configurations`() = runBlocking {
        account.restore() // nothing stored → SignedOut
        assertEquals(ConfigResolution.SignInRequired, provider().resolve(serverId, "Paris"))
        assertTrue(backend.sent.isEmpty())
        signedIn()
        backend.on("/app/servers/$serverId/config", err(401, "DEVICE_REVOKED"))
        assertEquals(ConfigResolution.SignedOut, provider().resolve(serverId, "Paris"))
        assertEquals(ConfigResolution.SignInRequired, provider().resolve(serverId, "Paris"))
        assertNotEquals(0, backend.sent.size)
    }
}
