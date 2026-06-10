package com.privatevpn.app.core.backend.krot

import com.privatevpn.app.core.dns.DefaultDnsProvider
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

data class KrotConnectionSpec(
    val schema: String,
    val profileId: String,
    val transportProfile: String,
    val server: KrotServerSpec,
    val credentials: KrotCredentialsSpec,
    val tunnel: KrotTunnelSpec,
    val normalizedJson: String
) {
    val dnsServers: List<String>
        get() = tunnel.dns.ifEmpty { DefaultDnsProvider.defaultServers }

    fun credentialKeyBytes(): ByteArray = decodeCredentialKey(credentials.credentialKey)

    companion object {
        const val KEY_PREFIX: String = "nora1."
        const val SCHEMA: String = "nora-connection-key-v1"
        const val TRANSPORT_TLS_HTTP_COVER: String = "tls_http_cover_v1"

        fun isConnectionKey(value: String): Boolean =
            value.trim().startsWith(KEY_PREFIX, ignoreCase = true)

        fun parseConnectionKey(rawInput: String): KrotConnectionSpec {
            val trimmed = rawInput.trim()
            require(isConnectionKey(trimmed)) { "KRot: ключ должен начинаться с nora1." }

            val encodedPayload = trimmed
                .substring(KEY_PREFIX.length)
                .substringBefore("?")
                .substringBefore("#")
                .trim()
            require(encodedPayload.isNotBlank()) { "KRot: пустой payload ключа." }

            val decodedJson = decodeBase64Url(encodedPayload)
            return parseJson(decodedJson)
        }

        fun parseProfilePayload(payload: String): KrotConnectionSpec {
            val trimmed = payload.trim()
            return if (isConnectionKey(trimmed)) {
                parseConnectionKey(trimmed)
            } else {
                parseJson(trimmed)
            }
        }

        private fun parseJson(jsonText: String): KrotConnectionSpec {
            val root = JSONObject(jsonText)
            val schema = root.requiredString("schema")
            require(schema == SCHEMA) { "KRot: неподдерживаемая schema '$schema'." }

            val profileId = root.requiredString("profile_id")
            val transportProfile = root.requiredString("transport_profile")
            require(transportProfile == TRANSPORT_TLS_HTTP_COVER) {
                "KRot: transport_profile '$transportProfile' пока не поддерживается."
            }

            val serverJson = root.requiredObject("server")
            val server = KrotServerSpec(
                host = serverJson.requiredString("host"),
                port = serverJson.requiredPort("port"),
                tlsName = serverJson.requiredString("tls_name"),
                coverHost = serverJson.optString("cover_host").trim()
                    .ifBlank { serverJson.requiredString("tls_name") }
            )

            val credentialsJson = root.requiredObject("credentials")
            val credentialKey = credentialsJson.requiredString("credential_key")
            require(decodeCredentialKey(credentialKey).isNotEmpty()) {
                "KRot: credential_key пустой после base64 decode."
            }
            val credentials = KrotCredentialsSpec(
                credentialId = credentialsJson.requiredString("credential_id"),
                credentialKey = credentialKey
            )

            val tunnelJson = root.requiredObject("tunnel")
            val tunnel = KrotTunnelSpec(
                clientIp = tunnelJson.requiredString("client_ip"),
                serverIp = tunnelJson.requiredString("server_ip"),
                cidr = tunnelJson.requiredString("cidr"),
                dns = tunnelJson.optStringArray("dns")
            )
            require(tunnel.clientIp != tunnel.serverIp) {
                "KRot: tunnel.client_ip и tunnel.server_ip должны отличаться."
            }
            require(tunnel.prefixLength() in 0..32) {
                "KRot: tunnel.cidr содержит некорректный IPv4 prefix."
            }

            return KrotConnectionSpec(
                schema = schema,
                profileId = profileId,
                transportProfile = transportProfile,
                server = server,
                credentials = credentials,
                tunnel = tunnel,
                normalizedJson = root.toString(2)
            )
        }

        private fun decodeBase64Url(value: String): String {
            val padded = value.padEnd((value.length + 3) / 4 * 4, '=')
            return runCatching {
                String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8)
            }.getOrElse { error ->
                throw IllegalArgumentException("KRot: payload ключа не является валидным base64url.", error)
            }
        }

        private fun decodeCredentialKey(value: String): ByteArray {
            require(value.trim().isNotBlank()) { "KRot: credential_key обязателен." }
            return runCatching {
                Base64.getDecoder().decode(value.trim())
            }.getOrElse { error ->
                throw IllegalArgumentException("KRot: credential_key не является валидным base64.", error)
            }
        }

        private fun JSONObject.requiredObject(key: String): JSONObject =
            optJSONObject(key) ?: throw IllegalArgumentException("KRot: отсутствует объект '$key'.")

        private fun JSONObject.requiredString(key: String): String =
            optString(key).trim().takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("KRot: отсутствует поле '$key'.")

        private fun JSONObject.requiredPort(key: String): Int {
            val port = optInt(key, -1)
            require(port in 1..65535) { "KRot: '$key' должен быть в диапазоне 1-65535." }
            return port
        }

        private fun JSONObject.optStringArray(key: String): List<String> {
            val array = optJSONArray(key) ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    }
}

data class KrotServerSpec(
    val host: String,
    val port: Int,
    val tlsName: String,
    val coverHost: String
)

data class KrotCredentialsSpec(
    val credentialId: String,
    val credentialKey: String
)

data class KrotTunnelSpec(
    val clientIp: String,
    val serverIp: String,
    val cidr: String,
    val dns: List<String>
) {
    fun prefixLength(defaultPrefix: Int = 24): Int {
        val value = cidr.substringAfter('/', missingDelimiterValue = defaultPrefix.toString())
        return value.toIntOrNull() ?: defaultPrefix
    }
}
