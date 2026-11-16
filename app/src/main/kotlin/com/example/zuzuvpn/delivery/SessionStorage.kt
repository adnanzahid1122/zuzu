package com.example.zuzuvpn.delivery

import android.util.Log
import com.example.zuzuvpn.data.SecretBox
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** What survives an app restart. The access token is never stored (memory only). */
data class StoredSession(val deviceId: String, val customerId: String, val email: String, val refreshToken: String) {
    override fun toString() = "StoredSession(deviceId=$deviceId, email=<redacted>, refreshToken=<redacted>)"
}

interface SessionStorage {
    fun load(): StoredSession?
    fun save(session: StoredSession)
    fun clear()
}

/** The session file sealed with a Keystore AES-GCM key ([SecretBox]), in the no-backup directory. */
class FileSessionStorage(private val file: File, private val box: SecretBox) : SessionStorage {
    override fun load(): StoredSession? = try {
        if (!file.exists()) {
            null
        } else {
            val plain = box.open(file.readBytes())
            try {
                val o = JSONObject(String(plain, Charsets.UTF_8))
                StoredSession(o.getString("deviceId"), o.getString("customerId"), o.getString("email"), o.getString("refreshToken"))
            } finally {
                plain.fill(0)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Session unreadable; discarding (${e.javaClass.simpleName})")
        clear()
        null
    }

    override fun save(session: StoredSession) {
        val plain = JSONObject()
            .put("deviceId", session.deviceId).put("customerId", session.customerId)
            .put("email", session.email).put("refreshToken", session.refreshToken)
            .toString().toByteArray(Charsets.UTF_8)
        try {
            atomicWrite(file, box.seal(plain))
        } finally {
            plain.fill(0)
        }
    }

    override fun clear() {
        file.delete()
    }

    private companion object {
        const val TAG = "ZuzuSession"
    }
}

/**
 * Encrypted configuration cache (config-delivery-v1.md §4): the envelope **exactly as received** (still
 * encrypted to the device key) plus the nonce it was requested with, sealed once more with a Keystore
 * key. Plaintext credentials are never written. Used only when a fresh request cannot reach the API.
 */
interface EnvelopeCache {
    fun put(serverId: String, envelope: ConfigEnvelope, nonce: String)
    fun get(serverId: String): Pair<ConfigEnvelope, String>?
    fun remove(serverId: String)
    fun clear()
}

class FileEnvelopeCache(private val dir: File, private val box: SecretBox) : EnvelopeCache {
    private fun fileFor(serverId: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(serverId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.bin")
    }

    override fun put(serverId: String, envelope: ConfigEnvelope, nonce: String) {
        val plain = JSONObject().put("envelope", JSONObject(envelope.toJson())).put("nonce", nonce).toString().toByteArray(Charsets.UTF_8)
        dir.mkdirs()
        atomicWrite(fileFor(serverId), box.seal(plain))
    }

    override fun get(serverId: String): Pair<ConfigEnvelope, String>? = try {
        val f = fileFor(serverId)
        if (!f.exists()) null else JSONObject(String(box.open(f.readBytes()), Charsets.UTF_8)).let { ConfigEnvelope.parse(it.getJSONObject("envelope").toString()) to it.getString("nonce") }
    } catch (e: Exception) {
        remove(serverId)
        null
    }

    override fun remove(serverId: String) {
        fileFor(serverId).delete()
    }

    override fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

internal fun atomicWrite(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.outputStream().use { out ->
        out.write(bytes)
        out.fd.sync()
    }
    if (!tmp.renameTo(file)) {
        tmp.delete()
        error("could not replace ${file.name}")
    }
}
