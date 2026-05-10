package com.privatevpn.app.profiles.subscriptions

import su.happ.proxyutility.util.ErrorCodeJNIWrapper
import java.nio.charset.StandardCharsets
import java.util.Base64

object HappCrypt5Decryptor {
    fun isCrypt5Link(value: String): Boolean {
        val normalized = value.trim()
        return normalized.startsWith(CRYPT5_PREFIX, ignoreCase = true)
    }

    fun decryptIfNeeded(value: String): String {
        val normalized = value.trim()
        return if (isCrypt5Link(normalized)) decryptToText(normalized) else normalized
    }

    fun decryptToText(value: String): String {
        val payload = stripCrypt5Prefix(value)
        require(payload.isNotBlank()) { "HAPP crypt5 ссылка не содержит данных." }

        val nativeInput = swapSix(payload)
        val nativeOutput = ErrorCodeJNIWrapper().c(nativeInput)
        require(nativeOutput.isNotBlank()) { "HAPP crypt5: не найден ключ расшифровки или payload поврежден." }

        return decodeBase64Flexible(swapPairs(nativeOutput)).trim()
    }

    private fun stripCrypt5Prefix(value: String): String {
        val withoutFragment = value.trim().substringBefore('#')
        return if (withoutFragment.startsWith(CRYPT5_PREFIX, ignoreCase = true)) {
            withoutFragment.substring(CRYPT5_PREFIX.length)
        } else {
            withoutFragment
        }
    }

    private fun swapSix(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val end = (index + 6).coerceAtMost(value.length)
            val chunk = value.substring(index, end)
            if (chunk.length > 5) {
                append(chunk[1])
                append(chunk[3])
                append(chunk[5])
                append(chunk[0])
                append(chunk[2])
                append(chunk[4])
            } else {
                append(chunk)
            }
            index += 6
        }
    }

    private fun swapPairs(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val end = (index + 2).coerceAtMost(value.length)
            val chunk = value.substring(index, end)
            if (chunk.length > 1) {
                append(chunk[1])
                append(chunk[0])
            } else {
                append(chunk)
            }
            index += 2
        }
    }

    private fun decodeBase64Flexible(value: String): String {
        val normalized = value.trim()
            .replace('-', '+')
            .replace('_', '/')
            .padEnd((value.trim().length + 3) / 4 * 4, '=')
        val decoded = Base64.getDecoder().decode(normalized)
        return String(decoded, StandardCharsets.UTF_8)
    }

    private const val CRYPT5_PREFIX = "happ://crypt5/"
}
