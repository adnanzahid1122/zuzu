package com.example.zuzuvpn.data

import android.util.Log
import com.example.zuzuvpn.data.servers.ServerEntry
import com.example.zuzuvpn.data.servers.ServerInfo
import com.zuzuvpn.shadowsocks.ShadowsocksProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Non-secret view of the store: public server info only. */
data class ServerStoreState(
    val loaded: Boolean = false,
    val servers: List<ServerInfo> = emptyList(),
)

/**
 * Encrypted on-device server store: public [ServerInfo] plus private connection configuration for
 * each server. Used by the debug local data source today; a future backend data source may reuse
 * it as a cache if the API contract allows storing configurations.
 *
 * Security:
 *  * the whole list (including passwords/keys) is encrypted as one blob by [SecretBox]
 *    ([KeystoreSecretBox]: AES-256-GCM, non-exportable Android Keystore key) and written atomically
 *    to the no-backup directory (the app also sets allowBackup=false);
 *  * nothing is kept in SharedPreferences; observers only see [ServerStoreState] (no secrets);
 *  * if the blob cannot be decrypted (corrupt file, or the Keystore key is gone e.g. after a
 *    restore to another device) it is discarded and the store starts empty.
 *
 * @param legacyFile file written by the first single-profile version, migrated on first load.
 * @param legacyDecoder parses that file's decrypted JSON payload.
 */
class ShadowsocksProfileStore(
    private val file: File,
    private val box: SecretBox,
    private val legacyFile: File? = null,
    private val legacyDecoder: ((ByteArray) -> ShadowsocksProfile)? = null,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ServerStoreState())
    val state: StateFlow<ServerStoreState> = mutableState.asStateFlow()

    fun newId(): String = idGenerator()

    /** Loads (decrypting) and publishes the non-secret [state]. */
    suspend fun refresh() {
        mutex.withLock { publish(read()) }
    }

    /** All entries including private configuration. For data sources only, never for the UI. */
    suspend fun entries(): List<ServerEntry> = mutex.withLock { read().also(::publish) }

    /** The entry for [id] (with its private configuration), or null. */
    suspend fun load(id: String): ServerEntry? = entries().firstOrNull { it.info.id == id }

    /** Adds the entry, or replaces the one with the same id. */
    suspend fun save(entry: ServerEntry) = mutex.withLock {
        val current = read()
        val servers = if (current.any { it.info.id == entry.info.id }) {
            current.map { if (it.info.id == entry.info.id) entry else it }
        } else {
            current + entry
        }
        write(servers)
    }

    /** Adds every entry whose id is not stored yet; never replaces existing ones. Returns the added ids. */
    suspend fun addMissing(entries: List<ServerEntry>): List<String> = mutex.withLock {
        val current = read()
        val existing = current.map { it.info.id }.toSet()
        val added = entries.filter { it.info.id !in existing }
        if (added.isNotEmpty()) write(current + added)
        added.map { it.info.id }
    }

    /** Removes a server and its credentials. */
    suspend fun delete(id: String) = mutex.withLock {
        write(read().filterNot { it.info.id == id })
    }

    // ---- internals (call with mutex held) ----

    private suspend fun read(): List<ServerEntry> = withContext(Dispatchers.IO) {
        migrateLegacy()
        if (!file.exists()) return@withContext emptyList()
        try {
            val bytes = file.readBytes()
            require(bytes.isNotEmpty() && bytes[0] == FORMAT_VERSION) { "unknown format" }
            val plain = box.open(bytes.copyOfRange(1, bytes.size))
            try {
                ServerListCodec.decode(plain)
            } finally {
                plain.fill(0)
            }
        } catch (e: Exception) {
            // Never include e.message: it could describe the data. The class name is enough.
            Log.w(TAG, "Saved servers could not be read; discarding (${e.javaClass.simpleName})")
            file.delete()
            emptyList()
        }
    }

    private suspend fun write(servers: List<ServerEntry>) = withContext(Dispatchers.IO) {
        if (servers.isEmpty()) {
            file.delete()
        } else {
            writeSealed(servers)
        }
        publish(servers)
    }

    private fun writeSealed(servers: List<ServerEntry>) {
        val plain = ServerListCodec.encode(servers)
        val sealed = try {
            byteArrayOf(FORMAT_VERSION) + box.seal(plain)
        } finally {
            plain.fill(0)
        }
        writeAtomically(sealed)
    }

    private fun writeAtomically(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("could not replace server store")
        }
    }

    private fun migrateLegacy() {
        val legacy = legacyFile ?: return
        val decoder = legacyDecoder ?: return
        if (!legacy.exists()) return
        try {
            if (!file.exists()) {
                val bytes = legacy.readBytes()
                require(bytes.isNotEmpty() && bytes[0] == LEGACY_FORMAT_VERSION) { "unknown legacy format" }
                val plain = box.open(bytes.copyOfRange(1, bytes.size))
                val profile = try {
                    decoder(plain)
                } finally {
                    plain.fill(0)
                }
                writeSealed(listOf(ServerListCodec.developmentEntry(newId(), profile)))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy server could not be migrated; discarding (${e.javaClass.simpleName})")
        } finally {
            legacy.delete()
        }
    }

    private fun publish(servers: List<ServerEntry>) {
        mutableState.value = ServerStoreState(loaded = true, servers = servers.map { it.info })
    }

    private companion object {
        const val TAG = "ZuzuServers"
        const val FORMAT_VERSION: Byte = 2
        const val LEGACY_FORMAT_VERSION: Byte = 1
    }
}
