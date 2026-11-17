package com.privatevpn.app.profiles.model

import org.json.JSONArray
import org.json.JSONObject

data class SubscriptionSyncDiagnostics(
    val httpStatusCode: Int? = null,
    val contentType: String? = null,
    val responseBodyLength: Int? = null,
    val detectedFormat: String? = null,
    val compatibilityMode: String? = null,
    val selectedResponseSource: String? = null,
    val selectedStatus: Int? = null,
    val selectedConnectableCount: Int? = null,
    val selectedMarkerCount: Int? = null,
    val selectedBodySignature: String? = null,
    val selectedRawLineCount: Int? = null,
    val requestEndpoint: String? = null,
    val endpointVariant: String? = null,
    val usedClientType: String? = null,
    val hwidActive: Boolean? = null,
    val hwidNotSupported: Boolean? = null,
    val retryWithHwid: Boolean? = null,
    val discoveredEntries: Int? = null,
    val parsedProfiles: Int? = null,
    val savedProfiles: Int? = null,
    val invalidEntries: Int? = null,
    val markerEntries: Int? = null,
    val connectableEntries: Int? = null,
    val insertedProfiles: Int? = null,
    val updatedProfiles: Int? = null,
    val filteredOutProfiles: Int? = null,
    val duplicateCount: Int? = null,
    val headersSummary: String? = null,
    val metadataHeadersReceived: String? = null,
    val metadataExtracted: String? = null,
    val metadataIgnored: String? = null,
    val payloadPreview: String? = null,
    val shortError: String? = null
)

data class SubscriptionMetadataPayload(
    val displayTitle: String? = null,
    val providerName: String? = null,
    val providerDomain: String? = null,
    val providerSite: String? = null,
    val providerSiteUrl: String? = null,
    val supportUrl: String? = null,
    val profileWebPageUrl: String? = null,
    val announcementText: String? = null,
    val planId: String? = null,
    val userId: String? = null,
    val note: String? = null,
    val badgeText: String? = null,
    val trafficUploadBytes: Long? = null,
    val trafficDownloadBytes: Long? = null,
    val trafficTotalBytes: Long? = null,
    val trafficRemainingBytes: Long? = null,
    val expireAt: Long? = null,
    val expireText: String? = null,
    val serverCount: Int? = null,
    val logoUrl: String? = null,
    val logoHint: String? = null,
    val tags: List<String> = emptyList(),
    val labelLine: String? = null,
    val providerMessage: String? = null,
    val clientMode: String? = null,
    val platformHints: List<String> = emptyList(),
    val diagnostics: SubscriptionSyncDiagnostics? = null,
    val clientPolicy: SubscriptionClientPolicy? = null
) {
    val resolvedProviderSiteUrl: String?
        get() = providerSiteUrl ?: providerSite

    val resolvedProviderDomain: String?
        get() = providerDomain ?: providerName

    val usedTrafficBytes: Long?
        get() = when {
            trafficUploadBytes == null && trafficDownloadBytes == null -> null
            else -> (trafficUploadBytes ?: 0L) + (trafficDownloadBytes ?: 0L)
        }

    val resolvedTrafficRemainingBytes: Long?
        get() = trafficRemainingBytes ?: run {
            val total = trafficTotalBytes ?: return@run null
            val used = usedTrafficBytes ?: return@run null
            (total - used).coerceAtLeast(0L)
        }

    fun preferredExternalUrl(): String? {
        return resolvedProviderSiteUrl ?: profileWebPageUrl ?: supportUrl
    }
}

