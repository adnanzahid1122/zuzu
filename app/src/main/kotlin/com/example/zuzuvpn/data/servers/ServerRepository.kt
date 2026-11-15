package com.example.zuzuvpn.data.servers

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a data source can currently offer. Public info only. */
sealed interface ServerDirectory {
    data object Loading : ServerDirectory

    /** No server source exists in this build (the backend API is not implemented yet). */
    data object NotConfigured : ServerDirectory

    data class Available(val servers: List<ServerInfo>) : ServerDirectory
}

/**
 * Where servers come from. Implementations:
 *  * debug builds: [com.example.zuzuvpn.dev] local data source (build-time test server + servers
 *    entered under Settings → Developer, all in the encrypted on-device store);
 *  * release builds today: [UnconfiguredServerDataSource];
 *  * later: a backend API data source that maps its own DTOs to [ServerInfo] / [ConnectionConfig]
 *    and decides how (and whether) configurations are cached. No endpoint is defined yet.
 *
 * Replacing the data source changes neither the UI nor the VPN service or engines.
 */
interface ServerDataSource {
    val directory: StateFlow<ServerDirectory>

    suspend fun refresh()

    /** The private configuration for [serverId], or null if unknown / not authorized. */
    suspend fun connectionConfig(serverId: String): ConnectionConfig?
}

/** Release builds until the backend exists: honestly reports that no servers are available. */
class UnconfiguredServerDataSource : ServerDataSource {
    override val directory: StateFlow<ServerDirectory> = MutableStateFlow(ServerDirectory.NotConfigured)

    override suspend fun refresh() = Unit

    override suspend fun connectionConfig(serverId: String): ConnectionConfig? = null
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
    private val serverOrder = compareBy<ServerInfo>({ !it.isAvailable }, { !it.isRecommended }, { it.loadPercent ?: Int.MAX_VALUE }, { it.city.orEmpty() }, { it.name })

    /** Countries A–Z (development last); servers in a country by availability, recommendation, load. */
    fun groupByCountry(servers: List<ServerInfo>): List<CountryGroup> =
        servers.groupBy { it.countryKey }
            .map { (key, list) ->
                val first = list.first()
                CountryGroup(key, first.country, first.countryCode, list.sortedWith(serverOrder))
            }
            .sortedWith(compareBy({ it.isDevelopment }, { it.country.lowercase() }))

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
