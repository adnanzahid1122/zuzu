package com.example.zuzuvpn.data.controlplane

import com.example.zuzuvpn.data.servers.ServerCatalog
import com.example.zuzuvpn.data.servers.ServerDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in smoke test against the deployed public directory (read-only GETs, no credentials).
 * Skipped unless ZUZU_LIVE_API=1; ZUZU_API_BASE_URL overrides the default https://api.gorabbit.net.
 */
class LiveDirectorySmokeTest {
    private val base = System.getenv("ZUZU_API_BASE_URL") ?: "https://api.gorabbit.net"

    @Test fun liveDirectoryLoadsThroughTheRealClient() = runBlocking {
        assumeTrue("set ZUZU_LIVE_API=1 to run", System.getenv("ZUZU_LIVE_API") == "1")
        val http = UrlConnectionHttpGetter(userAgent = "ZuzuVPN-Android-test")
        val endpoints = ControlPlaneEndpoints(base)

        val countries = DirectoryParser.parseCountries(http.get(endpoints.countries).body)
        val source = ControlPlaneServerDataSource(http, endpoints)
        source.refresh()
        val dir = source.directory.value
        assertTrue("expected Available, got $dir", dir is ServerDirectory.Available)
        dir as ServerDirectory.Available
        assertNull(dir.refreshError)
        assertEquals(countries.map { it.code }, dir.countryOrder)
        // Every listed server belongs to a listed country, and the counts agree with the API.
        assertTrue(dir.servers.all { it.countryCode in dir.countryOrder })
        assertEquals(countries.sumOf { it.serverCount }, dir.servers.size)
        assertTrue(dir.servers.all { it.isAvailable && !it.isDevelopment })
        dir.servers.forEach { assertNull(source.connectionConfig(it.id)) }
        val groups = ServerCatalog.groupByCountry(dir.servers, dir.countryOrder)
        println("LIVE: ${countries.size} countries, ${dir.servers.size} servers: " + groups.joinToString { g -> "${g.countryCode}=${g.servers.map { it.name }}" })
    }
}
