package com.example.zuzuvpn.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import com.example.zuzuvpn.ZuzuApplication
import com.example.zuzuvpn.data.servers.ServerCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single owner of Zuzu's Android VPN interface.
 *
 * Every connect/disconnect request is handled on one main-thread coroutine chain: a new request
 * cancels and waits for the previous session, and the previous engine is always fully stopped
 * before another engine starts, so two engines can never hold a TUN interface at once.
 */
class ZuzuVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifications: VpnNotifications
    private var sessionJob: Job? = null
    private var engine: VpnEngine? = null
    private var currentProtocol: VpnProtocol? = null
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        notifications = VpnNotifications(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                // Satisfy the foreground-service contract if this start came via startForegroundService.
                enterForeground(VpnState.Disconnecting)
                requestStop(VpnState.Disconnected)
            }
            ACTION_CONNECT -> startSession(ConnectionHandoff.take(intent.getStringExtra(EXTRA_TOKEN)))
            // Started by the system (always-on VPN) with the VpnService action, or restarted:
            // no request is given, so the selected server is resolved instead.
            else -> startSession(request = null)
        }
        return START_NOT_STICKY
    }

    private fun startSession(request: ConnectionRequest?) {
        val protocol = request?.config?.protocol ?: VpnProtocol.Shadowsocks
        val initial = VpnState.Connecting(protocol, ConnectStep.Preparing)
        enterForeground(initial)
        publish(initial)

        val previous = sessionJob
        sessionJob = scope.launch {
            previous?.cancelAndJoin()
            withContext(NonCancellable) { stopEngine() }
            publish(initial)

            if (prepare(this@ZuzuVpnService) != null) {
                finishWith(VpnState.Failed(protocol, VpnError.PermissionDenied))
                return@launch
            }

            val resolved = request ?: selectedRequest()
            if (resolved == null) {
                finishWith(VpnState.Failed(protocol, VpnError.NoServerConfigured))
                return@launch
            }
            val selected = engineFor(resolved.config.protocol)
            engine = selected
            currentProtocol = resolved.config.protocol
            val outcome: VpnState.Failed = try {
                selected.start(this@ZuzuVpnService, resolved.config, resolved.server.name) { step ->
                    publish(VpnState.Connecting(resolved.config.protocol, step))
                }
                publish(VpnState.Connected(resolved.config.protocol, resolved.server.name, SystemClock.elapsedRealtime()))
                val reason = selected.awaitUnexpectedStop()
                VpnState.Failed(protocol, reason, clockHint(reason, selected))
            } catch (e: CancellationException) {
                throw e
            } catch (e: VpnEngineException) {
                VpnState.Failed(protocol, e.error, clockHint(e.error, selected))
            } catch (e: Throwable) {
                Log.e(TAG, "unexpected failure (${e.javaClass.simpleName})")
                VpnState.Failed(protocol, VpnError.Unknown)
            }
            withContext(NonCancellable) { stopEngine() }
            finishWith(outcome)
        }
    }

    private fun requestStop(finalState: VpnState) {
        publish(VpnState.Disconnecting)
        val previous = sessionJob
        sessionJob = scope.launch {
            previous?.cancelAndJoin()
            withContext(NonCancellable) { stopEngine() }
            finishWith(finalState)
        }
    }

    /**
     * Used when the system starts the service without a request (always-on VPN): resolve the
     * user's selected server through the repository. Null when nothing is available, which fails
     * the session cleanly with [VpnError.NoServerConfigured].
     */
    private suspend fun selectedRequest(): ConnectionRequest? {
        val container = (application as ZuzuApplication).container
        val servers = container.servers
        servers.refresh()
        val server = ServerCatalog.selected(servers.directory.value, container.serverSelection.selectedId.value) ?: return null
        val config = servers.connectionConfig(server.id) ?: return null
        return ConnectionRequest(server, config)
    }

    private suspend fun stopEngine() {
        val running = engine ?: return
        engine = null
        runCatching { running.stop() }.onFailure { Log.w(TAG, "engine stop failed (${it.javaClass.simpleName})") }
    }

    private fun engineFor(protocol: VpnProtocol): VpnEngine {
        return when (protocol) {
            VpnProtocol.Shadowsocks -> ShadowsocksEngine(applicationContext)
            VpnProtocol.WireGuard -> WireGuardEngine()
        }
    }

    private fun publish(state: VpnState) {
        VpnStateStore.set(state)
        if (state is VpnState.Connecting || state is VpnState.Connected || state is VpnState.Disconnecting) {
            notifications.update(state)
        }
    }

    private fun finishWith(state: VpnState) {
        publish(state)
        currentProtocol = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Only stops if no newer start request has arrived in the meantime.
        stopSelf(lastStartId)
    }

    private fun enterForeground(state: VpnState) {
        val notification = notifications.build(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, VpnNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(VpnNotifications.ID, notification)
        }
    }

    /** Shadowsocks 2022 rejects clients whose clock is off; flag it when automatic time is disabled. */
    private fun clockHint(error: VpnError, engine: VpnEngine): Boolean =
        engine.clockSensitive &&
            (error == VpnError.TrafficCheckFailed || error == VpnError.EngineStopped) &&
            Settings.Global.getInt(contentResolver, Settings.Global.AUTO_TIME, 1) == 0

    override fun onRevoke() {
        // Another VPN app took over, or the user revoked permission in system settings.
        requestStop(VpnState.Failed(currentProtocol, VpnError.RevokedBySystem))
    }

    override fun onDestroy() {
        sessionJob?.cancel()
        val running = engine
        engine = null
        if (running != null) {
            // Last-resort cleanup if the system destroys us mid-session.
            runBlocking { withTimeoutOrNull(STOP_TIMEOUT_MS) { running.stop() } }
            if (VpnStateStore.state.value !is VpnState.Failed) VpnStateStore.set(VpnState.Disconnected)
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ZuzuVpnService"
        private const val STOP_TIMEOUT_MS = 4_000L
        const val ACTION_CONNECT = "com.example.zuzuvpn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.example.zuzuvpn.action.DISCONNECT"
        private const val EXTRA_TOKEN = "request_token" // one-time ConnectionHandoff token, never a secret itself

        fun connectIntent(context: Context, token: String): Intent =
            Intent(context, ZuzuVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_TOKEN, token)

        fun disconnectIntent(context: Context): Intent =
            Intent(context, ZuzuVpnService::class.java).setAction(ACTION_DISCONNECT)
    }
}
