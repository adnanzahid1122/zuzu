package com.example.zuzuvpn.dev

import android.content.Context
import androidx.compose.runtime.Composable
import com.example.zuzuvpn.AppContainer
import com.example.zuzuvpn.data.ServerStoreState
import com.example.zuzuvpn.data.ShadowsocksProfileStore
import com.example.zuzuvpn.data.servers.ConnectionConfig
import com.example.zuzuvpn.data.servers.ServerDataSource
import com.example.zuzuvpn.data.servers.ServerDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Debug builds: the local data source over the encrypted store, seeded on first launch from the
 * build-time test-server configuration, plus internal screens.
 */
fun createDeveloperTools(context: Context, container: AppContainer): DeveloperTools {
    val seeded = CompletableDeferred<Unit>()
    val dataSource = LocalServerDataSource(container.shadowsocksProfiles, container.appScope, seeded)
    container.appScope.launch {
        try {
            seedBuildTimeServers(
                context,
                DevServerSeeder(container.shadowsocksProfiles, container.serverSelection, PreferencesSeedLedger(context)),
            )
        } finally {
            seeded.complete(Unit)
        }
    }
    return object : DeveloperTools {
        override val localDataSource: ServerDataSource = dataSource
        override val ui: DeveloperUi = DebugDeveloperUi
    }
}

private object DebugDeveloperUi : DeveloperUi {
    @Composable
    override fun SettingsEntry(onOpen: () -> Unit) = DeveloperSettingsEntry(onOpen)

    @Composable
    override fun Screen(onClose: () -> Unit, onServerReady: () -> Unit) = DeveloperScreen(onClose, onServerReady)
}

/**
 * Local (debug/test) server source: the Keystore-encrypted store, which holds the build-time test
 * servers and any server entered under Settings → Developer. The UI only ever sees [ServerDirectory]
 * (public info); configurations are handed out only to the connection layer.
 *
 * @param ready completes once first-launch seeding has finished, so the directory never shows a
 *   misleading empty state before the test server is in place.
 */
class LocalServerDataSource(
    private val store: ShadowsocksProfileStore,
    scope: CoroutineScope,
    private val ready: CompletableDeferred<Unit> = CompletableDeferred(Unit),
) : ServerDataSource {
    override val directory: StateFlow<ServerDirectory> = store.state
        .map(::toDirectory)
        .stateIn(scope, SharingStarted.Eagerly, toDirectory(store.state.value))

    override suspend fun refresh() {
        ready.await()
        store.refresh()
    }

    override suspend fun connectionConfig(serverId: String): ConnectionConfig? {
        ready.await()
        return store.load(serverId)?.config
    }

    companion object {
        fun toDirectory(state: ServerStoreState): ServerDirectory =
            if (state.loaded) ServerDirectory.Available(state.servers) else ServerDirectory.Loading
    }
}
