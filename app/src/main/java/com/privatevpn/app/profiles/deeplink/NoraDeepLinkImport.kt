package com.privatevpn.app.profiles.deeplink

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

sealed interface NoraDeepLinkImport {
    data class Subscription(
        val url: String,
        val displayName: String?
    ) : NoraDeepLinkImport

    data class Profile(val payload: String) : NoraDeepLinkImport
}

object NoraDeepLinkParser {
    const val SCHEME = "noravpn"
    const val HOST = "import"
    const val VERSION = "1"

    private const val MAX_LINK_CHARS = 384 * 1024
    private const val MAX_PROFILE_BYTES = 256 * 1024
    private const val MAX_SUBSCRIPTION_URL_BYTES = 8 * 1024
    private const val MAX_DISPLAY_NAME_BYTES = 256

    fun isNoraDeepLink(rawUri: String?): Boolean {
        if (rawUri.isNullOrBlank() || rawUri.length > MAX_LINK_CHARS) return false
        return NORA_LINK_PREFIX.containsMatchIn(rawUri)
    }

    fun parse(rawUri: String): NoraDeepLinkImport {
        require(rawUri.isNotBlank()) { "Deep link is empty" }
        require(rawUri.length <= MAX_LINK_CHARS) { "Deep link is too large" }

        val uri = runCatching { URI(rawUri) }
            .getOrElse { throw IllegalArgumentException("Malformed deep link URI", it) }

        require(uri.scheme.equals(SCHEME, ignoreCase = true)) { "Unsupported deep link scheme" }
        require(uri.host.equals(HOST, ignoreCase = true)) { "Unsupported deep link host" }
        require(uri.rawUserInfo == null && uri.port == -1) { "User info and port are not allowed" }
        require(uri.rawFragment == null) { "Fragments are not allowed" }

        val pathSegments = uri.rawPath.orEmpty()
            .split('/')
            .filter { it.isNotEmpty() }
        require(pathSegments.size in 1..2) { "Expected an import type and optional payload" }

        val importType = decodePercentEncoded(pathSegments[0], 32).lowercase()
        require(importType == PROFILE_PATH || importType == SUBSCRIPTION_PATH) {
            "Unsupported import type"
        }

        val query = parseQuery(uri.rawQuery)
        val allowedKeys = when (importType) {
            SUBSCRIPTION_PATH -> setOf(VERSION_PARAM, URL_PARAM, DATA_PARAM, PAYLOAD_PARAM, NAME_PARAM)
            else -> setOf(VERSION_PARAM, DATA_PARAM, PAYLOAD_PARAM)
        }
        require(query.keys.all { it in allowedKeys }) { "Unsupported deep link parameter" }

        val version = query.singleValue(VERSION_PARAM)
        require(version == null || version == VERSION) { "Unsupported deep link version" }

        val pathPayload = pathSegments.getOrNull(1)
        val plainPayload = when (importType) {
            SUBSCRIPTION_PATH -> query.singleValue(URL_PARAM) ?: query.singleValue(DATA_PARAM)
            else -> query.singleValue(DATA_PARAM)
        }
        val encodedPayload = query.singleValue(PAYLOAD_PARAM)
        val suppliedPayloads = listOfNotNull(pathPayload, plainPayload, encodedPayload)
        require(suppliedPayloads.size == 1) { "Exactly one payload must be supplied" }

        val maxPayloadBytes = if (importType == SUBSCRIPTION_PATH) {
            MAX_SUBSCRIPTION_URL_BYTES
        } else {
            MAX_PROFILE_BYTES
        }
        val payload = when {
            pathPayload != null -> decodeBase64Url(pathPayload, maxPayloadBytes)
            encodedPayload != null -> decodeBase64Url(encodedPayload, maxPayloadBytes)
            else -> validateDecodedText(plainPayload.orEmpty(), maxPayloadBytes)
        }.trim()
        require(payload.isNotEmpty()) { "Decoded payload is empty" }

        return when (importType) {
            SUBSCRIPTION_PATH -> NoraDeepLinkImport.Subscription(
                url = validateSubscriptionUrl(payload),
                displayName = query.singleValue(NAME_PARAM)
                    ?.let { validateDecodedText(it.trim(), MAX_DISPLAY_NAME_BYTES) }
                    ?.takeIf { it.isNotEmpty() }
            )

            else -> NoraDeepLinkImport.Profile(payload)
        }
    }

