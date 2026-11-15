package com.example.zuzuvpn.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.example.zuzuvpn.data.servers.ConnectionConfig
import com.example.zuzuvpn.data.servers.ServerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Protocol-independent entry point used by the UI.
 *
 * The caller must obtain VPN permission first ([VpnPermission.prepare]); [connect] does not show
 * any UI.
 */
interface VpnController {
    val state: StateFlow<VpnState>

    /**
     * Connects to [server] with its resolved private [config] (a snapshot for this session).
     * The engine is chosen from the config's protocol.
     */
    fun connect(server: ServerInfo, config: ConnectionConfig)

    fun disconnect()

    /** Clears a [VpnState.Failed] back to [VpnState.Disconnected]. */
    fun acknowledgeFailure()
}

object VpnPermission {
    /** Null when permission is already granted, otherwise the system consent activity to launch. */
    fun prepare(context: Context): Intent? = VpnService.prepare(context)
}

/**
 * Process-wide tunnel state. Written only by [ZuzuVpnService]; read through [VpnController].
 */
internal object VpnStateStore {
    private val mutable = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = mutable.asStateFlow()

    fun set(state: VpnState) {
        mutable.value = state
    }

    fun clearFailure() {
        mutable.update { if (it is VpnState.Failed) VpnState.Disconnected else it }
    }
}

/** [VpnController] backed by the foreground [ZuzuVpnService]. */
class ServiceVpnController(context: Context) : VpnController {
    private val appContext = context.applicationContext

    override val state: StateFlow<VpnState> = VpnStateStore.state

    override fun connect(server: ServerInfo, config: ConnectionConfig) {
        val token = ConnectionHandoff.put(ConnectionRequest(server, config))
        ContextCompat.startForegroundService(appContext, ZuzuVpnService.connectIntent(appContext, token))
    }

    override fun disconnect() {
        // Delivered to the running service; if none is running this is a no-op.
        if (state.value is VpnState.Disconnected || state.value is VpnState.Failed) return
        appContext.startService(ZuzuVpnService.disconnectIntent(appContext))
    }

    override fun acknowledgeFailure() = VpnStateStore.clearFailure()
}
