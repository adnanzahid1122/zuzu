package com.example.zuzuvpn.vpn

/** VPN protocols Zuzu can run. Only one owns the Android VPN interface at a time. */
enum class VpnProtocol { WireGuard, Shadowsocks }

/** Steps shown while connecting, in order. */
enum class ConnectStep { Preparing, ResolvingServer, StartingEngine, EstablishingTunnel, VerifyingTraffic }

/** Why a connection attempt failed or a running tunnel stopped. */
enum class VpnError {
    /** The user declined the system VPN dialog, or another app's VPN took over the permission. */
    PermissionDenied,
    /** No server has been configured for the requested protocol. */
    NoServerConfigured,
    /** The stored server settings are invalid (e.g. wrong key length). */
    InvalidServerConfig,
    /** The server's host name could not be resolved before the tunnel started. */
    ServerNameNotResolved,
    /** The protocol engine could not start (e.g. native library or local port failure). */
    EngineStartFailed,
    /** Android refused to create the VPN interface. */
    TunnelEstablishFailed,
    /** The tunnel came up but no test traffic got through (wrong key, server down, clock skew, blocked). */
    TrafficCheckFailed,
    /** A running engine stopped unexpectedly. */
    EngineStopped,
    /** Another VPN app took over the VPN interface. */
    RevokedBySystem,
    /** The protocol is bundled but not yet wired to the controller. */
    ProtocolNotAvailable,
    Unknown,
}

sealed interface VpnState {
    data object Disconnected : VpnState

    data class Connecting(val protocol: VpnProtocol, val step: ConnectStep) : VpnState

    /**
     * Only reported after test traffic has passed through the tunnel.
     * @param sinceElapsedRealtime `SystemClock.elapsedRealtime()` when the tunnel was verified.
     */
    data class Connected(
        val protocol: VpnProtocol,
        val serverLabel: String,
        val sinceElapsedRealtime: Long,
    ) : VpnState

    data object Disconnecting : VpnState

    /**
     * @param clockHint true when automatic time is off on the device. Shadowsocks 2022 rejects
     *   clients whose clock is more than about 30 seconds off, so this is a likely cause.
     */
    data class Failed(
        val protocol: VpnProtocol?,
        val error: VpnError,
        val clockHint: Boolean = false,
    ) : VpnState
}
