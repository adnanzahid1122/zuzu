package com.example.zuzuvpn.data.controlplane

import com.example.zuzuvpn.data.servers.CompositeServerDataSource
import com.example.zuzuvpn.data.servers.ConnectionConfig
import com.example.zuzuvpn.data.servers.DirectoryError
import com.example.zuzuvpn.data.servers.ServerCatalog
import com.example.zuzuvpn.data.servers.ServerDataSource
import com.example.zuzuvpn.data.servers.ServerDirectory
import com.example.zuzuvpn.data.servers.ServerInfo
import com.example.zuzuvpn.ui.home.ConnectAction
import com.example.zuzuvpn.ui.locations.DirectoryStatus
import com.example.zuzuvpn.ui.locations.LocationsUiState
import com.example.zuzuvpn.vpn.VpnProtocol
import com.example.zuzuvpn.vpn.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val BASE = "https://api.gorabbit.net"
private val endpoints = ControlPlaneEndpoints(BASE)

/** JSON shaped exactly like the live API (GET /api/v1/servers, GET /api/v1/countries). */
private fun serverJson(
    id: String,
    name: String,
    code: String = "FR",
    country: String = "France",
    city: String? = "Paris",
    protocol: String = "shadowsocks",
    extra: String = "",
) = """{"id":"$id","name":"$name","country":{"code":"$code","name":"$country","flag":"x"},"city":${city?.let { "\"$it\"" } ?: "null"},"protocol":"$protocol","available":true,"premium":false,"recommended":false,"priority":1,"supportsUdp":true,"loadPercent":null,"latencyMs":null$extra}"""

private fun page(vararg items: String, page: Int = 1, total: Int = items.size) =
    """{"items":[${items.joinToString(",")}],"page":$page,"pageSize":100,"total":$total}"""

private const val COUNTRIES = """[{"code":"FR","name":"France","flag":"x","serverCount":1},{"code":"DE","name":"Germany","flag":"x","serverCount":1}]"""

/** Fake transport: URL -> response, or an IOException for unreachable. */
private class FakeHttp(var routes: Map<String, Any>) : HttpGetter {
    val requested = mutableListOf<String>()

    override suspend fun get(url: String): HttpResult {
        requested += url
        return when (val r = routes[url]) {
            is HttpResult -> r
            is IOException -> throw r
            null -> HttpResult(404, """{"error":{"statusCode":404,"code":"NOT_FOUND","message":"Not found"}}""")
            else -> error("bad route")
        }
    }
}

private fun ok(body: String) = HttpResult(200, body)
private val serversUrl = endpoints.servers(1, 100)

class ControlPlaneEndpointsTest {
    @Test
    fun `api v1 is appended exactly once`() {
        for (base in listOf(BASE, "$BASE/", "$BASE/api", "$BASE/api/", "$BASE/api/v1", "$BASE/api/v1/")) {
            val e = ControlPlaneEndpoints(base)
            assertEquals("https://api.gorabbit.net/api/v1/countries", e.countries)
            assertEquals("https://api.gorabbit.net/api/v1/servers?page=2&pageSize=100", e.servers(2, 100))
        }
    }

    @Test
    fun `only https is accepted`() {
        assertThrows(IllegalArgumentException::class.java) { ControlPlaneEndpoints("http://api.gorabbit.net") }
    }
}

class DirectoryParserTest {
    @Test
    fun `parses countries`() {
        val countries = DirectoryParser.parseCountries(COUNTRIES)
        assertEquals(listOf(PublicCountry("FR", "France", 1), PublicCountry("DE", "Germany", 1)), countries)
    }

    @Test
    fun `skips malformed countries and rejects a non-array body`() {
        val countries = DirectoryParser.parseCountries("""[{"code":"ZZ","name":"Nowhere"},{"code":"FR"},{"name":"x"},42,{"code":"de","name":"Germany"}]""")
        assertEquals(listOf(PublicCountry("DE", "Germany", 0)), countries)
        assertThrows(InvalidDirectoryResponse::class.java) { DirectoryParser.parseCountries("""{"items":[]}""") }
        assertThrows(InvalidDirectoryResponse::class.java) { DirectoryParser.parseCountries("<html>") }
    }

