package com.example.zuzuvpn

import android.app.Application
import com.example.zuzuvpn.data.KeystoreSecretBox
import com.example.zuzuvpn.data.ShadowsocksProfileStore
import com.example.zuzuvpn.data.servers.PreferencesServerSelection
import com.example.zuzuvpn.data.servers.ServerRepository
import com.example.zuzuvpn.data.servers.ServerSelection
import com.example.zuzuvpn.dev.DeveloperTools
import com.example.zuzuvpn.dev.createDeveloperTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.example.zuzuvpn.vpn.ServiceVpnController
import com.example.zuzuvpn.vpn.VpnController
import com.zuzuvpn.shadowsocks.ShadowsocksProfile
import org.json.JSONObject
import java.io.File

class ZuzuApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** Process-wide singletons. */
class AppContainer(app: Application) {
    /** Lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Internal engine profiles (encrypted). In debug builds these are development servers entered on
     * the device; in production they will be configurations provisioned by the backend for a
     * location. Users never edit them in the consumer UI.
     */
    val shadowsocksProfiles = ShadowsocksProfileStore(
        file = File(app.noBackupFilesDir, "ss_servers.bin"),
        box = KeystoreSecretBox(),
        legacyFile = File(app.noBackupFilesDir, "ss_profile.bin"),
        legacyDecoder = ::decodeLegacyProfile,
    )
    val vpnController: VpnController = ServiceVpnController(app)

    /** The selected server id, shared by Home, Locations and the VPN service. */
    val serverSelection: ServerSelection = PreferencesServerSelection(app)

    /** Build-type specific: the debug test-server source and internal screens exist only in debug builds. */
    val developerTools: DeveloperTools = createDeveloperTools(app, this)

    /** The UI's single entry point for servers; the data source behind it is replaceable. */
    val servers = ServerRepository(developerTools.dataSource)
}

/** Payload of the first single-profile store (JSON). Kept only to migrate it. */
private fun decodeLegacyProfile(bytes: ByteArray): ShadowsocksProfile {
    val json = JSONObject(String(bytes, Charsets.UTF_8))
    return ShadowsocksProfile(
        name = json.getString("name"),
        host = json.getString("host"),
        port = json.getInt("port"),
        password = json.getString("password"),
        method = json.getString("method"),
        udpEnabled = json.optBoolean("udp", true),
    )
}
