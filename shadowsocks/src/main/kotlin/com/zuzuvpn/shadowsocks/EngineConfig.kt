package com.zuzuvpn.shadowsocks

/**
 * Addresses and sizes shared by the Android TUN interface and hev-socks5-tunnel.
 * The ranges are private (RFC 1918 / RFC 4193) and only exist inside the tunnel.
 */
object TunnelSpec {
    const val MTU = 1500
    const val IPV4_ADDRESS = "10.255.0.1"
    const val IPV4_PREFIX = 30
    const val IPV6_ADDRESS = "fd66:7a75:7a75::1"
    const val IPV6_PREFIX = 126

    /**
     * Resolvers used when UDP relay is enabled: DNS queries go through the tunnel over UDP to these
     * servers and are resolved from the Shadowsocks server's network.
     */
    val TUNNELED_DNS_SERVERS = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111")

    /**
     * When UDP relay is disabled, hev-socks5-tunnel answers DNS itself ("mapped DNS"): each name gets
     * a placeholder address from [MAPDNS_NETWORK] and connections to it are sent to the server by
     * name over TCP, so the name is resolved on the server side and nothing leaks.
     */
    const val MAPDNS_ADDRESS = "10.255.0.2"
    const val MAPDNS_NETWORK = "100.64.0.0"
    const val MAPDNS_NETMASK = "255.192.0.0"
}

/** Builds the shadowsocks-rust local configuration (JSON). The result contains the key. */
object SsConfigBuilder {
    /**
     * @param serverIp literal IP of the server, resolved by the app before the VPN starts so the
     *   native client never needs DNS (which would otherwise loop through the tunnel).
     */
    fun build(profile: ShadowsocksProfile, serverIp: String, socksPort: Int): String = buildString {
        append('{')
        field("server", serverIp); append(',')
        append("\"server_port\":").append(profile.port).append(',')
        field("method", profile.method); append(',')
        field("password", profile.effectivePassword); append(',')
        field("local_address", "127.0.0.1"); append(',')
        append("\"local_port\":").append(socksPort).append(',')
        field("mode", if (profile.udpEnabled) "tcp_and_udp" else "tcp_only"); append(',')
        append("\"timeout\":300")
        append('}')
    }

    private fun StringBuilder.field(name: String, value: String) {
        append('"').append(name).append("\":").append(jsonString(value))
    }

    internal fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}

/** Builds the hev-socks5-tunnel YAML configuration. Contains only per-session SOCKS credentials. */
object HevConfigBuilder {
    fun build(socksPort: Int, socksUser: String, socksPassword: String, udpEnabled: Boolean): String {
        require(socksUser.all { it.isLetterOrDigit() } && socksPassword.all { it.isLetterOrDigit() }) {
            "SOCKS credentials must be alphanumeric"
        }
        return buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${TunnelSpec.MTU}")
            appendLine("  ipv4: ${TunnelSpec.IPV4_ADDRESS}")
            appendLine("  ipv6: '${TunnelSpec.IPV6_ADDRESS}'")
            appendLine("socks5:")
            appendLine("  address: 127.0.0.1")
            appendLine("  port: $socksPort")
            appendLine("  udp: 'udp'")
            appendLine("  username: '$socksUser'")
            appendLine("  password: '$socksPassword'")
            if (!udpEnabled) {
                appendLine("mapdns:")
                appendLine("  address: ${TunnelSpec.MAPDNS_ADDRESS}")
                appendLine("  port: 53")
                appendLine("  network: ${TunnelSpec.MAPDNS_NETWORK}")
                appendLine("  netmask: ${TunnelSpec.MAPDNS_NETMASK}")
                appendLine("  cache-size: 10000")
            }
            appendLine("misc:")
            appendLine("  log-level: error")
        }
    }
}