    @Test
    fun `maps the public server fields`() {
        val body = page(
            serverJson("a", "Paris", extra = ""),
            """{"id":"b","name":"Berlin 1","country":{"code":"DE","name":"Germany","flag":"x"},"city":null,"protocol":"wireguard","available":true,"premium":true,"recommended":true,"priority":7,"supportsUdp":false,"loadPercent":42,"latencyMs":35}""",
        )
        val result = DirectoryParser.parseServerPage(body)
        assertEquals(0, result.skipped)
        assertEquals(2, result.total)
        val paris = result.servers[0]
        assertEquals(ServerInfo("a", "Paris", "France", "FR", "Paris", VpnProtocol.Shadowsocks, priority = 1), paris)
        val berlin = result.servers[1]
        assertEquals(VpnProtocol.WireGuard, berlin.protocol)
        assertNull(berlin.city)
        assertTrue(berlin.isPremium && berlin.isRecommended)
        assertEquals(7, berlin.priority)
        assertEquals(42, berlin.loadPercent)
        assertEquals(35, berlin.latencyMs)
    }

    @Test
    fun `skips malformed or incomplete servers without guessing`() {
        val body = page(
            serverJson("ok", "Paris"),
            """{"name":"No id","country":{"code":"FR","name":"France"},"protocol":"shadowsocks","available":true}""",
            """{"id":"x1","name":"  ","country":{"code":"FR","name":"France"},"protocol":"shadowsocks","available":true}""",
            """{"id":"x2","name":"Bad code","country":{"code":"XX","name":"Nowhere"},"protocol":"shadowsocks","available":true}""",
            """{"id":"x3","name":"No country","protocol":"shadowsocks","available":true}""",
            """{"id":"x4","name":"Odd protocol","country":{"code":"FR","name":"France"},"protocol":"openvpn","available":true}""",
            "17",
        )
        val result = DirectoryParser.parseServerPage(body)
        assertEquals(listOf("ok"), result.servers.map { it.id })
        assertEquals(6, result.skipped)
    }

    @Test
    fun `filters servers the backend does not mark available`() {
        val body = page(
            serverJson("a", "Paris"),
            serverJson("b", "Lyon").replace("\"available\":true", "\"available\":false"),
            serverJson("c", "Nice").replace("\"available\":true,", ""),
        )
        assertEquals(listOf("a"), DirectoryParser.parseServerPage(body).servers.map { it.id })
    }

    @Test
    fun `never invents metrics`() {
        val body = page(serverJson("a", "Paris").replace("\"loadPercent\":null,\"latencyMs\":null", "\"loadPercent\":250,\"latencyMs\":-3"))
        val s = DirectoryParser.parseServerPage(body).servers.single()
        assertNull(s.loadPercent)
        assertNull(s.latencyMs)
    }

    @Test
    fun `rejects a body that is not a server page`() {
        assertThrows(InvalidDirectoryResponse::class.java) { DirectoryParser.parseServerPage("[]") }
        assertThrows(InvalidDirectoryResponse::class.java) { DirectoryParser.parseServerPage("""{"total":0}""") }
    }
}

class ControlPlaneServerDataSourceTest {
    private fun source(http: FakeHttp) = ControlPlaneServerDataSource(http, endpoints)

    @Test
    fun `starts loading, then lists servers in the dashboard's country order`() = runBlocking {
        val http = FakeHttp(
            mapOf(
                serversUrl to ok(page(serverJson("a", "Paris"), serverJson("b", "Frankfurt", "DE", "Germany", "Frankfurt"))),
                endpoints.countries to ok("""[{"code":"DE","name":"Germany","serverCount":1},{"code":"FR","name":"France","serverCount":1}]"""),
            ),
        )
        val src = source(http)
        assertEquals(ServerDirectory.Loading, src.directory.value)
        src.refresh()
        val dir = src.directory.value as ServerDirectory.Available
        assertEquals(listOf("a", "b"), dir.servers.map { it.id })
        assertEquals(listOf("DE", "FR"), dir.countryOrder)
        assertNull(dir.refreshError)
        assertEquals(listOf("Germany", "France"), ServerCatalog.groupByCountry(dir.servers, dir.countryOrder).map { it.country })
        assertTrue(http.requested.all { it.startsWith("https://api.gorabbit.net/api/v1/") })
    }

