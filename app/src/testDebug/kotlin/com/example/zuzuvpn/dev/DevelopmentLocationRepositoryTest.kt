package com.example.zuzuvpn.dev

import com.example.zuzuvpn.data.SecretBox
import com.example.zuzuvpn.data.ShadowsocksProfileStore
import com.example.zuzuvpn.data.locations.ConnectionTarget
import com.example.zuzuvpn.data.locations.LocationDirectory
import com.example.zuzuvpn.vpn.VpnProtocol
import com.zuzuvpn.shadowsocks.ShadowsocksProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

/** Debug development path: test servers -> development locations -> the engine's internal profile. */
class DevelopmentLocationRepositoryTest {
    private class TestBox : SecretBox {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun seal(plain: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }.let { it.iv + it.doFinal(plain) }
        override fun open(sealed: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
                .doFinal(sealed, 12, sealed.size - 12)
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

    @Test fun emptyStoreGivesAnEmptyDevelopmentDirectory() = runBlocking {
        val repo = DevelopmentLocationRepository(store, scope)
        repo.refresh()
        val directory = withTimeout(2_000) { repo.directory.first { it is LocationDirectory.Available } }
        assertEquals(LocationDirectory.Available(emptyList()), directory)
    }

    @Test fun testServersBecomeLabelledDevelopmentLocations() = runBlocking {
        val id = store.save(null, chacha)
        val repo = DevelopmentLocationRepository(store, scope)
        val directory = withTimeout(2_000) {
            repo.directory.first { it is LocationDirectory.Available && it.locations.isNotEmpty() }
        } as LocationDirectory.Available
        val location = directory.locations.single()
        assertEquals(DevelopmentLocationRepository.locationIdOf(id), location.id)
        assertTrue(location.isDevelopment)
        assertNull(location.countryCode)
        assertNull(location.latencyMs)
        assertNull(location.loadPercent)
        // The location carries no address, port or password.
        assertFalse(location.toString().contains("203.0.113.11"))
        assertFalse(location.toString().contains("placeholder"))
    }

    @Test fun locationResolvesToTheExactInternalProfileForBothCiphers() = runBlocking {
        val repo = DevelopmentLocationRepository(store, scope)
        for (profile in listOf(aead2022, chacha)) {
            val id = store.save(null, profile)
            val target = repo.connectionTarget(DevelopmentLocationRepository.locationIdOf(id))
            assertEquals(ConnectionTarget(VpnProtocol.Shadowsocks, id), target)
            // What ShadowsocksEngine.start(serverId = target.profileId) will load:
            assertEquals(profile, store.load(target!!.profileId))
        }
    }

    @Test fun unknownOrForeignIdsHaveNoTarget() = runBlocking {
        val id = store.save(null, aead2022)
        val repo = DevelopmentLocationRepository(store, scope)
        assertNull(repo.connectionTarget(id)) // raw profile id is not a location id
        assertNull(repo.connectionTarget("de-fra"))
        assertNull(repo.connectionTarget(DevelopmentLocationRepository.locationIdOf("missing")))
        store.delete(id)
        assertNull(repo.connectionTarget(DevelopmentLocationRepository.locationIdOf(id)))
    }
}
