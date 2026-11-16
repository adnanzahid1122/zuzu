package com.example.zuzuvpn.data.controlplane

import com.example.zuzuvpn.data.servers.CountryCodes
import com.example.zuzuvpn.data.servers.ServerInfo
import com.example.zuzuvpn.vpn.VpnProtocol
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A country from `GET /api/v1/countries` (public fields only). */
data class PublicCountry(val code: String, val name: String, val serverCount: Int)

/** One page of `GET /api/v1/servers`. [skipped] counts malformed items that were left out. */
data class ServerPage(val servers: List<ServerInfo>, val page: Int, val pageSize: Int, val total: Int, val skipped: Int)

class InvalidDirectoryResponse(message: String) : Exception(message)

/**
 * Maps the Control Plane's public directory JSON to [ServerInfo]. Tolerant per item: an item with a
 * missing or invalid required field is skipped (never guessed); a response that is not the expected
 * shape fails as a whole. Error messages never include response contents.
 *
 * Fields used (servers): id, name, country.code, country.name, city, protocol, available, premium,
 * recommended, priority, loadPercent, latencyMs. `country.flag` is not used: flags are derived from
 * the ISO code. `supportsUdp` is not needed by the UI.
 */
object DirectoryParser {
    fun parseCountries(body: String): List<PublicCountry> {
        val array = try {
            JSONArray(body)
        } catch (e: JSONException) {
            throw InvalidDirectoryResponse("countries: not a JSON array")
        }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val code = CountryCodes.normalize(o.text("code")) ?: return@mapNotNull null
            val name = o.text("name") ?: return@mapNotNull null
            PublicCountry(code, name, o.optInt("serverCount", 0).coerceAtLeast(0))
        }.distinctBy { it.code }
    }

    fun parseServerPage(body: String): ServerPage {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw InvalidDirectoryResponse("servers: not a JSON object")
        }
        val items = root.optJSONArray("items") ?: throw InvalidDirectoryResponse("servers: items missing")
        var skipped = 0
        val servers = ArrayList<ServerInfo>(items.length())
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i)
            if (o == null) {
                skipped++
                continue
            }
            // Only servers the backend marks as available are offered (the API returns only those today).
            if (o.opt("available") != true) continue
            val server = parseServer(o)
            if (server == null) skipped++ else servers += server
        }
        return ServerPage(
            servers = servers,
            page = root.optInt("page", 1),
            pageSize = root.optInt("pageSize", items.length()),
            total = root.optInt("total", servers.size),
            skipped = skipped,
        )
    }

    private fun parseServer(o: JSONObject): ServerInfo? {
        val id = o.text("id")?.takeIf { it.length <= 64 } ?: return null
        val name = o.text("name") ?: return null
        val country = o.optJSONObject("country") ?: return null
        val code = CountryCodes.normalize(country.text("code")) ?: return null
        val countryName = country.text("name") ?: return null
        val protocol = when (o.text("protocol")) {
            "shadowsocks" -> VpnProtocol.Shadowsocks
            "wireguard" -> VpnProtocol.WireGuard
            else -> return null
        }
        return ServerInfo(
            id = id,
            name = name,
            country = countryName,
            countryCode = code,
            city = o.text("city"),
            protocol = protocol,
            isAvailable = true,
            // Metrics only when the backend supplies plausible values; otherwise hidden (null).
            loadPercent = o.intOrNull("loadPercent")?.takeIf { it in 0..100 },
            latencyMs = o.intOrNull("latencyMs")?.takeIf { it in 0..60_000 },
            isRecommended = o.optBoolean("recommended", false),
            isPremium = o.optBoolean("premium", false),
            priority = o.intOrNull("priority") ?: 0,
        )
    }

    /** Non-blank trimmed string, or null (also for JSON null and non-strings). */
    private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun JSONObject.intOrNull(key: String): Int? = when (val v = opt(key)) {
        is Int -> v
        is Long -> v.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        is Double -> v.takeIf { it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
        else -> null
    }
}
