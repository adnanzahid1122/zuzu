package com.example.zuzuvpn.delivery

import com.example.zuzuvpn.data.controlplane.HttpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection

/** Raw JSON transport; implementations must send [body] byte-for-byte (it is covered by signatures). */
fun interface JsonHttp {
    /** Throws [IOException] when no response was received. */
    suspend fun send(method: String, url: String, body: ByteArray?, headers: Map<String, String>): HttpResult
}

/** HTTPS only, no redirects, bounded response size, nothing logged. */
class HttpsJsonClient(private val userAgent: String, private val maxBytes: Int = 256 * 1024) : JsonHttp {
    override suspend fun send(method: String, url: String, body: ByteArray?, headers: Map<String, String>): HttpResult = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as? HttpsURLConnection ?: throw IOException("HTTPS required")
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", userAgent)
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpResult(status, stream?.use { readBounded(it, maxBytes) }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream, limit: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) throw IOException("Response too large")
        }
        return out.toString("UTF-8")
    }
}

/** A non-2xx answer: [code] is the backend's machine-readable code (docs/api/app-api-v1.md). Never carries request data. */
class ApiFailure(val status: Int, val code: String, val data: JSONObject?) : Exception("$status $code")

/** No response (offline, DNS, TLS, timeout). */
class NetworkFailure : Exception("network")

/** Signs requests: "ZUZU-SIG-V1" with X-Zuzu-Key-Id / -Timestamp / -Nonce / -Signature headers. */
class RequestSigner(
    private val keys: DeviceKeys,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: SecureRandom = SecureRandom(),
) {
    fun headers(method: String, path: String, body: ByteArray): Map<String, String> {
        val timestamp = clock().toString()
        val nonce = Base64Url.encode(ByteArray(18).also(random::nextBytes))
        val message = ProtocolV1.requestMessage(method, path, timestamp, nonce, body)
        return mapOf(
            "X-Zuzu-Key-Id" to keys.signingKid,
            "X-Zuzu-Timestamp" to timestamp,
            "X-Zuzu-Nonce" to nonce,
            "X-Zuzu-Signature" to Base64Url.encode(keys.sign(message.toByteArray(Charsets.UTF_8))),
        )
    }
}

data class SignInResult(val enrollmentToken: String, val customerId: String, val email: String, val challenge: String) {
    override fun toString() = "SignInResult(customerId=$customerId, <redacted>)"
}

data class DeviceTokens(val accessToken: String, val expiresIn: Long, val refreshToken: String) {
    override fun toString() = "DeviceTokens(expiresIn=$expiresIn, <redacted>)"
}

data class EnrollResult(val deviceId: String, val reattached: Boolean, val tokens: DeviceTokens, val signingKid: String, val encryptionKid: String, val suite: String)

data class RegisteredDevice(val id: String, val label: String, val model: String?, val lastSeenAt: String?)

/** Typed client for the customer endpoints of docs/api/app-api-v1.md. [root] ends with "/api/v1". */
class AppApi(private val root: String, private val http: JsonHttp) {
    private val rootPath: String = URL(root).path.trimEnd('/')

    private suspend fun call(method: String, route: String, body: JSONObject?, headers: Map<String, String> = emptyMap(), bytes: ByteArray? = body?.toString()?.toByteArray(Charsets.UTF_8)): JSONObject? {
        val result = try {
            http.send(method, root + route, bytes, headers)
        } catch (e: IOException) {
            throw NetworkFailure()
        }
        if (result.status in 200..299) {
            if (result.status == 204 || result.body.isBlank()) return null
            return try {
                JSONObject(result.body)
            } catch (e: JSONException) {
                throw ApiFailure(result.status, "INVALID_RESPONSE", null)
            }
        }
        val error = runCatching { JSONObject(result.body).optJSONObject("error") }.getOrNull()
        throw ApiFailure(result.status, error?.optString("code")?.takeIf { it.isNotEmpty() } ?: "HTTP_${result.status}", error?.optJSONObject("data"))
    }

    private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")

    private fun signIn(o: JSONObject?): SignInResult {
        val token = o?.optString("enrollmentToken").orEmpty()
        val customer = o?.optJSONObject("customer") ?: throw ApiFailure(200, "INVALID_RESPONSE", null)
        // The enrollment token's jti is the proof-of-possession challenge (single use).
        val claims = token.split('.').getOrNull(1)?.let(Base64Url::decode)?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
        val jti = claims?.optString("jti").orEmpty()
        if (token.isEmpty() || jti.isEmpty()) throw ApiFailure(200, "INVALID_RESPONSE", null)
        return SignInResult(token, customer.getString("id"), customer.getString("email"), jti)
    }

