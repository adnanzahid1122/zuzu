package com.example.zuzuvpn.data.controlplane

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * URLs of the Zuzu Control Plane's public directory (no authentication):
 *  * `GET /api/v1/countries` — active countries that have at least one active server;
 *  * `GET /api/v1/servers?page=&pageSize=` — active servers in active countries (paginated, max 100).
 *
 * [baseUrl] is the API origin (BuildConfig.API_BASE_URL). `/api/v1` is appended exactly once, even
 * if the configured value already ends with `/api` or `/api/v1`.
 */
class ControlPlaneEndpoints(baseUrl: String) {
    val root: String

    init {
        var origin = baseUrl.trim().trimEnd('/')
        require(origin.startsWith("https://")) { "The Control Plane API must use HTTPS" }
        origin = origin.removeSuffix("/api/v1").removeSuffix("/api").trimEnd('/')
        root = "$origin/api/v1"
    }

    val countries: String get() = "$root/countries"

    fun servers(page: Int, pageSize: Int): String = "$root/servers?page=$page&pageSize=$pageSize"

    companion object {
        /** The API's maximum page size. */
        const val MAX_PAGE_SIZE = 100
    }
}

data class HttpResult(val status: Int, val body: String)

/** Minimal transport, so the data source can be tested without a network. */
fun interface HttpGetter {
    /** Throws [IOException] when the request cannot be completed (no network, DNS, TLS, timeout). */
    suspend fun get(url: String): HttpResult
}

/**
 * HTTPS GET over [HttpURLConnection] (no extra dependency). Rejects non-HTTPS URLs, limits the
 * response size and never logs request or response contents.
 */
class UrlConnectionHttpGetter(
    private val userAgent: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val maxBytes: Int = 2 * 1024 * 1024,
) : HttpGetter {
    override suspend fun get(url: String): HttpResult = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as? HttpsURLConnection ?: throw IOException("HTTPS required")
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", userAgent)
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val bytes = input.readNBytesCompat(maxBytes + 1)
                if (bytes.size > maxBytes) throw IOException("Response too large")
                String(bytes, Charsets.UTF_8)
            }.orEmpty()
            HttpResult(status, body)
        } finally {
            connection.disconnect()
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
