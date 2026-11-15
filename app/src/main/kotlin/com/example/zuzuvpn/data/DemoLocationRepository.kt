package com.example.zuzuvpn.data

import com.example.zuzuvpn.ui.home.SampleServers
import com.example.zuzuvpn.ui.home.ServerLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory DEMO source of locations and the current selection, shared by Home and Locations.
 *
 * Phase 2 replaces this with a repository backed by the Zuzu API. Nothing here contacts a
 * server, measures latency or knows whether a location is available.
 */
object DemoLocationRepository {
    val locations: List<ServerLocation> = SampleServers.all

    private val _selected = MutableStateFlow(SampleServers.default)
    val selected: StateFlow<ServerLocation> = _selected.asStateFlow()

    fun select(id: String) {
        locations.firstOrNull { it.id == id }?.let { _selected.value = it }
    }
}
