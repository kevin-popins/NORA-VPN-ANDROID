package com.privatevpn.app.core.backend.xray

import com.privatevpn.app.core.backend.runtime.RuntimeConfigPreparer
import com.privatevpn.app.core.backend.runtime.RuntimePreparationInput
import com.privatevpn.app.core.backend.runtime.RuntimePreparationResult
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

class XrayRuntimeConfigPreparer : RuntimeConfigPreparer {
    override fun prepare(input: RuntimePreparationInput): RuntimePreparationResult {
        val root = runCatching { JSONObject(input.normalizedConfig.normalizedPayload) }
            .getOrElse { throw IllegalArgumentException("Нормализованный конфиг не является валидным JSON", it) }

        val primaryOutboundTag = ensurePrimaryOutboundTag(root)

        val notes = mutableListOf<String>()
        notes += "Runtime-конфиг подготовлен для профиля '${input.normalizedConfig.profileName}'"
        notes += "Приватная сессия: ${if (input.privateSessionEnabled) "включена" else "выключена"}"
        notes += input.normalizedConfig.warnings
        appendOutboundPipelineDiagnostics(
            root = root,
            notes = notes,
            stageLabel = "Import pipeline"
        )

        val existingDns = root.optJSONObject("dns")
        val dnsNormalization = normalizeDnsServersForRuntime(input.dnsServers)
        root.put("dns", buildDnsSection(dnsNormalization.servers, existingDns))
        dnsNormalization.notes.forEach { note -> notes += note }
        sanitizeLegacyInbounds(
            root = root,
            notes = notes
        )

        applyDataPlaneSocks(
            root = root,
            input = input,
            notes = notes,
            fallbackOutboundTag = primaryOutboundTag
        )

        applyLocalhostSocks(
            root = root,
            input = input,
            notes = notes
        )

        normalizeRealityKeyAliases(root = root, notes = notes)
        appendOutboundPipelineDiagnostics(
            root = root,
            notes = notes,
            stageLabel = "Runtime pipeline"
        )
        validateAndDescribeOutbound(
            root = root,
            profileType = input.normalizedConfig.profileType,
            notes = notes
        )
        ensureLogSection(root)
        notes += buildRuntimeProxyShortIdTrace(root)

        val runtimeConfig = root.toString(2)
        notes += "Финальный runtime config сформирован: size=${runtimeConfig.length}, sha256=${sha256(runtimeConfig)}"

        return RuntimePreparationResult(
            runtimeConfig = runtimeConfig,
            notes = notes,
            dataPlaneProxy = input.dataPlaneProxy
        )
    }

    private fun ensurePrimaryOutboundTag(root: JSONObject): String {
        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("В runtime конфиге отсутствует секция outbounds")
        require(outbounds.length() > 0) { "В runtime конфиге отсутствуют outbounds для выхода в интернет" }

        val primaryOutbound = outbounds.optJSONObject(0)
            ?: throw IllegalArgumentException("Первый outbound должен быть объектом JSON")
        val existingTag = primaryOutbound.optString("tag").takeIf { it.isNotBlank() }
        if (existingTag != null) {
            return existingTag
        }

        primaryOutbound.put("tag", DEFAULT_PRIMARY_OUTBOUND_TAG)
        return DEFAULT_PRIMARY_OUTBOUND_TAG
    }

    private fun buildDnsSection(dnsServers: List<String>, existingDns: JSONObject?): JSONObject {
        val servers = dnsServers
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("1.1.1.1", "9.9.9.9") }

