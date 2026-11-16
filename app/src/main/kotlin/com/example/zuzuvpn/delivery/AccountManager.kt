package com.example.zuzuvpn.delivery

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why the customer is signed out (shown once by the UI). */
enum class SignOutReason { SignedOut, SessionExpired, DeviceRevoked, AccountDisabled }

sealed interface AccountState {
    data object Loading : AccountState
    data class SignedOut(val reason: SignOutReason? = null) : AccountState
    data class SignedIn(val email: String, val deviceId: String) : AccountState {
        override fun toString() = "SignedIn(email=<redacted>, deviceId=$deviceId)"
    }
}

/** Result of an interactive sign-in. */
sealed interface SignInOutcome {
    data object Success : SignInOutcome

    /** Device limit reached: the user may replace one of [devices] (needs the same credentials again). */
    data class DeviceLimit(val limit: Int, val devices: List<RegisteredDevice>) : SignInOutcome

    /** [code] is the backend's error code (e.g. INVALID_CREDENTIALS) or NETWORK / KEYSTORE. */
    data class Failed(val code: String) : SignInOutcome
}

/** Information about this installation sent at enrollment. */
data class DeviceInfo(val label: String, val model: String?, val appVersion: String?)

/**
 * Customer session for this installation: sign-in → automatic device enrollment → device-bound
 * tokens. The access token lives in memory; the refresh token in [storage] (Keystore-sealed);
 * the device keys in [keyStore] (Android Keystore).
 *
 *  * Refresh is signed with the device key and single-flight (one refresh for concurrent callers).
 *  * DEVICE_REVOKED / ACCOUNT_DISABLED: keys, session and cached envelopes are deleted (the identity is dead).
 *  * SESSION_EXPIRED / REFRESH_TOKEN_REUSED: tokens are dropped but keys kept; the next sign-in re-attaches.
 */
