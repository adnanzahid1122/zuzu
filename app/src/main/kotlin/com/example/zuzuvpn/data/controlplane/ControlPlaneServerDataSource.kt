package com.example.zuzuvpn.data.controlplane

import android.util.Log
import com.example.zuzuvpn.data.servers.ConfigResolution
import com.example.zuzuvpn.data.servers.ConnectionConfig
import com.example.zuzuvpn.delivery.SecureConfigProvider
import com.example.zuzuvpn.data.servers.DirectoryError
import com.example.zuzuvpn.data.servers.ServerDataSource
import com.example.zuzuvpn.data.servers.ServerDirectory
import com.example.zuzuvpn.data.servers.ServerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Server directory from the Zuzu Control Plane's public endpoints. The directory is public metadata
 * only. Connection configurations come exclusively from encrypted per-device delivery
 * ([SecureConfigProvider]) for a signed-in customer; never from the directory.
 *
 * Each [refresh] re-reads the whole directory, so servers added, changed, disabled or removed in
 * the dashboard appear on the next refresh. A failed refresh keeps the last good list and reports
 * the error alongside it. Nothing is cached on disk.
 */
class ControlPlaneServerDataSource(
    private val http: HttpGetter,
    private val endpoints: ControlPlaneEndpoints,
    private val maxPages: Int = 20,
    /** Encrypted per-device configuration delivery; null = directory only (no configurations). */
    private val configs: SecureConfigProvider? = null,
) : ServerDataSource {
    private val state = MutableStateFlow<ServerDirectory>(ServerDirectory.Loading)
    override val directory: StateFlow<ServerDirectory> = state.asStateFlow()
    private val mutex = Mutex()

    override suspend fun refresh() = mutex.withLock {
        state.value = try {
            val servers = fetchServers()
            // Countries are optional: they give the dashboard's display order and drop servers whose
            // country was disabled between the two requests. Without them, servers are still shown.
            val countries = try {
                DirectoryParser.parseCountries(fetch(endpoints.countries))
            } catch (e: DirectoryFailure) {
                Log.w(TAG, "Countries unavailable (${e.error})")
                null
            } catch (e: InvalidDirectoryResponse) {
                Log.w(TAG, "Countries response invalid")
                null
            }
            val codes = countries?.map { it.code }?.toSet()
            ServerDirectory.Available(
                servers = if (codes == null) servers else servers.filter { it.countryCode in codes },
                countryOrder = countries?.map { it.code }.orEmpty(),
            )
        } catch (e: DirectoryFailure) {
            Log.w(TAG, "Server directory refresh failed (${e.error})")
            when (val previous = state.value) {
                is ServerDirectory.Available -> previous.copy(refreshError = e.error)
                else -> ServerDirectory.Failed(e.error)
            }
        }
    }

    override suspend fun connectionConfig(serverId: String): ConnectionConfig? =
        (resolveConnection(serverId) as? ConfigResolution.Ready)?.config

    override suspend fun resolveConnection(serverId: String): ConfigResolution {
        val provider = configs ?: return ConfigResolution.Unavailable
        val server = (state.value as? ServerDirectory.Available)?.servers?.firstOrNull { it.id == serverId } ?: return ConfigResolution.Unavailable
        return provider.resolve(serverId, server.name)
    }

    private suspend fun fetchServers(): List<ServerInfo> {
        val all = LinkedHashMap<String, ServerInfo>()
        var skipped = 0
        for (page in 1..maxPages) {
            val result = try {
                DirectoryParser.parseServerPage(fetch(endpoints.servers(page, ControlPlaneEndpoints.MAX_PAGE_SIZE)))
            } catch (e: InvalidDirectoryResponse) {
                throw DirectoryFailure(DirectoryError.InvalidResponse)
            }
            result.servers.forEach { all.putIfAbsent(it.id, it) }
            skipped += result.skipped
            val seen = page * ControlPlaneEndpoints.MAX_PAGE_SIZE
            if (result.servers.isEmpty() && result.skipped == 0 || seen >= result.total) break
        }
        if (skipped > 0) Log.w(TAG, "Skipped $skipped malformed server entries")
        return all.values.toList()
    }

    private suspend fun fetch(url: String): String {
        val result = try {
            http.get(url)
        } catch (e: IOException) {
            throw DirectoryFailure(DirectoryError.Network)
        }
        return when (result.status) {
            in 200..299 -> result.body
            429 -> throw DirectoryFailure(DirectoryError.RateLimited)
            in 500..599 -> throw DirectoryFailure(DirectoryError.Server)
            else -> throw DirectoryFailure(DirectoryError.Http(result.status))
        }
    }

    private class DirectoryFailure(val error: DirectoryError) : Exception()

    private companion object {
        const val TAG = "ZuzuDirectory"
    }
}