object SubscriptionMetadataCodec {
    private const val KEY_TITLE = "title"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_PROVIDER_DOMAIN = "providerDomain"
    private const val KEY_PROVIDER_SITE = "providerSite"
    private const val KEY_PROVIDER_SITE_URL = "providerSiteUrl"
    private const val KEY_SUPPORT_URL = "supportUrl"
    private const val KEY_PROFILE_WEB_PAGE_URL = "profileWebPageUrl"
    private const val KEY_ANNOUNCEMENT = "announcementText"
    private const val KEY_PLAN_ID = "planId"
    private const val KEY_USER_ID = "userId"
    private const val KEY_NOTE = "note"
    private const val KEY_BADGE = "badgeText"
    private const val KEY_TRAFFIC_UPLOAD = "trafficUploadBytes"
    private const val KEY_TRAFFIC_DOWNLOAD = "trafficDownloadBytes"
    private const val KEY_TRAFFIC_TOTAL = "trafficTotalBytes"
    private const val KEY_TRAFFIC_REMAINING = "trafficRemainingBytes"
    private const val KEY_EXPIRE_AT = "expireAt"
    private const val KEY_EXPIRE_TEXT = "expireText"
    private const val KEY_SERVER_COUNT = "serverCount"
    private const val KEY_LOGO_URL = "logoUrl"
    private const val KEY_LOGO_HINT = "logoHint"
    private const val KEY_TAGS = "tags"
    private const val KEY_LABEL_LINE = "labelLine"
    private const val KEY_PROVIDER_MESSAGE = "providerMessage"
    private const val KEY_CLIENT_MODE = "clientMode"
    private const val KEY_PLATFORMS = "platformHints"
    private const val KEY_DIAGNOSTICS = "diagnostics"

    fun decode(raw: String?): SubscriptionMetadataPayload {
        if (raw.isNullOrBlank()) return SubscriptionMetadataPayload()
        val text = raw.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return SubscriptionMetadataPayload(providerName = text)
        }

