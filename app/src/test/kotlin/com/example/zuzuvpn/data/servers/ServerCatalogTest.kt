package com.example.zuzuvpn.data.servers

import com.example.zuzuvpn.ui.locations.DirectoryStatus
import com.example.zuzuvpn.ui.locations.LocationsUiState
import com.example.zuzuvpn.ui.navigation.ZuzuDestination
import com.example.zuzuvpn.vpn.VpnProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grouping, search, selection and Locations state (formerly LocationsTest, before ServerCatalog replaced LocationCatalog). */
class ServerCatalogTest {
    // Test data only.
    private val de = ServerInfo("de-fra", "Frankfurt", "Germany", "DE", "Frankfurt", VpnProtocol.Shadowsocks, isRecommended = true)
    private val jp = ServerInfo("jp-tyo", "Tokyo", "Japan", "JP", "Tokyo", VpnProtocol.Shadowsocks)
    private val deBer = ServerInfo("de-ber", "Berlin", "Germany", "DE", "Berlin", VpnProtocol.Shadowsocks)
    private val dev = ServerInfo("dev-1", "My test box", "My test box", null, null, VpnProtocol.Shadowsocks, isDevelopment = true)
    private val all = listOf(jp, dev, de, deBer)

    @Test fun unconfiguredSourceReportsNotConfiguredAndNoConfig() = runBlocking {
        val source = UnconfiguredServerDataSource()
        assertEquals(ServerDirectory.NotConfigured, source.directory.value)
        assertNull(source.connectionConfig("anything"))
    }

    @Test fun groupsCountriesAlphabeticallyWithDevelopmentLast() {
        val groups = ServerCatalog.groupByCountry(all)
        assertEquals(listOf("Germany", "Japan", "My test box"), groups.map { it.country })
        // Within a country: recommended first, then by city.
        assertEquals(listOf(de, deBer), groups.first().servers)
    }

    @Test fun sourceCountryOrderAndPriorityAreRespected() {
        val groups = ServerCatalog.groupByCountry(all, countryOrder = listOf("JP", "DE"))
        assertEquals(listOf("Japan", "Germany", "My test box"), groups.map { it.country })
        val hi = deBer.copy(id = "de-ham", city = "Hamburg", priority = 5)
        assertEquals(listOf(de, hi, deBer), ServerCatalog.groupByCountry(listOf(deBer, hi, de)).single().servers)
    }

    @Test fun searchMatchesCountryCityAndCode() {
        val groups = ServerCatalog.groupByCountry(all)
        assertEquals(listOf("Germany"), ServerCatalog.search(groups, "germ").map { it.country })
        assertEquals(listOf(jp), ServerCatalog.search(groups, "tokyo").single().servers)
        assertEquals(listOf("Germany"), ServerCatalog.search(groups, "de").map { it.country })
        assertTrue(ServerCatalog.search(groups, "atlantis").isEmpty())
    }

    @Test fun selectedServerMustExistInTheDirectory() {
        val directory = ServerDirectory.Available(all)
        assertEquals(jp, ServerCatalog.selected(directory, "jp-tyo"))
        assertNull(ServerCatalog.selected(directory, "gone"))
        assertNull(ServerCatalog.selected(ServerDirectory.NotConfigured, "jp-tyo"))
        assertNull(ServerCatalog.selected(ServerDirectory.Failed(DirectoryError.Network), "jp-tyo"))
    }

    @Test fun bestServerSkipsUnavailableOnes() {
        val group = ServerCatalog.groupByCountry(listOf(de.copy(isAvailable = false), deBer)).single()
        assertEquals(deBer, ServerCatalog.bestIn(group))
        assertNull(ServerCatalog.bestIn(group.copy(servers = listOf(de.copy(isAvailable = false)))))
    }

    @Test fun locationsStateShowsOnlyWhatTheSourceProvides() {
        val notConfigured = LocationsUiState.from(ServerDirectory.NotConfigured, "", null)
        assertEquals(DirectoryStatus.NotConfigured, notConfigured.status)
        assertTrue(notConfigured.countries.isEmpty())

        val state = LocationsUiState.from(ServerDirectory.Available(all), "", "de-fra")
        assertEquals(listOf(de), state.recommended) // only entries the source marked
        assertEquals(3, state.countries.size)
        assertTrue(state.hasDevelopment)
        assertTrue(state.countries.flatMap { it.servers }.all { it.latencyMs == null && it.loadPercent == null }) // nothing invented

        val searching = LocationsUiState.from(ServerDirectory.Available(all), "japan", null)
        assertTrue(searching.recommended.isEmpty())
        assertEquals(listOf("Japan"), searching.countries.map { it.country })
        assertFalse(LocationsUiState.from(ServerDirectory.Available(listOf(jp)), "", null).hasDevelopment)

        val stale = LocationsUiState.from(ServerDirectory.Available(listOf(jp), refreshError = DirectoryError.Server), "", null)
        assertEquals(DirectoryStatus.Available, stale.status)
        assertEquals(DirectoryError.Server, stale.error)
    }

    @Test fun consumerNavigationHasExactlyThreeAreas() {
        assertEquals(
            listOf(ZuzuDestination.Home, ZuzuDestination.Locations, ZuzuDestination.Settings),
            ZuzuDestination.entries.toList(),
        )
    }
}