class AccountManager(
    private val api: AppApi,
    private val keyStore: DeviceKeyStore,
    private val storage: SessionStorage,
    private val cache: EnvelopeCache,
    private val deviceInfo: DeviceInfo,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutable = MutableStateFlow<AccountState>(AccountState.Loading)
    val state: StateFlow<AccountState> = mutable.asStateFlow()

    private val mutex = Mutex()
    private var session: StoredSession? = null
    private var keys: DeviceKeys? = null
    private var accessToken: String? = null
    private var accessExpiresAt = 0L

    /** Credentials of a sign-in that hit the device limit; memory only, until replaced or abandoned. */
    private var pendingCredentials: Pair<String, String>? = null

    /**
     * Restores a stored session (no network). A Keystore error leaves keys and session untouched
     * (signed out for now; the next start retries) instead of destroying the device identity.
     */
    suspend fun restore(): Unit = withContext(Dispatchers.IO) { restoreLocked() }

    private suspend fun restoreLocked() = mutex.withLock {
        val stored = storage.load()
        val loaded = try {
            stored?.let { keyStore.load() }
        } catch (e: java.security.GeneralSecurityException) {
            Log.w(TAG, "Keystore unavailable at restore (${e.javaClass.simpleName})")
            mutable.value = AccountState.SignedOut()
            return@withLock
        }
        if (stored != null && loaded != null) {
            session = stored
            keys = loaded
            mutable.value = AccountState.SignedIn(stored.email, stored.deviceId)
        } else {
            if (stored != null) storage.clear() // keys gone (e.g. data restored elsewhere): the session is unusable
            mutable.value = AccountState.SignedOut()
        }
    }

    val deviceId: String? get() = session?.deviceId
    val currentKeys: DeviceKeys? get() = keys

    // Sign-in work (Keystore key generation: RSA-3072 can take seconds on older phones) never runs on the main thread.
    suspend fun signIn(email: String, password: String, register: Boolean): SignInOutcome = withContext(Dispatchers.IO) {
        authenticate(email to password, signIn = { if (register) api.register(email, password) else api.login(email, password) }, retry = { api.login(email, password) })
    }

    suspend fun signInWithGoogle(idToken: String): SignInOutcome = withContext(Dispatchers.IO) {
        authenticate(null, signIn = { api.google(idToken) }, retry = { api.google(idToken) })
    }

    /** After [SignInOutcome.DeviceLimit]: signs in again with the remembered credentials and replaces [deviceId]. */
    suspend fun replaceDevice(deviceId: String): SignInOutcome {
        val credentials = pendingCredentials ?: return SignInOutcome.Failed("SIGN_IN_AGAIN")
        val login: suspend () -> SignInResult = { api.login(credentials.first, credentials.second) }
        return withContext(Dispatchers.IO) { authenticate(credentials, replaceDeviceId = deviceId, signIn = login, retry = login) }
    }

    fun abandonPendingSignIn() {
        pendingCredentials = null
    }

    /** [retry] signs in again after this installation's old keys turned out to be unusable (never a second registration). */
    private suspend fun authenticate(
        credentials: Pair<String, String>?,
        replaceDeviceId: String? = null,
        signIn: suspend () -> SignInResult,
        retry: suspend () -> SignInResult,
    ): SignInOutcome = mutex.withLock {
        try {
            val result = signIn()
            val deviceKeys = keyStore.load() ?: keyStore.create()
            val enrolled = try {
                api.enroll(result, deviceKeys, deviceInfo.label, deviceInfo.model, deviceInfo.appVersion, replaceDeviceId)
            } catch (e: ApiFailure) {
                if (e.code == "DEVICE_REVOKED" || e.code == "DEVICE_KEY_IN_USE") {
                    // These keys belong to a revoked (or foreign) device: start a fresh identity and retry once.
                    keyStore.delete()
                    cache.clear()
                    val fresh = keyStore.create()
                    val again = retry()
                    return@withLock finish(again, api.enroll(again, fresh, deviceInfo.label, deviceInfo.model, deviceInfo.appVersion, replaceDeviceId), fresh)
                }
                throw e
            }
            finish(result, enrolled, deviceKeys)
        } catch (e: ApiFailure) {
            if (e.code == "DEVICE_LIMIT_REACHED") {
                pendingCredentials = credentials
                SignInOutcome.DeviceLimit(e.data?.optInt("limit") ?: 0, AppApi.devicesOf(e))
            } else {
                SignInOutcome.Failed(e.code)
            }
        } catch (e: NetworkFailure) {
            SignInOutcome.Failed("NETWORK")
        } catch (e: java.security.GeneralSecurityException) {
            Log.w(TAG, "Keystore failure (${e.javaClass.simpleName})")
            SignInOutcome.Failed("KEYSTORE")
        }
    }

    private fun finish(signIn: SignInResult, enrolled: EnrollResult, deviceKeys: DeviceKeys): SignInOutcome {
        // The server must echo exactly the keys we registered.
        if (enrolled.signingKid != deviceKeys.signingKid || enrolled.encryptionKid != deviceKeys.encryptionKid || enrolled.suite != deviceKeys.suite.id) {
            return SignInOutcome.Failed("INVALID_RESPONSE")
        }
        val stored = StoredSession(enrolled.deviceId, signIn.customerId, signIn.email, enrolled.tokens.refreshToken)
        storage.save(stored)
        session = stored
        keys = deviceKeys
        setAccess(enrolled.tokens)
        pendingCredentials = null
        mutable.value = AccountState.SignedIn(stored.email, stored.deviceId)
        return SignInOutcome.Success
    }

    private fun setAccess(tokens: DeviceTokens) {
        accessToken = tokens.accessToken
        accessExpiresAt = clock() + tokens.expiresIn
    }

    /**
     * Runs [call] with a valid access token: refreshes first when the token is missing or about to
     * expire, and once more after an UNAUTHORIZED answer. Terminal errors end the session.
     */
    suspend fun <T> authorized(call: suspend (accessToken: String, keys: DeviceKeys, deviceId: String) -> T): T {
        val (token, k, id) = currentAccess(forceRefresh = false)
        return try {
            call(token, k, id)
        } catch (e: ApiFailure) {
            if (e.status == 401 && e.code == "UNAUTHORIZED") {
                val (fresh, k2, id2) = currentAccess(forceRefresh = true)
                try {
                    call(fresh, k2, id2)
                } catch (e2: ApiFailure) {
                    handleTerminal(e2)
                    throw e2
                }
            } else {
                handleTerminal(e)
                throw e
            }
        }
    }

    /** Rotates the session now (signed refresh), e.g. when the app returns to the foreground. */
    suspend fun refreshNow() {
        currentAccess(forceRefresh = true)
    }

    private suspend fun currentAccess(forceRefresh: Boolean): Triple<String, DeviceKeys, String> = mutex.withLock {
        val s = session ?: throw ApiFailure(401, "NOT_SIGNED_IN", null)
        val k = keys ?: throw ApiFailure(401, "NOT_SIGNED_IN", null)
        val token = accessToken
        if (!forceRefresh && token != null && clock() < accessExpiresAt - 60) return@withLock Triple(token, k, s.deviceId)
        val tokens = try {
            api.refresh(s.refreshToken, RequestSigner(k, clock))
        } catch (e: ApiFailure) {
            endSessionFor(e)
            throw e
        }
        val updated = s.copy(refreshToken = tokens.refreshToken)
        storage.save(updated)
        session = updated
        setAccess(tokens)
        Triple(tokens.accessToken, k, s.deviceId)
    }

    private suspend fun handleTerminal(e: ApiFailure) {
        if (e.code in DEAD_IDENTITY || e.code in DEAD_SESSION) mutex.withLock { endSessionFor(e) }
    }

    /** Call with [mutex] held. */
    private fun endSessionFor(e: ApiFailure) {
        when (e.code) {
            in DEAD_IDENTITY -> wipe(if (e.code == "ACCOUNT_DISABLED") SignOutReason.AccountDisabled else SignOutReason.DeviceRevoked, deleteKeys = true)
            // Keys no longer match the server's record: start a fresh identity at the next sign-in.
            "DEVICE_KEY_INVALID" -> wipe(SignOutReason.SessionExpired, deleteKeys = true)
            in DEAD_SESSION -> wipe(SignOutReason.SessionExpired, deleteKeys = false)
        }
    }

    /** Signs out: tells the server (signed; best effort), then deletes keys, session and cache. */
    suspend fun signOut(): Unit = withContext(Dispatchers.IO) {
        runCatching { authorized { token, k, _ -> api.logout(token, RequestSigner(k, clock)) } }
        mutex.withLock { wipe(SignOutReason.SignedOut, deleteKeys = true) }
    }

    private fun wipe(reason: SignOutReason, deleteKeys: Boolean) {
        storage.clear()
        cache.clear()
        if (deleteKeys) keyStore.delete()
        session = null
        keys = null
        accessToken = null
        accessExpiresAt = 0
        pendingCredentials = null
        mutable.value = AccountState.SignedOut(reason)
    }

    private companion object {
        const val TAG = "ZuzuAccount"
        val DEAD_IDENTITY = setOf("DEVICE_REVOKED", "ACCOUNT_DISABLED")
        val DEAD_SESSION = setOf("SESSION_EXPIRED", "REFRESH_TOKEN_REUSED", "DEVICE_KEY_INVALID")
    }
}
