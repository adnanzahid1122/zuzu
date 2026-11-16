package com.example.zuzuvpn.dev

import androidx.compose.runtime.Composable
import com.example.zuzuvpn.data.servers.ServerDataSource

/**
 * Build-type specific pieces. Implemented twice:
 *  * src/debug: a local data source over the encrypted store, seeded on first launch from the
 *    build-time test-server configuration (zuzu-dev/test-servers.json, untracked), plus internal
 *    screens reached from Settings → Developer;
 *  * src/release: no local data source and no internal screens (servers come only from the
 *    Control Plane API, see AppContainer).
 *
 * The release build therefore contains neither the test credentials, the seeding code, the
 * manual server editor nor ss:// import.
 */
interface DeveloperTools {
    /** Local test-server source (debug builds), shown alongside the Control Plane directory; null in release. */
    val localDataSource: ServerDataSource?

    /** Internal UI, or null when the build has none (release). */
    val ui: DeveloperUi?
}

interface DeveloperUi {
    /** A section shown at the end of Settings that opens [Screen]. */
    @Composable
    fun SettingsEntry(onOpen: () -> Unit)

    /** [onClose] returns to Settings; [onServerReady] runs after a test server was saved and selected. */
    @Composable
    fun Screen(onClose: () -> Unit, onServerReady: () -> Unit)
}
