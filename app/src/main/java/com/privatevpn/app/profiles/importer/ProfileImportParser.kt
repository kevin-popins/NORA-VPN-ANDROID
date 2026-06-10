package com.privatevpn.app.profiles.importer

import android.net.Uri
import com.privatevpn.app.core.backend.krot.KrotConnectionSpec
import com.privatevpn.app.core.dns.DefaultDnsProvider
import com.privatevpn.app.profiles.awg.AmneziaWgConfigParser
import com.privatevpn.app.profiles.model.ImportedProfileDraft
import com.privatevpn.app.profiles.model.ProfileType
import com.privatevpn.app.profiles.subscriptions.HappCrypt5Decryptor
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

class ProfileImportParser {
    private val amneziaWgConfigParser = AmneziaWgConfigParser()

    fun parse(rawInput: String): ImportedProfileDraft {
        val input = normalize(rawInput).trim()
        require(input.isNotBlank()) { "Пустой профиль." }

        return when {
            HappCrypt5Decryptor.isCrypt5Link(input) -> parse(HappCrypt5Decryptor.decryptToText(input))
            KrotConnectionSpec.isConnectionKey(input) -> parseKrotConnectionKey(input)
            input.startsWith(VLESS_PREFIX, ignoreCase = true) -> parseVless(input)
            input.startsWith(TROJAN_PREFIX, ignoreCase = true) -> parseUriProfile(input, ProfileType.TROJAN)
            input.startsWith(VMESS_PREFIX, ignoreCase = true) -> parseVmess(input)
            looksLikeAwgConf(input) -> parseAmneziaWgConf(input)
            looksLikeJson(input) -> parseXrayJson(input)
            else -> throw IllegalArgumentException("Неподдерживаемый формат профиля.")
        }
    }

    private fun parseKrotConnectionKey(input: String): ImportedProfileDraft {
        val spec = KrotConnectionSpec.parseConnectionKey(input)
        val dnsFallbackApplied = spec.tunnel.dns.isEmpty()

        return ImportedProfileDraft(
            displayName = "KRot ${spec.server.host}:${spec.server.port}",
            type = ProfileType.KROT,
            sourceRaw = input,
            normalizedJson = spec.normalizedJson,
            dnsServers = spec.dnsServers,
            dnsFallbackApplied = dnsFallbackApplied,
            isPartialImport = false,
            importWarnings = emptyList()
        )
    }

