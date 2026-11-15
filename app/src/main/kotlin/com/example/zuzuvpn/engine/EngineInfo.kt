package com.example.zuzuvpn.engine

import android.content.Context
import com.wireguard.android.backend.GoBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only probe of the bundled WireGuard engine (:tunnel).
 *
 * Constructing [GoBackend] loads libwg-go.so through JNI, so a successful version string proves
 * the native library was packaged and the JNI-bound class names resolve. It does NOT start a
 * tunnel, request VPN permission or touch the network.
 */
object EngineInfo {
    suspend fun wireguardGoVersion(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching { GoBackend(context.applicationContext).version }
    }
}
