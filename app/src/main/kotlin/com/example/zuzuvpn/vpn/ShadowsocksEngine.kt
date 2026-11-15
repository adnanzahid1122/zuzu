package com.example.zuzuvpn.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.example.zuzuvpn.MainActivity
import com.example.zuzuvpn.data.servers.ConnectionConfig
import com.zuzuvpn.shadowsocks.HevConfigBuilder
import com.zuzuvpn.shadowsocks.HevTunnel
import com.zuzuvpn.shadowsocks.ProtectServer
import com.zuzuvpn.shadowsocks.ShadowsocksProfile
import com.zuzuvpn.shadowsocks.ShadowsocksProfileValidator
import com.zuzuvpn.shadowsocks.SsConfigBuilder
import com.zuzuvpn.shadowsocks.SsLocal
import com.zuzuvpn.shadowsocks.TunnelSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.UnknownHostException
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Shadowsocks 2022 over a TUN interface:
 *
 *   apps -> TUN -> hev-socks5-tunnel -> SOCKS5 (127.0.0.1, random port, per-session user/password)
 *        -> shadowsocks-rust client -> protected socket -> Shadowsocks server
 *
 * TCP and UDP (when enabled) are relayed; IPv4 and IPv6 are both routed into the tunnel so
 * nothing leaks around it. DNS goes through the tunnel (see [TunnelSpec]).
 */
@OptIn(ExperimentalCoroutinesApi::class) // CompletableDeferred.getCompleted
class ShadowsocksEngine(private val context: Context) : VpnEngine {
    override val protocol = VpnProtocol.Shadowsocks

    @Volatile override var clockSensitive = false
        private set

    private var protectServer: ProtectServer? = null
    private var clientThread: Thread? = null
    private var clientExit: CompletableDeferred<String?>? = null
    private var tun: ParcelFileDescriptor? = null
    private var hevStarted = false
    private var hevConfig: File? = null

    override suspend fun start(service: VpnService, config: ConnectionConfig, sessionName: String, onStep: (ConnectStep) -> Unit) = withContext(Dispatchers.IO) {
        onStep(ConnectStep.Preparing)
        // The configuration is a snapshot: later changes at the source do not affect this session.
        val profile = (config as? ConnectionConfig.Shadowsocks)?.profile ?: throw VpnEngineException(VpnError.ProtocolNotAvailable)
        clockSensitive = ShadowsocksProfile.isAead2022(profile.method)
        if (ShadowsocksProfileValidator.validate(profile).isNotEmpty()) {
            throw VpnEngineException(VpnError.InvalidServerConfig)
        }
        loadNativeLibraries()

        // Resolve before the VPN exists: afterwards our own DNS lookups would go into the tunnel.
        onStep(ConnectStep.ResolvingServer)
        val serverIp = resolve(profile.host)

        onStep(ConnectStep.StartingEngine)
        startClient(service, profile, serverIp)

        onStep(ConnectStep.EstablishingTunnel)
        establishTunnel(service, profile, sessionName)

        onStep(ConnectStep.VerifyingTraffic)
        verifyTraffic()
    }

    private fun loadNativeLibraries() {
        try {
            SsLocal.nativeVersion()
            HevTunnel.TProxyIsRunning()
        } catch (e: UnsatisfiedLinkError) {
            throw VpnEngineException(VpnError.EngineStartFailed, e)
        }
    }

    private fun resolve(host: String): InetAddress = try {
        val all = InetAddress.getAllByName(host)
        // Prefer IPv4: the device may have no IPv6 route on its physical network.
        all.firstOrNull { it is Inet4Address } ?: all.first()
    } catch (e: UnknownHostException) {
        throw VpnEngineException(VpnError.ServerNameNotResolved, e)
    }

