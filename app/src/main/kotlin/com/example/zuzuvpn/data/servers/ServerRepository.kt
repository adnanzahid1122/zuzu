package com.example.zuzuvpn.data.servers

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What a data source can currently offer. Public info only. */
sealed interface ServerDirectory {
    data object Loading : ServerDirectory

    /** No server source exists in this build. */
    data object NotConfigured : ServerDirectory

    /** The directory could not be loaded and there is no earlier list to show. */
    data class Failed(val error: DirectoryError) : ServerDirectory

    data class Available(
        val servers: List<ServerInfo>,
        /** Country codes in the source's display order; countries not listed sort A–Z after them. */
        val countryOrder: List<String> = emptyList(),
        /** Set when the latest refresh failed and [servers] is the last good list. */
        val refreshError: DirectoryError? = null,
    ) : ServerDirectory
}

/** Why the server directory could not be refreshed. Carries no response content. */
sealed interface DirectoryError {
    /** No connection, DNS/TLS failure or timeout. */
    data object Network : DirectoryError

    /** HTTP 429. */
    data object RateLimited : DirectoryError

    /** HTTP 5xx. */
    data object Server : DirectoryError

    /** Any other unexpected HTTP status (e.g. 404 from a wrong API address). */
    data class Http(val status: Int) : DirectoryError

    /** The response was not the expected JSON shape. */
    data object InvalidResponse : DirectoryError
}

/**
 * Where servers come from. Implementations:
 *  * [com.example.zuzuvpn.data.controlplane.ControlPlaneServerDataSource]: the Zuzu Control Plane's
 *    public directory (all builds). Public metadata only; it provides no configurations yet;
 *  * debug builds also: [com.example.zuzuvpn.dev] local data source (build-time test server +
 *    servers entered under Settings → Developer, all in the encrypted on-device store), merged
 *    with the Control Plane through [CompositeServerDataSource];
 *  * [UnconfiguredServerDataSource]: a build without any source.
 *
 * Replacing the data source changes neither the UI nor the VPN service or engines.
 */
interface ServerDataSource {
    val directory: StateFlow<ServerDirectory>

    suspend fun refresh()

    /** The private configuration for [serverId], or null if unknown / not authorized. */
    suspend fun connectionConfig(serverId: String): ConnectionConfig?
}

/** A build without any server source: honestly reports that no servers are available. */
class UnconfiguredServerDataSource : ServerDataSource {
    override val directory: StateFlow<ServerDirectory> = MutableStateFlow(ServerDirectory.NotConfigured)

    override suspend fun refresh() = Unit

    override suspend fun connectionConfig(serverId: String): ConnectionConfig? = null
}

/**
 * Several sources shown as one directory (debug builds: Control Plane + local test servers).
 * Servers are merged by id (first source wins); a configuration comes only from the source whose
 * entry is shown. While any source is still loading for the first time the directory is [ServerDirectory.Loading].
 */
class CompositeServerDataSource(private val sources: List<ServerDataSource>, scope: CoroutineScope) : ServerDataSource {
    override val directory: StateFlow<ServerDirectory> =
        combine(sources.map { it.directory }) { merge(it.toList()) }
            .stateIn(scope, SharingStarted.Eagerly, merge(sources.map { it.directory.value }))

    override suspend fun refresh() = coroutineScope {
        sources.map { async { it.refresh() } }.awaitAll()
        Unit
    }

    /**
     * Only the source whose entry is displayed for [serverId] (the first that lists it, matching
     * [merge]) is asked. Another source can therefore never supply the configuration for a server
     * shown with the Control Plane's metadata, nor relabel a local test server via a colliding id.
     */
    override suspend fun connectionConfig(serverId: String): ConnectionConfig? {
        val owner = sources.firstOrNull { source ->
            (source.directory.value as? ServerDirectory.Available)?.servers?.any { it.id == serverId } == true
        }
        return owner?.connectionConfig(serverId)
    }

