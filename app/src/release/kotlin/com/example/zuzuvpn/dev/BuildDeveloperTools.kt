package com.example.zuzuvpn.dev

import android.content.Context
import com.example.zuzuvpn.AppContainer
import com.example.zuzuvpn.data.servers.ServerDataSource
import com.example.zuzuvpn.data.servers.UnconfiguredServerDataSource

/**
 * Release builds: no test servers, no seeding and no internal screens. Until the backend API data
 * source exists, the server directory reports "not configured" and Connect explains that no
 * locations are available.
 */
@Suppress("UNUSED_PARAMETER")
fun createDeveloperTools(context: Context, container: AppContainer): DeveloperTools = object : DeveloperTools {
    override val dataSource: ServerDataSource = UnconfiguredServerDataSource()
    override val ui: DeveloperUi? = null
}