        return runCatching {
            val root = JSONObject(text)
            val diagnosticsJson = root.optJSONObject(KEY_DIAGNOSTICS)
            val providerSiteLegacy = root.optString(KEY_PROVIDER_SITE).trim().takeIf { it.isNotBlank() }
            val providerSiteUrl = root.optString(KEY_PROVIDER_SITE_URL).trim().takeIf { it.isNotBlank() }
                ?: providerSiteLegacy
            SubscriptionMetadataPayload(
                displayTitle = root.optString(KEY_TITLE).trim().takeIf { it.isNotBlank() },
                providerName = root.optString(KEY_PROVIDER).trim().takeIf { it.isNotBlank() },
                providerDomain = root.optString(KEY_PROVIDER_DOMAIN).trim().takeIf { it.isNotBlank() },
                providerSite = providerSiteLegacy ?: providerSiteUrl,
                providerSiteUrl = providerSiteUrl,
                supportUrl = root.optString(KEY_SUPPORT_URL).trim().takeIf { it.isNotBlank() },
                profileWebPageUrl = root.optString(KEY_PROFILE_WEB_PAGE_URL).trim().takeIf { it.isNotBlank() },
                announcementText = root.optString(KEY_ANNOUNCEMENT).trim().takeIf { it.isNotBlank() },
                planId = root.optString(KEY_PLAN_ID).trim().takeIf { it.isNotBlank() },
                userId = root.optString(KEY_USER_ID).trim().takeIf { it.isNotBlank() },
                note = root.optString(KEY_NOTE).trim().takeIf { it.isNotBlank() },
                badgeText = root.optString(KEY_BADGE).trim().takeIf { it.isNotBlank() },
                trafficUploadBytes = root.optLongOrNull(KEY_TRAFFIC_UPLOAD),
                trafficDownloadBytes = root.optLongOrNull(KEY_TRAFFIC_DOWNLOAD),
                trafficTotalBytes = root.optLongOrNull(KEY_TRAFFIC_TOTAL),
                trafficRemainingBytes = root.optLongOrNull(KEY_TRAFFIC_REMAINING),
                expireAt = root.optLongOrNull(KEY_EXPIRE_AT),
                expireText = root.optString(KEY_EXPIRE_TEXT).trim().takeIf { it.isNotBlank() },
                serverCount = root.optIntOrNull(KEY_SERVER_COUNT),
                logoUrl = root.optString(KEY_LOGO_URL).trim().takeIf { it.isNotBlank() },
                logoHint = root.optString(KEY_LOGO_HINT).trim().takeIf { it.isNotBlank() },
                tags = root.optStringArray(KEY_TAGS),
                labelLine = root.optString(KEY_LABEL_LINE).trim().takeIf { it.isNotBlank() },
                providerMessage = root.optString(KEY_PROVIDER_MESSAGE).trim().takeIf { it.isNotBlank() },
                clientMode = root.optString(KEY_CLIENT_MODE).trim().takeIf { it.isNotBlank() },
                platformHints = root.optStringArray(KEY_PLATFORMS),
                diagnostics = diagnosticsJson?.toDiagnostics(),
                clientPolicy = root.optJSONObject("clientPolicy")?.let { policy ->
                    SubscriptionClientPolicy(
                        `protected` = policy.optBooleanOrNull("protected"),
                        publicLabel = policy.opt("publicLabel") as? String,
                        issuedAtMs = policy.optLongOrNull("issuedAtMs"),
                        revision = policy.optLongOrNull("revision"),
                        expiresAtMs = policy.optLongOrNull("expiresAtMs") ?: policy.optLongOrNull("noticeExpiresAtMs")
                    )
                }
            )
        }.getOrElse {
            SubscriptionMetadataPayload(providerName = text)
        }
    }

    fun encode(payload: SubscriptionMetadataPayload): String? {
        if (isEmpty(payload)) return null

        return JSONObject().apply {
            payload.displayTitle?.let { putNonBlank(KEY_TITLE, it) }
            payload.providerName?.let { putNonBlank(KEY_PROVIDER, it) }
            payload.providerDomain?.let { putNonBlank(KEY_PROVIDER_DOMAIN, it) }
            payload.providerSite?.let { putNonBlank(KEY_PROVIDER_SITE, it) }
            payload.resolvedProviderSiteUrl?.let { putNonBlank(KEY_PROVIDER_SITE_URL, it) }
            payload.supportUrl?.let { putNonBlank(KEY_SUPPORT_URL, it) }
            payload.profileWebPageUrl?.let { putNonBlank(KEY_PROFILE_WEB_PAGE_URL, it) }
            payload.announcementText?.let { putNonBlank(KEY_ANNOUNCEMENT, it) }
            payload.planId?.let { putNonBlank(KEY_PLAN_ID, it) }
            payload.userId?.let { putNonBlank(KEY_USER_ID, it) }
            payload.note?.let { putNonBlank(KEY_NOTE, it) }
            payload.badgeText?.let { putNonBlank(KEY_BADGE, it) }
            payload.trafficUploadBytes?.let { put(KEY_TRAFFIC_UPLOAD, it) }
            payload.trafficDownloadBytes?.let { put(KEY_TRAFFIC_DOWNLOAD, it) }
            payload.trafficTotalBytes?.let { put(KEY_TRAFFIC_TOTAL, it) }
            payload.trafficRemainingBytes?.let { put(KEY_TRAFFIC_REMAINING, it) }
            payload.expireAt?.let { put(KEY_EXPIRE_AT, it) }
            payload.expireText?.let { putNonBlank(KEY_EXPIRE_TEXT, it) }
            payload.serverCount?.let { put(KEY_SERVER_COUNT, it) }
            payload.logoUrl?.let { putNonBlank(KEY_LOGO_URL, it) }
            payload.logoHint?.let { putNonBlank(KEY_LOGO_HINT, it) }
            if (payload.tags.isNotEmpty()) put(KEY_TAGS, JSONArray(payload.tags))
            payload.labelLine?.let { putNonBlank(KEY_LABEL_LINE, it) }
            payload.providerMessage?.let { putNonBlank(KEY_PROVIDER_MESSAGE, it) }
            payload.clientMode?.let { putNonBlank(KEY_CLIENT_MODE, it) }
            if (payload.platformHints.isNotEmpty()) put(KEY_PLATFORMS, JSONArray(payload.platformHints))
            payload.diagnostics?.let { put(KEY_DIAGNOSTICS, it.toJson()) }
            payload.clientPolicy?.let { policy ->
                put("clientPolicy", JSONObject().apply {
                    policy.`protected`?.let { put("protected", it) }
                    policy.publicLabel?.let { put("publicLabel", it) }
                    policy.issuedAtMs?.let { put("issuedAtMs", it) }
                    policy.revision?.let { put("revision", it) }
                    policy.expiresAtMs?.let { put("expiresAtMs", it) }
                })
            }
        }.toString()
    }

    fun encode(
        displayTitle: String?,
        providerName: String?,
        providerSite: String?,
        clientMode: String?,
        platformHints: List<String>,
        diagnostics: SubscriptionSyncDiagnostics?,
        providerDomain: String? = null,
        providerSiteUrl: String? = null,
        supportUrl: String? = null,
        profileWebPageUrl: String? = null,
        announcementText: String? = null,
        planId: String? = null,
        userId: String? = null,
        note: String? = null,
        badgeText: String? = null,
        trafficUploadBytes: Long? = null,
        trafficDownloadBytes: Long? = null,
        trafficTotalBytes: Long? = null,
        trafficRemainingBytes: Long? = null,
        expireAt: Long? = null,
        expireText: String? = null,
        serverCount: Int? = null,
        logoUrl: String? = null,
        logoHint: String? = null,
        tags: List<String> = emptyList(),
        labelLine: String? = null,
        providerMessage: String? = null,
        clientPolicy: SubscriptionClientPolicy? = null
    ): String? {
        return encode(
            SubscriptionMetadataPayload(
                displayTitle = displayTitle,
                providerName = providerName,
                providerDomain = providerDomain,
                providerSite = providerSite,
                providerSiteUrl = providerSiteUrl ?: providerSite,
                supportUrl = supportUrl,
                profileWebPageUrl = profileWebPageUrl,
                announcementText = announcementText,
                planId = planId,
                userId = userId,
                note = note,
                badgeText = badgeText,
                trafficUploadBytes = trafficUploadBytes,
                trafficDownloadBytes = trafficDownloadBytes,
                trafficTotalBytes = trafficTotalBytes,
                trafficRemainingBytes = trafficRemainingBytes,
                expireAt = expireAt,
                expireText = expireText,
                serverCount = serverCount,
                logoUrl = logoUrl,
                logoHint = logoHint,
                tags = tags,
                labelLine = labelLine,
                providerMessage = providerMessage,
                clientPolicy = clientPolicy,
                clientMode = clientMode,
                platformHints = platformHints,
                diagnostics = diagnostics
            )
        )
    }

    private fun isEmpty(payload: SubscriptionMetadataPayload): Boolean {
        return payload.displayTitle.isNullOrBlank() &&
            payload.providerName.isNullOrBlank() &&
            payload.providerDomain.isNullOrBlank() &&
            payload.providerSite.isNullOrBlank() &&
            payload.providerSiteUrl.isNullOrBlank() &&
            payload.supportUrl.isNullOrBlank() &&
            payload.profileWebPageUrl.isNullOrBlank() &&
            payload.announcementText.isNullOrBlank() &&
            payload.planId.isNullOrBlank() &&
            payload.userId.isNullOrBlank() &&
            payload.note.isNullOrBlank() &&
            payload.badgeText.isNullOrBlank() &&
            payload.trafficUploadBytes == null &&
            payload.trafficDownloadBytes == null &&
            payload.trafficTotalBytes == null &&
            payload.trafficRemainingBytes == null &&
            payload.expireAt == null &&
            payload.expireText.isNullOrBlank() &&
            payload.serverCount == null &&
            payload.logoUrl.isNullOrBlank() &&
            payload.logoHint.isNullOrBlank() &&
            payload.tags.isEmpty() &&
            payload.labelLine.isNullOrBlank() &&
            payload.providerMessage.isNullOrBlank() &&
            payload.clientMode.isNullOrBlank() &&
            payload.platformHints.isEmpty() &&
            payload.diagnostics == null && payload.clientPolicy == null
    }

    private fun SubscriptionSyncDiagnostics.toJson(): JSONObject {
        return JSONObject().apply {
            putIfNotNull("httpStatusCode", httpStatusCode)
            putIfNotNull("contentType", contentType)
            putIfNotNull("responseBodyLength", responseBodyLength)
            putIfNotNull("detectedFormat", detectedFormat)
            putIfNotNull("compatibilityMode", compatibilityMode)
            putIfNotNull("selectedResponseSource", selectedResponseSource)
            putIfNotNull("selectedStatus", selectedStatus)
            putIfNotNull("selectedConnectableCount", selectedConnectableCount)
            putIfNotNull("selectedMarkerCount", selectedMarkerCount)
            putIfNotNull("selectedBodySignature", selectedBodySignature)
            putIfNotNull("selectedRawLineCount", selectedRawLineCount)
            putIfNotNull("requestEndpoint", requestEndpoint)
            putIfNotNull("endpointVariant", endpointVariant)
            putIfNotNull("usedClientType", usedClientType)
            putIfNotNull("hwidActive", hwidActive)
            putIfNotNull("hwidNotSupported", hwidNotSupported)
            putIfNotNull("retryWithHwid", retryWithHwid)
            putIfNotNull("discoveredEntries", discoveredEntries)
            putIfNotNull("parsedProfiles", parsedProfiles)
            putIfNotNull("savedProfiles", savedProfiles)
            putIfNotNull("invalidEntries", invalidEntries)
            putIfNotNull("markerEntries", markerEntries)
            putIfNotNull("connectableEntries", connectableEntries)
            putIfNotNull("insertedProfiles", insertedProfiles)
            putIfNotNull("updatedProfiles", updatedProfiles)
            putIfNotNull("filteredOutProfiles", filteredOutProfiles)
            putIfNotNull("duplicateCount", duplicateCount)
            putIfNotNull("headersSummary", headersSummary)
            putIfNotNull("metadataHeadersReceived", metadataHeadersReceived)
            putIfNotNull("metadataExtracted", metadataExtracted)
            putIfNotNull("metadataIgnored", metadataIgnored)
            putIfNotNull("payloadPreview", payloadPreview)
            putIfNotNull("shortError", shortError)
        }
    }

    private fun JSONObject.toDiagnostics(): SubscriptionSyncDiagnostics {
        return SubscriptionSyncDiagnostics(
            httpStatusCode = optIntOrNull("httpStatusCode"),
            contentType = optString("contentType").trim().takeIf { it.isNotBlank() },
            responseBodyLength = optIntOrNull("responseBodyLength"),
            detectedFormat = optString("detectedFormat").trim().takeIf { it.isNotBlank() },
            compatibilityMode = optString("compatibilityMode").trim().takeIf { it.isNotBlank() },
            selectedResponseSource = optString("selectedResponseSource").trim().takeIf { it.isNotBlank() },
            selectedStatus = optIntOrNull("selectedStatus"),
            selectedConnectableCount = optIntOrNull("selectedConnectableCount"),
            selectedMarkerCount = optIntOrNull("selectedMarkerCount"),
            selectedBodySignature = optString("selectedBodySignature").trim().takeIf { it.isNotBlank() },
            selectedRawLineCount = optIntOrNull("selectedRawLineCount"),
            requestEndpoint = optString("requestEndpoint").trim().takeIf { it.isNotBlank() },
            endpointVariant = optString("endpointVariant").trim().takeIf { it.isNotBlank() },
            usedClientType = optString("usedClientType").trim().takeIf { it.isNotBlank() },
            hwidActive = optBooleanOrNull("hwidActive"),
            hwidNotSupported = optBooleanOrNull("hwidNotSupported"),
            retryWithHwid = optBooleanOrNull("retryWithHwid"),
            discoveredEntries = optIntOrNull("discoveredEntries"),
            parsedProfiles = optIntOrNull("parsedProfiles"),
            savedProfiles = optIntOrNull("savedProfiles"),
            invalidEntries = optIntOrNull("invalidEntries"),
            markerEntries = optIntOrNull("markerEntries"),
            connectableEntries = optIntOrNull("connectableEntries"),
            insertedProfiles = optIntOrNull("insertedProfiles"),
            updatedProfiles = optIntOrNull("updatedProfiles"),
            filteredOutProfiles = optIntOrNull("filteredOutProfiles"),
            duplicateCount = optIntOrNull("duplicateCount"),
            headersSummary = optString("headersSummary").trim().takeIf { it.isNotBlank() },
            metadataHeadersReceived = optString("metadataHeadersReceived").trim().takeIf { it.isNotBlank() },
            metadataExtracted = optString("metadataExtracted").trim().takeIf { it.isNotBlank() },
            metadataIgnored = optString("metadataIgnored").trim().takeIf { it.isNotBlank() },
            payloadPreview = optString("payloadPreview").trim().takeIf { it.isNotBlank() },
            shortError = optString("shortError").trim().takeIf { it.isNotBlank() }
        )
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Boolean -> value
            is String -> value.trim().lowercase().let { lowered ->
                when (lowered) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> null
                }
            }

            else -> null
        }
    }

    private fun JSONObject.optStringArray(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        val result = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotBlank()) result += value
        }
        return result
    }

    private fun JSONObject.putIfNotNull(key: String, value: Any?) {
        if (value == null) return
        when (value) {
            is String -> if (value.isNotBlank()) put(key, value)
            else -> put(key, value)
        }
    }

    private fun JSONObject.putNonBlank(key: String, value: String) {
        val normalized = value.trim()
        if (normalized.isNotBlank()) put(key, normalized)
    }
}
