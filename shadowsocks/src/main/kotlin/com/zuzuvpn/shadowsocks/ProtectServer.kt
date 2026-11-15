package com.zuzuvpn.shadowsocks

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Serves shadowsocks-rust's `outbound_vpn_protect_path` protocol so the client's sockets to the
 * Shadowsocks server bypass the VPN instead of looping back into it.
 *
 * Protocol (shadowsocks-rust 1.25.0, crates/shadowsocks/src/net/sys/unix/linux/mod.rs): the client
 * connects to the Unix socket, sends one byte with the socket file descriptor attached
 * (SCM_RIGHTS), and waits for one byte; 0xFF means failure, anything else success.
 *
 * The socket lives in the app's private no-backup directory, so other apps cannot reach it.
 */
class ProtectServer(
    private val socketFile: File,
    private val protect: (Int) -> Boolean,
) : AutoCloseable {
    @Volatile private var running = false
    private var bound: LocalSocket? = null
    private var server: LocalServerSocket? = null
    private var worker: Thread? = null

    val path: String get() = socketFile.absolutePath

    fun start() {
        check(!running) { "already started" }
        require(path.toByteArray().size < UNIX_PATH_MAX) { "socket path too long" }
        socketFile.delete()
        val socket = LocalSocket()
        socket.bind(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        bound = socket
        server = LocalServerSocket(socket.fileDescriptor)
        running = true
        worker = thread(name = "zuzu-protect", isDaemon = true) { serve() }
    }

    private fun serve() {
        val listener = server ?: return
        while (running) {
            val client = try {
                listener.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept failed: ${e.javaClass.simpleName}")
                break
            }
            if (!running) {
                client.close()
                break
            }
            client.use(::handle)
        }
    }

    private fun handle(client: LocalSocket) {
        var fds: Array<FileDescriptor>? = null
        try {
            // The native client gives up after 3 s; never let one stuck peer block every other request.
            client.soTimeout = REQUEST_TIMEOUT_MS
            if (client.inputStream.read() < 0) return
            fds = client.ancillaryFileDescriptors
            val fd = fds?.firstOrNull()
            val ok = fd != null && ParcelFileDescriptor.dup(fd).use { protect(it.fd) }
            client.outputStream.write(if (ok) 0 else 0xFF)
            if (!ok) Log.w(TAG, "protect() failed")
        } catch (e: IOException) {
            Log.w(TAG, "protect request failed: ${e.javaClass.simpleName}")
        } finally {
            fds?.forEach { runCatching { Os.close(it) } }
        }
    }

    override fun close() {
        if (!running) return
        running = false
        // LocalServerSocket.accept() is not interrupted by close() for a socket built from a file
        // descriptor, so wake it with a throwaway connection first.
        runCatching { LocalSocket().use { it.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM)) } }
        runCatching { server?.close() }
        runCatching { bound?.close() }
        worker?.join(1_000)
        server = null
        bound = null
        worker = null
        socketFile.delete()
    }

    private companion object {
        const val TAG = "ZuzuProtect"
        const val UNIX_PATH_MAX = 108
        const val REQUEST_TIMEOUT_MS = 3_000
    }
}