    private suspend fun startClient(service: VpnService, profile: ShadowsocksProfile, serverIp: InetAddress) {
        val protect = ProtectServer(File(context.noBackupFilesDir, PROTECT_SOCKET)) { fd -> service.protect(fd) }
        try {
            protect.start()
        } catch (e: Exception) {
            throw VpnEngineException(VpnError.EngineStartFailed, e)
        }
        protectServer = protect

        val socksPort = freeLoopbackPort()
        val socksUser = randomToken()
        val socksPassword = randomToken()
        val config = SsConfigBuilder.build(profile, serverIp.hostAddress!!, socksPort)

        val exit = CompletableDeferred<String?>()
        clientExit = exit
        clientThread = thread(name = "zuzu-sslocal", isDaemon = true) {
            val error = runCatching { SsLocal.nativeRun(config, protect.path, socksUser, socksPassword) }
                .getOrElse { "JNI failure: ${it.javaClass.simpleName}" }
            exit.complete(error)
        }

        // Wait until the SOCKS5 listener accepts connections (or the client exits with an error).
        val ready = withTimeoutOrNull(CLIENT_START_TIMEOUT_MS) {
            while (true) {
                if (exit.isCompleted) return@withTimeoutOrNull false
                if (canConnect(socksPort)) return@withTimeoutOrNull true
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
        if (!ready) {
            exit.takeIf { it.isCompleted }?.getCompleted()?.let { Log.w(TAG, "client failed to start: $it") }
            throw VpnEngineException(VpnError.EngineStartFailed)
        }

        pendingHevConfig = HevConfigBuilder.build(socksPort, socksUser, socksPassword, profile.udpEnabled)
    }

    private var pendingHevConfig: String? = null

    private fun establishTunnel(service: VpnService, profile: ShadowsocksProfile, sessionName: String) {
        val builder = service.Builder()
            .setSession(sessionName)
            .setMtu(TunnelSpec.MTU)
            .addAddress(TunnelSpec.IPV4_ADDRESS, TunnelSpec.IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)
            // IPv6 is always captured, so it cannot bypass the tunnel even if the server has no IPv6.
            .addAddress(TunnelSpec.IPV6_ADDRESS, TunnelSpec.IPV6_PREFIX)
            .addRoute("::", 0)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (profile.udpEnabled) {
            TunnelSpec.TUNNELED_DNS_SERVERS.forEach(builder::addDnsServer)
        } else {
            builder.addDnsServer(TunnelSpec.MAPDNS_ADDRESS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            throw VpnEngineException(VpnError.TunnelEstablishFailed, e)
        } ?: throw VpnEngineException(VpnError.PermissionDenied)
        tun = pfd

        val configFile = File(context.noBackupFilesDir, HEV_CONFIG)
        configFile.writeText(pendingHevConfig!!)
        pendingHevConfig = null
        hevConfig = configFile
        if (!HevTunnel.TProxyStartService(configFile.absolutePath, pfd.fd)) {
            throw VpnEngineException(VpnError.TunnelEstablishFailed)
        }
        hevStarted = true
    }

    /** Requires a real HTTPS request (DNS + TCP + TLS) to succeed over the VPN network. */
    private suspend fun verifyTraffic() {
        val vpnNetwork = withTimeoutOrNull(VPN_NETWORK_TIMEOUT_MS) {
            var network: Network? = null
            while (network == null) {
                network = findVpnNetwork()
                if (network == null) delay(100)
            }
            network
        } ?: throw VpnEngineException(VpnError.TunnelEstablishFailed)

        repeat(PROBE_ATTEMPTS) { attempt ->
            for (url in PROBE_URLS) {
                if (clientExit?.isCompleted == true) throw VpnEngineException(VpnError.EngineStopped)
                if (probe(vpnNetwork, url)) return
            }
            if (attempt < PROBE_ATTEMPTS - 1) delay(1_000)
        }
        throw VpnEngineException(VpnError.TrafficCheckFailed)
    }

    /**
     * The VPN network created by this app. The owner UID is only exposed from API 30
     * (NetworkCapabilities.getOwnerUid); on API 24-29 any VPN network is accepted, relying on
     * Android allowing a single active VPN per user, which we have just established.
     */
    @Suppress("DEPRECATION") // allNetworks: simplest way to find the VPN network on API 24+
    private fun findVpnNetwork(): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || caps.ownerUid == Process.myUid())
        }
    }

    private fun probe(network: Network, url: String): Boolean {
        val connection = network.openConnection(URL(url)) as HttpURLConnection
        return try {
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.responseCode == HttpURLConnection.HTTP_NO_CONTENT
        } catch (e: Exception) {
            Log.i(TAG, "connectivity probe failed: ${e.javaClass.simpleName}")
            false
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun awaitUnexpectedStop(): VpnError {
        val exit = clientExit ?: return VpnError.EngineStopped
        while (true) {
            if (exit.isCompleted) {
                exit.getCompleted()?.let { Log.w(TAG, "client stopped: $it") }
                return VpnError.EngineStopped
            }
            if (hevStarted && !HevTunnel.TProxyIsRunning()) return VpnError.EngineStopped
            delay(1_000)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        // Order: stop reading the TUN, close it, stop the client, then the protect server.
        if (hevStarted) {
            runCatching { HevTunnel.TProxyStopService() }
            hevStarted = false
        }
        runCatching { tun?.close() }
        tun = null
        hevConfig?.delete()
        hevConfig = null
        pendingHevConfig = null

        clientThread?.let { worker ->
            // nativeStop() is a no-op until nativeRun has registered its stop channel, so keep
            // signalling until the thread actually ends (covers a stop right after the thread started).
            val deadline = SystemClock.elapsedRealtime() + CLIENT_STOP_TIMEOUT_MS
            while (worker.isAlive && SystemClock.elapsedRealtime() < deadline) {
                runCatching { SsLocal.nativeStop() }
                worker.join(100)
            }
            if (worker.isAlive) Log.w(TAG, "client did not stop in time")
        }
        clientThread = null
        clientExit = null

        protectServer?.close()
        protectServer = null
    }

    private fun canConnect(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 200) }
    }.isSuccess

    /** A loopback port that is currently free for both TCP and UDP (SOCKS5 UDP relay uses the same port). */
    private fun freeLoopbackPort(): Int {
        repeat(10) {
            val port = ServerSocket(0, 1, LOOPBACK).use { it.localPort }
            val udpFree = runCatching { DatagramSocket(InetSocketAddress(LOOPBACK, port)).close() }.isSuccess
            if (udpFree) return port
        }
        throw VpnEngineException(VpnError.EngineStartFailed)
    }

    private fun randomToken(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "ZuzuShadowsocks"
        const val PROTECT_SOCKET = "ss_protect.sock"
        const val HEV_CONFIG = "hev-socks5-tunnel.yml"
        const val CLIENT_START_TIMEOUT_MS = 5_000L
        const val CLIENT_STOP_TIMEOUT_MS = 3_000L
        const val VPN_NETWORK_TIMEOUT_MS = 5_000L
        const val PROBE_TIMEOUT_MS = 6_000
        const val PROBE_ATTEMPTS = 3
        val PROBE_URLS = listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val random = SecureRandom()
    }
}
