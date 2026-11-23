package com.privatevpn.app.profiles.model

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

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
            presentationName = if (nameLeaksEndpoint) "Сервер" else profile.displayName)
    }

    fun sourceTitle(source: SubscriptionSource): String {
        val metadata = SubscriptionMetadataCodec.decode(source.metadata)
        val candidates = listOfNotNull(metadata.displayTitle?.trim()?.takeIf { it.isNotEmpty() }, source.displayName.trim().takeIf { it.isNotEmpty() })
        if (metadata.clientPolicy?.endpointProtectionEnabled != true) return candidates.firstOrNull() ?: "Подписка"
        val host = runCatching { URI(source.sourceUrl).host }.getOrNull()
        return candidates.firstOrNull { title ->
            !SubscriptionClientPolicy.containsEndpointText(title) &&
                (host == null || !title.contains(host, ignoreCase = true))
        } ?: "Подписка"
    }

    /** Provider navigation is separate from the private subscription/config address. */
    fun informationUrl(source: SubscriptionSource): String? {
        val metadata = SubscriptionMetadataCodec.decode(source.metadata)
        return sequenceOf(metadata.profileWebPageUrl, metadata.preferredExternalUrl()).mapNotNull { candidate ->
            candidate?.trim()?.takeIf { value ->
                runCatching {
                    val uri = URI(value)
                    (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
                        !uri.host.isNullOrBlank() && uri.rawUserInfo == null && (uri.port == -1 || uri.port in 1..65535)
                }.getOrDefault(false)
            }
        }.firstOrNull()
    }

    /** Keep only the existing public announcement, never a raw metadata/config fallback. */
    fun protectedAnnouncement(source: SubscriptionSource, profiles: List<VpnProfile>): String? {
        val metadata = SubscriptionMetadataCodec.decode(source.metadata)
        if (metadata.clientPolicy?.endpointProtectionEnabled != true) return null
        val text = metadata.announcementText?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (text.length > 2048 || text.toByteArray(Charsets.UTF_8).size > 4096) return null
        if (text.codePoints().anyMatch {
                Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() ||
                    it in 0xD800..0xDFFF || it == 0x2028 || it == 0x2029
            }) return null
        if (SubscriptionClientPolicy.containsEndpointText(text) ||
            ANNOUNCEMENT_PRIVATE_MATERIAL.containsMatchIn(text) ||
            ANNOUNCEMENT_UNICODE_DOMAIN.containsMatchIn(text) ||
            ANNOUNCEMENT_ENCODED_BLOB.containsMatchIn(text)) return null
        // Reject the whole original instead of leaving fragments beside redaction placeholders.
        if (redactor(profiles, listOf(source))(text) != text) return null
        if (announcementPrivateValues(source, profiles).any { text.contains(it) }) return null
        return text
    }

    private val ANNOUNCEMENT_PRIVATE_MATERIAL = Regex(
        """\b(?:vless|vmess|trojan|ss|ssr|socks5?|https?|hysteria2?|hy2|tuic|wireguard|wg|awg|amneziawg|ssh|data):|[{}]|</?[a-z][^>]*>|-----BEGIN\b|\[\s*(?:Interface|Peer)\s*]|\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\b|\b(?:password|passwd|pass|pwd|private[_ -]?key|public[_ -]?key|preshared[_ -]?key|short[_ -]?id|sid|pbk|psk|api[_ -]?key|key|token|access[_ -]?token|secret|authorization|uuid|hwid|user[_ -]?id|plan[_ -]?id|id|endpoint|address|server|dns|sni)\s*[:=]|(?<![\p{L}\p{N}])(?:[a-z]:[\\/]|\\\\|/(?:home|users|etc|data|sdcard)/)""",
        RegexOption.IGNORE_CASE
    )
    private val ANNOUNCEMENT_UNICODE_DOMAIN = Regex("""[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)*\.[\p{L}]{2,63}""")
    private val ANNOUNCEMENT_ENCODED_BLOB = Regex("""(?<![\p{L}\p{N}])[A-Za-z0-9_+/-]{24,}={0,2}(?![\p{L}\p{N}])""")
    /** Only the new announcement surface needs standalone source/transport credential matching. */
    private fun announcementPrivateValues(source: SubscriptionSource, profiles: List<VpnProfile>): Set<String> = buildSet {
        fun rememberValue(value: Any?) {
            when (value) {
                is String -> value.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                is Number -> add(value.toString())
                is JSONArray -> (0 until value.length()).forEach { rememberValue(value.opt(it)) }
            }
        }
        fun uriValues(raw: String, sourceUrl: Boolean) {
            runCatching {
                val uri = URI(raw.trim().substringBefore('#'))
                uri.rawUserInfo?.let { raw ->
                    rememberValue(raw)
                    raw.split(':').forEach(::rememberValue)
                }
                uri.userInfo?.let { decoded ->
                    rememberValue(decoded)
                    decoded.split(':').forEach(::rememberValue)
                }
                uri.rawQuery?.split('&')?.forEach { part ->
                    val raw = part.substringAfter('=', part)
                    rememberValue(raw)
                    rememberValue(URLDecoder.decode(raw, "UTF-8"))
                }
                if (sourceUrl) {
                    val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
                    val token = if (segments.lastOrNull()?.lowercase(Locale.ROOT) in setOf("happ", "raw", "base64", "json"))
                        segments.dropLast(1).lastOrNull() else segments.lastOrNull()
                    token?.let { raw ->
                        rememberValue(raw)
                        rememberValue(URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8"))
                    }
                }
            }
        }
        fun read(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    val item = value.opt(key)
                    if (key.lowercase(Locale.ROOT).replace(Regex("[_ -]"), "") in ANNOUNCEMENT_SECRET_KEYS) rememberValue(item)
                    read(item)
                }
                is JSONArray -> (0 until value.length()).forEach { read(value.opt(it)) }
            }
        }
        uriValues(source.sourceUrl, sourceUrl = true)
        profiles.filter { it.parentSubscriptionId == source.id }.forEach { profile ->
            uriValues(profile.sourceRaw, sourceUrl = false)
            profile.normalizedJson?.let { runCatching { read(JSONObject(it)) } }
            Regex("""(?im)^\s*(?:PrivateKey|PresharedKey|Password)\s*=\s*([^\r\n]+)""")
                .findAll(profile.sourceRaw).forEach { rememberValue(it.groupValues[1].trim().trim('"', '\'')) }
        }
    }
    private val ANNOUNCEMENT_SECRET_KEYS = setOf(
        "password", "passwd", "pass", "pwd", "privatekey", "publickey", "presharedkey", "shortid", "shortids",
        "sid", "pbk", "psk", "apikey", "key", "token", "accesstoken", "secret", "authorization", "auth",
        "uuid", "id", "hwid", "user", "username", "userid"
    )
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
