package com.privatevpn.app.profiles.subscriptions

import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/** Filters unknown inert service records; exact known technical entries have a separate role. */
object SubscriptionServiceRecords {
    fun isServiceRecord(sourceRaw: String, normalizedJson: String? = null): Boolean {
        val raw = sourceRaw.trim()
        if (raw.startsWith("vmess://", true)) {
            val encoded = raw.substringAfter("://").substringBefore('#').trim()
            val json = runCatching {
                val value = encoded.replace('-', '+').replace('_', '/')
                JSONObject(String(Base64.getDecoder().decode(value.padEnd((value.length + 3) / 4 * 4, '=')), StandardCharsets.UTF_8))
            }.getOrNull()
            if (json != null && isSyntheticEndpoint(json.optString("add"), json.optInt("port", -1), json.optString("id"))) return true
        }
        if (raw.startsWith("vless://", true) || raw.startsWith("trojan://", true)) {
            val uri = runCatching { URI(raw.substringBefore('#')) }.getOrNull()
            if (uri != null && isSyntheticEndpoint(uri.host.orEmpty(), uri.port, uri.userInfo.orEmpty())) return true
        }
        val jsonText = if (raw.startsWith("{")) raw else normalizedJson.orEmpty()
        val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return false
        val endpoints = buildList {
            val outbounds = json.optJSONArray("outbounds") ?: return@buildList
            for (index in 0 until outbounds.length()) {
                val settings = outbounds.optJSONObject(index)?.optJSONObject("settings") ?: continue
                for (key in listOf("vnext", "servers")) {
                    val servers = settings.optJSONArray(key) ?: continue
                    for (serverIndex in 0 until servers.length()) {
                        val server = servers.optJSONObject(serverIndex) ?: continue
                        add(isSyntheticEndpoint(server.optString("address"), server.optInt("port", -1), server.optJSONArray("users")?.optJSONObject(0)?.optString("id").orEmpty()))
                    }
                }
            }
        }
        return endpoints.isNotEmpty() && endpoints.all { it }
    }

    /** Exact known inert entry, independent of title or message semantics. */
    fun isKnownTechnicalEntry(sourceRaw: String, normalizedJson: String? = null): Boolean =
        isKnownTechnicalEntry(sourceRaw, normalizedJson, LEGACY_RESERVED_TUPLE_SHA256)

    internal fun isKnownTechnicalEntry(sourceRaw: String, normalizedJson: String?, expectedTupleSha256: String): Boolean {
        val raw = sourceRaw.trim()
        val reserved = when {
            raw.startsWith("vless://", true) -> runCatching {
                val uri = URI(raw.substringBefore('#'))
                val query = uri.rawQuery.orEmpty().split('&').map { pair ->
                    val separator = pair.indexOf('=')
                    require(separator > 0)
                    val key = java.net.URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8.name())
                    val value = java.net.URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8.name())
                    key to value
                }
                query.size == 3 && query.map { it.first }.toSet() == setOf("encryption", "type", "security") &&
                    query.toMap() == mapOf("encryption" to "none", "type" to "tcp", "security" to "none") &&
                    uri.port == 1 &&
                    reservedTupleSha256(uri.host.orEmpty(), uri.userInfo.orEmpty(), uri.port) == expectedTupleSha256
            }.getOrDefault(false)
            raw.startsWith("{") -> runCatching {
                val root = JSONObject(raw)
                val role = root.optJSONObject("meta")?.opt("serverDescription")
                if (role != LEGACY_MARKER_ROLE) return@runCatching false
                val outbounds = root.optJSONArray("outbounds") ?: return@runCatching false
                if ((0 until outbounds.length()).any { outbounds.optJSONObject(it) == null }) return@runCatching false
                val proxies = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
                    .filter { it.optString("protocol") !in setOf("freedom", "blackhole", "dns") }
                proxies.isNotEmpty() && proxies.all { outbound ->
                    if (outbound.optString("protocol") != "vless") return@all false
                    val stream = outbound.optJSONObject("streamSettings") ?: return@all false
                    if (stream.keys().asSequence().toSet() != setOf("network", "security") ||
                        stream.optString("network") != "tcp" || stream.optString("security") != "none") return@all false
                    val settings = outbound.optJSONObject("settings") ?: return@all false
                    if (settings.keys().asSequence().toSet() != setOf("vnext")) return@all false
                    val servers = settings.optJSONArray("vnext") ?: return@all false
                    if (servers.length() != 1) return@all false
                    val server = servers.optJSONObject(0) ?: return@all false
                    val port = server.opt("port") as? Number ?: return@all false
                    if (server.keys().asSequence().toSet() != setOf("address", "port", "users") || port.toDouble() != 1.0) return@all false
                    val users = server.optJSONArray("users") ?: return@all false
                    if (users.length() != 1) return@all false
                    val user = users.optJSONObject(0) ?: return@all false
                    user.keys().asSequence().toSet() == setOf("id", "encryption") && user.optString("encryption") == "none" &&
                        reservedTupleSha256(server.optString("address"), user.optString("id"), server.optInt("port", -1)) == expectedTupleSha256
                }
            }.getOrDefault(false)
            else -> false
        }
        return reserved || (normalizedJson != null && normalizedJson != sourceRaw &&
            isKnownTechnicalEntry(normalizedJson, null, expectedTupleSha256))
    }

    internal fun reservedTupleSha256(host: String, credential: String, port: Int): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("vless\n${host.lowercase(Locale.US)}\n${credential.lowercase(Locale.US)}\n$port".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private const val LEGACY_MARKER_ROLE = "Аварийная заглушка"
    private const val LEGACY_RESERVED_TUPLE_SHA256 = "6315e5c82e2c1d9dbad79b79309d1f17c4896a62a9b14b539b1eeb1bf229fdef"

    private fun isSyntheticEndpoint(host: String, port: Int, credential: String): Boolean {
        val normalized = host.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.US)
        return normalized in LOCAL_HOSTS && (port in 0..1 || credential.equals(ZERO_UUID, true))
    }

    private val LOCAL_HOSTS = setOf("0.0.0.0", "127.0.0.1", "localhost", "::", "::1")
    private const val ZERO_UUID = "00000000-0000-0000-0000-000000000000"
}

object SubscriptionCompatibilityPolicy {
    fun shouldProbe(connectableCount: Int, markerCount: Int, hwidNotSupported: Boolean, unusableResponse: Boolean = false): Boolean =
        connectableCount == 0 && (markerCount > 0 || hwidNotSupported || unusableResponse)
}