    private fun parseVless(input: String): ImportedProfileDraft {
        return runCatching {
            val uri = Uri.parse(input)
            val address = uri.host?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("VLESS: отсутствует address")
            val port = uri.port.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("VLESS: отсутствует корректный port")
            val uuid = uri.userInfo?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("VLESS: отсутствует uuid")

            val displayName = uri.fragment?.takeIf { it.isNotBlank() }
                ?: "$address:$port"

            val transport = uri.queryParameterValue("type")?.takeIf { it.isNotBlank() } ?: "tcp"
            val flow = uri.queryParameterValue("flow")?.takeIf { it.isNotBlank() }
            val security = uri.queryParameterValue("security")?.lowercase() ?: "none"
            val publicKey = uri.queryParameterValue("pbk", "publicKey", "publickey", "password")
            val shortId = uri.queryParameterValue("sid", "shortId", "shortid", "short_id")
            val serverName = uri.queryParameterValue("sni", "serverName", "servername", "server_name")
            val fingerprint = uri.queryParameterValue("fp", "fingerprint")
            val spiderX = uri.queryParameterValue("spx", "spiderX", "spiderx", "spider_x")
            val path = uri.queryParameterValue("path")
            val host = uri.queryParameterValue("host", "authority")
            val alpn = uri.queryParameterValue("alpn")
            val serviceName = uri.queryParameterValue("serviceName", "servicename", "service_name", "service")
            val mode = uri.queryParameterValue("mode")
            val headerType = uri.queryParameterValue("headerType", "headertype", "header_type")
            val allowInsecure = uri.queryParameterValue("allowInsecure", "allowinsecure")

            val warnings = mutableListOf<String>()
            if (security == "reality") {
                if (publicKey.isNullOrBlank()) warnings += "VLESS REALITY: отсутствует publicKey/pbk"
                if (serverName.isNullOrBlank()) warnings += "VLESS REALITY: отсутствует serverName/sni, будет использован address"
            }

            val normalizedJson = buildVlessConfig(
                address = address,
                port = port,
                uuid = uuid,
                flow = flow,
                transport = transport,
                security = security,
                publicKey = publicKey,
                shortId = shortId,
                serverName = serverName,
                fingerprint = fingerprint,
                spiderX = spiderX,
                path = path,
                host = host,
                alpn = alpn,
                serviceName = serviceName,
                mode = mode,
                headerType = headerType,
                allowInsecure = allowInsecure
            )

            ImportedProfileDraft(
                displayName = displayName,
                type = if (security == "reality") {
                    ProfileType.XRAY_VLESS_REALITY
                } else {
                    ProfileType.VLESS
                },
                sourceRaw = input,
                normalizedJson = normalizedJson,
                dnsServers = DefaultDnsProvider.defaultServers,
                dnsFallbackApplied = true,
                isPartialImport = warnings.isNotEmpty(),
                importWarnings = warnings
            )
        }.getOrElse { error ->
            ImportedProfileDraft(
                displayName = "VLESS",
                type = ProfileType.XRAY_VLESS_REALITY,
                sourceRaw = input,
                normalizedJson = null,
                dnsServers = DefaultDnsProvider.defaultServers,
                dnsFallbackApplied = true,
                isPartialImport = true,
                importWarnings = listOf(
                    "Не удалось полностью разобрать VLESS профиль: ${error.message ?: "неизвестная ошибка"}"
                )
            )
        }
    }

    private fun parseUriProfile(input: String, type: ProfileType): ImportedProfileDraft {
        return runCatching {
            val uri = Uri.parse(input)
            val name = uri.fragment?.takeIf { it.isNotBlank() }
                ?: uri.host?.takeIf { it.isNotBlank() }
                ?: type.name

            ImportedProfileDraft(
                displayName = name,
                type = type,
                sourceRaw = input,
                normalizedJson = null,
                dnsServers = DefaultDnsProvider.defaultServers,
                dnsFallbackApplied = true,
                isPartialImport = false,
                importWarnings = emptyList()
            )
        }.getOrElse {
            ImportedProfileDraft(
                displayName = type.name,
                type = type,
                sourceRaw = input,
                normalizedJson = null,
                dnsServers = DefaultDnsProvider.defaultServers,
                dnsFallbackApplied = true,
                isPartialImport = true,
                importWarnings = listOf("Не удалось полноценно разобрать URI. Сохранён частичный импорт.")
            )
        }
    }

    private fun parseVmess(input: String): ImportedProfileDraft {
        val encodedPart = input.removePrefix(VMESS_PREFIX).trim()
        require(encodedPart.isNotBlank()) { "VMess ссылка не содержит полезной нагрузки." }

        return runCatching {
            val decodedJson = decodeBase64(encodedPart)
            val json = JSONObject(decodedJson)

            val displayName = json.optString("ps").takeIf { it.isNotBlank() }
                ?: json.optString("add").takeIf { it.isNotBlank() }
                ?: "VMESS"

            ImportedProfileDraft(
                displayName = displayName,
                type = ProfileType.VMESS,
                sourceRaw = input,
                normalizedJson = json.toString(2),
                dnsServers = DefaultDnsProvider.defaultServers,
                dnsFallbackApplied = true,
                isPartialImport = false,
                importWarnings = emptyList()
            )
        }.getOrElse {
            ImportedProfileDraft(
                displayName = "VMESS",
                type = ProfileType.VMESS,
                sourceRaw = input,
                normalizedJson = null,
                dnsServers = DefaultDnsProvider.defaultServers,
                dnsFallbackApplied = true,
                isPartialImport = true,
                importWarnings = listOf("Не удалось декодировать VMESS JSON. Сохранён частичный импорт.")
            )
        }
    }

