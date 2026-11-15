package com.example.zuzuvpn.dev

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.example.zuzuvpn.data.ShadowsocksProfileStore
import com.example.zuzuvpn.data.servers.ConnectionConfig
import com.example.zuzuvpn.data.servers.CountryCodes
import com.example.zuzuvpn.data.servers.ServerCatalog
import com.example.zuzuvpn.data.servers.ServerEntry
import com.example.zuzuvpn.data.servers.ServerInfo
import com.example.zuzuvpn.data.servers.ServerSelection
import com.example.zuzuvpn.vpn.VpnProtocol
import com.zuzuvpn.shadowsocks.ShadowsocksProfile
import com.zuzuvpn.shadowsocks.ShadowsocksProfileValidator
import com.zuzuvpn.shadowsocks.ShadowsocksUri
import com.zuzuvpn.shadowsocks.ShadowsocksUriException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Debug builds only (this source set is not compiled into release builds).
 *
 * Build time: app/build.gradle.kts reads the untracked zuzu-dev/test-servers.json, encrypts it with
 * a random per-build AES-256-GCM key and packages it as a debug-only asset; the key goes into the
 * generated debug class [DevSeedKey]. That keeps the password out of the APK as plaintext. It is
 * obfuscation, not protection: anyone holding the debug APK can recover the test credentials, so
 * debug APKs must be treated as containing them.
 *
 * Run time: [DevServerSeeder] copies the servers into the Keystore-encrypted store on first launch.
 */
internal object DevSeedAsset {
    const val ASSET_PATH = "zuzu-dev/test-servers.bin"
    private const val IV_LENGTH = 12

    /** Decrypted JSON, or null when this build has no test-server configuration. */
    fun read(context: Context): String? {
        if (DevSeedKey.KEY_BASE64.isEmpty()) return null
        val sealed = try {
            context.assets.open(ASSET_PATH).use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            return null
        }
        val key = SecretKeySpec(Base64.decode(DevSeedKey.KEY_BASE64, Base64.NO_WRAP), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, IV_LENGTH))
        val plain = cipher.doFinal(sealed, IV_LENGTH, sealed.size - IV_LENGTH)
        return try {
            String(plain, Charsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }
}

/** A problem in zuzu-dev/test-servers.json. Messages name fields, never values. */
class DevConfigException(message: String) : Exception(message)

/**
 * Parses the local test-server configuration into normalised [ServerEntry] values.
 *
 * ```
 * { "servers": [ {
 *     "id": "test-fr-1", "name": "France", "country": "France", "countryCode": "FR", "city": null,
 *     "protocol": "shadowsocks", "recommended": true, "premium": false,
 *     "shadowsocks": { "server": "…", "server_port": 443, "local_port": 1080,
 *                      "password": "…", "method": "chacha20-ietf-poly1305", "udp": false }
 *     // or instead of "shadowsocks":  "uri": "ss://…"
 * } ] }
 * ```
 * `local_port` is accepted for compatibility with sslocal configs but ignored: the engine always
 * uses a random loopback port with per-session SOCKS credentials. Every server is validated with
 * the same rules as the engine ([ShadowsocksProfileValidator]).
 */
object DevServerConfig {
    const val PLACEHOLDER = "PASTE_TEST_SERVER_PASSWORD_HERE"