    @Test
    fun `an empty directory is available but empty`() = runBlocking {
        val src = source(FakeHttp(mapOf(serversUrl to ok(page()), endpoints.countries to ok("[]"))))
        src.refresh()
        assertEquals(ServerDirectory.Available(emptyList()), src.directory.value)
        val ui = LocationsUiState.from(src.directory.value, "", null)
        assertEquals(DirectoryStatus.Available, ui.status)
        assertTrue(ui.countries.isEmpty())
    }

    @Test
    fun `failures are reported by kind`() = runBlocking {
        val cases = mapOf<Any, DirectoryError>(
            IOException("offline") to DirectoryError.Network,
            HttpResult(500, "{}") to DirectoryError.Server,
            HttpResult(503, "") to DirectoryError.Server,
            HttpResult(429, "{}") to DirectoryError.RateLimited,
            HttpResult(404, "{}") to DirectoryError.Http(404),
            ok("<html>not json</html>") to DirectoryError.InvalidResponse,
        )
        for ((response, expected) in cases) {
            val src = source(FakeHttp(mapOf(serversUrl to response)))
            src.refresh()
            assertEquals(ServerDirectory.Failed(expected), src.directory.value)
            assertEquals(DirectoryStatus.Failed, LocationsUiState.from(src.directory.value, "", null).status)
        }
    }

    @Test
    fun `a failed refresh keeps the last list and recovers on retry`() = runBlocking {
        val http = FakeHttp(mapOf(serversUrl to ok(page(serverJson("a", "Paris"))), endpoints.countries to ok(COUNTRIES)))
        val src = source(http)
        src.refresh()
        http.routes = mapOf(serversUrl to IOException("offline"))
        src.refresh()
        val stale = src.directory.value as ServerDirectory.Available
        assertEquals(listOf("a"), stale.servers.map { it.id })
        assertEquals(DirectoryError.Network, stale.refreshError)
        http.routes = mapOf(serversUrl to ok(page(serverJson("a", "Paris"))), endpoints.countries to ok(COUNTRIES))
        src.refresh()
        assertNull((src.directory.value as ServerDirectory.Available).refreshError)
    }

    @Test
    fun `refresh reflects servers added, changed and removed in the dashboard`() = runBlocking {
        val http = FakeHttp(mapOf(serversUrl to ok(page(serverJson("a", "Paris"), serverJson("b", "Lyon"))), endpoints.countries to ok(COUNTRIES)))
        val src = source(http)
        src.refresh()
        http.routes = mapOf(serversUrl to ok(page(serverJson("a", "Paris Nord"), serverJson("c", "Nice"))), endpoints.countries to ok(COUNTRIES))
        src.refresh()
        val servers = (src.directory.value as ServerDirectory.Available).servers
        assertEquals(listOf("a" to "Paris Nord", "c" to "Nice"), servers.map { it.id to it.name })
    }

    @Test
    fun `drops servers whose country is no longer listed, but works without the countries endpoint`() = runBlocking {
        val body = page(serverJson("a", "Paris"), serverJson("n", "Amsterdam", "NL", "Netherlands", "Amsterdam"))
        val src = source(FakeHttp(mapOf(serversUrl to ok(body), endpoints.countries to ok(COUNTRIES))))
        src.refresh()
        assertEquals(listOf("a"), (src.directory.value as ServerDirectory.Available).servers.map { it.id })

        val noCountries = source(FakeHttp(mapOf(serversUrl to ok(body), endpoints.countries to HttpResult(500, ""))))
        noCountries.refresh()
        val dir = noCountries.directory.value as ServerDirectory.Available
        assertEquals(listOf("a", "n"), dir.servers.map { it.id })
        assertNull(dir.refreshError)
    }