    private fun parseXrayJson(input: String): ImportedProfileDraft {
        val json = JSONObject(input)
        val warnings = mutableListOf<String>()
        var dnsFallbackApplied = false

        if (!json.has("outbounds")) {
            warnings += "В JSON не найдена секция outbounds."
        }
        if (!json.has("inbounds")) {
            warnings += "В JSON не найдена секция inbounds."
        } else {
            val legacyInboundWarnings = describeLegacyInbounds(json)
            warnings += legacyInboundWarnings
        }

        val dnsServers = extractDnsServers(json).ifEmpty {
            dnsFallbackApplied = true
            warnings += "DNS в конфиге отсутствует: подставлены DNS по умолчанию приложения."
            val defaultDns = JSONArray(DefaultDnsProvider.defaultServers)
            json.put("dns", JSONObject().put("servers", defaultDns))
            DefaultDnsProvider.defaultServers
        }

        val displayName = json.optString("remarks").takeIf { it.isNotBlank() }
            ?: json.optString("tag").takeIf { it.isNotBlank() }
            ?: "Xray JSON"

        return ImportedProfileDraft(
            displayName = displayName,
            type = detectJsonProfileType(json),
            sourceRaw = input,
            normalizedJson = json.toString(2),
            dnsServers = dnsServers,
            dnsFallbackApplied = dnsFallbackApplied,
            isPartialImport = warnings.isNotEmpty(),
            importWarnings = warnings
        )
    }

    private fun parseAmneziaWgConf(input: String): ImportedProfileDraft {
        val parsed = amneziaWgConfigParser.parse(input)
        val warnings = parsed.importWarnings.toMutableList()
        val dnsServers = parsed.dnsServers.ifEmpty {
            warnings += "DNS в AWG конфиге отсутствует: подставлены DNS по умолчанию приложения."
            DefaultDnsProvider.defaultServers
        }

        return ImportedProfileDraft(
            displayName = parsed.displayName,
            type = ProfileType.AMNEZIA_WG_20,
            sourceRaw = input,
            normalizedJson = parsed.normalizedConfig,
            dnsServers = dnsServers,
            dnsFallbackApplied = parsed.dnsServers.isEmpty(),
            isPartialImport = warnings.isNotEmpty(),
            importWarnings = warnings
        )
    }

