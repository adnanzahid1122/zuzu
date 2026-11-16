package com.example.zuzuvpn.ui.locations

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.zuzuvpn.ZuzuApplication
import com.example.zuzuvpn.data.servers.CountryGroup
import com.example.zuzuvpn.data.servers.DirectoryError
import com.example.zuzuvpn.data.servers.ServerCatalog
import com.example.zuzuvpn.data.servers.ServerDirectory
import com.example.zuzuvpn.data.servers.ServerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DirectoryStatus { Loading, NotConfigured, Failed, Available }

data class LocationsUiState(
    val status: DirectoryStatus = DirectoryStatus.Loading,
    val query: String = "",
    val selectedId: String? = null,
    /** Servers the source marks as recommended; shown only when [query] is blank. */
    val recommended: List<ServerInfo> = emptyList(),
    /** Countries with their servers, or the search results when [query] is not blank. */
    val countries: List<CountryGroup> = emptyList(),
    /** True when development test servers (debug builds) are listed. */
    val hasDevelopment: Boolean = false,
    /** Why loading failed ([DirectoryStatus.Failed]) or why the latest refresh failed (list is stale). */
    val error: DirectoryError? = null,
    /** A user-requested refresh (pull-to-refresh / Retry) is running. */
    val isRefreshing: Boolean = false,
) {
    companion object {
        fun from(directory: ServerDirectory, query: String, selectedId: String?): LocationsUiState = when (directory) {
            ServerDirectory.Loading -> LocationsUiState(DirectoryStatus.Loading, query, selectedId)
            ServerDirectory.NotConfigured -> LocationsUiState(DirectoryStatus.NotConfigured, query, selectedId)
            is ServerDirectory.Failed -> LocationsUiState(DirectoryStatus.Failed, query, selectedId, error = directory.error)
            is ServerDirectory.Available -> {
                val groups = ServerCatalog.groupByCountry(directory.servers, directory.countryOrder)
                LocationsUiState(
                    status = DirectoryStatus.Available,
                    query = query,
                    selectedId = selectedId,
                    recommended = if (query.isBlank()) groups.flatMap { g -> g.servers.filter { it.isRecommended } } else emptyList(),
                    countries = ServerCatalog.search(groups, query),
                    hasDevelopment = directory.servers.any { it.isDevelopment },
                    error = directory.refreshError,
                )
            }
        }
    }
}

class LocationsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as ZuzuApplication).container
    private val servers = container.servers
    private val selection = container.serverSelection
    private val query = MutableStateFlow("")
    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<LocationsUiState> =
        combine(servers.directory, query, selection.selectedId, refreshing) { directory, q, selectedId, isRefreshing ->
            LocationsUiState.from(directory, q, selectedId).copy(isRefreshing = isRefreshing)
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = LocationsUiState.from(servers.directory.value, "", selection.selectedId.value),
            )

    init {
        viewModelScope.launch { servers.refresh() }
    }

    /** Pull-to-refresh and Retry: re-reads the directory from its source. Selecting never connects. */
    fun onRefresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                servers.refresh()
            } finally {
                refreshing.value = false
            }
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** Selects a specific server. */
    fun onSelectServer(id: String) {
        selection.select(id)
    }

    /** Selects the best available server in a country. Returns false if none is available. */
    fun onSelectCountry(group: CountryGroup): Boolean {
        val best = ServerCatalog.bestIn(group) ?: return false
        selection.select(best.id)
        return true
    }
}
