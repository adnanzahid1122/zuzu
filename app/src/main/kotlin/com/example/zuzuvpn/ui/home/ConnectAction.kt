package com.example.zuzuvpn.ui.home

import com.example.zuzuvpn.data.servers.ServerDirectory
import com.example.zuzuvpn.data.servers.ServerInfo
import com.example.zuzuvpn.vpn.VpnState

/** What the main button does, decided from the VPN state, the server directory and the selection. */
sealed interface ConnectAction {
    data object Disconnect : ConnectAction
    data object Ignore : ConnectAction

    /** No server source in this build: explain it, never start the service. */
    data object LocationsUnavailable : ConnectAction

    /** The server directory could not be loaded (and no earlier list exists). */
    data object DirectoryUnavailable : ConnectAction

    /** Servers exist but none is selected (or the selection disappeared). */
    data object NoLocation : ConnectAction

    /** The selected server is marked unavailable by its source. */
    data object ServerUnavailable : ConnectAction

    /** Resolve this server's configuration, ask for permissions, then connect. */
    data class Connect(val serverId: String) : ConnectAction

    companion object {
        fun decide(vpn: VpnState, directory: ServerDirectory, selected: ServerInfo?): ConnectAction = when (vpn) {
            is VpnState.Connecting, is VpnState.Connected -> Disconnect
            VpnState.Disconnecting -> Ignore
            VpnState.Disconnected, is VpnState.Failed -> when (directory) {
                ServerDirectory.Loading -> Ignore
                ServerDirectory.NotConfigured -> LocationsUnavailable
                is ServerDirectory.Failed -> DirectoryUnavailable
                is ServerDirectory.Available -> when {
                    selected == null -> NoLocation
                    !selected.isAvailable -> ServerUnavailable
                    else -> Connect(selected.id)
                }
            }
        }
    }
}