        val dns = JSONObject().put("servers", JSONArray(servers))
        existingDns?.optJSONObject("hosts")?.let { hosts ->
            dns.put("hosts", JSONObject(hosts.toString()))
        }
        existingDns?.optString("queryStrategy")?.takeIf { it.isNotBlank() }?.let { queryStrategy ->
            dns.put("queryStrategy", queryStrategy)
        }
        existingDns?.takeIf { it.has("disableCache") }?.opt("disableCache")?.let { disableCache ->
            dns.put("disableCache", disableCache)
        }
        return dns
    }

    private fun normalizeDnsServersForRuntime(dnsServers: List<String>): DnsNormalizationResult {
        val normalized = mutableListOf<String>()
        val notes = mutableListOf<String>()

        dnsServers.forEach { raw ->
            val value = raw.trim()
            if (value.isBlank()) return@forEach

            val lowered = value.lowercase(Locale.US)
            if (lowered.startsWith("https://") || lowered.startsWith("http://")) {
                val host = extractHostFromHttpUrl(value)
                if (!host.isNullOrBlank() && isIpv4Address(host)) {
                    normalized += host
                    notes += "DNS compat: '$value' преобразован в '$host' (DoH-IP -> plain IP)"
                    return@forEach
                }
            }

            normalized += value
        }

        val distinct = normalized
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return DnsNormalizationResult(
            servers = if (distinct.isNotEmpty()) distinct else listOf("1.1.1.1", "9.9.9.9"),
            notes = notes
        )
    }

    private fun extractHostFromHttpUrl(url: String): String? {
        val trimmed = url.trim()
        val schemeIndex = trimmed.indexOf("://")
        if (schemeIndex <= 0) return null
        val hostStart = schemeIndex + 3
        if (hostStart >= trimmed.length) return null

        val afterScheme = trimmed.substring(hostStart)
        val end = afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let { idx ->
            if (idx < 0) afterScheme.length else idx
        }
        val hostPort = afterScheme.substring(0, end)
        if (hostPort.isBlank()) return null

        // IPv6 literal like [2001:db8::1]:443
        if (hostPort.startsWith("[") && hostPort.contains("]")) {
            return hostPort.substringAfter("[").substringBefore("]").trim()
        }

        return hostPort.substringBefore(":").trim()
    }

    private fun isIpv4Address(value: String): Boolean {
        val parts = value.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val number = part.toIntOrNull() ?: return@all false
            number in 0..255 && part == number.toString()
        }
    }

    private fun applyDataPlaneSocks(
        root: JSONObject,
        input: RuntimePreparationInput,
        notes: MutableList<String>,
        fallbackOutboundTag: String
    ) {
        val dataPlane = input.dataPlaneProxy
        require(dataPlane.port in 1..65535) { "Некорректный порт внутреннего SOCKS data plane" }
        require(dataPlane.username.isNotBlank() && dataPlane.password.isNotBlank()) {
            "Некорректные учётные данные внутреннего SOCKS data plane"
        }

        val inbounds = root.optJSONArray("inbounds") ?: JSONArray().also { root.put("inbounds", it) }
        val filteredInbounds = JSONArray()
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(index) ?: continue
            val tag = inbound.optString("tag")
            if (tag != PRIVATEVPN_DATAPLANE_SOCKS_TAG) {
                filteredInbounds.put(inbound)
            }
        }

        val dataPlaneInbound = JSONObject()
            .put("tag", PRIVATEVPN_DATAPLANE_SOCKS_TAG)
            .put("listen", dataPlane.host)
            .put("port", dataPlane.port)
            .put("protocol", "socks")
            .put(
                "settings",
                JSONObject()
                    .put("auth", "password")
                    .put(
                        "accounts",
                        JSONArray().put(
                            JSONObject()
                                .put("user", dataPlane.username)
                                .put("pass", dataPlane.password)
                        )
                    )
                    .put("udp", true)
            )
        filteredInbounds.put(dataPlaneInbound)
        root.put("inbounds", filteredInbounds)

        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val routeTarget = resolveDataPlaneRouteTarget(
            root = root,
            routing = routing,
            fallbackOutboundTag = fallbackOutboundTag,
            notes = notes
        )
        val routingRules = routing.optJSONArray("rules") ?: JSONArray()
        val filteredRules = JSONArray()
        for (index in 0 until routingRules.length()) {
            val rule = routingRules.optJSONObject(index) ?: continue
            val inboundTagArray = rule.optJSONArray("inboundTag")
            val isDataPlaneRule = inboundTagArray?.let { tags ->
                (0 until tags.length()).any { tagIndex ->
                    tags.optString(tagIndex) == PRIVATEVPN_DATAPLANE_SOCKS_TAG
                }
            } ?: false
            if (!isDataPlaneRule) {
                filteredRules.put(rule)
            }
        }

        val dataPlaneRule = JSONObject()
            .put("type", "field")
            .put("inboundTag", JSONArray().put(PRIVATEVPN_DATAPLANE_SOCKS_TAG))

        when (routeTarget.kind) {
            RouteTargetKind.BALANCER -> dataPlaneRule.put("balancerTag", routeTarget.tag)
            RouteTargetKind.OUTBOUND -> dataPlaneRule.put("outboundTag", routeTarget.tag)
        }

        val rebuiltRules = JSONArray().put(dataPlaneRule)
        for (index in 0 until filteredRules.length()) {
            rebuiltRules.put(filteredRules.get(index))
        }
        routing.put("rules", rebuiltRules)

        notes += "Data plane SOCKS добавлен: ${dataPlane.host}:${dataPlane.port}, auth=password, mode=internal-only, " +
            "routeTarget=${routeTarget.kind.name.lowercase()}:${routeTarget.tag}"
    }

    private fun resolveDataPlaneRouteTarget(
        root: JSONObject,
        routing: JSONObject,
        fallbackOutboundTag: String,
        notes: MutableList<String>
    ): RouteTarget {
        val balancers = routing.optJSONArray("balancers")
        if (balancers != null && balancers.length() > 0) {
            for (index in 0 until balancers.length()) {
                val balancer = balancers.optJSONObject(index) ?: continue
                val tag = balancer.optString("tag").trim()
                if (tag.isBlank()) continue
                val selectors = extractBalancerSelectors(balancer)
                val matchingTags = collectBalancerMatchedOutboundTags(root, selectors)
                val strategy = balancer.optJSONObject("strategy")
                    ?.optString("type")
                    ?.trim()
                    .orEmpty()
                    .ifBlank { "default" }
                if (matchingTags.isEmpty()) {
                    notes += "Routing balancer пропущен: tag=$tag selector=${selectors.joinToString(",")} " +
                        "strategy=$strategy matchedOutbounds=<none>"
                    continue
                }
                notes += "Routing balancer обнаружен: tag=$tag selector=${selectors.joinToString(",")} " +
                    "strategy=$strategy matchedOutbounds=${matchingTags.joinToString(",")}"
                return RouteTarget(kind = RouteTargetKind.BALANCER, tag = tag)
            }
        }

        notes += "Routing balancer не обнаружен: data-plane направлен в outbound '$fallbackOutboundTag'"
        return RouteTarget(kind = RouteTargetKind.OUTBOUND, tag = fallbackOutboundTag)
    }

    private fun appendOutboundPipelineDiagnostics(
        root: JSONObject,
        notes: MutableList<String>,
        stageLabel: String
    ) {
        val outbounds = root.optJSONArray("outbounds")
        if (outbounds == null) {
            notes += "$stageLabel: outbounds отсутствуют"
            return
        }

        val tags = mutableListOf<String>()
        val realityDescriptors = mutableListOf<String>()
        var vlessCount = 0
        var realityCount = 0

        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val tag = outbound.optString("tag").trim().ifBlank { "<none>" }
            val protocol = outbound.optString("protocol").trim().ifBlank { "unknown" }
            tags += tag

            if (protocol.equals("vless", ignoreCase = true)) {
                vlessCount += 1
            }

            val streamSettings = outbound.optJSONObject("streamSettings")
            val security = streamSettings?.optString("security")?.trim()?.lowercase().orEmpty()
            if (security != "reality") continue

            realityCount += 1
            val realitySettings = streamSettings?.optJSONObject("realitySettings")
            val shortIdPresent = realitySettings?.optString("shortId")?.isNotBlank() == true
            val publicKeyPresent = realitySettings?.optString("publicKey")?.isNotBlank() == true
            val passwordPresent = realitySettings?.optString("password")?.isNotBlank() == true
            val serverNamePresent = realitySettings?.optString("serverName")?.isNotBlank() == true
            val fingerprintPresent = realitySettings?.optString("fingerprint")?.isNotBlank() == true

            realityDescriptors += "tag=$tag shortId=$shortIdPresent publicKey=$publicKeyPresent " +
                "password=$passwordPresent serverName=$serverNamePresent fingerprint=$fingerprintPresent"
        }

        notes += "$stageLabel: outbounds=${outbounds.length()} vless=$vlessCount reality=$realityCount " +
            "tags=${tags.joinToString(limit = MAX_OUTBOUND_TAG_LOGS, truncated = "...")}"

        if (realityDescriptors.isEmpty()) {
            notes += "$stageLabel: REALITY outbounds не найдены"
        } else {
            realityDescriptors.take(MAX_REALITY_OUTBOUND_LOGS).forEach { descriptor ->
                notes += "$stageLabel REALITY: $descriptor"
            }
            if (realityDescriptors.size > MAX_REALITY_OUTBOUND_LOGS) {
                notes += "$stageLabel REALITY: ещё ${realityDescriptors.size - MAX_REALITY_OUTBOUND_LOGS} outbound(ов)"
            }
        }

        val routing = root.optJSONObject("routing")
        val balancers = routing?.optJSONArray("balancers")
        if (balancers != null && balancers.length() > 0) {
            for (index in 0 until balancers.length()) {
                val balancer = balancers.optJSONObject(index) ?: continue
                val tag = balancer.optString("tag").trim().ifBlank { "<none>" }
                val selectors = extractBalancerSelectors(balancer)
                val strategyType = balancer.optJSONObject("strategy")
                    ?.optString("type")
                    ?.trim()
                    .orEmpty()
                    .ifBlank { "default" }
                val matchedTags = collectBalancerMatchedOutboundTags(root, selectors)
                notes += "$stageLabel balancer: tag=$tag selector=${selectors.joinToString(",")} " +
                    "strategy=$strategyType matchedOutbounds=${matchedTags.joinToString(",")}"
            }
        } else {
            notes += "$stageLabel: balancer не указан"
        }
    }

    private fun collectBalancerMatchedOutboundTags(
        root: JSONObject,
        selectors: List<String>
    ): List<String> {
        if (selectors.isEmpty()) return emptyList()

        val outbounds = root.optJSONArray("outbounds") ?: return emptyList()
        val matched = linkedSetOf<String>()
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val tag = outbound.optString("tag").trim()
            if (tag.isBlank()) continue
            if (selectors.any { selectorMatchesTag(selector = it, tag = tag) }) {
                matched += tag
            }
        }
        return matched.toList()
    }

    private fun buildRuntimeProxyShortIdTrace(root: JSONObject): String {
        val outbounds = root.optJSONArray("outbounds")
            ?: return "SHORTID TRACE stage=4 runtime-prepared proxy.shortId=absent reason=no-outbounds"
        val proxyOutbound = findProxyOutbound(outbounds)
            ?: return "SHORTID TRACE stage=4 runtime-prepared proxy.shortId=absent reason=no-proxy-outbound"

        val tag = proxyOutbound.optString("tag").trim().ifBlank { "<none>" }
        val streamSettings = proxyOutbound.optJSONObject("streamSettings")
        val security = streamSettings?.optString("security")?.trim()?.lowercase().orEmpty()
        val realitySettings = streamSettings?.optJSONObject("realitySettings")
        val shortId = realitySettings?.optString("shortId")?.trim().orEmpty()
        val state = if (shortId.isBlank()) "absent" else "present($shortId)"

        return "SHORTID TRACE stage=4 runtime-prepared outboundTag=$tag security=$security proxy.shortId=$state"
    }

    private fun findProxyOutbound(outbounds: JSONArray): JSONObject? {
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            if (outbound.optString("tag").equals("proxy", ignoreCase = true)) {
                return outbound
            }
        }
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            if (outbound.optString("protocol").equals("vless", ignoreCase = true)) {
                return outbound
            }
        }
        return outbounds.optJSONObject(0)
    }

    private fun applyLocalhostSocks(
        root: JSONObject,
        input: RuntimePreparationInput,
        notes: MutableList<String>
    ) {
        val inbounds = root.optJSONArray("inbounds") ?: JSONArray().also { root.put("inbounds", it) }
        val filtered = JSONArray()
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(index) ?: continue
            if (inbound.optString("tag") != PRIVATEVPN_SOCKS_TAG) {
                filtered.put(inbound)
            }
        }
        root.put("inbounds", filtered)

        val socks = input.socksSettings
        if (input.privateSessionEnabled) {
            notes += "localhost SOCKS отключён в режиме приватной сессии для минимизации локальных каналов обхода"
            return
        }

        if (!socks.enabled) {
            notes += "localhost SOCKS выключен в настройках"
            return
        }

        if (socks.port == input.dataPlaneProxy.port) {
            throw IllegalArgumentException(
                "Порт пользовательского localhost SOCKS конфликтует с внутренним data plane портом"
            )
        }

        require(socks.port in 1..65535) { "Порт localhost SOCKS должен быть в диапазоне 1..65535" }
        require(socks.login.isNotBlank() && socks.password.isNotBlank()) {
            "Для localhost SOCKS обязательны логин и пароль"
        }

        val socksInbound = JSONObject()
            .put("tag", PRIVATEVPN_SOCKS_TAG)
            .put("listen", "127.0.0.1")
            .put("port", socks.port)
            .put("protocol", "socks")
            .put(
                "settings",
                JSONObject()
                    .put("auth", "password")
                    .put(
                        "accounts",
                        JSONArray().put(
                            JSONObject()
                                .put("user", socks.login)
                                .put("pass", socks.password)
                        )
                    )
                    .put("udp", true)
            )

        filtered.put(socksInbound)
        notes += "localhost SOCKS добавлен в runtime: 127.0.0.1:${socks.port}, auth=password"
    }

    private fun sanitizeLegacyInbounds(
        root: JSONObject,
        notes: MutableList<String>
    ) {
        val inbounds = root.optJSONArray("inbounds") ?: JSONArray()
        if (inbounds.length() == 0) {
            root.put("inbounds", JSONArray())
            notes += "Legacy inbounds не обнаружены"
            return
        }

        val sanitized = JSONArray()
        val removedDescriptors = mutableListOf<String>()

        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(index) ?: continue
            val tag = inbound.optString("tag")
            val protocol = inbound.optString("protocol").ifBlank { "unknown" }
            val listen = inbound.optString("listen").ifBlank { "0.0.0.0" }
            val port = inbound.opt("port")?.toString() ?: "?"

            val keep = tag == PRIVATEVPN_DATAPLANE_SOCKS_TAG || tag == PRIVATEVPN_SOCKS_TAG
            if (keep) {
                sanitized.put(inbound)
            } else {
                removedDescriptors += "$protocol@$listen:$port tag=${tag.ifBlank { "<none>" }}"
            }
        }

        root.put("inbounds", sanitized)

        if (removedDescriptors.isEmpty()) {
            notes += "Legacy inbounds не обнаружены"
            return
        }

        notes += "Legacy inbounds удалены из импортированного профиля: ${removedDescriptors.size}"
        removedDescriptors.take(MAX_REMOVED_INBOUND_LOGS).forEach { descriptor ->
            notes += "Удалён inbound: $descriptor"
        }
        if (removedDescriptors.size > MAX_REMOVED_INBOUND_LOGS) {
            notes += "Удалено ещё ${removedDescriptors.size - MAX_REMOVED_INBOUND_LOGS} inbound(ов)"
        }
    }

    private fun ensureLogSection(root: JSONObject) {
        val log = root.optJSONObject("log") ?: JSONObject().also { root.put("log", it) }
        if (!log.has("loglevel")) {
            log.put("loglevel", "warning")
        }
    }

    private fun normalizeRealityKeyAliases(
        root: JSONObject,
        notes: MutableList<String>
    ) {
        val outbounds = root.optJSONArray("outbounds") ?: return
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val streamSettings = outbound.optJSONObject("streamSettings") ?: continue
            val security = streamSettings.optString("security").lowercase()
            if (security != "reality") continue

            val realitySettings = streamSettings.optJSONObject("realitySettings")
                ?: throw IllegalArgumentException("security=reality указан, но realitySettings отсутствует")
            val publicKey = realitySettings.firstNonBlankString("publicKey", "publickey", "pbk")
            val password = realitySettings.firstNonBlankString("password")

            when {
                password.isNotBlank() && publicKey.isBlank() -> {
                    realitySettings.put("publicKey", password)
                    notes += "REALITY: добавлен alias realitySettings.publicKey из password (совместимость ссылок VLESS)"
                }

                publicKey.isNotBlank() && realitySettings.optString("publicKey").isBlank() -> {
                    realitySettings.put("publicKey", publicKey)
                    notes += "REALITY: нормализован alias publicKey из альтернативного ключа"
                }
            }
            listOf("publickey", "pbk").forEach { alias ->
                if (realitySettings.has(alias)) realitySettings.remove(alias)
            }

            normalizeRealityStringAlias(
                settings = realitySettings,
                canonicalKey = "shortId",
                aliases = listOf("shortid", "sid"),
                arrayAliases = listOf("shortIds", "shortids"),
                notes = notes
            )
            normalizeRealityStringAlias(
                settings = realitySettings,
                canonicalKey = "serverName",
                aliases = listOf("servername", "server_name", "sni"),
                arrayAliases = listOf("serverNames", "servernames"),
                notes = notes
            )
            normalizeRealityStringAlias(
                settings = realitySettings,
                canonicalKey = "fingerprint",
                aliases = listOf("fp"),
                arrayAliases = emptyList(),
                notes = notes
            )
            normalizeRealityStringAlias(
                settings = realitySettings,
                canonicalKey = "spiderX",
                aliases = listOf("spiderx", "spider_x", "spx"),
                arrayAliases = emptyList(),
                notes = notes,
                preserveEmptyValue = true
            )
            if (realitySettings.optString("shortId").isBlank() && !realitySettings.has("shortId")) {
                realitySettings.put("shortId", "")
                notes += "REALITY: shortId отсутствует; установлен пустой shortId для совместимости с серверами, где разрешён empty shortId"
            }
            if (realitySettings.optString("serverName").isBlank()) {
                outbound.extractFirstVnextAddress()?.let { address ->
                    realitySettings.put("serverName", address)
                    notes += "REALITY: serverName отсутствует; использован address '$address'"
                }
            }
            if (realitySettings.optString("fingerprint").isBlank()) {
                realitySettings.put("fingerprint", "chrome")
                notes += "REALITY: fingerprint отсутствует; применён default 'chrome'"
            }
            if (!realitySettings.has("spiderX")) {
                realitySettings.put("spiderX", "")
                notes += "REALITY: spiderX отсутствует; применено пустое значение по умолчанию"
            }

            if (realitySettings.has("password")) {
                realitySettings.remove("password")
                notes += "REALITY: удалён compatibility-ключ realitySettings.password; в runtime оставлен canonical publicKey"
            }
        }
    }

    private fun normalizeRealityStringAlias(
        settings: JSONObject,
        canonicalKey: String,
        aliases: List<String>,
        arrayAliases: List<String>,
        notes: MutableList<String>,
        preserveEmptyValue: Boolean = false
    ) {
        val existing = settings.optString(canonicalKey)
        if (existing.isBlank() && !(preserveEmptyValue && settings.has(canonicalKey))) {
            var normalized = false

            for (alias in aliases) {
                val value = settings.optString(alias)
                if (value.isNotBlank() || (preserveEmptyValue && settings.has(alias) && value.isEmpty())) {
                    settings.put(canonicalKey, value)
                    notes += "REALITY: нормализован alias realitySettings.$canonicalKey из $alias"
                    normalized = true
                    break
                }
            }

            if (!normalized) {
                for (alias in arrayAliases) {
                    val values = settings.optJSONArray(alias) ?: continue
                    val value = (0 until values.length())
                        .asSequence()
                        .map { values.optString(it).trim() }
                        .firstOrNull { it.isNotBlank() }
                    if (!value.isNullOrBlank()) {
                        settings.put(canonicalKey, value)
                        notes += "REALITY: нормализован alias realitySettings.$canonicalKey из $alias[0]"
                        break
                    }
                }
            }
        }

        (aliases + arrayAliases).forEach { alias ->
            if (settings.has(alias)) settings.remove(alias)
        }
    }

    private fun validateAndDescribeOutbound(
        root: JSONObject,
        profileType: String,
        notes: MutableList<String>
    ) {
        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("В runtime конфиге отсутствует секция outbounds")
        require(outbounds.length() > 0) { "В runtime конфиге нет ни одного outbound" }

        val validationOutbounds = resolveValidationOutbounds(root = root, outbounds = outbounds)
        require(validationOutbounds.isNotEmpty()) { "Не найден outbound для self-check" }
        notes += "Self-check candidates: count=${validationOutbounds.size}, " +
            "tags=${validationOutbounds.map { it.optString("tag").ifBlank { "<none>" } }.joinToString(",")}"

        val expectsVless = profileType.equals("VLESS", ignoreCase = true) ||
            profileType.equals("XRAY_VLESS_REALITY", ignoreCase = true)

        var validatedVless = 0
        for (index in validationOutbounds.indices) {
            val outbound = validationOutbounds[index]
            val isVless = validateAndDescribeSingleOutbound(
                outbound = outbound,
                position = index + 1,
                total = validationOutbounds.size,
                expectsVless = expectsVless,
                notes = notes
            )
            if (isVless) {
                validatedVless += 1
            }
        }

        if (expectsVless && validatedVless == 0) {
            throw IllegalArgumentException("Self-check runtime: не найден VLESS outbound для профиля $profileType")
        }
    }

    private fun resolveValidationOutbounds(root: JSONObject, outbounds: JSONArray): List<JSONObject> {
        val routing = root.optJSONObject("routing")
        val balancers = routing?.optJSONArray("balancers")
        val resolved = linkedSetOf<JSONObject>()

        if (balancers != null && balancers.length() > 0) {
            for (index in 0 until balancers.length()) {
                val balancer = balancers.optJSONObject(index) ?: continue
                val selectors = extractBalancerSelectors(balancer)
                if (selectors.isEmpty()) continue
                for (outIndex in 0 until outbounds.length()) {
                    val outbound = outbounds.optJSONObject(outIndex) ?: continue
                    val tag = outbound.optString("tag").trim()
                    if (tag.isBlank()) continue
                    if (selectors.any { selectorMatchesTag(selector = it, tag = tag) }) {
                        resolved += outbound
                    }
                }
            }
        }

        if (resolved.isNotEmpty()) {
            return resolved.toList()
        }

        val firstVless = findFirstVlessOutbound(outbounds)
        if (firstVless != null) {
            return listOf(firstVless)
        }
        return listOfNotNull(outbounds.optJSONObject(0))
    }

    private fun validateAndDescribeSingleOutbound(
        outbound: JSONObject,
        position: Int,
        total: Int,
        expectsVless: Boolean,
        notes: MutableList<String>
    ): Boolean {
        val tag = outbound.optString("tag").trim().ifBlank { "<none>" }
        val protocol = outbound.optString("protocol").trim()
        val streamSettings = outbound.optJSONObject("streamSettings")
        val network = streamSettings?.optString("network")?.trim()
            .takeUnless { it.isNullOrBlank() }
            ?: "raw"
        val security = streamSettings?.optString("security")?.trim()?.lowercase().orEmpty()
        val realitySettings = streamSettings?.optJSONObject("realitySettings")
        val flow = outbound.extractFlow()

        notes += "Self-check outbound[$position/$total tag=$tag]: " +
            "protocol=${protocol.ifBlank { "не задан" }}, network=${network.ifBlank { "не задан" }}, " +
            "security=${security.ifBlank { "не задан" }}, flow=${flow ?: "не задан"}, " +
            "realitySettings=${if (realitySettings != null) "есть" else "нет"}"

        if (realitySettings != null) {
            val presence = listOf(
                "publicKey" to realitySettings.optString("publicKey").isNotBlank(),
                "password" to realitySettings.optString("password").isNotBlank(),
                "serverName" to realitySettings.optString("serverName").isNotBlank(),
                "fingerprint" to realitySettings.optString("fingerprint").isNotBlank(),
                "shortId" to realitySettings.optString("shortId").isNotBlank(),
                "spiderX" to realitySettings.has("spiderX")
            )
            notes += "Self-check REALITY[$tag]: " + presence.joinToString { "${it.first}=${it.second}" }

            val spiderXState = describeJsonStringField(realitySettings, "spiderX")
            notes += "Self-check REALITY[$tag] spiderX: состояние=${spiderXState.state}, " +
                "ключ=${if (spiderXState.present) "есть" else "нет"}, значение='${spiderXState.valueForLog}'"
        }

        val isVless = protocol.equals("vless", ignoreCase = true)
        if (expectsVless && !isVless) {
            throw IllegalArgumentException(
                "Self-check runtime: профиль VLESS, но outbound '$tag' имеет protocol='${protocol.ifBlank { "не задан" }}'"
            )
        }

        if (!isVless || security != "reality") {
            return isVless
        }

        if (realitySettings == null) {
            throw IllegalArgumentException(
                "Self-check runtime: VLESS+REALITY outbound '$tag' неполный, отсутствует streamSettings.realitySettings"
            )
        }
        val softWarnings = mutableListOf<String>()
        if (realitySettings.optString("publicKey").isBlank() &&
            realitySettings.optString("password").isBlank()
        ) {
            softWarnings += "realitySettings.publicKey/password"
        }
        if (realitySettings.optString("serverName").isBlank()) softWarnings += "realitySettings.serverName"
        if (realitySettings.optString("fingerprint").isBlank()) softWarnings += "realitySettings.fingerprint"
        if (!realitySettings.has("shortId")) softWarnings += "realitySettings.shortId"
        if (flow.isNullOrBlank()) softWarnings += "settings.vnext[0].users[0].flow"
        if (softWarnings.isNotEmpty()) {
            notes += "Self-check REALITY[$tag]: мягкая совместимость, не блокируем запуск; поля требуют проверки Xray: " +
                softWarnings.joinToString()
        }
        return true
    }

    private fun findFirstVlessOutbound(outbounds: JSONArray): JSONObject? {
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            if (outbound.optString("protocol").equals("vless", ignoreCase = true)) {
                return outbound
            }
        }
        return null
    }

    private fun extractBalancerSelectors(balancer: JSONObject): List<String> {
        val selectorArray = balancer.optJSONArray("selector") ?: return emptyList()
        val selectors = mutableListOf<String>()
        for (index in 0 until selectorArray.length()) {
            val selector = selectorArray.optString(index).trim()
            if (selector.isNotBlank()) selectors += selector
        }
        return selectors
    }

    private fun selectorMatchesTag(selector: String, tag: String): Boolean {
        if (selector.equals(tag, ignoreCase = true)) return true
        return tag.startsWith("$selector-", ignoreCase = true)
    }

    private fun JSONObject.extractFlow(): String? {
        val settings = optJSONObject("settings") ?: return null
        val vnext = settings.optJSONArray("vnext") ?: return null
        if (vnext.length() == 0) return null
        val firstVnext = vnext.optJSONObject(0) ?: return null
        val users = firstVnext.optJSONArray("users") ?: return null
        if (users.length() == 0) return null
        val firstUser = users.optJSONObject(0) ?: return null
        return firstUser.optString("flow").trim().takeIf { it.isNotBlank() }
    }

    private fun JSONObject.extractFirstVnextAddress(): String? {
        val settings = optJSONObject("settings") ?: return null
        val vnext = settings.optJSONArray("vnext") ?: return null
        if (vnext.length() == 0) return null
        val firstVnext = vnext.optJSONObject(0) ?: return null
        return firstVnext.optString("address").trim().takeIf { it.isNotBlank() }
    }

    private fun JSONObject.firstNonBlankString(vararg keys: String): String {
        keys.forEach { key ->
            val value = optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class JsonStringFieldState(
        val present: Boolean,
        val state: String,
        val valueForLog: String
    )

    private enum class RouteTargetKind {
        OUTBOUND,
        BALANCER
    }

    private data class RouteTarget(
        val kind: RouteTargetKind,
        val tag: String
    )

    private data class DnsNormalizationResult(
        val servers: List<String>,
        val notes: List<String>
    )

    private fun describeJsonStringField(json: JSONObject, key: String): JsonStringFieldState {
        if (!json.has(key)) {
            return JsonStringFieldState(
                present = false,
                state = "missing",
                valueForLog = "<missing>"
            )
        }
        if (json.isNull(key)) {
            return JsonStringFieldState(
                present = true,
                state = "null",
                valueForLog = "<null>"
            )
        }

        val raw = json.opt(key)
        if (raw !is String) {
            return JsonStringFieldState(
                present = true,
                state = "non-string",
                valueForLog = java.lang.String.valueOf(raw).take(120)
            )
        }

        if (raw.isEmpty()) {
            return JsonStringFieldState(
                present = true,
                state = "empty-string",
                valueForLog = ""
            )
        }

        return JsonStringFieldState(
            present = true,
            state = "value",
            valueForLog = raw.take(120)
        )
    }

    private companion object {
        const val DEFAULT_PRIMARY_OUTBOUND_TAG = "privatevpn-primary-outbound"
        const val PRIVATEVPN_DATAPLANE_SOCKS_TAG = "privatevpn-dataplane-socks"
        const val PRIVATEVPN_SOCKS_TAG = "privatevpn-local-socks"
        const val MAX_REMOVED_INBOUND_LOGS = 8
        const val MAX_OUTBOUND_TAG_LOGS = 24
        const val MAX_REALITY_OUTBOUND_LOGS = 20
    }
}