    suspend fun register(email: String, password: String): SignInResult =
        signIn(call("POST", "/app/auth/register", JSONObject().put("email", email).put("password", password)))

    suspend fun login(email: String, password: String): SignInResult =
        signIn(call("POST", "/app/auth/login", JSONObject().put("email", email).put("password", password)))

    suspend fun google(idToken: String): SignInResult =
        signIn(call("POST", "/app/auth/google", JSONObject().put("idToken", idToken)))

    /** Automatic enrollment (or re-attach) with proof of possession of the signing key. */
    suspend fun enroll(signIn: SignInResult, keys: DeviceKeys, label: String, model: String?, appVersion: String?, replaceDeviceId: String? = null): EnrollResult {
        val message = ProtocolV1.enrollmentMessage(signIn.customerId, signIn.challenge, keys.signingKid, keys.encryptionKid, keys.suite)
        val body = JSONObject()
            .put("signingKey", Base64Url.encode(keys.signingSpki))
            .put("encryptionKey", Base64Url.encode(keys.encryptionSpki))
            .put("suite", keys.suite.id)
            .put("signature", Base64Url.encode(keys.sign(message.toByteArray(Charsets.UTF_8))))
            .put("label", label.take(100))
        model?.let { body.put("model", it.take(100)) }
        appVersion?.let { body.put("appVersion", it.take(40)) }
        replaceDeviceId?.let { body.put("replaceDeviceId", it) }
        val o = call("POST", "/app/devices", body, bearer(signIn.enrollmentToken)) ?: throw ApiFailure(200, "INVALID_RESPONSE", null)
        return EnrollResult(
            deviceId = o.getJSONObject("device").getString("id"),
            reattached = o.optBoolean("reattached"),
            tokens = DeviceTokens(o.getString("accessToken"), o.getLong("expiresIn"), o.getString("refreshToken")),
            signingKid = o.getString("signingKeyId"),
            encryptionKid = o.getString("encryptionKeyId"),
            suite = o.getString("suite"),
        )
    }

    suspend fun refresh(refreshToken: String, signer: RequestSigner): DeviceTokens {
        val bytes = JSONObject().put("refreshToken", refreshToken).toString().toByteArray(Charsets.UTF_8)
        val o = call("POST", "/app/auth/refresh", null, signer.headers("POST", "$rootPath/app/auth/refresh", bytes), bytes) ?: throw ApiFailure(200, "INVALID_RESPONSE", null)
        return DeviceTokens(o.getString("accessToken"), o.getLong("expiresIn"), o.getString("refreshToken"))
    }

    suspend fun logout(accessToken: String, signer: RequestSigner) {
        call("POST", "/app/auth/logout", null, bearer(accessToken) + signer.headers("POST", "$rootPath/app/auth/logout", ByteArray(0)), bytes = null)
    }

    suspend fun me(accessToken: String): JSONObject = call("GET", "/app/me", null, bearer(accessToken), bytes = null) ?: JSONObject()

    suspend fun requestConfig(accessToken: String, serverId: String, kid: String, nonce: String, protocols: List<String>): ConfigEnvelope {
        val body = JSONObject().put("v", ProtocolV1.VERSION).put("kid", kid).put("nonce", nonce).put("protocols", JSONArray(protocols))
        val o = call("POST", "/app/servers/$serverId/config", body, bearer(accessToken)) ?: throw ApiFailure(200, "INVALID_RESPONSE", null)
        return ConfigEnvelope.parse(o.toString())
    }

    companion object {
        fun devicesOf(failure: ApiFailure): List<RegisteredDevice> {
            val list = failure.data?.optJSONArray("devices") ?: return emptyList()
            return (0 until list.length()).mapNotNull { i ->
                val d = list.optJSONObject(i) ?: return@mapNotNull null
                RegisteredDevice(d.optString("id"), d.optString("label"), d.optString("model").takeIf { it.isNotEmpty() && !d.isNull("model") }, d.optString("lastSeenAt").takeIf { it.isNotEmpty() })
            }
        }
    }
}
