@file:Suppress("FunctionName")

package com.zuzuvpn.shadowsocks

/**
 * JNI facade for libhev-socks5-tunnel.so (hev-socks5-tunnel 2.19.3).
 *
 * The method names are fixed by hev's JNI_OnLoad, which registers them on this class
 * (built with PKGNAME=com/zuzuvpn/shadowsocks, CLSNAME=HevTunnel). Do not rename.
 */
object HevTunnel {
    const val VERSION = "hev-socks5-tunnel 2.19.3"

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /** Starts forwarding on a worker thread. The caller keeps ownership of [tunFd]. */
    @JvmStatic
    external fun TProxyStartService(configPath: String, tunFd: Int): Boolean

    /** Stops forwarding and joins the worker thread. */
    @JvmStatic
    external fun TProxyStopService(): Boolean

    @JvmStatic
    external fun TProxyIsRunning(): Boolean

    /** [txPackets, txBytes, rxPackets, rxBytes] since start. */
    @JvmStatic
    external fun TProxyGetStats(): LongArray
}
