package com.example.zuzuvpn.data

import com.example.zuzuvpn.data.servers.ConnectionConfig
import com.example.zuzuvpn.data.servers.CountryCodes
import com.example.zuzuvpn.data.servers.ServerEntry
import com.example.zuzuvpn.data.servers.ServerInfo
import com.example.zuzuvpn.vpn.VpnProtocol
import com.zuzuvpn.shadowsocks.ShadowsocksProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Binary plaintext of the server store (encrypted as a whole by [SecretBox]); pure JVM, unit-tested.
 *
 * v2 (written):
 *   u8 2 | i32 count | count x (
 *     utf id | utf name | utf country | nullable-utf countryCode | nullable-utf city |
 *     bool recommended | bool premium | bool development |
 *     u8 configTag | config )
 *   configTag 1 = Shadowsocks: utf host | i32 port | utf method | utf password | bool udp
 *   configTag 2 = WireGuard:   utf configText
 *
 * v1 (read only; first on-device store): u8 1 | bool hasSelected | [utf selected] | i32 count |
 *   count x (utf id | utf name | utf host | i32 port | utf method | utf password | bool udp).
 *   v1 entries were entered manually in debug builds, so they become development servers.
 *
 * Live metrics (availability, load, latency) are not stored: they come from the directory source.
 */
internal object ServerListCodec {
    private const val V1 = 1
    private const val V2 = 2
    private const val TAG_SHADOWSOCKS = 1
    private const val TAG_WIREGUARD = 2
    private const val MAX_SERVERS = 1_000

    fun encode(servers: List<ServerEntry>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(V2)
            out.writeInt(servers.size)
            for ((info, config) in servers) {
                out.writeUTF(info.id)
                out.writeUTF(info.name)
                out.writeUTF(info.country)
                out.writeNullableUTF(info.countryCode)
                out.writeNullableUTF(info.city)
                out.writeBoolean(info.isRecommended)
                out.writeBoolean(info.isPremium)
                out.writeBoolean(info.isDevelopment)
                when (config) {
                    is ConnectionConfig.Shadowsocks -> {
                        val p = config.profile
                        out.writeByte(TAG_SHADOWSOCKS)
                        out.writeUTF(p.host)
                        out.writeInt(p.port)
                        out.writeUTF(p.method)
                        out.writeUTF(p.password)
                        out.writeBoolean(p.udpEnabled)
                    }
                    is ConnectionConfig.WireGuard -> {
                        out.writeByte(TAG_WIREGUARD)
                        out.writeUTF(config.configText)
                    }
                }
            }
        }
        return bytes.toByteArray()
    }

    /** @throws IllegalArgumentException / java.io.IOException on malformed input. */
    fun decode(data: ByteArray): List<ServerEntry> = DataInputStream(ByteArrayInputStream(data)).use { input ->
        val servers = when (input.readUnsignedByte()) {
            V1 -> decodeV1(input)
            V2 -> decodeV2(input)
            else -> throw IllegalArgumentException("unsupported version")
        }
        require(input.read() == -1) { "trailing data" }
        require(servers.map { it.info.id }.toSet().size == servers.size) { "duplicate ids" }
        servers
    }

    private fun decodeV2(input: DataInputStream): List<ServerEntry> {
        val count = input.readInt()
        require(count in 0..MAX_SERVERS) { "bad count" }
        return List(count) {
            val id = input.readUTF()
            val name = input.readUTF()
            val country = input.readUTF()
            val countryCode = input.readNullableUTF()?.let { CountryCodes.normalize(it) ?: throw IllegalArgumentException("bad country code") }
            val city = input.readNullableUTF()
            val recommended = input.readBoolean()
            val premium = input.readBoolean()
            val development = input.readBoolean()
            val config = when (input.readUnsignedByte()) {
                TAG_SHADOWSOCKS -> {
                    val host = input.readUTF()
                    val port = input.readInt()
                    val method = input.readUTF()
                    val password = input.readUTF()
                    val udp = input.readBoolean()
                    ConnectionConfig.Shadowsocks(ShadowsocksProfile(name, host, port, password, method, udp))
                }
                TAG_WIREGUARD -> ConnectionConfig.WireGuard(input.readUTF())
                else -> throw IllegalArgumentException("unknown config type")
            }
            ServerEntry(
                ServerInfo(
                    id = id,
                    name = name,
                    country = country,
                    countryCode = countryCode,
                    city = city,
                    protocol = config.protocol,
                    isRecommended = recommended,
                    isPremium = premium,
                    isDevelopment = development,
                ),
                config,
            )
        }
    }

    private fun decodeV1(input: DataInputStream): List<ServerEntry> {
        if (input.readBoolean()) input.readUTF() // old store-level selection; selection now lives in ServerSelection
        val count = input.readInt()
        require(count in 0..MAX_SERVERS) { "bad count" }
        return List(count) {
            val id = input.readUTF()
            val profile = ShadowsocksProfile(
                name = input.readUTF(),
                host = input.readUTF(),
                port = input.readInt(),
                method = input.readUTF(),
                password = input.readUTF(),
                udpEnabled = input.readBoolean(),
            )
            developmentEntry(id, profile)
        }
    }

    /** A manually entered (debug) Shadowsocks server: no country metadata. */
    fun developmentEntry(id: String, profile: ShadowsocksProfile) = ServerEntry(
        ServerInfo(
            id = id,
            name = profile.name,
            country = profile.name,
            countryCode = null,
            protocol = VpnProtocol.Shadowsocks,
            isDevelopment = true,
        ),
        ConnectionConfig.Shadowsocks(profile),
    )

    private fun DataOutputStream.writeNullableUTF(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullableUTF(): String? = if (readBoolean()) readUTF() else null
}
