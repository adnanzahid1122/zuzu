package com.zuzuvpn.shadowsocks

/**
 * A user-supplied Shadowsocks server.
 *
 * What [password] means depends on [method]:
 *  * AEAD-2022 (`2022-blake3-aes-128-gcm`): the SIP022 pre-shared key, base64 of exactly 16 bytes.
 *    Multi-user servers may give an identity chain `iPSK:uPSK`; every part must be such a key.
 *    Surrounding whitespace is ignored.
 *  * Classic AEAD (`chacha20-ietf-poly1305`): a free-form password; shadowsocks-rust derives the
 *    key from its bytes (EVP_BytesToKey), so it is used exactly as entered and may contain ':'.
 *
 * [toString] never prints the password or key.
 */
data class ShadowsocksProfile(
    val name: String,
    val host: String,
    val port: Int,
    val password: String,
    val method: String = METHOD_2022_BLAKE3_AES_128_GCM,
    val udpEnabled: Boolean = true,
) {
    override fun toString(): String =
        "ShadowsocksProfile(name=$name, host=$host, port=$port, method=$method, udpEnabled=$udpEnabled, password=<redacted>)"

    /** The password exactly as it is handed to the native engine. */
    val effectivePassword: String get() = normalizedPassword(method, password)

    companion object {
        const val METHOD_2022_BLAKE3_AES_128_GCM = "2022-blake3-aes-128-gcm"
        const val METHOD_CHACHA20_IETF_POLY1305 = "chacha20-ietf-poly1305"

        /**
         * Ciphers this client accepts, in display order: Zuzu's preferred AEAD-2022 cipher, plus the
         * classic AEAD cipher used by existing servers. Both are compiled into libzuzuss.so
         * (shadowsocks-rust features aead-cipher-2022 and aead-cipher).
         */
        val SUPPORTED_METHODS = listOf(METHOD_2022_BLAKE3_AES_128_GCM, METHOD_CHACHA20_IETF_POLY1305)

        fun isAead2022(method: String): Boolean = method.startsWith("2022-")

        /** Required key length in bytes for AEAD-2022 methods; null for password-based methods. */
        fun keyLength(method: String): Int? = when (method) {
            METHOD_2022_BLAKE3_AES_128_GCM -> 16
            else -> null
        }

        /** AEAD-2022 keys are base64, so whitespace is trimmed; classic passwords are kept verbatim. */
        fun normalizedPassword(method: String, password: String): String =
            if (isAead2022(method)) password.trim() else password
    }
}

/** Problems found by [ShadowsocksProfileValidator]. */
enum class ProfileProblem {
    EmptyHost,
    InvalidHost,
    InvalidPort,
    UnsupportedMethod,
    EmptyKey,
    InvalidKeyEncoding,
    WrongKeyLength,
    /** Classic AEAD password contains control characters (usually a pasted line break). */
    InvalidPasswordCharacters,
}

object ShadowsocksProfileValidator {
    private val HOSTNAME = Regex("^(?=.{1,253}$)([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.?$")
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val IPV6 = Regex("^[0-9A-Fa-f:.]+$")

    fun validate(profile: ShadowsocksProfile): List<ProfileProblem> = buildList {
        val host = profile.host.trim()
        when {
            host.isEmpty() -> add(ProfileProblem.EmptyHost)
            !isValidHost(host) -> add(ProfileProblem.InvalidHost)
        }
        if (profile.port !in 1..65535) add(ProfileProblem.InvalidPort)
        val method = profile.method
        val supported = method in ShadowsocksProfile.SUPPORTED_METHODS
        if (!supported) add(ProfileProblem.UnsupportedMethod)

        val password = profile.effectivePassword
        when {
            password.isBlank() -> add(ProfileProblem.EmptyKey)
            !supported -> Unit
            ShadowsocksProfile.isAead2022(method) ->
                keyProblem(password, ShadowsocksProfile.keyLength(method)!!)?.let(::add)
            password.any { it.isISOControl() } -> add(ProfileProblem.InvalidPasswordCharacters)
        }
    }

    fun isValidHost(host: String): Boolean = when {
        IPV4.matches(host) -> true
        host.contains(':') -> IPV6.matches(host) && host.count { it == ':' } in 2..7
        // Reject all-numeric dotted names that are not valid IPv4 (e.g. 999.1.1.1).
        host.all { it.isDigit() || it == '.' } -> false
        else -> HOSTNAME.matches(host)
    }

    private fun keyProblem(key: String, keyLength: Int): ProfileProblem? {
        for (part in key.split(':')) {
            if (part.isEmpty()) return ProfileProblem.InvalidKeyEncoding
            val bytes = Base64Codec.decodeKey(part) ?: return ProfileProblem.InvalidKeyEncoding
            val wrong = bytes.size != keyLength
            bytes.fill(0)
            if (wrong) return ProfileProblem.WrongKeyLength
        }
        return null
    }
}

/** Key inspection for UI previews that never exposes the key bytes. */
object Base64Key {
    /** Decoded length in bytes under the strict key rules, or null if [key] is not a valid key encoding. */
    fun length(key: String): Int? = Base64Codec.decodeKey(key.trim())?.let { bytes ->
        val size = bytes.size
        bytes.fill(0)
        size
    }
}

/**
 * RFC 4648 base64 decoder.
 *
 * [decodeKey] mirrors the decoder shadowsocks-rust 1.25.0 uses for AEAD-2022 keys
 * (`base64::alphabet::STANDARD` with `DecodePaddingMode::Indifferent`, trailing bits rejected):
 *  * only the standard alphabet (`+`, `/`); URL-safe `-` and `_` are rejected,
 *  * padding is optional, but when present it must be the canonical `=`/`==`,
 *  * unused bits of the last character must be zero,
 *  * no whitespace or other characters.
 * So a key that passes validation here is exactly a key the native client accepts.
 *
 * [decodeLenient] also accepts the URL-safe alphabet; it is used only for ss:// link parts
 * (SIP002 user info is base64url).
 */
internal object Base64Codec {
    fun decodeKey(input: String): ByteArray? = decode(input, allowUrlSafe = false)

    fun decodeLenient(input: String): ByteArray? = decode(input, allowUrlSafe = true)

    fun decodeLenientToString(input: String): String? = decodeLenient(input)?.toString(Charsets.UTF_8)

    private fun decode(input: String, allowUrlSafe: Boolean): ByteArray? {
        val body = input.trimEnd('=')
        val padding = input.length - body.length
        if (body.isEmpty() || body.length % 4 == 1) return null
        if (padding > 0 && padding != (4 - body.length % 4) % 4) return null

        val out = java.io.ByteArrayOutputStream(body.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in body) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' -> 62
                '/' -> 63
                '-' -> if (allowUrlSafe) 62 else return null
                '_' -> if (allowUrlSafe) 63 else return null
                else -> return null
            }
            buffer = ((buffer shl 6) or v) and 0xFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        // The bits left over in the last character must be zero.
        if (buffer and ((1 shl bits) - 1) != 0) return null
        return out.toByteArray()
    }
}
