package com.example.zuzuvpn.data

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

    private fun store(b: SecretBox = box, legacy: File? = null, decoder: ((ByteArray) -> ShadowsocksProfile)? = null) =
        ShadowsocksProfileStore(file, b, legacy, decoder, newId = { "id-${++ids}" })

    @Before fun setUp() {
        dir = Files.createTempDirectory("zuzu-store").toFile()
        file = File(dir, "ss_servers.bin")
        box = TestBox()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun savedServerPersistsAcrossInstances() = runBlocking {
        val id = store().save(null, profile())
        val reopened = store()
        assertEquals(profile(), reopened.load(id))
        reopened.refresh()
        assertEquals(listOf("Home server"), reopened.state.value.servers.map { it.name })
        assertEquals(id, reopened.state.value.selectedId)
    }

    @Test fun fileContainsNoPlaintextSecretsOrNames() = runBlocking {
        store().save(null, profile(name = "VisibleName", host = "plain.example.net"))
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains(key))
        assertFalse(raw.contains("VisibleName"))
        assertFalse(raw.contains("plain.example.net"))
    }

    @Test fun firstServerIsSelectedAndSelectionPersists() = runBlocking {
        val s = store()
        val a = s.save(null, profile(name = "A"))
        val b = s.save(null, profile(name = "B"), select = false)
        assertEquals(a, s.state.value.selectedId)
        s.select(b)
        val reopened = store().also { it.refresh() }
        assertEquals(b, reopened.state.value.selectedId)
        assertEquals("B", reopened.state.value.selected?.name)
    }

    @Test fun editingReplacesInPlace() = runBlocking {
        val s = store()
        val id = s.save(null, profile(name = "Old"))
        s.save(id, profile(name = "New").copy(udpEnabled = false))
        assertEquals(1, s.state.value.servers.size)
        assertEquals("New", s.load(id)?.name)
        assertEquals(false, s.load(id)?.udpEnabled)
    }

    @Test fun deletingRemovesServerAndMovesSelection() = runBlocking {
        val s = store()
        val a = s.save(null, profile(name = "A"))
        val b = s.save(null, profile(name = "B"), select = false)
        s.delete(a)
        assertNull(s.load(a))
        assertEquals(b, s.state.value.selectedId)
        s.delete(b)
        assertTrue(s.state.value.servers.isEmpty())
        assertNull(s.state.value.selectedId)
        assertFalse("credential file removed when the last server is deleted", file.exists())
    }

    @Test fun corruptedStorageIsDiscardedSafely() = runBlocking {
        store().save(null, profile())
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
        store().save(null, profile())
        val s = store(TestBox(key = null))
        s.refresh()
        assertTrue(s.state.value.servers.isEmpty())
        assertNull(s.state.value.selected)
    }

    @Test fun legacySingleProfileIsMigrated() = runBlocking {
        val legacy = File(dir, "ss_profile.bin")
        legacy.writeBytes(byteArrayOf(1) + box.seal("LEGACY".toByteArray()))
        val s = store(legacy = legacy, decoder = { bytes ->
            assertArrayEquals("LEGACY".toByteArray(), bytes)
            profile(name = "Migrated")
        })
        s.refresh()
        assertEquals(listOf("Migrated"), s.state.value.servers.map { it.name })
        assertEquals(s.state.value.servers.single().id, s.state.value.selectedId)
        assertFalse(legacy.exists())
    }

    @Test fun summariesAndToStringNeverContainTheKey() = runBlocking {
        val s = store()
        val id = s.save(null, profile())
        assertFalse(s.state.value.toString().contains(key))
        assertFalse(SavedServer(id, profile()).toString().contains(key))
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
        val id = store().save(null, p)
        assertEquals(p, store().load(id))
    }

    @Test fun codecRoundTripAndRejectsTrailingData() {
        val list = ServerList(listOf(SavedServer("x", profile())), "x")
        assertEquals(list, ServerListCodec.decode(ServerListCodec.encode(list)))
        try {
            ServerListCodec.decode(ServerListCodec.encode(list) + byteArrayOf(0))
            throw AssertionError("expected failure")
        } catch (_: IllegalArgumentException) {
        }
    }
}
