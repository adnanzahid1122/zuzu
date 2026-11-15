package com.example.zuzuvpn.ui.locations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.zuzuvpn.data.DemoLocationRepository
import com.example.zuzuvpn.ui.home.ServerLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class LocationsUiState(
    val query: String = "",
    val selectedId: String = "",
    /** Shown only when [query] is blank. */
    val recommended: List<ServerLocation> = emptyList(),
    /** All locations, or the search results when [query] is not blank. */
    val locations: List<ServerLocation> = emptyList(),
    /** Always true in Phase 1: every entry is sample data. */
    val isDemo: Boolean = true,
)

class LocationsViewModel : ViewModel() {

    private val query = MutableStateFlow("")

    val state: StateFlow<LocationsUiState> =
        combine(query, DemoLocationRepository.selected) { q, selected ->
            buildState(q, selected.id)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = buildState("", DemoLocationRepository.selected.value.id),
        )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onSelect(id: String) {
        DemoLocationRepository.select(id)
    }

    private fun buildState(q: String, selectedId: String): LocationsUiState {
        val all = DemoLocationRepository.locations
        val trimmed = q.trim()
        return if (trimmed.isEmpty()) {
            LocationsUiState(
                query = q,
                selectedId = selectedId,
                recommended = all.filter { it.isRecommended },
                locations = all.sortedWith(compareBy({ it.countryName }, { it.cityName })),
            )
        } else {
            LocationsUiState(
                query = q,
                selectedId = selectedId,
                recommended = emptyList(),
                locations = all.filter {
                    it.countryName.contains(trimmed, ignoreCase = true) ||
                        it.cityName.contains(trimmed, ignoreCase = true) ||
                        it.countryCode.equals(trimmed, ignoreCase = true)
                },
            )
        }
    }
}
