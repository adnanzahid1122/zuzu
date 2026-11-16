package com.example.zuzuvpn.dev

import com.example.zuzuvpn.data.SecretBox
import com.example.zuzuvpn.data.ServerListCodec
import com.example.zuzuvpn.data.ShadowsocksProfileStore
import com.example.zuzuvpn.data.servers.CompositeServerDataSource
import com.example.zuzuvpn.data.servers.ConnectionConfig
import com.example.zuzuvpn.data.servers.ServerDataSource
import com.example.zuzuvpn.data.servers.ServerDirectory
import com.example.zuzuvpn.data.servers.ServerInfo
import com.example.zuzuvpn.vpn.VpnProtocol
import com.zuzuvpn.shadowsocks.ShadowsocksProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Debug development path: encrypted test servers -> development directory entries -> the engine's
 * configuration (formerly DevelopmentLocationRepositoryTest, before LocalServerDataSource replaced
 * DevelopmentLocationRepository). Also covers how it merges with the Control Plane directory.
 */
class LocalServerDataSourceTest {
    private class TestBox : SecretBox {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun seal(plain: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }.let { it.iv + it.doFinal(plain) }
        override fun open(sealed: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
                .doFinal(sealed, 12, sealed.size - 12)
    }

    /** Stand-in for the Control Plane source: public entries, never a configuration. */
    private class PublicOnlySource(servers: List<ServerInfo>) : ServerDataSource {
        override val directory: StateFlow<ServerDirectory> = MutableStateFlow(ServerDirectory.Available(servers))
        override suspend fun refresh() = Unit
        override suspend fun connectionConfig(serverId: String): ConnectionConfig? = null
    }

    private lateinit var dir: File
    private lateinit var store: ShadowsocksProfileStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Placeholder credentials for tests only.
    private val aead2022 = ShadowsocksProfile("Box A", "203.0.113.10", 8388, "AAAAAAAAAAAAAAAAAAAAAA==")
    private val chacha = ShadowsocksProfile(
        "Box B", "203.0.113.11", 443, " placeholder:pw ", ShadowsocksProfile.METHOD_CHACHA20_IETF_POLY1305, udpEnabled = false,
    )

    @Before fun setUp() {
        dir = Files.createTempDirectory("zuzu-dev").toFile()
        store = ShadowsocksProfileStore(File(dir, "s.bin"), TestBox())
    }

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private suspend fun availableOf(source: ServerDataSource, nonEmpty: Boolean = false) = withTimeout(2_000) {
        source.directory.first { it is ServerDirectory.Available && (!nonEmpty || it.servers.isNotEmpty()) }
    } as ServerDirectory.Available

    @Test fun emptyStoreGivesAnEmptyDevelopmentDirectory() = runBlocking {
        val source = LocalServerDataSource(store, scope)
        source.refresh()
        assertEquals(ServerDirectory.Available(emptyList()), availableOf(source))
    }

    @Test fun staysLoadingUntilFirstLaunchSeedingHasFinished() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val source = LocalServerDataSource(store, scope, ready)
        assertEquals(ServerDirectory.Loading, source.directory.value)
        val refreshing = scope.launch { source.refresh() }
        assertFalse(refreshing.isCompleted)
        ready.complete(Unit)
        withTimeout(2_000) { refreshing.join() }
        assertEquals(ServerDirectory.Available(emptyList()), availableOf(source))
    }

    @Test fun testServersBecomeLabelledDevelopmentEntriesWithoutSecrets() = runBlocking {
        store.save(ServerListCodec.developmentEntry("dev-b", chacha))
        val source = LocalServerDataSource(store, scope)
        source.refresh()
        val server = availableOf(source, nonEmpty = true).servers.single()
        assertEquals("dev-b", server.id)
        assertTrue(server.isDevelopment)
        assertNull(server.countryCode)
        assertNull(server.latencyMs)
        assertNull(server.loadPercent)
        // The directory entry carries no address, port or password.
        assertFalse(server.toString().contains("203.0.113.11"))
        assertFalse(server.toString().contains("placeholder"))
    }

    @Test fun entryResolvesToTheExactProfileForBothCiphers() = runBlocking {
        val source = LocalServerDataSource(store, scope)
        for ((id, profile) in listOf("dev-a" to aead2022, "dev-b" to chacha)) {
            store.save(ServerListCodec.developmentEntry(id, profile))
            assertEquals(ConnectionConfig.Shadowsocks(profile), source.connectionConfig(id))
            assertEquals(profile, (source.connectionConfig(id) as ConnectionConfig.Shadowsocks).profile)
        }
    }

    @Test fun unknownOrDeletedIdsHaveNoConfiguration() = runBlocking {
        store.save(ServerListCodec.developmentEntry("dev-a", aead2022))
        val source = LocalServerDataSource(store, scope)
        assertNull(source.connectionConfig("de-fra"))
        assertNull(source.connectionConfig("missing"))
        store.delete("dev-a")
        assertNull(source.connectionConfig("dev-a"))
    }

    @Test fun mergedWithControlPlaneTheTestServerStillConnects() = runBlocking {
        store.save(ServerListCodec.developmentEntry("dev-a", aead2022))
        val local = LocalServerDataSource(store, scope)
        local.refresh()
        val cpServer = ServerInfo("cp-1", "Paris", "France", "FR", "Paris", VpnProtocol.Shadowsocks)
        val composite = CompositeServerDataSource(listOf(PublicOnlySource(listOf(cpServer)), local), scope)
        val merged = availableOf(composite, nonEmpty = true)
        withTimeout(2_000) { composite.directory.first { (it as? ServerDirectory.Available)?.servers?.size == 2 } }
        assertTrue(merged.servers.any { it.id == "cp-1" })
        assertEquals(ConnectionConfig.Shadowsocks(aead2022), composite.connectionConfig("dev-a"))
        assertNull(composite.connectionConfig("cp-1")) // public directory entries never carry a configuration
    }

    @Test fun aDirectoryEntryCannotTakeOverALocalServersConfiguration() = runBlocking {
        store.save(ServerListCodec.developmentEntry("dev-a", aead2022))
        val local = LocalServerDataSource(store, scope)
        local.refresh()
        // The API lists a server with a colliding id: it is shown with the API's metadata, so it must
        // not be connected with the local test server's credentials.
        val impostor = ServerInfo("dev-a", "Impostor", "France", "FR", null, VpnProtocol.Shadowsocks)
        val composite = CompositeServerDataSource(listOf(PublicOnlySource(listOf(impostor)), local), scope)
        withTimeout(2_000) { composite.directory.first { it is ServerDirectory.Available } }
        assertNull(composite.connectionConfig("dev-a"))
    }
}

