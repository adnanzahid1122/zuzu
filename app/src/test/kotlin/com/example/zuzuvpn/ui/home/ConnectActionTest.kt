package com.example.zuzuvpn.ui.home

import com.example.zuzuvpn.data.locations.LocationDirectory
import com.example.zuzuvpn.data.locations.VpnLocation
import com.example.zuzuvpn.vpn.ConnectStep
import com.example.zuzuvpn.vpn.VpnError
import com.example.zuzuvpn.vpn.VpnProtocol
import com.example.zuzuvpn.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectActionTest {
    private val location = VpnLocation(id = "loc-1", countryCode = "DE", countryName = "Germany", cityName = "Frankfurt")
    private val available = LocationDirectory.Available(listOf(location))

    @Test fun missingBackendNeverStartsTheService() {
        assertEquals(ConnectAction.LocationsUnavailable, ConnectAction.decide(VpnState.Disconnected, LocationDirectory.NotConfigured, null))
        assertEquals(
            ConnectAction.LocationsUnavailable,
            ConnectAction.decide(VpnState.Failed(VpnProtocol.Shadowsocks, VpnError.TrafficCheckFailed), LocationDirectory.NotConfigured, null),
        )
    }

    @Test fun noSelectedLocationIsRefused() {
        assertEquals(ConnectAction.NoLocation, ConnectAction.decide(VpnState.Disconnected, available, null))
        assertEquals(ConnectAction.NoLocation, ConnectAction.decide(VpnState.Disconnected, LocationDirectory.Available(emptyList()), null))
    }

    @Test fun waitsWhileTheDirectoryLoads() {
        assertEquals(ConnectAction.Ignore, ConnectAction.decide(VpnState.Disconnected, LocationDirectory.Loading, null))
    }

    @Test fun connectsByStableLocationId() {
        assertEquals(ConnectAction.Connect("loc-1"), ConnectAction.decide(VpnState.Disconnected, available, location))
    }

    @Test fun activeSessionsDisconnect() {
        assertEquals(ConnectAction.Disconnect, ConnectAction.decide(VpnState.Connected(VpnProtocol.Shadowsocks, "Germany", 0), available, location))
        assertEquals(
            ConnectAction.Disconnect,
            ConnectAction.decide(VpnState.Connecting(VpnProtocol.Shadowsocks, ConnectStep.VerifyingTraffic), available, location),
        )
        assertEquals(ConnectAction.Ignore, ConnectAction.decide(VpnState.Disconnecting, available, location))
    }
}
