package com.zuzuvpn.shadowsocks

/**
 * JNI facade for libzuzuss.so: the shadowsocks-rust local client (SOCKS5 inbound on loopback).
 *
 * Only one instance can run per process. [nativeRun] blocks the calling thread.
 */
object SsLocal {
    init {
        System.loadLibrary("zuzuss")
    }

    /**
     * Runs the client until [nativeStop] is called.
     *
     * @param configJson shadowsocks-rust local configuration (see [SsConfigBuilder]); contains the key.
     * @param protectPath filesystem path of the Unix socket served by [ProtectServer].
     * @param socksUser username the loopback SOCKS5 listener requires (random per session).
     * @param socksPassword password the loopback SOCKS5 listener requires (random per session).
     * @return null after a requested stop, otherwise a short error description without secrets.
     */
    @JvmStatic
    external fun nativeRun(configJson: String, protectPath: String, socksUser: String, socksPassword: String): String?

    /** Makes a running [nativeRun] return. Safe to call when nothing is running. */
    @JvmStatic
    external fun nativeStop()

    @JvmStatic
    external fun nativeVersion(): String
}
