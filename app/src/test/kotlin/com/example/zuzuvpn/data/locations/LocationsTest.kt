package com.example.zuzuvpn.data.locations

import com.example.zuzuvpn.ui.locations.DirectoryStatus
import com.example.zuzuvpn.ui.locations.LocationsUiState
import com.example.zuzuvpn.ui.navigation.ZuzuDestination
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationsTest {
    // Test data only; production locations come from the (future) backend.
    private val de = VpnLocation("de-fra", "DE", "Germany", "Frankfurt", isRecommended = true)
    private val jp = VpnLocation("jp-tyo", "JP", "Japan", "Tokyo")
    private val deBer = VpnLocation("de-ber", "DE", "Germany", "Berlin")
    private val dev = VpnLocation("dev-1", null, "My test box", null, isDevelopment = true)
    private val all = listOf(jp, dev, de, deBer)

    @Test fun releaseRepositoryReportsNotConfiguredAndNoTarget() = runBlocking {
        val repo = UnconfiguredLocationRepository()
        assertEquals(LocationDirectory.NotConfigured, repo.directory.value)
        assertNull(repo.connectionTarget("anything"))
    }

    @Test fun sortsByCountryThenCityWithDevelopmentLast() {
        assertEquals(listOf(deBer, de, jp, dev), LocationCatalog.sorted(all))
    }

    @Test fun searchMatchesCountryCityAndCode() {
        assertEquals(listOf(deBer, de), LocationCatalog.search(all, "germ"))
        assertEquals(listOf(jp), LocationCatalog.search(all, "tokyo"))
        assertEquals(listOf(deBer, de), LocationCatalog.search(all, "de"))
        assertTrue(LocationCatalog.search(all, "atlantis").isEmpty())
    }

    @Test fun selectedLocationMustExistInTheDirectory() {
        val directory = LocationDirectory.Available(all)
        assertEquals(jp, LocationCatalog.selected(directory, "jp-tyo"))
        assertNull(LocationCatalog.selected(directory, "gone"))
        assertNull(LocationCatalog.selected(LocationDirectory.NotConfigured, "jp-tyo"))
    }

    @Test fun locationsStateShowsOnlyWhatTheSourceProvides() {
        val notConfigured = LocationsUiState.from(LocationDirectory.NotConfigured, "", null)
        assertEquals(DirectoryStatus.NotConfigured, notConfigured.status)
        assertTrue(notConfigured.locations.isEmpty())

        val state = LocationsUiState.from(LocationDirectory.Available(all), "", "de-fra")
        assertEquals(listOf(de), state.recommended) // only entries the source marked
        assertEquals(4, state.locations.size)
        assertTrue(state.hasDevelopment)
        assertTrue(state.locations.all { it.latencyMs == null && it.loadPercent == null }) // nothing invented

        val searching = LocationsUiState.from(LocationDirectory.Available(all), "japan", null)
        assertTrue(searching.recommended.isEmpty())
        assertEquals(listOf(jp), searching.locations)
        assertFalse(LocationsUiState.from(LocationDirectory.Available(listOf(jp)), "", null).hasDevelopment)
    }

    @Test fun consumerNavigationHasExactlyThreeAreas() {
        assertEquals(
            listOf(ZuzuDestination.Home, ZuzuDestination.Locations, ZuzuDestination.Settings),
            ZuzuDestination.entries.toList(),
        )
    }
}
