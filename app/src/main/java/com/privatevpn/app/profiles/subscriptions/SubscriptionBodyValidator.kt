package com.privatevpn.app.profiles.subscriptions

import com.privatevpn.app.profiles.model.ProfileType
import com.privatevpn.app.profiles.model.VpnProfile
import org.json.JSONObject
import java.net.URI

/** Compatibility warnings (such as inserted DNS) do not make a parsed transport malformed. */
object SubscriptionBodyValidator {
    fun isComplete(profiles: List<VpnProfile>): Boolean = profiles.isNotEmpty() && profiles.all { profile ->
        when (profile.type) {
            ProfileType.KROT, ProfileType.AMNEZIA_WG_20 -> !profile.normalizedJson.isNullOrBlank()
            ProfileType.TROJAN -> runCatching {
                val uri = URI(profile.sourceRaw.substringBefore('#'))
                !uri.host.isNullOrBlank() && !uri.userInfo.isNullOrBlank() && uri.port in 1..65535
            }.getOrDefault(false)
            else -> validJsonTransport(profile.normalizedJson)
        }
    }

    private fun validJsonTransport(value: String?): Boolean = runCatching {
        val root = JSONObject(value ?: return@runCatching false)
        if (root.has("add") && root.has("id")) {
            return@runCatching root.optString("add").isNotBlank() && root.optString("id").isNotBlank() && root.optInt("port", -1) in 1..65535
        }
        val outbounds = root.optJSONArray("outbounds") ?: return@runCatching false
        val proxies = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
            .filter { it.optString("protocol").lowercase() !in setOf("freedom", "blackhole", "dns") }
        proxies.isNotEmpty() && proxies.all { outbound ->
            val protocol = outbound.optString("protocol").lowercase()
            val settings = outbound.optJSONObject("settings") ?: return@all false
            val stream = outbound.optJSONObject("streamSettings")
            if (stream?.optString("security").equals("reality", true)) {
                val reality = stream?.optJSONObject("realitySettings") ?: return@all false
                if (reality.optString("publicKey").isBlank() && reality.optString("password").isBlank()) return@all false
            }
            val servers = settings.optJSONArray(if (protocol in setOf("vless", "vmess")) "vnext" else "servers") ?: return@all false
            servers.length() > 0 && (0 until servers.length()).all { index ->
                val server = servers.optJSONObject(index) ?: return@all false
                server.optString("address").isNotBlank() && server.optInt("port", -1) in 1..65535 && when (protocol) {
                    "vless", "vmess" -> !server.optJSONArray("users")?.optJSONObject(0)?.optString("id").isNullOrBlank()
                    "trojan", "shadowsocks" -> server.optString("password").isNotBlank()
                    "socks", "http" -> true
                    else -> false
                }
            }
        }
    }.getOrDefault(false)
}
