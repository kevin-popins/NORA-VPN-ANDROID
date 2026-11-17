package com.privatevpn.app.profiles.model

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Resolves display restrictions without changing persisted profile or transport identity. */
object SubscriptionPresentation {
    fun policy(source: SubscriptionSource?): SubscriptionClientPolicy? = source?.let {
        SubscriptionMetadataCodec.decode(it.metadata).clientPolicy
    }

    fun apply(profile: VpnProfile, sources: List<SubscriptionSource>): VpnProfile {
        val source = sources.firstOrNull { it.id == profile.parentSubscriptionId }
        val policy = policy(source)
        if (policy?.endpointProtectionEnabled != true) return profile.copy(
            endpointProtectionEnabled = false, protectedPublicLabel = null, presentationName = null
        )
        val label = policy.publicDisplayLabel
        val endpoints = endpointTokens(profile) + listOfNotNull(source?.sourceUrl?.let { runCatching { URI(it).host }.getOrNull() })
        val nameLeaksEndpoint = endpoints.any { profile.displayName.contains(it, ignoreCase = true) } ||
            profile.displayName.contains(Regex("(?:https?|vless|vmess|trojan)://", RegexOption.IGNORE_CASE)) ||
            profile.displayName.contains(Regex("""(?:[0-9]{1,3}\.){3}[0-9]{1,3}"""))
        return profile.copy(endpointProtectionEnabled = true, protectedPublicLabel = label,
            presentationName = if (nameLeaksEndpoint) label else profile.displayName)
    }

    fun sourceTitle(source: SubscriptionSource): String {
        val policy = policy(source)
        if (policy?.endpointProtectionEnabled != true) return source.displayName
        val host = runCatching { URI(source.sourceUrl).host }.getOrNull()
        return if (SubscriptionClientPolicy.containsEndpointText(source.displayName) || (host != null && source.displayName.contains(host, true)))
            policy.publicDisplayLabel else source.displayName
    }

    /** Build once when Room metadata changes; traffic ticks must not repeatedly parse raw JSON. */
    fun redactor(profiles: List<VpnProfile>, sources: List<SubscriptionSource>): (String) -> String {
        val protectedSources = sources.filter { policy(it)?.endpointProtectionEnabled == true }
        val ids = protectedSources.map { it.id }.toSet()
        val replacements = linkedMapOf<String, String>()
        protectedSources.forEach { source ->
            replacements[source.sourceUrl] = "[защищённая подписка]"
            runCatching { URI(source.sourceUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                replacements[it] = "[защищённый адрес]"
            }
        }
        profiles.filter { it.parentSubscriptionId in ids }.forEach { profile ->
            replacements[profile.sourceRaw] = "[защищённый профиль]"
            profile.normalizedJson?.let { replacements[it] = "[защищённая конфигурация]" }
            endpointTokens(profile).forEach { replacements.putIfAbsent(it, "[защищённый адрес]") }
        }
        val ordered = replacements.filterKeys { it.isNotEmpty() }.entries.sortedByDescending { it.key.length }
        return { text -> ordered.fold(text) { safe, entry -> safe.replace(entry.key, entry.value, ignoreCase = true) } }
    }

    fun redact(text: String, profiles: List<VpnProfile>, sources: List<SubscriptionSource>): String =
        redactor(profiles, sources)(text)

    private fun endpointTokens(profile: VpnProfile): Set<String> = buildSet {
        runCatching { URI(profile.sourceRaw.trim().substringBefore('#')).host }.getOrNull()?.let { add(it) }
        fun read(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    val item = value.opt(key)
                    if (key in setOf("address", "add", "host", "Host", "authority", "server", "endpoint", "serverName", "sni") && item is String && item.isNotBlank()) {
                        add(item); runCatching { URI("scheme://$item").host }.getOrNull()?.let { add(it) }
                    }
                    read(item)
                }
                is JSONArray -> (0 until value.length()).forEach { read(value.opt(it)) }
            }
        }
        profile.normalizedJson?.let { runCatching { read(JSONObject(it)) } }
        Regex("""(?im)^\s*Endpoint\s*=\s*([^\s]+)""").find(profile.sourceRaw)?.groupValues?.getOrNull(1)?.let {
            add(it); runCatching { URI("scheme://$it").host }.getOrNull()?.let { host -> add(host) }
        }
    }.filter { it.length > 2 }.toSet()
}

val VpnProfile.userFacingName: String get() = presentationName ?: displayName
