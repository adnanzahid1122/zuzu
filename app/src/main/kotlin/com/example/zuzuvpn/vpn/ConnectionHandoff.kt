package com.example.zuzuvpn.vpn

import com.example.zuzuvpn.data.servers.ConnectionConfig
import com.example.zuzuvpn.data.servers.ServerInfo
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** A resolved connection request: public server info for the UI/notification, private config for the engine. */
data class ConnectionRequest(val server: ServerInfo, val config: ConnectionConfig) {
    override fun toString() = "ConnectionRequest(server=$server, config=$config)" // config redacts secrets
}

/**
 * Passes a [ConnectionRequest] from the controller to [ZuzuVpnService] inside the process, so
 * credentials never travel in an Intent. The Intent only carries a random one-time token.
 */
internal object ConnectionHandoff {
    private val pending = ConcurrentHashMap<String, ConnectionRequest>()
    private val random = SecureRandom()

    fun put(request: ConnectionRequest): String {
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        pending.clear() // only the latest request matters
        pending[token] = request
        return token
    }

    /** Returns and forgets the request for [token]. */
    fun take(token: String?): ConnectionRequest? = token?.let(pending::remove)
}
