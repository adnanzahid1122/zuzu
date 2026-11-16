package com.example.zuzuvpn.dev

import android.content.Context
import com.example.zuzuvpn.AppContainer
import com.example.zuzuvpn.data.servers.ServerDataSource

/**
 * Release builds: no test servers, no seeding and no internal screens. Servers come only from the
 * Control Plane directory (see AppContainer).
 */
@Suppress("UNUSED_PARAMETER")
fun createDeveloperTools(context: Context, container: AppContainer): DeveloperTools = object : DeveloperTools {
    override val localDataSource: ServerDataSource? = null
    override val ui: DeveloperUi? = null
}
