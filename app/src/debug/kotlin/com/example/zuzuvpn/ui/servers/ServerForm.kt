package com.example.zuzuvpn.ui.servers

import com.zuzuvpn.shadowsocks.Base64Key
import com.zuzuvpn.shadowsocks.ProfileProblem
import com.zuzuvpn.shadowsocks.ShadowsocksProfile
import com.zuzuvpn.shadowsocks.ShadowsocksProfileValidator
import com.zuzuvpn.shadowsocks.ShadowsocksUri
import com.zuzuvpn.shadowsocks.ShadowsocksUriException

/** Form fields that can show a validation error. */
enum class FormField { Host, Port, Method, Key }

/**
 * The editable values of the server editor. [toString] hides the key.
 * Pure Kotlin: validation is unit-tested without Android.
 */
data class ServerForm(
    val name: String = "",
    val host: String = "",
    val port: String = "",
    val method: String = ShadowsocksProfile.METHOD_2022_BLAKE3_AES_128_GCM,
    val key: String = "",
    /** Off until the user confirms their server relays UDP (DNS then resolves on the server over TCP). */
    val udpEnabled: Boolean = false,
) {
    override fun toString(): String = "ServerForm(name=$name, host=$host, port=$port, method=$method, udpEnabled=$udpEnabled, key=<redacted>)"

    sealed interface Result {
        data class Valid(val profile: ShadowsocksProfile) : Result
        data class Invalid(val errors: Map<FormField, ProfileProblem>) : Result
    }

    /** Validates with the same rules the engine uses (see [ShadowsocksProfileValidator]). */
    fun validate(): Result {
        val trimmedHost = host.trim()
        val parsedPort = port.trim().toIntOrNull()
        val trimmedMethod = method.trim()
        val profile = ShadowsocksProfile(
            name = name.trim().ifEmpty { trimmedHost },
            host = trimmedHost,
            port = parsedPort ?: 0,
            // 2022 keys are trimmed; classic AEAD passwords are kept exactly as typed.
            password = ShadowsocksProfile.normalizedPassword(trimmedMethod, key),
            method = trimmedMethod,
            udpEnabled = udpEnabled,
        )
        val errors = ShadowsocksProfileValidator.validate(profile).associateBy(::fieldOf)
        return if (errors.isEmpty()) Result.Valid(profile) else Result.Invalid(errors)
    }

    companion object {
        fun from(profile: ShadowsocksProfile) = ServerForm(
            name = profile.name,
            host = profile.host,
            port = profile.port.toString(),
            method = profile.method,
            key = profile.password,
            udpEnabled = profile.udpEnabled,
        )

        fun fieldOf(problem: ProfileProblem): FormField = when (problem) {
            ProfileProblem.EmptyHost, ProfileProblem.InvalidHost -> FormField.Host
            ProfileProblem.InvalidPort -> FormField.Port
            ProfileProblem.UnsupportedMethod -> FormField.Method
            ProfileProblem.EmptyKey, ProfileProblem.InvalidKeyEncoding, ProfileProblem.WrongKeyLength,
            ProfileProblem.InvalidPasswordCharacters -> FormField.Key
        }
    }
}

/** Safe description of an imported server: everything except the key material. */
data class ImportPreview(
    val profile: ShadowsocksProfile,
    /** AEAD-2022 only: number of keys (2+ for a multi-user identity chain); 0 for a password. */
    val keyCount: Int,
    /** AEAD-2022 only: length of each key in bytes; 0 for a password. */
    val keyBytes: Int,
) {
    /** Classic AEAD: a free-form password. The preview never reveals its content or length. */
    val isPassword: Boolean get() = keyCount == 0

    override fun toString(): String = "ImportPreview(profile=$profile, keyCount=$keyCount, keyBytes=$keyBytes)"
}

object ServerImport {
    sealed interface Outcome {
        data class Success(val preview: ImportPreview) : Outcome
        data class UriError(val reason: ShadowsocksUriException.Reason) : Outcome
        /** The link parsed, but its values fail the same validation as manual entry. */
        data class InvalidProfile(val problem: ProfileProblem) : Outcome
    }

    /** Parses and fully validates an ss:// link. Never throws; results contain no key text. */
    fun parse(text: String): Outcome {
        val profile = try {
            ShadowsocksUri.parse(text)
        } catch (e: ShadowsocksUriException) {
            return Outcome.UriError(e.reason)
        }
        ShadowsocksProfileValidator.validate(profile).firstOrNull()?.let { return Outcome.InvalidProfile(it) }
        if (!ShadowsocksProfile.isAead2022(profile.method)) {
            return Outcome.Success(ImportPreview(profile, keyCount = 0, keyBytes = 0))
        }
        val parts = profile.effectivePassword.split(':')
        return Outcome.Success(ImportPreview(profile, keyCount = parts.size, keyBytes = Base64Key.length(parts.first()) ?: 0))
    }
}
