package com.privatevpn.app.profiles.subscriptions

import com.privatevpn.app.profiles.model.ImportedProfileDraft
import com.privatevpn.app.profiles.model.ProfileType
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

data class HappRoutingProfile(
    val name: String?,
    val sourceLink: String,
    val routing: JSONObject,
    val dns: JSONObject?,
    val dnsServers: List<String>
)

object HappRoutingCompat {
    private val ROUTING_LINK_REGEX = Regex(
        pattern = "\\bhapp://routing/(?:add|onadd)/\\S+",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    fun isRoutingLink(value: String): Boolean {
        val normalized = value.trim().lowercase(Locale.US)
        return normalized.startsWith("happ://routing/")
    }

    fun findFirstRoutingProfile(text: String): HappRoutingProfile? {
        val link = ROUTING_LINK_REGEX.find(text)?.value
            ?.trimEnd(',', ';', ')', ']', '}', '"', '\'')
            ?: return null
        return decodeRoutingLink(link)
    }

    fun applyRoutingToDraft(
        draft: ImportedProfileDraft,
        routingProfile: HappRoutingProfile
    ): ImportedProfileDraft {
        if (draft.type == ProfileType.AMNEZIA_WG_20 || draft.type == ProfileType.KROT) return draft

        val payload = draft.normalizedJson?.trim().orEmpty()
        if (!payload.startsWith("{")) return draft

        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return draft
        if (!root.has("outbounds")) return draft

        root.put("routing", JSONObject(routingProfile.routing.toString()))
        routingProfile.dns?.let { dns ->
            root.put("dns", JSONObject(dns.toString()))
        }

        val dnsServers = routingProfile.dnsServers.ifEmpty { draft.dnsServers }
        val routeName = routingProfile.name?.takeIf { it.isNotBlank() } ?: "HAPP"
        val warnings = draft.importWarnings + "Применён HAPP Routing профиль '$routeName'."

        return draft.copy(
            normalizedJson = root.toString(2),
            dnsServers = dnsServers,
            dnsFallbackApplied = draft.dnsFallbackApplied && routingProfile.dnsServers.isEmpty(),
            isPartialImport = draft.isPartialImport,
            importWarnings = warnings
        )
    }

    private fun decodeRoutingLink(link: String): HappRoutingProfile? {
        val payload = link.replaceFirst(
            regex = Regex("^happ://routing/(?:add|onadd)/", RegexOption.IGNORE_CASE),
            replacement = ""
        ).takeIf { it != link }
            ?: return null

        val normalizedPayload = payload.substringBefore("?")
            .substringBefore("#")
            .trim()
        if (normalizedPayload.isBlank()) return null

        val decoded = runCatching { decodeBase64(normalizedPayload) }.getOrNull() ?: return null
        val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return null

        val routing = buildXrayRouting(json)
        val dnsServers = extractDnsServers(json)
        val dns = buildXrayDns(json, dnsServers)

        return HappRoutingProfile(
            name = json.optStringCaseInsensitive("Name").takeIf { it.isNotBlank() },
            sourceLink = link,
            routing = routing,
            dns = dns,
            dnsServers = dnsServers
        )
    }

    private fun buildXrayRouting(profile: JSONObject): JSONObject {
        val routing = JSONObject()
        routing.put(
            "domainStrategy",
            profile.optStringCaseInsensitive("DomainStrategy").ifBlank { "AsIs" }
        )

        val rules = JSONArray()
        addRoutingRule(
            rules = rules,
            domains = profile.optStringArrayCaseInsensitive("BlockSites"),
            ips = profile.optStringArrayCaseInsensitive("BlockIp"),
            outboundTag = "block"
        )
        addRoutingRule(
            rules = rules,
            domains = profile.optStringArrayCaseInsensitive("DirectSites"),
            ips = profile.optStringArrayCaseInsensitive("DirectIp"),
            outboundTag = "direct"
        )
        addRoutingRule(
            rules = rules,
            domains = profile.optStringArrayCaseInsensitive("ProxySites"),
            ips = profile.optStringArrayCaseInsensitive("ProxyIp"),
            outboundTag = "proxy"
        )

        routing.put("rules", rules)
        return routing
    }

    private fun addRoutingRule(
        rules: JSONArray,
        domains: List<String>,
        ips: List<String>,
        outboundTag: String
    ) {
        if (domains.isEmpty() && ips.isEmpty()) return

        val rule = JSONObject()
            .put("type", "field")
            .put("outboundTag", outboundTag)
        if (domains.isNotEmpty()) rule.put("domain", JSONArray(domains))
        if (ips.isNotEmpty()) rule.put("ip", JSONArray(ips))
        rules.put(rule)
    }

    private fun buildXrayDns(profile: JSONObject, dnsServers: List<String>): JSONObject? {
        val dnsHosts = profile.optJSONObjectCaseInsensitive("DnsHosts")
        if (dnsHosts == null && dnsServers.isEmpty()) return null

        val dns = JSONObject()
        if (dnsServers.isNotEmpty()) dns.put("servers", JSONArray(dnsServers))
        dnsHosts?.let { dns.put("hosts", JSONObject(it.toString())) }
        return dns
    }

    private fun extractDnsServers(profile: JSONObject): List<String> {
        return listOf(
            profile.optStringCaseInsensitive("RemoteDNSIP"),
            profile.optStringCaseInsensitive("DomesticDNSIP"),
            profile.optStringCaseInsensitive("RemoteDns"),
            profile.optStringCaseInsensitive("DomesticDns")
        )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun decodeBase64(value: String): String {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = normalized.padEnd((normalized.length + 3) / 4 * 4, '=')
        return String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
    }

    private fun JSONObject.optStringCaseInsensitive(key: String): String {
        val direct = optString(key)
        if (direct.isNotBlank()) return direct

        val target = key.lowercase(Locale.US)
        val keys = keys()
        while (keys.hasNext()) {
            val candidate = keys.next()
            if (candidate.lowercase(Locale.US) == target) {
                return optString(candidate)
            }
        }
        return ""
    }

    private fun JSONObject.optJSONObjectCaseInsensitive(key: String): JSONObject? {
        optJSONObject(key)?.let { return it }

        val target = key.lowercase(Locale.US)
        val keys = keys()
        while (keys.hasNext()) {
            val candidate = keys.next()
            if (candidate.lowercase(Locale.US) == target) {
                return optJSONObject(candidate)
            }
        }
        return null
    }

    private fun JSONObject.optStringArrayCaseInsensitive(key: String): List<String> {
        val array = optJSONArray(key) ?: run {
            val target = key.lowercase(Locale.US)
            val keys = keys()
            var found: JSONArray? = null
            while (keys.hasNext()) {
                val candidate = keys.next()
                if (candidate.lowercase(Locale.US) == target) {
                    found = optJSONArray(candidate)
                    break
                }
            }
            found
        } ?: return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct()
    }
}