    fun parse(json: String): List<ServerEntry> {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            throw DevConfigException("not valid JSON") // JSONException messages quote the input
        }
        val array = root.optJSONArray("servers") ?: throw DevConfigException("missing \"servers\" array")
        val entries = (0 until array.length()).map { index -> parseServer(array, index) }
        val duplicate = entries.groupBy { it.info.id }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) throw DevConfigException("duplicate id \"${duplicate.key}\"")
        return entries
    }

    private fun parseServer(array: JSONArray, index: Int): ServerEntry {
        val where = "servers[$index]"
        val o = array.optJSONObject(index) ?: throw DevConfigException("$where is not an object")
        fun text(name: String): String = o.optString(name, "").trim().ifEmpty { throw DevConfigException("$where.$name is required") }
        fun optionalText(name: String): String? = if (o.isNull(name)) null else o.optString(name, "").trim().ifEmpty { null }

        val id = text("id")
        if (!id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) throw DevConfigException("$where.id has invalid characters")
        val name = text("name")
        val country = text("country")
        val countryCode = CountryCodes.normalize(text("countryCode")) ?: throw DevConfigException("$where.countryCode is not ISO 3166-1 alpha-2")
        val protocol = when (text("protocol").lowercase()) {
            "shadowsocks" -> VpnProtocol.Shadowsocks
            else -> throw DevConfigException("$where.protocol must be \"shadowsocks\"")
        }

        val profile = when {
            o.has("uri") -> {
                val uri = text("uri")
                if (uri.contains(PLACEHOLDER)) throw DevConfigException("$where.uri is still the placeholder")
                try {
                    ShadowsocksUri.parse(uri).copy(name = name, udpEnabled = o.optBoolean("udp", false))
                } catch (e: ShadowsocksUriException) {
                    throw DevConfigException("$where.uri is invalid (${e.reason})")
                }
            }
            o.has("shadowsocks") -> {
                val ss = o.optJSONObject("shadowsocks") ?: throw DevConfigException("$where.shadowsocks is not an object")
                val password = ss.optString("password", "")
                if (password.isEmpty()) throw DevConfigException("$where.shadowsocks.password is required")
                if (password == PLACEHOLDER) throw DevConfigException("$where.shadowsocks.password is still the placeholder")
                val port = ss.optInt("server_port", -1)
                ShadowsocksProfile(
                    name = name,
                    host = ss.optString("server", "").trim().ifEmpty { throw DevConfigException("$where.shadowsocks.server is required") },
                    port = port,
                    password = password,
                    method = ss.optString("method", "").trim().lowercase()
                        .ifEmpty { throw DevConfigException("$where.shadowsocks.method is required") },
                    udpEnabled = ss.optBoolean("udp", false),
                )
            }
            else -> throw DevConfigException("$where needs \"shadowsocks\" or \"uri\"")
        }
        ShadowsocksProfileValidator.validate(profile).firstOrNull()?.let { throw DevConfigException("$where is invalid ($it)") }

        return ServerEntry(
            ServerInfo(
                id = id,
                name = name,
                country = country,
                countryCode = countryCode,
                city = optionalText("city"),
                protocol = protocol,
                isRecommended = o.optBoolean("recommended", false),
                isPremium = o.optBoolean("premium", false),
                isDevelopment = true,
            ),
            ConnectionConfig.Shadowsocks(profile),
        )
    }
}

/** Remembers which build-time ids were already seeded (ids only, not secret). */
interface SeedLedger {
    fun seededIds(): Set<String>

    fun record(ids: Collection<String>)
}

class PreferencesSeedLedger(context: Context) : SeedLedger {
    private val prefs = context.getSharedPreferences("zuzu_dev_seed", Context.MODE_PRIVATE)

    override fun seededIds(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    override fun record(ids: Collection<String>) {
        prefs.edit { putStringSet(KEY, seededIds() + ids) }
    }

    private companion object {
        const val KEY = "seeded_ids"
    }
}

/**
 * Seeds build-time test servers into the encrypted store:
 *  * each id is seeded at most once (the [SeedLedger]); a server the developer later edits or
 *    deletes is never overwritten or brought back. To re-seed changed values, change the id or
 *    clear the app's data;
 *  * an id that already exists in the store is never replaced;
 *  * if no valid server is selected afterwards, the best seeded (else stored) server is selected.
 */
class DevServerSeeder(
    private val store: ShadowsocksProfileStore,
    private val selection: ServerSelection,
    private val ledger: SeedLedger,
) {
    data class Result(val added: List<String>, val selected: String?)

    suspend fun seed(entries: List<ServerEntry>): Result {
        val already = ledger.seededIds()
        val fresh = entries.filter { it.info.id !in already }
        val added = store.addMissing(fresh)
        ledger.record(fresh.map { it.info.id })

        val stored = store.entries().map { it.info }
        val selectedId = selection.selectedId.value
        if (selectedId != null && stored.any { it.id == selectedId }) return Result(added, null)
        val seededIds = entries.map { it.info.id }.toSet()
        val choice = ServerCatalog.defaultChoice(stored.filter { it.id in seededIds }) ?: ServerCatalog.defaultChoice(stored)
        selection.select(choice?.id)
        return Result(added, choice?.id)
    }
}

/** Reads the build-time configuration (if any) and seeds it. Logs only counts and class names. */
internal suspend fun seedBuildTimeServers(context: Context, seeder: DevServerSeeder) {
    try {
        val json = DevSeedAsset.read(context) ?: return
        val result = seeder.seed(DevServerConfig.parse(json))
        Log.i(TAG, "test servers: ${result.added.size} added${if (result.selected != null) ", selection set" else ""}")
    } catch (e: DevConfigException) {
        Log.w(TAG, "test-server config rejected: ${e.message}")
    } catch (e: Exception) {
        Log.w(TAG, "test-server seeding failed (${e.javaClass.simpleName})")
    }
}

private const val TAG = "ZuzuDevSeed"
