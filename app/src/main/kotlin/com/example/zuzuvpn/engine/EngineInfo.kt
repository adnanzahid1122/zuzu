package com.example.zuzuvpn.engine

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.zuzuvpn.shadowsocks.HevTunnel
import com.zuzuvpn.shadowsocks.SsLocal
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

    /** Loads libzuzuss.so (shadowsocks-rust) and returns its version. Starts nothing. */
    suspend fun shadowsocksVersion(): Result<String> = withContext(Dispatchers.IO) {
        // UnsatisfiedLinkError is an Error, not an Exception, so catch Throwable explicitly.
        try {
            Result.success(SsLocal.nativeVersion())
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Loads libhev-socks5-tunnel.so and returns its pinned version. Starts nothing. */
    suspend fun hevVersion(): Result<String> = withContext(Dispatchers.IO) {
        try {
            HevTunnel.TProxyIsRunning()
            Result.success(HevTunnel.VERSION)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