    private fun buildVlessConfig(
        address: String,
        port: Int,
        uuid: String,
        flow: String?,
        transport: String,
        security: String,
        publicKey: String?,
        shortId: String?,
        serverName: String?,
        fingerprint: String?,
        spiderX: String?,
        path: String?,
        host: String?,
        alpn: String?,
        serviceName: String?,
        mode: String?,
        headerType: String?,
        allowInsecure: String?
    ): String {
        val user = JSONObject()
            .put("id", uuid)
            .put("encryption", "none")
        if (!flow.isNullOrBlank()) {
            user.put("flow", flow)
        }

        val vnext = JSONObject()
            .put("address", address)
            .put("port", port)
            .put("users", JSONArray().put(user))

        val streamSettings = JSONObject()
            .put("network", transport)
            .put("security", security)

        if (security == "reality") {
            val realitySettings = JSONObject()
            if (!publicKey.isNullOrBlank()) {
                realitySettings.put("publicKey", publicKey)
                realitySettings.put("password", publicKey)
            }
            if (!shortId.isNullOrBlank()) {
                realitySettings.put("shortId", shortId)
            } else {
                realitySettings.put("shortId", "")
            }
            realitySettings.put("serverName", serverName?.takeIf { it.isNotBlank() } ?: address)
            if (!fingerprint.isNullOrBlank()) realitySettings.put("fingerprint", fingerprint)
            if (spiderX != null) realitySettings.put("spiderX", spiderX)
            streamSettings.put("realitySettings", realitySettings)
        }
        if (security == "tls") {
            val tlsSettings = JSONObject()
            tlsSettings.put("serverName", serverName?.takeIf { it.isNotBlank() } ?: address)
            if (!fingerprint.isNullOrBlank()) tlsSettings.put("fingerprint", fingerprint)
            parseCsv(alpn).takeIf { it.isNotEmpty() }?.let { tlsSettings.put("alpn", JSONArray(it)) }
            if (allowInsecure?.equals("true", ignoreCase = true) == true || allowInsecure == "1") {
                tlsSettings.put("allowInsecure", true)
            }
            streamSettings.put("tlsSettings", tlsSettings)
        }

        applyTransportSettings(
            streamSettings = streamSettings,
            transport = transport,
            path = path,
            host = host,
            serviceName = serviceName,
            mode = mode,
            headerType = headerType
        )

        val outboundProxy = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            .put("streamSettings", streamSettings)

        val outboundDirect = JSONObject()
            .put("tag", "direct")
            .put("protocol", "freedom")
            .put("settings", JSONObject())

        val outboundBlock = JSONObject()
            .put("tag", "block")
            .put("protocol", "blackhole")
            .put("settings", JSONObject())

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray())
            .put("outbounds", JSONArray().put(outboundProxy).put(outboundDirect).put(outboundBlock))
            .put("routing", JSONObject().put("domainStrategy", "AsIs"))
            .toString(2)
    }

    private fun applyTransportSettings(
        streamSettings: JSONObject,
        transport: String,
        path: String?,
        host: String?,
        serviceName: String?,
        mode: String?,
        headerType: String?
    ) {
        when (transport.lowercase()) {
            "ws", "websocket" -> {
                streamSettings.put("network", "ws")
                val wsSettings = JSONObject()
                if (!path.isNullOrBlank()) wsSettings.put("path", path)
                if (!host.isNullOrBlank()) wsSettings.put("headers", JSONObject().put("Host", host))
                streamSettings.put("wsSettings", wsSettings)
            }

            "grpc" -> {
                val grpcSettings = JSONObject()
                if (!serviceName.isNullOrBlank()) grpcSettings.put("serviceName", serviceName)
                if (!host.isNullOrBlank()) grpcSettings.put("authority", host)
                if (mode.equals("multi", ignoreCase = true) || mode.equals("multiMode", ignoreCase = true)) {
                    grpcSettings.put("multiMode", true)
                }
                streamSettings.put("grpcSettings", grpcSettings)
            }

            "h2", "http" -> {
                streamSettings.put("network", "h2")
                val httpSettings = JSONObject()
                if (!path.isNullOrBlank()) httpSettings.put("path", path)
                parseCsv(host).takeIf { it.isNotEmpty() }?.let { httpSettings.put("host", JSONArray(it)) }
                streamSettings.put("httpSettings", httpSettings)
            }

            "httpupgrade" -> {
                val httpUpgradeSettings = JSONObject()
                if (!path.isNullOrBlank()) httpUpgradeSettings.put("path", path)
                if (!host.isNullOrBlank()) httpUpgradeSettings.put("host", host)
                streamSettings.put("httpupgradeSettings", httpUpgradeSettings)
            }

            "splithttp" -> {
                val splitHttpSettings = JSONObject()
                if (!path.isNullOrBlank()) splitHttpSettings.put("path", path)
                if (!host.isNullOrBlank()) splitHttpSettings.put("host", host)
                streamSettings.put("splithttpSettings", splitHttpSettings)
            }

            "xhttp" -> {
                val xhttpSettings = JSONObject()
                if (!path.isNullOrBlank()) xhttpSettings.put("path", path)
                if (!host.isNullOrBlank()) xhttpSettings.put("host", host)
                mode?.takeIf { it.isNotBlank() }?.let { xhttpSettings.put("mode", it) }
                streamSettings.put("xhttpSettings", xhttpSettings)
            }

            "tcp", "raw" -> {
                if (!headerType.isNullOrBlank() && !headerType.equals("none", ignoreCase = true)) {
                    val header = JSONObject().put("type", headerType)
                    if (!host.isNullOrBlank()) {
                        val request = JSONObject().put("headers", JSONObject().put("Host", JSONArray(parseCsv(host))))
                        header.put("request", request)
                    }
                    streamSettings.put("tcpSettings", JSONObject().put("header", header))
                }
            }
        }
    }

    private fun parseCsv(value: String?): List<String> {
        return value
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    private fun extractDnsServers(json: JSONObject): List<String> {
        val dnsObject = json.optJSONObject("dns") ?: return emptyList()
        val servers = dnsObject.optJSONArray("servers") ?: return emptyList()

        return buildList {
            for (index in 0 until servers.length()) {
                when (val value = servers.get(index)) {
                    is String -> add(value)
                    is JSONObject -> {
                        val address = value.optString("address")
                        if (address.isNotBlank()) add(address)
                    }
                }
            }
        }
    }

    private fun describeLegacyInbounds(json: JSONObject): List<String> {
        val inbounds = json.optJSONArray("inbounds") ?: return emptyList()
        if (inbounds.length() == 0) return emptyList()

        val descriptions = mutableListOf<String>()
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(index) ?: continue
            val protocol = inbound.optString("protocol").ifBlank { "unknown" }
            val listen = inbound.optString("listen").ifBlank { "0.0.0.0" }
            val port = inbound.opt("port")?.toString() ?: "?"
            descriptions += "$protocol@$listen:$port"
        }

        if (descriptions.isEmpty()) return emptyList()

        return listOf(
            "Обнаружены inbound секции (${descriptions.size}). " +
                "Для клиентского VPN приложение формирует собственные runtime inbounds; " +
                "legacy inbound из профиля не используется как внутренний data plane приватной сессии.",
            "Inbound из профиля: ${descriptions.joinToString(limit = 6, truncated = "...")}"
        )
    }

    private fun decodeBase64(value: String): String {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = normalized.padEnd((normalized.length + 3) / 4 * 4, '=')
        return String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
    }

    private fun looksLikeJson(input: String): Boolean =
        input.startsWith("{") && input.endsWith("}")

    private fun looksLikeAwgConf(input: String): Boolean {
        val hasInterface = Regex("(?im)^\\s*\\[Interface\\]\\s*$").containsMatchIn(input)
        val hasPeer = Regex("(?im)^\\s*\\[Peer\\]\\s*$").containsMatchIn(input)
        return hasInterface && hasPeer
    }

    private fun normalize(input: String): String {
        return input.removePrefix("\uFEFF")
    }

    private fun Uri.queryParameterValue(vararg names: String): String? {
        names.forEach { name ->
            getQueryParameter(name)?.takeIf { it.isNotBlank() }?.let { return it }
        }

        val nameSet = names.map { it.lowercase() }.toSet()
        return queryParameterNames
            .firstOrNull { candidate -> candidate.lowercase() in nameSet }
            ?.let { candidate -> getQueryParameter(candidate) }
            ?.takeIf { it.isNotBlank() }
    }

    private fun detectJsonProfileType(json: JSONObject): ProfileType {
        val outbounds = json.optJSONArray("outbounds") ?: return ProfileType.XRAY_JSON
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val protocol = outbound.optString("protocol").trim().lowercase()
            if (protocol != "vless") continue
            val security = outbound
                .optJSONObject("streamSettings")
                ?.optString("security")
                ?.trim()
                ?.lowercase()
            if (security == "reality") {
                return ProfileType.XRAY_VLESS_REALITY
            }
        }
        return ProfileType.XRAY_JSON
    }

    private companion object {
        const val VLESS_PREFIX = "vless://"
        const val VMESS_PREFIX = "vmess://"
        const val TROJAN_PREFIX = "trojan://"
    }
}