    companion object {
        fun merge(parts: List<ServerDirectory>): ServerDirectory {
            if (parts.any { it == ServerDirectory.Loading }) return ServerDirectory.Loading
            val available = parts.filterIsInstance<ServerDirectory.Available>()
            val failure = parts.filterIsInstance<ServerDirectory.Failed>().firstOrNull()?.error
            if (available.isEmpty()) return failure?.let(ServerDirectory::Failed) ?: ServerDirectory.NotConfigured
            return ServerDirectory.Available(
                servers = available.flatMap { it.servers }.distinctBy { it.id },
                countryOrder = available.flatMap { it.countryOrder }.distinct(),
                refreshError = available.firstNotNullOfOrNull { it.refreshError } ?: failure,
            )
        }
    }
}

/** Servers of one country, for the Locations list. */
data class CountryGroup(
    val key: String,
    val country: String,
    val countryCode: String?,
    val servers: List<ServerInfo>,
) {
    val isDevelopment: Boolean get() = servers.all { it.isDevelopment }
    val isRecommended: Boolean get() = servers.any { it.isRecommended }
    val availableCount: Int get() = servers.count { it.isAvailable }
}

/** The UI's single entry point for servers. Wraps a [ServerDataSource] with grouping and selection rules. */
class ServerRepository(private val source: ServerDataSource) {
    val directory: StateFlow<ServerDirectory> get() = source.directory

    suspend fun refresh() = source.refresh()

    /** Private configuration for the connection layer only (never for display). */
    suspend fun connectionConfig(serverId: String): ConnectionConfig? = source.connectionConfig(serverId)
}

/** Pure grouping, search and selection rules (unit-tested). */
object ServerCatalog {
    private val serverOrder = compareBy<ServerInfo>(
        { !it.isAvailable },
        { !it.isRecommended },
        { -it.priority },
        { it.loadPercent ?: Int.MAX_VALUE },
        { it.city.orEmpty() },
        { it.name },
    )

    /**
     * Countries in [countryOrder] (the source's display order), then the rest A–Z, development last;
     * servers in a country by availability, recommendation, priority, load.
     */
    fun groupByCountry(servers: List<ServerInfo>, countryOrder: List<String> = emptyList()): List<CountryGroup> {
        val rank = countryOrder.withIndex().associate { (i, code) -> code to i }
        return servers.groupBy { it.countryKey }
            .map { (key, list) ->
                val first = list.first()
                CountryGroup(key, first.country, first.countryCode, list.sortedWith(serverOrder))
            }
            .sortedWith(compareBy({ it.isDevelopment }, { rank[it.countryCode] ?: Int.MAX_VALUE }, { it.country.lowercase() }))
    }

    fun search(groups: List<CountryGroup>, query: String): List<CountryGroup> {
        val q = query.trim()
        if (q.isEmpty()) return groups
        return groups.mapNotNull { group ->
            val countryMatch = group.country.contains(q, ignoreCase = true) || group.countryCode.equals(q, ignoreCase = true)
            if (countryMatch) {
                group
            } else {
                val servers = group.servers.filter { it.city.orEmpty().contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true) }
                if (servers.isEmpty()) null else group.copy(servers = servers)
            }
        }
    }

    /** Best server to use when the user picks a whole country: available, recommended, least loaded. */
    fun bestIn(group: CountryGroup): ServerInfo? = group.servers.filter { it.isAvailable }.minWithOrNull(serverOrder)

    /** The selected server if it still exists in [directory]. */
    fun selected(directory: ServerDirectory, selectedId: String?): ServerInfo? =
        (directory as? ServerDirectory.Available)?.servers?.firstOrNull { it.id == selectedId }

    /** First choice when nothing valid is selected: recommended and available, otherwise any available. */
    fun defaultChoice(servers: List<ServerInfo>): ServerInfo? = servers.filter { it.isAvailable }.minWithOrNull(serverOrder)
}

/** Remembers the selected server id (not secret). The one selection used by Home, Locations and the service. */
interface ServerSelection {
    val selectedId: StateFlow<String?>

    fun select(id: String?)
}

class PreferencesServerSelection(context: Context) : ServerSelection {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getString(KEY, null))
    override val selectedId: StateFlow<String?> = state.asStateFlow()

    override fun select(id: String?) {
        prefs.edit { if (id == null) remove(KEY) else putString(KEY, id) }
        state.value = id
    }

    private companion object {
        // Same file/key as the previous LocationSelection, so an existing selection survives.
        const val PREFS = "zuzu_locations"
        const val KEY = "selected_location_id"
    }
}
