package com.example.zuzuvpn.ui.home

import com.example.zuzuvpn.data.servers.DirectoryError
import com.example.zuzuvpn.data.servers.ServerDirectory
import com.example.zuzuvpn.data.servers.ServerInfo
import com.example.zuzuvpn.vpn.ConnectStep
import com.example.zuzuvpn.vpn.VpnError
import com.example.zuzuvpn.vpn.VpnProtocol
import com.example.zuzuvpn.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectActionTest {
    private val server = ServerInfo(id = "srv-1", name = "Frankfurt", country = "Germany", countryCode = "DE", city = "Frankfurt", protocol = VpnProtocol.Shadowsocks)
    private val available = ServerDirectory.Available(listOf(server))

    @Test fun missingSourceNeverStartsTheService() {
        assertEquals(ConnectAction.LocationsUnavailable, ConnectAction.decide(VpnState.Disconnected, ServerDirectory.NotConfigured, null))
        assertEquals(
            ConnectAction.LocationsUnavailable,
            ConnectAction.decide(VpnState.Failed(VpnProtocol.Shadowsocks, VpnError.TrafficCheckFailed), ServerDirectory.NotConfigured, null),
        )
    }

    @Test fun unreachableDirectoryNeverStartsTheService() {
        for (error in listOf(DirectoryError.Network, DirectoryError.RateLimited, DirectoryError.Server, DirectoryError.Http(404), DirectoryError.InvalidResponse)) {
            assertEquals(ConnectAction.DirectoryUnavailable, ConnectAction.decide(VpnState.Disconnected, ServerDirectory.Failed(error), null))
        }
    }

    @Test fun noSelectedServerIsRefused() {
        assertEquals(ConnectAction.NoLocation, ConnectAction.decide(VpnState.Disconnected, available, null))
        assertEquals(ConnectAction.NoLocation, ConnectAction.decide(VpnState.Disconnected, ServerDirectory.Available(emptyList()), null))
    }

    @Test fun unavailableServerIsRefused() {
        val down = server.copy(isAvailable = false)
        assertEquals(ConnectAction.ServerUnavailable, ConnectAction.decide(VpnState.Disconnected, ServerDirectory.Available(listOf(down)), down))
    }

    @Test fun waitsWhileTheDirectoryLoads() {
        assertEquals(ConnectAction.Ignore, ConnectAction.decide(VpnState.Disconnected, ServerDirectory.Loading, null))
    }

    @Test fun connectsByStableServerId() {
        assertEquals(ConnectAction.Connect("srv-1"), ConnectAction.decide(VpnState.Disconnected, available, server))
        // A stale (refresh-failed) list still allows connecting to a listed server.
        val stale = available.copy(refreshError = DirectoryError.Network)
        assertEquals(ConnectAction.Connect("srv-1"), ConnectAction.decide(VpnState.Disconnected, stale, server))
    }

    @Test fun activeSessionsDisconnect() {
        assertEquals(ConnectAction.Disconnect, ConnectAction.decide(VpnState.Connected(VpnProtocol.Shadowsocks, "Germany", 0), available, server))
        assertEquals(
            ConnectAction.Disconnect,
            ConnectAction.decide(VpnState.Connecting(VpnProtocol.Shadowsocks, ConnectStep.VerifyingTraffic), available, server),
        )
        assertEquals(ConnectAction.Ignore, ConnectAction.decide(VpnState.Disconnecting, available, server))
    }
}
