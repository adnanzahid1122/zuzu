package com.example.zuzuvpn.data

import com.example.zuzuvpn.data.servers.ConnectionConfig
import com.example.zuzuvpn.data.servers.ServerEntry
import com.example.zuzuvpn.data.servers.ServerInfo
import com.example.zuzuvpn.vpn.VpnProtocol
import com.zuzuvpn.shadowsocks.ShadowsocksProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ShadowsocksProfileStoreTest {
    /** AES-256-GCM like [KeystoreSecretBox], but with an in-memory key (no Android Keystore on the JVM). */
    private class TestBox(var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : SecretBox {
        override fun seal(plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return c.iv + c.doFinal(plain)
        }

        override fun open(sealed: ByteArray): ByteArray {
            val k = key ?: throw MissingKeyException()
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, sealed, 0, 12)) }
            return c.doFinal(sealed, 12, sealed.size - 12)
        }
    }

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var box: TestBox
    private var ids = 0

    // Placeholder key: 16 bytes of 0xFB. Not a real secret.
    private val key = "+/v7+/v7+/v7+/v7+/v7+w=="
    private fun profile(name: String = "Home server", host: String = "vpn.example.com") =
        ShadowsocksProfile(name = name, host = host, port = 8388, password = key)

    private fun entry(id: String, p: ShadowsocksProfile = profile(), countryCode: String? = "DE") = ServerEntry(
        ServerInfo(id = id, name = p.name, country = "Germany", countryCode = countryCode, city = "Frankfurt", protocol = VpnProtocol.Shadowsocks),
        ConnectionConfig.Shadowsocks(p),
    )

    private fun ShadowsocksProfileStore.profileOf(id: String): ShadowsocksProfile? = runBlocking {
        (load(id)?.config as? ConnectionConfig.Shadowsocks)?.profile
    }

    private fun store(b: SecretBox = box, legacy: File? = null, decoder: ((ByteArray) -> ShadowsocksProfile)? = null) =
        ShadowsocksProfileStore(file, b, legacy, decoder, idGenerator = { "id-${++ids}" })

    @Before fun setUp() {
        dir = Files.createTempDirectory("zuzu-store").toFile()
        file = File(dir, "ss_servers.bin")
        box = TestBox()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun savedServerPersistsAcrossInstances() = runBlocking {
        store().save(entry("a"))
        val reopened = store()
        assertEquals(entry("a"), reopened.load("a"))
        reopened.refresh()
        assertTrue(reopened.state.value.loaded)
        assertEquals(listOf("Home server"), reopened.state.value.servers.map { it.name })
    }

    @Test fun fileContainsNoPlaintextSecretsOrNames() = runBlocking {
        store().save(entry("a", profile(name = "VisibleName", host = "plain.example.net")))
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains(key))
        assertFalse(raw.contains("VisibleName"))
        assertFalse(raw.contains("plain.example.net"))
    }

    @Test fun observableStateCarriesPublicInfoOnly() = runBlocking {
        val s = store()
        s.save(entry("a"))
        val info = s.state.value.servers.single()
        assertEquals(ServerInfo("a", "Home server", "Germany", "DE", "Frankfurt", VpnProtocol.Shadowsocks), info)
        assertFalse(s.state.value.toString().contains(key))
        assertFalse(s.state.value.toString().contains("vpn.example.com"))
    }

    @Test fun editingReplacesInPlace() = runBlocking {
        val s = store()
        s.save(entry("a", profile(name = "Old")))
        s.save(entry("a", profile(name = "New").copy(udpEnabled = false)))
        assertEquals(1, s.state.value.servers.size)
        assertEquals("New", s.profileOf("a")?.name)
        assertEquals(false, s.profileOf("a")?.udpEnabled)
    }

    @Test fun addMissingNeverReplacesExistingEntries() = runBlocking {
        val s = store()
        s.save(entry("a", profile(name = "Mine")))
        val added = s.addMissing(listOf(entry("a", profile(name = "Seed")), entry("b", profile(name = "Other"))))
        assertEquals(listOf("b"), added)
        assertEquals("Mine", s.profileOf("a")?.name)
    }

    @Test fun deletingRemovesServerAndCredentialFile() = runBlocking {
        val s = store()
        s.save(entry("a"))
        s.save(entry("b"))
        s.delete("a")
        assertNull(s.load("a"))
        assertEquals(listOf("b"), s.state.value.servers.map { it.id })
        s.delete("b")
        assertTrue(s.state.value.servers.isEmpty())
        assertFalse("credential file removed when the last server is deleted", file.exists())
    }

    @Test fun corruptedStorageIsDiscardedSafely() = runBlocking {
        store().save(entry("a"))
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte() // break the GCM tag
        file.writeBytes(bytes)
        val s = store()
        s.refresh()
        assertTrue(s.state.value.loaded)
        assertTrue(s.state.value.servers.isEmpty())
        assertFalse(file.exists())
    }

    @Test fun garbageFileIsDiscardedSafely() = runBlocking {
        file.writeBytes(byteArrayOf(9, 9, 9))
        val s = store()
        s.refresh()
        assertTrue(s.state.value.servers.isEmpty())
    }

    @Test fun missingKeystoreKeyStartsEmpty() = runBlocking {
        store().save(entry("a"))
        val s = store(TestBox(key = null))
        s.refresh()
        assertTrue(s.state.value.servers.isEmpty())
    }

    @Test fun legacySingleProfileIsMigratedAsDevelopmentServer() = runBlocking {
        val legacy = File(dir, "ss_profile.bin")
        legacy.writeBytes(byteArrayOf(1) + box.seal("LEGACY".toByteArray()))
        val s = store(legacy = legacy, decoder = { bytes ->
            assertArrayEquals("LEGACY".toByteArray(), bytes)
            profile(name = "Migrated")
        })
        s.refresh()
        val migrated = s.state.value.servers.single()
        assertEquals("Migrated", migrated.name)
        assertTrue(migrated.isDevelopment)
        assertEquals(profile(name = "Migrated"), s.profileOf(migrated.id))
        assertFalse(legacy.exists())
    }

    @Test fun entriesAndToStringNeverContainTheKey() {
        assertFalse(entry("a").toString().contains(key))
        assertFalse(ConnectionConfig.Shadowsocks(profile()).toString().contains(key))
    }

    @Test fun chachaPasswordSurvivesPersistenceExactly() = runBlocking {
        // Placeholder password with spaces, ':' and non-ASCII characters.
        val p = ShadowsocksProfile(
            name = "Classic",
            host = "203.0.113.9",
            port = 443,
            password = " pässwörd:with spaces ",
            method = ShadowsocksProfile.METHOD_CHACHA20_IETF_POLY1305,
            udpEnabled = false,
        )
        store().save(entry("c", p))
        assertEquals(p, store().profileOf("c"))
    }

    @Test fun codecRoundTripAndRejectsTrailingData() {
        val wg = ServerEntry(
            ServerInfo("w", "WG", "Japan", "JP", null, VpnProtocol.WireGuard, isPremium = true, isRecommended = true),
            ConnectionConfig.WireGuard("[Interface]\nPrivateKey = placeholder"),
        )
        val list = listOf(entry("x"), entry("y", countryCode = null), wg)
        assertEquals(list, ServerListCodec.decode(ServerListCodec.encode(list)))
        try {
            ServerListCodec.decode(ServerListCodec.encode(list) + byteArrayOf(0))
            throw AssertionError("expected failure")
        } catch (_: IllegalArgumentException) {
        }
    }
}