    @Test
    fun `reads every page`() = runBlocking {
        val first = (1..100).map { serverJson("s$it", "Server $it") }.toTypedArray()
        val http = FakeHttp(
            mapOf(
                endpoints.servers(1, 100) to ok(page(*first, total = 101)),
                endpoints.servers(2, 100) to ok(page(serverJson("s101", "Server 101"), page = 2, total = 101)),
                endpoints.countries to ok(COUNTRIES),
            ),
        )
        val src = source(http)
        src.refresh()
        assertEquals(101, (src.directory.value as ServerDirectory.Available).servers.size)
    }

    @Test
    fun `never provides a connection configuration`() = runBlocking {
        val src = source(FakeHttp(mapOf(serversUrl to ok(page(serverJson("a", "Paris"))), endpoints.countries to ok(COUNTRIES))))
        src.refresh()
        assertNull(src.connectionConfig("a"))
    }
}

class ServerSelectionAndCompositeTest {
    private val paris = ServerInfo("cp-1", "Paris", "France", "FR", "Paris", VpnProtocol.Shadowsocks)
    private val dev = ServerInfo("dev-1", "Test", "Germany", "DE", null, VpnProtocol.Shadowsocks, isDevelopment = true)

    private class StaticSource(value: ServerDirectory, private val config: ConnectionConfig? = null) : ServerDataSource {
        override val directory: StateFlow<ServerDirectory> = MutableStateFlow(value)
        var refreshed = 0
        override suspend fun refresh() {
            refreshed++
        }
        override suspend fun connectionConfig(serverId: String) = config
    }

    @Test
    fun `selecting a directory server is resolved from the current list and disappears when removed`() {
        val directory = ServerDirectory.Available(listOf(paris))
        assertEquals(paris, ServerCatalog.selected(directory, "cp-1"))
        assertNull(ServerCatalog.selected(ServerDirectory.Available(emptyList()), "cp-1"))
        assertEquals(ConnectAction.Connect("cp-1"), ConnectAction.decide(VpnState.Disconnected, directory, paris))
        assertEquals(ConnectAction.NoLocation, ConnectAction.decide(VpnState.Disconnected, ServerDirectory.Available(emptyList()), null))
    }

    @Test
    fun `a failed directory never offers to connect`() {
        assertEquals(ConnectAction.DirectoryUnavailable, ConnectAction.decide(VpnState.Disconnected, ServerDirectory.Failed(DirectoryError.Network), null))
    }

    @Test
    fun `merges control plane and development servers`() {
        val merged = CompositeServerDataSource.merge(
            listOf(ServerDirectory.Available(listOf(paris), countryOrder = listOf("FR")), ServerDirectory.Available(listOf(dev))),
        ) as ServerDirectory.Available
        assertEquals(listOf("cp-1", "dev-1"), merged.servers.map { it.id })
        assertEquals(ServerDirectory.Loading, CompositeServerDataSource.merge(listOf(ServerDirectory.Loading, ServerDirectory.Available(listOf(dev)))))
        val offline = CompositeServerDataSource.merge(listOf(ServerDirectory.Failed(DirectoryError.Network), ServerDirectory.Available(listOf(dev))))
        assertEquals(DirectoryError.Network, (offline as ServerDirectory.Available).refreshError)
        assertEquals(ServerDirectory.Failed(DirectoryError.Server), CompositeServerDataSource.merge(listOf(ServerDirectory.Failed(DirectoryError.Server))))
    }

    @Test
    fun `configurations still come only from the local source`() = runBlocking {
        val devConfig = ConnectionConfig.WireGuard("opaque")
        val cp = StaticSource(ServerDirectory.Available(listOf(paris)))
        val local = StaticSource(ServerDirectory.Available(listOf(dev)), devConfig)
        val composite = CompositeServerDataSource(listOf(cp, local), CoroutineScope(Dispatchers.Unconfined))
        assertEquals(devConfig, composite.connectionConfig("dev-1"))
        composite.refresh()
        assertEquals(1, cp.refreshed)
        assertEquals(1, local.refreshed)
        assertEquals(2, (composite.directory.value as ServerDirectory.Available).servers.size)
    }
}
