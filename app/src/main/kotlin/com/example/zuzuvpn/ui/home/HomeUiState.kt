package com.example.zuzuvpn.ui.home

import com.example.zuzuvpn.data.servers.ServerDirectory
import com.example.zuzuvpn.data.servers.ServerInfo
import com.example.zuzuvpn.vpn.VpnState

/**
 * Visual phase of the home screen (button, mascot, status dot). Derived from the real
 * [com.example.zuzuvpn.vpn.VpnState]; `Connected` is only shown after test traffic has passed
 * through the tunnel.
 */
enum class ConnectionPhase { Disconnected, Connecting, Connected }

/** Problems the Home screen explains before (or instead of) starting the VPN. */
enum class HomeNotice {
    /** No trusted location source in this build. */
    LocationsUnavailable,
    /** The server directory could not be loaded (network or server problem). */
    DirectoryUnreachable,
    /** The user pressed Connect without a location. */
    NoLocation,
    /** The selected server is unavailable or has no connection configuration. */
    LocationNotReady,
    /** The user declined the system VPN permission dialog. */
    PermissionDenied,
    /** Zuzu servers need a signed-in account (or the session ended). */
    SignInRequired,
    SubscriptionRequired,
    PremiumRequired,
    RateLimited,
    /** The configuration response failed verification, or the device clock is far off. */
    SecurityError,
}

data class HomeUiState(
    val vpn: VpnState = VpnState.Disconnected,
    val directory: ServerDirectory = ServerDirectory.Loading,
    /** The selected server, if it exists in the current directory. */
    val location: ServerInfo? = null,
    val notice: HomeNotice? = null,
) {
    val phase: ConnectionPhase
        get() = when (vpn) {
            is VpnState.Connected -> ConnectionPhase.Connected
            is VpnState.Connecting, VpnState.Disconnecting -> ConnectionPhase.Connecting
            VpnState.Disconnected, is VpnState.Failed -> ConnectionPhase.Disconnected
        }
}
