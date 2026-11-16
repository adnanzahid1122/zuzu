package com.example.zuzuvpn.delivery

import android.util.Log
import com.example.zuzuvpn.data.servers.ConfigResolution
import com.example.zuzuvpn.data.servers.ConnectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * Obtains a server's configuration through encrypted delivery (POST /app/servers/{id}/config) and
 * decrypts it with this installation's Keystore keys. The decrypted configuration exists only in
 * memory and is handed to the existing connection path as a [ConnectionConfig].
 *
 * A fresh envelope is requested every time (so revocation and subscription changes apply at once).
 * Only if the API cannot be reached is the cached envelope (still encrypted; valid until its exp)
 * used, e.g. for an always-on restart before the network allows a request.
 */
class SecureConfigProvider(
    private val account: AccountManager,
    private val api: AppApi,
    private val cache: EnvelopeCache,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: SecureRandom = SecureRandom(),
) {
    /** Runs off the main thread (network, Keystore decryption, cache file I/O). */
    suspend fun resolve(serverId: String, serverName: String): ConfigResolution = withContext(Dispatchers.IO) { resolveOnIo(serverId, serverName) }

    private suspend fun resolveOnIo(serverId: String, serverName: String): ConfigResolution {
        // An always-on start at boot (or a tap right after launch) may come before the stored session is restored.
        withTimeoutOrNull(RESTORE_WAIT_MS) { account.state.first { it !is AccountState.Loading } }
        if (account.state.value !is AccountState.SignedIn) return ConfigResolution.SignInRequired
        val nonce = Base64Url.encode(ByteArray(18).also(random::nextBytes))
        return try {
            val (envelope, keys, deviceId) = account.authorized { token, keys, deviceId ->
                Triple(api.requestConfig(token, serverId, keys.encryptionKid, nonce, listOf("shadowsocks")), keys, deviceId)
            }
            val payload = EnvelopeOpener.open(envelope, keys, PendingRequest(serverId, deviceId, nonce), clock())
            cache.put(serverId, envelope, nonce)
            ConfigResolution.Ready(toConfig(payload, serverName))
        } catch (e: ApiFailure) {
            when (e.code) {
                "SUBSCRIPTION_REQUIRED" -> clearAnd(serverId, ConfigResolution.SubscriptionRequired)
                "ENTITLEMENT_REQUIRED" -> clearAnd(serverId, ConfigResolution.PremiumRequired)
                "SERVER_NOT_AVAILABLE", "PROTOCOL_NOT_SUPPORTED", "PEER_NOT_PROVISIONED", "PEER_NOT_INSTALLED" -> clearAnd(serverId, ConfigResolution.Unavailable)
                "TOO_MANY_REQUESTS" -> ConfigResolution.RateLimited
                "NOT_SIGNED_IN" -> ConfigResolution.SignInRequired
                "DEVICE_REVOKED", "ACCOUNT_DISABLED", "SESSION_EXPIRED", "REFRESH_TOKEN_REUSED", "DEVICE_KEY_INVALID" -> ConfigResolution.SignedOut
                "SIGNATURE_EXPIRED" -> ConfigResolution.SecurityError // device clock outside ±120 s
                else -> {
                    Log.w(TAG, "Configuration refused (${e.status} ${e.code})")
                    ConfigResolution.Unavailable
                }
            }
        } catch (e: NetworkFailure) {
            fromCache(serverId, serverName) ?: ConfigResolution.NetworkError
        } catch (e: EnvelopeException) {
            Log.w(TAG, "Envelope rejected (${e.failure})")
            ConfigResolution.SecurityError
        } catch (e: java.security.GeneralSecurityException) {
            Log.w(TAG, "Keystore operation failed (${e.javaClass.simpleName})")
            ConfigResolution.SecurityError
        }
    }

    private fun fromCache(serverId: String, serverName: String): ConfigResolution? {
        val keys = account.currentKeys ?: return null
        val deviceId = account.deviceId ?: return null
        val (envelope, nonce) = cache.get(serverId) ?: return null
        return try {
            ConfigResolution.Ready(toConfig(EnvelopeOpener.open(envelope, keys, PendingRequest(serverId, deviceId, nonce), clock()), serverName))
        } catch (e: Exception) {
            cache.remove(serverId) // expired or unusable
            null
        }
    }

    private fun clearAnd(serverId: String, result: ConfigResolution): ConfigResolution {
        cache.remove(serverId)
        return result
    }

    private fun toConfig(payload: ConfigPayload, serverName: String): ConnectionConfig = when (payload) {
        is ConfigPayload.Shadowsocks -> ConnectionConfig.Shadowsocks(payload.toProfile(serverName))
    }

    private companion object {
        const val TAG = "ZuzuConfig"
        const val RESTORE_WAIT_MS = 5_000L
    }
}
