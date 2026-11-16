package com.example.zuzuvpn.data.servers

import com.example.zuzuvpn.vpn.VpnProtocol
import com.zuzuvpn.shadowsocks.ShadowsocksProfile
import java.util.Locale

/**
 * Public, non-secret description of one VPN server. Safe to show anywhere in the UI.
 *
 * Never contains an address, port, password or cipher: those live only in [ConnectionConfig].
 * Live metrics ([loadPercent], [latencyMs]) stay null unless a trusted source supplied or measured
 * them; the UI hides them when null.
 */
data class ServerInfo(
    /** Stable unique id from the source. Selection and connection reference this. */
    val id: String,
    /** Display name, e.g. "France". */
    val name: String,
    /** Country name, e.g. "France". */
    val country: String,
    /** ISO 3166-1 alpha-2, upper case (e.g. "FR"); null when unknown. The flag is derived from it. */
    val countryCode: String?,
    val city: String? = null,
    val protocol: VpnProtocol,
    val isAvailable: Boolean = true,
    val loadPercent: Int? = null,
    val latencyMs: Int? = null,
    val isRecommended: Boolean = false,
    val isPremium: Boolean = false,
    /** Debug-build test server; labelled "Development" in the UI. Never true for production data. */
    val isDevelopment: Boolean = false,
    /** Ordering hint from the source (higher first within a country); 0 when the source has none. */
    val priority: Int = 0,
) {
    init {
        require(countryCode == null || CountryCodes.isValid(countryCode)) { "countryCode must be ISO 3166-1 alpha-2" }
    }

    /** Groups servers of the same country; servers without a code group by their country name. */
    val countryKey: String get() = countryCode ?: "name:${country.lowercase(Locale.ROOT)}"
}

/**
 * Private, protocol-specific connection configuration. Never shown in the UI; [toString] redacts
 * secrets. The VPN engine receives only this, so it does not know where it came from.
 */
sealed interface ConnectionConfig {
    val protocol: VpnProtocol

    /** Shadowsocks: server, port, method, password (or 2022 key) and UDP preference. */
    data class Shadowsocks(val profile: ShadowsocksProfile) : ConnectionConfig {
        override val protocol get() = VpnProtocol.Shadowsocks

        override fun toString() = "ConnectionConfig.Shadowsocks(method=${profile.method}, udp=${profile.udpEnabled}, <redacted>)"
    }

    /**
     * WireGuard: an opaque wg-quick style configuration (contains the private key). Room for the
     * secondary engine; nothing produces this yet.
     */
    data class WireGuard(val configText: String) : ConnectionConfig {
        override val protocol get() = VpnProtocol.WireGuard

        override fun toString() = "ConnectionConfig.WireGuard(<redacted>)"
    }
}

/** A server as a data source delivers it: public info plus its private configuration. */
data class ServerEntry(val info: ServerInfo, val config: ConnectionConfig) {
    init {
        require(info.protocol == config.protocol) { "protocol mismatch" }
    }

    override fun toString() = "ServerEntry(info=$info, config=$config)"
}

object CountryCodes {
    private val known: Set<String> = Locale.getISOCountries().toSet()

    /** Two upper-case letters that are an assigned ISO 3166-1 alpha-2 code. */
    fun isValid(code: String): Boolean = code.length == 2 && code.all { it in 'A'..'Z' } && code in known

    /** Normalises user/config input ("fr", " FR ") to "FR", or null if it is not a valid code. */
    fun normalize(code: String?): String? = code?.trim()?.uppercase(Locale.ROOT)?.takeIf(::isValid)

    /** Regional-indicator flag emoji derived from the code, so flags are never stored separately. */
    fun flagEmoji(code: String): String {
        val c = code.uppercase(Locale.ROOT)
        if (c.length != 2 || c.any { it !in 'A'..'Z' }) return c
        return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
    }
}
