package com.example.zuzuvpn.vpn

import android.net.VpnService
import com.example.zuzuvpn.data.servers.ConnectionConfig

/** Thrown by engines to report a specific, user-presentable failure. */
class VpnEngineException(val error: VpnError, cause: Throwable? = null) : Exception(error.name, cause)

/**
 * One VPN protocol implementation. Engines run inside [ZuzuVpnService], which guarantees that at
 * most one engine is started at a time and always calls [stop] before starting another.
 */
interface VpnEngine {
    val protocol: VpnProtocol

    /** True when the current session's protocol rejects clients with a skewed clock (Shadowsocks 2022). */
    val clockSensitive: Boolean get() = false

    /**
     * Starts the engine with an already resolved, normalised [config] and returns only after test
     * traffic has passed through the tunnel. The engine does not know (or care) whether the
     * configuration came from the local debug source or a backend.
     *
     * @param sessionName shown by Android's VPN UI; a public server name, never a secret.
     * @throws VpnEngineException on a known failure (e.g. a config for another protocol).
     */
    suspend fun start(service: VpnService, config: ConnectionConfig, sessionName: String, onStep: (ConnectStep) -> Unit)

    /** Suspends until the running engine stops on its own, and returns why. */
    suspend fun awaitUnexpectedStop(): VpnError

    /** Stops and releases everything [start] created. Idempotent; safe after a failed start. */
    suspend fun stop()
}

/**
 * WireGuard is bundled through the unchanged :tunnel module (GoBackend), which brings its own
 * VpnService (GoBackend$VpnService). It is not wired into this controller yet: integrating it
 * means having [ZuzuVpnService] stop first, then calling GoBackend.setState(...) and treating a
 * recent handshake plus a test request as "connected".
 */
class WireGuardEngine : VpnEngine {
    override val protocol = VpnProtocol.WireGuard

    override suspend fun start(service: VpnService, config: ConnectionConfig, sessionName: String, onStep: (ConnectStep) -> Unit): Unit =
        throw VpnEngineException(VpnError.ProtocolNotAvailable)

    override suspend fun awaitUnexpectedStop(): VpnError = kotlinx.coroutines.awaitCancellation()

    override suspend fun stop() = Unit
}
