package com.zuzuvpn.shadowsocks

import java.net.URLDecoder

class ShadowsocksUriException(val reason: Reason) : IllegalArgumentException(reason.name) {
    enum class Reason { NotShadowsocksUri, Malformed, MissingCredentials, MissingHost, InvalidPort, PluginNotSupported }
}

/**
 * Parses `ss://` links.
 *
 * Supported forms:
 *  * SIP002 with percent-encoded user info (required for AEAD-2022):
 *    `ss://2022-blake3-aes-128-gcm:<url-encoded key>@host:port#Name`
 *  * SIP002 with base64url user info: `ss://<base64url(method:key)>@host:port/?#Name`
 *  * Legacy: `ss://<base64(method:key@host:port)>#Name`
 *
 * SIP003 plugins (`?plugin=`) are rejected because no plugin is bundled. The result is not
 * validated; run [ShadowsocksProfileValidator] on it.
 */
object ShadowsocksUri {
    private const val SCHEME = "ss://"

    fun parse(input: String): ShadowsocksProfile {
        val text = input.trim()
        if (!text.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) {
            throw ShadowsocksUriException(ShadowsocksUriException.Reason.NotShadowsocksUri)
        }
        var rest = text.substring(SCHEME.length)

        val name = rest.substringAfter('#', "").let { if (it.isEmpty()) "" else percentDecode(it) }
        rest = rest.substringBefore('#')

        val query = rest.substringAfter('?', "")
        rest = rest.substringBefore('?').trimEnd('/')
        if (query.split('&').any { it.substringBefore('=').equals("plugin", ignoreCase = true) && it.substringAfter('=', "").isNotEmpty() }) {
            throw ShadowsocksUriException(ShadowsocksUriException.Reason.PluginNotSupported)
        }

        if (!rest.contains('@')) {
            // Legacy form: the whole "method:password@host:port" is base64.
            rest = Base64Codec.decodeLenientToString(rest)
                ?: throw ShadowsocksUriException(ShadowsocksUriException.Reason.Malformed)
            if (!rest.contains('@')) throw ShadowsocksUriException(ShadowsocksUriException.Reason.Malformed)
            return build(rest.substringBeforeLast('@'), rest.substringAfterLast('@'), name, userInfoEncoded = false)
        }
        return build(rest.substringBeforeLast('@'), rest.substringAfterLast('@'), name, userInfoEncoded = true)
    }

    private fun build(userInfoRaw: String, hostPort: String, name: String, userInfoEncoded: Boolean): ShadowsocksProfile {
        val userInfo = when {
            !userInfoEncoded -> userInfoRaw
            // Percent-encoded "method:password" (SIP002 for AEAD-2022)
            userInfoRaw.contains(':') || userInfoRaw.contains('%') -> percentDecode(userInfoRaw)
            // base64url("method:password")
            else -> Base64Codec.decodeLenientToString(userInfoRaw)
                ?: throw ShadowsocksUriException(ShadowsocksUriException.Reason.Malformed)
        }
        val method = userInfo.substringBefore(':', "")
        val password = userInfo.substringAfter(':', "")
        if (method.isEmpty() || password.isEmpty()) {
            throw ShadowsocksUriException(ShadowsocksUriException.Reason.MissingCredentials)
        }

        val (host, portText) = splitHostPort(hostPort)
        if (host.isBlank()) throw ShadowsocksUriException(ShadowsocksUriException.Reason.MissingHost)
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw ShadowsocksUriException(ShadowsocksUriException.Reason.InvalidPort)

        return ShadowsocksProfile(
            name = name.ifBlank { host },
            host = host,
            port = port,
            password = password,
            method = method.lowercase(),
        )
    }

    private fun splitHostPort(hostPort: String): Pair<String, String> {
        if (hostPort.startsWith('[')) {
            val end = hostPort.indexOf(']')
            if (end < 0 || end + 1 >= hostPort.length || hostPort[end + 1] != ':') {
                throw ShadowsocksUriException(ShadowsocksUriException.Reason.Malformed)
            }
            return hostPort.substring(1, end) to hostPort.substring(end + 2)
        }
        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) throw ShadowsocksUriException(ShadowsocksUriException.Reason.InvalidPort)
        return hostPort.substring(0, colon) to hostPort.substring(colon + 1)
    }

    // URLDecoder turns '+' into ' '; SIP002 keys can contain '+', so protect it first.
    // URLDecoder's own exception message quotes the input (which may hold the key), so it is
    // replaced by a reason-only exception without a cause.
    private fun percentDecode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        throw ShadowsocksUriException(ShadowsocksUriException.Reason.Malformed)
    }
}