    private fun validateSubscriptionUrl(value: String): String {
        val uri = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("Malformed subscription URL", it) }
        require(uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
            "Subscription URL must use HTTP or HTTPS"
        }
        require(!uri.host.isNullOrBlank()) { "Subscription URL must contain a host" }
        require(uri.port == -1 || uri.port in 1..65535) { "Subscription URL port is invalid" }
        require(uri.rawUserInfo == null) { "Subscription URL user info is not allowed" }
        require(uri.rawFragment == null) { "Subscription URL fragment is not allowed" }
        return value
    }

    private fun parseQuery(rawQuery: String?): Map<String, List<String>> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val result = linkedMapOf<String, MutableList<String>>()
        rawQuery.split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val separator = pair.indexOf('=')
            val rawKey = if (separator >= 0) pair.substring(0, separator) else pair
            val rawValue = if (separator >= 0) pair.substring(separator + 1) else ""
            val key = decodeQueryComponent(rawKey, 64).lowercase()
            require(key.isNotEmpty()) { "Deep link parameter name is empty" }
            val value = decodeQueryComponent(rawValue, MAX_PROFILE_BYTES)
            result.getOrPut(key) { mutableListOf() } += value
        }
        return result
    }

    private fun Map<String, List<String>>.singleValue(key: String): String? {
        val values = this[key].orEmpty()
        require(values.size <= 1) { "Deep link parameter '$key' is duplicated" }
        return values.singleOrNull()
    }

    private fun decodePercentEncoded(rawValue: String, maxBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(rawValue.length, maxBytes))
        var index = 0
        while (index < rawValue.length) {
            if (rawValue[index] == '%') {
                require(index + 2 < rawValue.length) { "Malformed percent encoding" }
                val high = rawValue[index + 1].digitToIntOrNull(16)
                    ?: throw IllegalArgumentException("Malformed percent encoding")
                val low = rawValue[index + 2].digitToIntOrNull(16)
                    ?: throw IllegalArgumentException("Malformed percent encoding")
                output.write((high shl 4) or low)
                index += 3
            } else {
                val nextPercent = rawValue.indexOf('%', startIndex = index).let {
                    if (it == -1) rawValue.length else it
                }
                val bytes = rawValue.substring(index, nextPercent).toByteArray(StandardCharsets.UTF_8)
                output.write(bytes)
                index = nextPercent
            }
            require(output.size() <= maxBytes) { "Decoded value is too large" }
        }
        return decodeUtf8(output.toByteArray()).also { validateControlCharacters(it) }
    }

    private fun decodeQueryComponent(rawValue: String, maxBytes: Int): String {
        return decodePercentEncoded(rawValue.replace("+", "%20"), maxBytes)
    }

    private fun decodeBase64Url(encoded: String, maxBytes: Int): String {
        require(encoded.isNotBlank()) { "Base64URL payload is empty" }
        require(encoded.matches(BASE64_URL_PATTERN)) { "Malformed Base64URL payload" }
        require(encoded.length <= ((maxBytes + 2) / 3) * 4 + 2) { "Decoded value is too large" }
        require(encoded.length % 4 != 1) { "Malformed Base64URL payload" }
        val withoutPadding = encoded.trimEnd('=')
        val padded = withoutPadding + "=".repeat((4 - withoutPadding.length % 4) % 4)
        val decoded = runCatching { Base64.getUrlDecoder().decode(padded) }
            .getOrElse { throw IllegalArgumentException("Malformed Base64URL payload", it) }
        require(decoded.size <= maxBytes) { "Decoded value is too large" }
        return decodeUtf8(decoded).also { validateControlCharacters(it) }
    }

    private fun validateDecodedText(value: String, maxBytes: Int): String {
        require(value.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) { "Decoded value is too large" }
        validateControlCharacters(value)
        return value
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse { throw IllegalArgumentException("Payload is not valid UTF-8", it) }
    }

    private fun validateControlCharacters(value: String) {
        require(value.none { it == '\u0000' || (it < ' ' && it != '\n' && it != '\r' && it != '\t') }) {
            "Payload contains forbidden control characters"
        }
    }

    private const val PROFILE_PATH = "profile"
    private const val SUBSCRIPTION_PATH = "subscription"
    private const val VERSION_PARAM = "v"
    private const val URL_PARAM = "url"
    private const val DATA_PARAM = "data"
    private const val PAYLOAD_PARAM = "payload"
    private const val NAME_PARAM = "name"
    private val BASE64_URL_PATTERN = Regex("^[A-Za-z0-9_-]+={0,2}$")
    private val NORA_LINK_PREFIX = Regex("^noravpn://import(?:/|\\?|$)", RegexOption.IGNORE_CASE)
}
