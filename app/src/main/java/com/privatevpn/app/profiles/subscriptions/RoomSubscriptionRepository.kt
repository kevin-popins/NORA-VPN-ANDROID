package com.privatevpn.app.profiles.subscriptions

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.room.withTransaction
import com.privatevpn.app.profiles.db.PrivateVpnDatabase
import com.privatevpn.app.profiles.db.ProfileDao
import com.privatevpn.app.profiles.db.SubscriptionSourceDao
import com.privatevpn.app.profiles.importer.ProfileImportParser
import com.privatevpn.app.profiles.model.SubscriptionMetadataCodec
import com.privatevpn.app.profiles.model.SubscriptionSource
import com.privatevpn.app.profiles.model.SubscriptionSourceType
import com.privatevpn.app.profiles.model.SubscriptionSyncDiagnostics
import com.privatevpn.app.profiles.model.SubscriptionSyncStatus
import com.privatevpn.app.profiles.model.VpnProfile
import com.privatevpn.app.profiles.model.ImportedProfileDraft
import com.privatevpn.app.profiles.repository.toDomain
import com.privatevpn.app.profiles.repository.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

class RoomSubscriptionRepository(
    private val database: PrivateVpnDatabase,
    private val appContext: Context? = null,
    private val profileDao: ProfileDao = database.profileDao(),
    private val subscriptionSourceDao: SubscriptionSourceDao = database.subscriptionSourceDao(),
    private val parser: SubscriptionParser = SubscriptionParser(ProfileImportParser())
) : SubscriptionRepository {

    override val subscriptions: Flow<List<SubscriptionSource>> =
        subscriptionSourceDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun addSubscription(sourceUrl: String, displayName: String?): SubscriptionSource {
        val normalizedUrl = HappCrypt5Decryptor.decryptIfNeeded(sourceUrl)
        require(normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")) {
            "URL подписки должен начинаться с http:// или https://"
        }
        val providerName = extractProviderName(normalizedUrl)
        val now = System.currentTimeMillis()
        val source = SubscriptionSource(
            id = UUID.randomUUID().toString(),
            displayName = displayName?.trim()?.takeIf { it.isNotBlank() } ?: normalizedUrl,
            sourceUrl = normalizedUrl,
            sourceType = SubscriptionSourceType.HTTP_TEXT,
            enabled = true,
            autoUpdateEnabled = true,
            updateIntervalMinutes = DEFAULT_UPDATE_INTERVAL_MINUTES,
            lastUpdatedAtMs = null,
            lastSuccessAtMs = null,
            lastError = null,
            isCollapsed = false,
            profileCount = 0,
            etag = null,
            lastModified = null,
            metadata = SubscriptionMetadataCodec.encode(
                displayTitle = displayName?.trim()?.takeIf { it.isNotBlank() },
                providerName = providerName,
                providerSite = null,
                clientMode = SubscriptionClientMode.AUTO.wireValue,
                platformHints = emptyList(),
                diagnostics = null
            ),
            childProfileIds = emptyList(),
            lastSelectedProfileId = null,
            syncStatus = SubscriptionSyncStatus.EMPTY,
            createdAtMs = now,
            updatedAtMs = now
        )
        subscriptionSourceDao.upsert(source.toEntity())
        return source
    }

    override suspend fun deleteSubscription(subscriptionId: String) {
        database.withTransaction {
            profileDao.deleteBySubscriptionId(subscriptionId)
            subscriptionSourceDao.deleteById(subscriptionId)
        }
    }

    override suspend fun renameSubscription(subscriptionId: String, displayName: String) {
        val current = subscriptionSourceDao.findById(subscriptionId)?.toDomain() ?: return
        val normalizedName = displayName.trim()
        if (normalizedName.isBlank()) return
        subscriptionSourceDao.upsert(
            current.copy(
                displayName = normalizedName,
                updatedAtMs = System.currentTimeMillis()
            ).toEntity()
        )
    }

    override suspend fun toggleCollapse(subscriptionId: String) {
        val current = subscriptionSourceDao.findById(subscriptionId)?.toDomain() ?: return
        subscriptionSourceDao.upsert(
            current.copy(
                isCollapsed = !current.isCollapsed,
                updatedAtMs = System.currentTimeMillis()
            ).toEntity()
        )
    }

    override suspend fun setEnabled(subscriptionId: String, enabled: Boolean) {
        val current = subscriptionSourceDao.findById(subscriptionId)?.toDomain() ?: return
        val status = if (!enabled) SubscriptionSyncStatus.DISABLED else current.syncStatus
        subscriptionSourceDao.upsert(
            current.copy(
                enabled = enabled,
                syncStatus = status,
                updatedAtMs = System.currentTimeMillis()
            ).toEntity()
        )
    }

    override suspend fun setAutoUpdateEnabled(subscriptionId: String, enabled: Boolean) {
        val current = subscriptionSourceDao.findById(subscriptionId)?.toDomain() ?: return
        subscriptionSourceDao.upsert(
            current.copy(
                autoUpdateEnabled = enabled,
                updatedAtMs = System.currentTimeMillis()
            ).toEntity()
        )
    }

    override suspend fun setUpdateIntervalMinutes(subscriptionId: String, minutes: Int) {
        val current = subscriptionSourceDao.findById(subscriptionId)?.toDomain() ?: return
        val normalized = minutes.coerceIn(MIN_UPDATE_INTERVAL_MINUTES, MAX_UPDATE_INTERVAL_MINUTES)
        subscriptionSourceDao.upsert(
            current.copy(
                updateIntervalMinutes = normalized,
                updatedAtMs = System.currentTimeMillis()
            ).toEntity()
        )
    }

    override suspend fun setLastSelectedProfile(subscriptionId: String, profileId: String?) {
        val current = subscriptionSourceDao.findById(subscriptionId)?.toDomain() ?: return
        subscriptionSourceDao.upsert(
            current.copy(
                lastSelectedProfileId = profileId?.trim()?.takeIf { it.isNotBlank() },
                updatedAtMs = System.currentTimeMillis()
            ).toEntity()
        )
    }

    override suspend fun refreshSubscription(subscriptionId: String, force: Boolean): SubscriptionRefreshResult {
        val source = subscriptionSourceDao.findById(subscriptionId)?.toDomain()
            ?: return SubscriptionRefreshResult(
                subscriptionId = subscriptionId,
                status = SubscriptionSyncStatus.ERROR,
                importedProfilesCount = 0,
                invalidEntriesCount = 0,
                message = "Подписка не найдена"
            )

        val meta = SubscriptionMetadataCodec.decode(source.metadata)
        var resolvedTitle = meta.displayTitle ?: source.displayName
        var providerName = meta.providerName ?: extractProviderName(source.sourceUrl)
        var providerDomain = meta.resolvedProviderDomain ?: extractProviderName(source.sourceUrl)
        var providerSite = meta.resolvedProviderSiteUrl
        var supportUrl = meta.supportUrl
        var profileWebPageUrl = meta.profileWebPageUrl
        var announcementText = meta.announcementText
        var planId = meta.planId
        var userId = meta.userId
        var note = meta.note
        var badgeText = meta.badgeText
        var trafficUploadBytes = meta.trafficUploadBytes
        var trafficDownloadBytes = meta.trafficDownloadBytes
        var trafficTotalBytes = meta.trafficTotalBytes
        var trafficRemainingBytes = meta.trafficRemainingBytes
        var expireAt = meta.expireAt
        var expireText = meta.expireText
        var logoUrl = meta.logoUrl
        var logoHint = meta.logoHint
        var labelLine = meta.labelLine
        var providerMessage = meta.providerMessage
        val configuredClientMode = SubscriptionClientMode.fromWireValue(meta.clientMode)
        var persistedClientMode = configuredClientMode
        val platformHints = meta.platformHints.toMutableSet()
        val tags = meta.tags.toMutableSet()
        var diagnostics = meta.diagnostics ?: SubscriptionSyncDiagnostics()

        if (!source.enabled) {
            subscriptionSourceDao.upsert(
                source.copy(
                    syncStatus = SubscriptionSyncStatus.DISABLED,
                    updatedAtMs = System.currentTimeMillis()
                ).toEntity()
            )
            return SubscriptionRefreshResult(
                subscriptionId = subscriptionId,
                status = SubscriptionSyncStatus.DISABLED,
                importedProfilesCount = source.profileCount,
                invalidEntriesCount = 0,
                message = "Подписка отключена"
            )
        }

        if (!force && !isDueForAutoUpdate(source, System.currentTimeMillis())) {
            return SubscriptionRefreshResult(
                subscriptionId = subscriptionId,
                status = source.syncStatus,
                importedProfilesCount = source.profileCount,
                invalidEntriesCount = 0,
                message = "Ещё не время автообновления"
            )
        }

        val startedAt = System.currentTimeMillis()
        subscriptionSourceDao.upsert(
            source.copy(
                syncStatus = SubscriptionSyncStatus.LOADING,
                lastUpdatedAtMs = startedAt,
                updatedAtMs = startedAt
            ).toEntity()
        )

        Log.i(TAG, "refresh start id=$subscriptionId, provider=$providerName")

        return runCatching {
            val compatibilityHwid = resolveCompatibilityHwid()
            var selectedAttempt = buildInitialFetchAttempt(
                sourceUrl = source.sourceUrl,
                mode = configuredClientMode,
                compatibilityHwid = compatibilityHwid
            )
            var response = fetchSubscriptionPayload(
                attempt = selectedAttempt,
                etag = source.etag,
                lastModified = source.lastModified,
                useConditionalCache = true
            )
            val firstHeaderMeta = extractProviderMetadata(response.headers)
            resolvedTitle = firstHeaderMeta.displayTitle ?: resolvedTitle
            providerName = firstHeaderMeta.providerName ?: providerName
            providerDomain = firstHeaderMeta.providerDomain ?: providerDomain
            providerSite = firstHeaderMeta.providerSite ?: providerSite
            supportUrl = firstHeaderMeta.supportUrl ?: supportUrl
            profileWebPageUrl = firstHeaderMeta.profileWebPageUrl ?: profileWebPageUrl
            announcementText = firstHeaderMeta.announcementText ?: announcementText
            planId = firstHeaderMeta.planId ?: planId
            userId = firstHeaderMeta.userId ?: userId
            note = firstHeaderMeta.note ?: note
            badgeText = firstHeaderMeta.badgeText ?: badgeText
            trafficUploadBytes = firstHeaderMeta.trafficUploadBytes ?: trafficUploadBytes
            trafficDownloadBytes = firstHeaderMeta.trafficDownloadBytes ?: trafficDownloadBytes
            trafficTotalBytes = firstHeaderMeta.trafficTotalBytes ?: trafficTotalBytes
            trafficRemainingBytes = firstHeaderMeta.trafficRemainingBytes ?: trafficRemainingBytes
            expireAt = firstHeaderMeta.expireAt ?: expireAt
            expireText = firstHeaderMeta.expireText ?: expireText
            logoUrl = firstHeaderMeta.logoUrl ?: logoUrl
            logoHint = firstHeaderMeta.logoHint ?: logoHint
            labelLine = firstHeaderMeta.labelLine ?: labelLine
            providerMessage = firstHeaderMeta.providerMessage ?: providerMessage
            tags += firstHeaderMeta.tags
            platformHints += firstHeaderMeta.platformHints

            diagnostics = diagnostics.copy(
                httpStatusCode = response.statusCode,
                contentType = response.contentType,
                responseBodyLength = response.bodyLength,
                requestEndpoint = selectedAttempt.endpointUrl,
                endpointVariant = selectedAttempt.endpointVariant,
                usedClientType = selectedAttempt.clientType,
                hwidActive = firstHeaderMeta.hwidActive,
                hwidNotSupported = firstHeaderMeta.hwidNotSupported,
                retryWithHwid = false,
                headersSummary = response.headersSummary,
                metadataHeadersReceived = firstHeaderMeta.metadataHeadersSummary,
                metadataExtracted = firstHeaderMeta.extractedMetadataSummary,
                metadataIgnored = firstHeaderMeta.ignoredMetadataSummary,
                payloadPreview = response.bodyPreview
            )

            Log.i(
                TAG,
                "http id=$subscriptionId mode=${configuredClientMode.wireValue} endpoint=${selectedAttempt.endpointUrl} " +
                    "status=${response.statusCode} type=${response.contentType ?: "-"} " +
                    "len=${response.bodyLength} headers='${response.headersSummary}' preview='${response.bodyPreview ?: "-"}'"
            )

            if (response.notModified) {
                val notModifiedDiagnostics = diagnostics.copy(
                    detectedFormat = diagnostics.detectedFormat ?: "unchanged",
                    discoveredEntries = source.profileCount,
                    parsedProfiles = source.profileCount,
                    savedProfiles = source.profileCount,
                    invalidEntries = 0,
                    markerEntries = diagnostics.markerEntries ?: 0,
                    connectableEntries = source.profileCount,
                    shortError = null
                )
                val refreshed = source.copy(
                    lastUpdatedAtMs = startedAt,
                    lastSuccessAtMs = startedAt,
                    lastError = null,
                    syncStatus = if (source.profileCount == 0) SubscriptionSyncStatus.EMPTY else SubscriptionSyncStatus.SUCCESS,
                    metadata = SubscriptionMetadataCodec.encode(
                        displayTitle = resolvedTitle,
                        providerName = providerName,
                        providerSite = providerSite,
                        clientMode = persistedClientMode.wireValue,
                        platformHints = platformHints.toList(),
                        diagnostics = notModifiedDiagnostics,
                        providerDomain = providerDomain,
                        providerSiteUrl = providerSite,
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
                        serverCount = source.profileCount,
                        logoUrl = logoUrl,
                        logoHint = logoHint,
                        tags = tags.toList(),
                        labelLine = labelLine,
                        providerMessage = providerMessage
                    ),
                    updatedAtMs = startedAt
                )
                subscriptionSourceDao.upsert(refreshed.toEntity())
                return@runCatching SubscriptionRefreshResult(
                    subscriptionId = subscriptionId,
                    status = refreshed.syncStatus,
                    importedProfilesCount = refreshed.profileCount,
                    invalidEntriesCount = 0,
                    message = "Изменений нет (HTTP 304)"
                )
            }

            val body = response.body.orEmpty()
            require(body.isNotBlank()) { "Сервер подписки вернул пустой ответ" }

            var parsedData = parseSubscriptionPayload(
                subscription = source,
                body = body,
                headers = response.headers,
                importedAt = startedAt
            )
            logShortIdTraceStage1And2(
                subscriptionId = subscriptionId,
                drafts = parsedData.parseResult.validProfiles
            )
            var selectedBodySignature = bodySignature(body)
            providerSite = parsedData.providerSite ?: providerSite
            platformHints += parsedData.platformHints
            var compatibilityMode = parsedData.compatibilityMode ?: firstHeaderMeta.compatibilityMode
            var retryWithHwid = false

            Log.i(
                TAG,
                "attempt parsed id=$subscriptionId source=${selectedAttempt.endpointVariant} status=${response.statusCode} " +
                    "bodySig=$selectedBodySignature rawLines=${parsedData.parseResult.rawLineCount} " +
                    "discovered=${parsedData.parseResult.discoveredEntriesCount} connectable=${parsedData.connectableProfiles.size} " +
                    "markers=${parsedData.markerEntriesCount}"
            )

            val probeDueToHwidHeaders = shouldRetryWithCompatibilityHwid(response = response, parsedData = parsedData)
            val probeDueToMissingShortId = shouldProbeAlternativeClientMode(
                subscriptionId = subscriptionId,
                parsedData = parsedData,
                selectedAttempt = selectedAttempt
            )

            if (probeDueToHwidHeaders || probeDueToMissingShortId) {
                val retryAttempts = buildCompatibilityRetryAttempts(
                    sourceUrl = source.sourceUrl,
                    compatibilityHwid = compatibilityHwid,
                    configuredMode = configuredClientMode,
                    currentAttempt = selectedAttempt
                )

                Log.i(
                    TAG,
                    "compat probe id=$subscriptionId reasonHwid=$probeDueToHwidHeaders " +
                        "reasonMissingShortId=$probeDueToMissingShortId retryCandidates=${retryAttempts.size}"
                )

                var bestResponse = response
                var bestParsedData = parsedData
                var bestMode = compatibilityMode
                var bestAttempt = selectedAttempt
                var bestHeaderMeta = firstHeaderMeta

                retryAttempts.forEach { attempt ->
                    val retryResponse = runCatching {
                        fetchSubscriptionPayload(
                            attempt = attempt,
                            etag = source.etag,
                            lastModified = source.lastModified,
                            useConditionalCache = false
                        )
                    }.onFailure { error ->
                        Log.w(
                            TAG,
                            "compat retry failed id=$subscriptionId endpoint=${attempt.endpointUrl} mode=${attempt.clientMode.wireValue}: ${error.message}"
                        )
                    }.getOrNull() ?: return@forEach

                    if (retryResponse.notModified || retryResponse.body.isNullOrBlank()) {
                        Log.i(
                            TAG,
                            "attempt skipped id=$subscriptionId source=${attempt.endpointVariant} status=${retryResponse.statusCode} " +
                                "reason=${if (retryResponse.notModified) "not-modified" else "empty-body"}"
                        )
                        return@forEach
                    }

                    val retryHeaderMeta = extractProviderMetadata(retryResponse.headers)
                    resolvedTitle = retryHeaderMeta.displayTitle ?: resolvedTitle
                    providerName = retryHeaderMeta.providerName ?: providerName
                    providerDomain = retryHeaderMeta.providerDomain ?: providerDomain
                    providerSite = retryHeaderMeta.providerSite ?: providerSite
                    supportUrl = retryHeaderMeta.supportUrl ?: supportUrl
                    profileWebPageUrl = retryHeaderMeta.profileWebPageUrl ?: profileWebPageUrl
                    announcementText = retryHeaderMeta.announcementText ?: announcementText
                    planId = retryHeaderMeta.planId ?: planId
                    userId = retryHeaderMeta.userId ?: userId
                    note = retryHeaderMeta.note ?: note
                    badgeText = retryHeaderMeta.badgeText ?: badgeText
                    trafficUploadBytes = retryHeaderMeta.trafficUploadBytes ?: trafficUploadBytes
                    trafficDownloadBytes = retryHeaderMeta.trafficDownloadBytes ?: trafficDownloadBytes
                    trafficTotalBytes = retryHeaderMeta.trafficTotalBytes ?: trafficTotalBytes
                    trafficRemainingBytes = retryHeaderMeta.trafficRemainingBytes ?: trafficRemainingBytes
                    expireAt = retryHeaderMeta.expireAt ?: expireAt
                    expireText = retryHeaderMeta.expireText ?: expireText
                    logoUrl = retryHeaderMeta.logoUrl ?: logoUrl
                    logoHint = retryHeaderMeta.logoHint ?: logoHint
                    labelLine = retryHeaderMeta.labelLine ?: labelLine
                    providerMessage = retryHeaderMeta.providerMessage ?: providerMessage
                    tags += retryHeaderMeta.tags
                    platformHints += retryHeaderMeta.platformHints

                    val retryParsed = parseSubscriptionPayload(
                        subscription = source,
                        body = retryResponse.body.orEmpty(),
                        headers = retryResponse.headers,
                        importedAt = startedAt
                    )
                    val retryQuality = buildParsedDataQuality(retryParsed)
                    val bestQualityBefore = buildParsedDataQuality(bestParsedData)
                    val retryBodySignature = bodySignature(retryResponse.body.orEmpty())
                    providerSite = retryParsed.providerSite ?: providerSite
                    platformHints += retryParsed.platformHints

                    Log.i(
                        TAG,
                        "attempt parsed id=$subscriptionId source=${attempt.endpointVariant} status=${retryResponse.statusCode} " +
                            "bodySig=$retryBodySignature rawLines=${retryParsed.parseResult.rawLineCount} " +
                            "discovered=${retryParsed.parseResult.discoveredEntriesCount} connectable=${retryParsed.connectableProfiles.size} " +
                            "markers=${retryParsed.markerEntriesCount} quality=${retryQuality.summary()}"
                    )

                    val retryCompatibilityMode = when {
                        retryParsed.connectableProfiles.isNotEmpty() &&
                            attempt.clientMode == SubscriptionClientMode.HAPP ->
                            COMPAT_MODE_HAPP_ENDPOINT

                        retryParsed.connectableProfiles.isNotEmpty() && attempt.useHwidHeaders ->
                            COMPAT_MODE_HWID_HEADER

                        else -> retryParsed.compatibilityMode ?: retryHeaderMeta.compatibilityMode ?: bestMode
                    }

                    val retryIsBetter = isRetryCandidateBetter(
                        candidate = retryParsed,
                        currentBest = bestParsedData
                    )

                    if (retryIsBetter) {
                        bestResponse = retryResponse
                        bestParsedData = retryParsed
                        bestAttempt = attempt
                        bestMode = retryCompatibilityMode
                        bestHeaderMeta = retryHeaderMeta
                        selectedBodySignature = retryBodySignature
                        Log.i(
                            TAG,
                            "attempt selected id=$subscriptionId source=${attempt.endpointVariant} " +
                                "oldQuality=${bestQualityBefore.summary()} newQuality=${retryQuality.summary()}"
                        )
                    }
                }

                if (bestAttempt != selectedAttempt) {
                    response = bestResponse
                    parsedData = bestParsedData
                    selectedAttempt = bestAttempt
                    compatibilityMode = bestMode
                    retryWithHwid = bestAttempt.useHwidHeaders

                    diagnostics = diagnostics.copy(
                        httpStatusCode = response.statusCode,
                        contentType = response.contentType,
                        responseBodyLength = response.bodyLength,
                        requestEndpoint = selectedAttempt.endpointUrl,
                        endpointVariant = selectedAttempt.endpointVariant,
                        usedClientType = selectedAttempt.clientType,
                        hwidActive = bestHeaderMeta.hwidActive,
                        hwidNotSupported = bestHeaderMeta.hwidNotSupported,
                        retryWithHwid = retryWithHwid,
                        headersSummary = response.headersSummary,
                        metadataHeadersReceived = bestHeaderMeta.metadataHeadersSummary,
                        metadataExtracted = bestHeaderMeta.extractedMetadataSummary,
                        metadataIgnored = bestHeaderMeta.ignoredMetadataSummary,
                        payloadPreview = response.bodyPreview
                    )

                    if (configuredClientMode == SubscriptionClientMode.AUTO &&
                        parsedData.connectableProfiles.isNotEmpty()
                    ) {
                        persistedClientMode = selectedAttempt.clientMode
                    }
                }
            }

            Log.i(
                TAG,
                "selected response id=$subscriptionId selectedResponseSource=${selectedAttempt.endpointVariant} " +
                    "selectedStatus=${response.statusCode} selectedConnectableCount=${parsedData.connectableProfiles.size} " +
                    "selectedMarkerCount=${parsedData.markerEntriesCount} selectedBodySignature=$selectedBodySignature"
            )

            val connectableProfiles = parsedData.connectableProfiles
            val importedIds = connectableProfiles.map { it.id }

            var persistedCount = source.profileCount
            var insertedProfilesCount = 0
            var updatedProfilesCount = 0
            val filteredOutCount = parsedData.markerEntriesCount
            val duplicateCount = parsedData.duplicateCount
            var syncStatus = resolveSyncStatus(
                discoveredEntries = parsedData.parseResult.discoveredEntriesCount,
                validProfiles = connectableProfiles.size,
                invalidEntries = parsedData.parseResult.invalidEntriesCount
            )

            var syncMessage = when (syncStatus) {
                SubscriptionSyncStatus.SUCCESS -> "Подписка обновлена"
                SubscriptionSyncStatus.PARTIAL -> "Подписка обновлена частично: ${connectableProfiles.size}/${parsedData.parseResult.discoveredEntriesCount}"
                SubscriptionSyncStatus.EMPTY -> "Подписка не содержит серверов"
                SubscriptionSyncStatus.ERROR -> "Не удалось распарсить подписку: 0/${parsedData.parseResult.discoveredEntriesCount}"
                else -> "Подписка обновлена"
            }

            if (connectableProfiles.isEmpty() && parsedData.markerEntriesCount > 0) {
                syncStatus = SubscriptionSyncStatus.EMPTY
                syncMessage = "Подписка загружена, но содержит только служебные записи"
            }

            val parseDiagnostics = diagnostics.copy(
                detectedFormat = parsedData.parseResult.detectedFormat.name.lowercase(Locale.US),
                compatibilityMode = compatibilityMode,
                selectedResponseSource = selectedAttempt.endpointVariant,
                selectedStatus = response.statusCode,
                selectedConnectableCount = connectableProfiles.size,
                selectedMarkerCount = parsedData.markerEntriesCount,
                selectedBodySignature = selectedBodySignature,
                selectedRawLineCount = parsedData.parseResult.rawLineCount,
                requestEndpoint = selectedAttempt.endpointUrl,
                endpointVariant = selectedAttempt.endpointVariant,
                usedClientType = selectedAttempt.clientType,
                hwidActive = headerValue(response.headers, HWID_ACTIVE_HEADER)
                    ?.equals("true", ignoreCase = true),
                hwidNotSupported = headerValue(response.headers, HWID_NOT_SUPPORTED_HEADER)
                    ?.equals("true", ignoreCase = true),
                retryWithHwid = retryWithHwid,
                discoveredEntries = parsedData.parseResult.discoveredEntriesCount,
                parsedProfiles = parsedData.parseResult.validProfiles.size,
                invalidEntries = parsedData.parseResult.invalidEntriesCount,
                markerEntries = parsedData.markerEntriesCount,
                connectableEntries = connectableProfiles.size,
                insertedProfiles = insertedProfilesCount,
                updatedProfiles = updatedProfilesCount,
                filteredOutProfiles = filteredOutCount,
                duplicateCount = duplicateCount
            )

            Log.i(
                TAG,
                "parse id=$subscriptionId format=${parsedData.parseResult.detectedFormat.name.lowercase(Locale.US)} " +
                    "mode=$compatibilityMode clientMode=${persistedClientMode.wireValue} endpoint=${selectedAttempt.endpointUrl} " +
                    "variant=${selectedAttempt.endpointVariant} retryWithHwid=$retryWithHwid " +
                    "base64Detected=${parsedData.parseResult.base64Detected} " +
                    "base64Decoded=${parsedData.parseResult.base64Decoded} rawLines=${parsedData.parseResult.rawLineCount} " +
                    "skipped=${parsedData.parseResult.skippedLinesCount} found=${parsedData.parseResult.discoveredEntriesCount} " +
                    "parsed=${parsedData.parseResult.validProfiles.size} markers=${parsedData.markerEntriesCount} " +
                    "connectable=${connectableProfiles.size} invalid=${parsedData.parseResult.invalidEntriesCount} " +
                    "filteredOut=$filteredOutCount duplicateCount=$duplicateCount"
            )

            Log.i(
                TAG,
                "parser handoff id=$subscriptionId bodySignature=$selectedBodySignature " +
                    "lineCount=${parsedData.parseResult.rawLineCount} connectableBeforePersistence=${connectableProfiles.size}"
            )

            if (connectableProfiles.isNotEmpty()) {
                var persistedProfilesAfterSave: List<VpnProfile> = emptyList()
                database.withTransaction {
                    val existingProfilesBefore = profileDao.findBySubscriptionId(subscriptionId).map { it.toDomain() }
                    val existingIdsBefore = existingProfilesBefore.map { it.id }.toSet()
                    updatedProfilesCount = connectableProfiles.count { existingIdsBefore.contains(it.id) }
                    insertedProfilesCount = (connectableProfiles.size - updatedProfilesCount).coerceAtLeast(0)

                    profileDao.deleteBySubscriptionId(subscriptionId)
                    profileDao.insertAll(connectableProfiles.map { it.toEntity() })
                    val persistedEntities = profileDao.findBySubscriptionId(subscriptionId)
                    persistedCount = persistedEntities.size
                    persistedProfilesAfterSave = persistedEntities.map { it.toDomain() }

                    val selectedProfile = source.lastSelectedProfileId
                        ?.takeIf { importedIds.contains(it) }
                        ?: importedIds.firstOrNull()

                    subscriptionSourceDao.upsert(
                        source.copy(
                            profileCount = persistedCount,
                            childProfileIds = importedIds,
                            lastSelectedProfileId = selectedProfile,
                            lastUpdatedAtMs = startedAt,
                            lastSuccessAtMs = startedAt,
                            lastError = if (syncStatus == SubscriptionSyncStatus.PARTIAL) syncMessage else null,
                            etag = response.etag ?: source.etag,
                            lastModified = response.lastModified ?: source.lastModified,
                            syncStatus = syncStatus,
                            metadata = SubscriptionMetadataCodec.encode(
                                displayTitle = resolvedTitle,
                                providerName = providerName,
                                providerSite = providerSite,
                                clientMode = persistedClientMode.wireValue,
                                platformHints = platformHints.toList(),
                                diagnostics = parseDiagnostics.copy(
                                    insertedProfiles = insertedProfilesCount,
                                    updatedProfiles = updatedProfilesCount,
                                    filteredOutProfiles = filteredOutCount,
                                    duplicateCount = duplicateCount,
                                    savedProfiles = persistedCount,
                                    shortError = if (syncStatus == SubscriptionSyncStatus.PARTIAL) {
                                        parsedData.parseResult.warnings.firstOrNull() ?: syncMessage
                                    } else {
                                        null
                                    }
                                ),
                                providerDomain = providerDomain,
                                providerSiteUrl = providerSite,
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
                                serverCount = persistedCount,
                                logoUrl = logoUrl,
                                logoHint = logoHint,
                                tags = tags.toList(),
                                labelLine = labelLine,
                                providerMessage = providerMessage
                            ),
                            updatedAtMs = startedAt
                        ).toEntity()
                    )
                }
                Log.i(
                    TAG,
                    "persist id=$subscriptionId parsedProfilesCount=${parsedData.parseResult.validProfiles.size} " +
                        "insertedProfilesCount=$insertedProfilesCount updatedProfilesCount=$updatedProfilesCount " +
                        "filteredOutCount=$filteredOutCount duplicateCount=$duplicateCount persistedCount=$persistedCount"
                )
                logShortIdTraceStage3(
                    subscriptionId = subscriptionId,
                    profiles = persistedProfilesAfterSave
                )

                if (persistedCount != connectableProfiles.size) {
                    Log.w(
                        TAG,
                        "persist mismatch id=$subscriptionId connectable=${connectableProfiles.size} persisted=$persistedCount"
                    )
                }
            } else {
                var clearedStaleMarkers = false
                if (parsedData.markerEntriesCount > 0) {
                    val existingProfiles = profileDao.findBySubscriptionId(subscriptionId).map { it.toDomain() }
                    if (existingProfiles.isNotEmpty() && existingProfiles.all { isServiceMarkerProfile(it) }) {
                        database.withTransaction {
                            profileDao.deleteBySubscriptionId(subscriptionId)
                        }
                        persistedCount = 0
                        clearedStaleMarkers = true
                        Log.i(TAG, "persist cleanup id=$subscriptionId removed stale marker-only profiles")
                    }
                }

                // No valid subset: keep previous child profiles intact unless they are stale marker-only entries.
                if (syncStatus == SubscriptionSyncStatus.SUCCESS) {
                    syncStatus = SubscriptionSyncStatus.ERROR
                    syncMessage = "Не удалось распарсить подписку: 0/${parsedData.parseResult.discoveredEntriesCount}"
                }
                val failureReason = parsedData.parseResult.warnings.firstOrNull() ?: syncMessage
                subscriptionSourceDao.upsert(
                    source.copy(
                        profileCount = if (clearedStaleMarkers) 0 else source.profileCount,
                        childProfileIds = if (clearedStaleMarkers) emptyList() else source.childProfileIds,
                        lastSelectedProfileId = if (clearedStaleMarkers) null else source.lastSelectedProfileId,
                        lastUpdatedAtMs = startedAt,
                        lastError = failureReason,
                        etag = response.etag ?: source.etag,
                        lastModified = response.lastModified ?: source.lastModified,
                        syncStatus = syncStatus,
                        metadata = SubscriptionMetadataCodec.encode(
                            displayTitle = resolvedTitle,
                            providerName = providerName,
                            providerSite = providerSite,
                            clientMode = persistedClientMode.wireValue,
                            platformHints = platformHints.toList(),
                            diagnostics = parseDiagnostics.copy(
                                insertedProfiles = insertedProfilesCount,
                                updatedProfiles = updatedProfilesCount,
                                filteredOutProfiles = filteredOutCount,
                                duplicateCount = duplicateCount,
                                savedProfiles = if (clearedStaleMarkers) 0 else source.profileCount,
                                shortError = failureReason
                            ),
                            providerDomain = providerDomain,
                            providerSiteUrl = providerSite,
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
                            serverCount = if (clearedStaleMarkers) 0 else source.profileCount,
                            logoUrl = logoUrl,
                            logoHint = logoHint,
                            tags = tags.toList(),
                            labelLine = labelLine,
                            providerMessage = providerMessage
                        ),
                        updatedAtMs = startedAt
                    ).toEntity()
                )
                Log.i(
                    TAG,
                    "persist skipped id=$subscriptionId parsedProfilesCount=${parsedData.parseResult.validProfiles.size} " +
                        "insertedProfilesCount=$insertedProfilesCount updatedProfilesCount=$updatedProfilesCount " +
                        "filteredOutCount=$filteredOutCount duplicateCount=$duplicateCount persistedCount=$persistedCount " +
                        "clearedStaleMarkers=$clearedStaleMarkers"
                )
            }

            SubscriptionRefreshResult(
                subscriptionId = subscriptionId,
                status = syncStatus,
                importedProfilesCount = persistedCount,
                invalidEntriesCount = parsedData.parseResult.invalidEntriesCount,
                message = syncMessage
            )
        }.getOrElse { error ->
            val errorReason = (error.message ?: "Ошибка обновления подписки").trim().take(240)
            val failedDiagnostics = diagnostics.copy(shortError = errorReason)
            val errorType = error::class.java.simpleName
            Log.w(
                TAG,
                "refresh failed id=$subscriptionId type=$errorType reason=$errorReason",
                error
            )
            subscriptionSourceDao.upsert(
                source.copy(
                    lastUpdatedAtMs = startedAt,
                    lastError = errorReason,
                    syncStatus = SubscriptionSyncStatus.ERROR,
                    metadata = SubscriptionMetadataCodec.encode(
                        displayTitle = resolvedTitle,
                        providerName = providerName,
                        providerSite = providerSite,
                        clientMode = persistedClientMode.wireValue,
                        platformHints = platformHints.toList(),
                        diagnostics = failedDiagnostics,
                        providerDomain = providerDomain,
                        providerSiteUrl = providerSite,
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
                        serverCount = source.profileCount,
                        logoUrl = logoUrl,
                        logoHint = logoHint,
                        tags = tags.toList(),
                        labelLine = labelLine,
                        providerMessage = providerMessage
                    ),
                    updatedAtMs = startedAt
                ).toEntity()
            )
            SubscriptionRefreshResult(
                subscriptionId = subscriptionId,
                status = SubscriptionSyncStatus.ERROR,
                importedProfilesCount = source.profileCount,
                invalidEntriesCount = 0,
                message = buildErrorMessage(failedDiagnostics, errorReason)
            )
        }
    }

    override suspend fun refreshAllSubscriptions(force: Boolean): List<SubscriptionRefreshResult> {
        val now = System.currentTimeMillis()
        val all = subscriptionSourceDao.findEnabled().map { it.toDomain() }
        val targets = if (force) {
            all
        } else {
            all.filter { it.autoUpdateEnabled && isDueForAutoUpdate(it, now) }
        }
        return targets.map { source ->
            refreshSubscription(subscriptionId = source.id, force = true)
        }
    }

    override suspend fun getSubscription(subscriptionId: String): SubscriptionSource? {
        return subscriptionSourceDao.findById(subscriptionId)?.toDomain()
    }

    private fun buildChildProfiles(
        subscription: SubscriptionSource,
        parseResult: SubscriptionParseResult,
        importedAt: Long
    ): BuildProfilesResult {
        val uniqueProfiles = mutableListOf<VpnProfile>()
        val usedIds = mutableSetOf<String>()
        var duplicateCount = 0

        parseResult.validProfiles.forEachIndexed { index, draft ->
            val baseId = buildChildId(subscription.id, draft.sourceRaw)
            var suffix = 0
            var uniqueId = baseId
            while (!usedIds.add(uniqueId)) {
                suffix += 1
                duplicateCount += 1
                uniqueId = "${baseId}_$suffix"
            }

            uniqueProfiles += VpnProfile(
                id = uniqueId,
                displayName = draft.displayName.ifBlank { "Server #${index + 1}" },
                type = draft.type,
                sourceRaw = draft.sourceRaw,
                normalizedJson = draft.normalizedJson,
                dnsServers = draft.dnsServers,
                dnsFallbackApplied = draft.dnsFallbackApplied,
                isPartialImport = draft.isPartialImport,
                importWarnings = draft.importWarnings,
                importedAtMs = importedAt,
                parentSubscriptionId = subscription.id,
                sourceOrder = index
            )
        }

        return BuildProfilesResult(
            profiles = uniqueProfiles,
            duplicateCount = duplicateCount
        )
    }

    private fun parseSubscriptionPayload(
        subscription: SubscriptionSource,
        body: String,
        headers: Map<String, String>,
        importedAt: Long
    ): ParsedSubscriptionData {
        val bodyWithRoutingHeader = headerValue(headers, HAPP_ROUTING_HEADER)
            ?.takeIf { it.startsWith("happ://routing/", ignoreCase = true) }
            ?.let { routingLink -> "$routingLink\n$body" }
            ?: body
        val parseResult = parser.parse(bodyWithRoutingHeader)
        val builtProfiles = buildChildProfiles(
            subscription = subscription,
            parseResult = parseResult,
            importedAt = importedAt
        )
        val allProfiles = parseResult.happRoutingProfile
            ?.let { routingProfile ->
                builtProfiles.profiles.map { profile ->
                    applyHappRoutingToProfile(profile = profile, routingProfile = routingProfile)
                }
            }
            ?: builtProfiles.profiles
        val (markerProfiles, connectableProfiles) = allProfiles.partition { isServiceMarkerProfile(it) }
        val markerMetadata = extractMarkerMetadata(markerProfiles)

        return ParsedSubscriptionData(
            parseResult = parseResult,
            allProfiles = allProfiles,
            connectableProfiles = connectableProfiles,
            markerEntriesCount = markerProfiles.size,
            duplicateCount = builtProfiles.duplicateCount,
            compatibilityMode = markerMetadata.compatibilityMode,
            providerSite = markerMetadata.providerSite,
            platformHints = markerMetadata.platformHints
        )
    }

    private fun applyHappRoutingToProfile(
        profile: VpnProfile,
        routingProfile: HappRoutingProfile
    ): VpnProfile {
        val draft = ImportedProfileDraft(
            displayName = profile.displayName,
            type = profile.type,
            sourceRaw = profile.sourceRaw,
            normalizedJson = profile.normalizedJson,
            dnsServers = profile.dnsServers,
            dnsFallbackApplied = profile.dnsFallbackApplied,
            isPartialImport = profile.isPartialImport,
            importWarnings = profile.importWarnings
        )
        val routed = HappRoutingCompat.applyRoutingToDraft(draft, routingProfile)
        return profile.copy(
            normalizedJson = routed.normalizedJson,
            dnsServers = routed.dnsServers,
            dnsFallbackApplied = routed.dnsFallbackApplied,
            isPartialImport = routed.isPartialImport,
            importWarnings = routed.importWarnings
        )
    }

    private fun logShortIdTraceStage1And2(
        subscriptionId: String,
        drafts: List<ImportedProfileDraft>
    ) {
        if (drafts.isEmpty()) {
            Log.i(TAG, "SHORTID TRACE stage1/2 id=$subscriptionId no-valid-drafts")
            return
        }

        drafts.take(SHORTID_TRACE_PROFILE_LIMIT).forEachIndexed { index, draft ->
            val stage1 = extractProxyShortIdState(draft.sourceRaw)
            val stage2Payload = draft.normalizedJson ?: draft.sourceRaw
            val stage2 = extractProxyShortIdState(stage2Payload)

            Log.i(
                TAG,
                "SHORTID TRACE stage=1 id=$subscriptionId idx=$index profile='${draft.displayName}' " +
                    "type=${draft.type.name} shortId=${stage1.shortIdForLog()} " +
                    "reality=${stage1.realityEnabled} payload=${stage1.payloadKind} " +
                    "proxyTag=${stage1.proxyTagFound} outbounds=${stage1.outboundCount}"
            )
            Log.i(
                TAG,
                "SHORTID TRACE stage=2 id=$subscriptionId idx=$index profile='${draft.displayName}' " +
                    "type=${draft.type.name} partial=${draft.isPartialImport} shortId=${stage2.shortIdForLog()} " +
                    "reality=${stage2.realityEnabled} payload=${stage2.payloadKind} " +
                    "proxyTag=${stage2.proxyTagFound} outbounds=${stage2.outboundCount}"
            )

            if (shouldDumpTracePayload(draft.displayName, index)) {
                logTracePayloadChunks(
                    prefix = "SHORTID TRACE stage=1 payload id=$subscriptionId idx=$index",
                    payload = draft.sourceRaw
                )
                if (!draft.normalizedJson.isNullOrBlank()) {
                    logTracePayloadChunks(
                        prefix = "SHORTID TRACE stage=2 payload id=$subscriptionId idx=$index",
                        payload = draft.normalizedJson
                    )
                }
            }
        }
    }

    private fun logShortIdTraceStage3(
        subscriptionId: String,
        profiles: List<VpnProfile>
    ) {
        if (profiles.isEmpty()) {
            Log.i(TAG, "SHORTID TRACE stage=3 id=$subscriptionId persisted-profiles-empty")
            return
        }

        profiles.take(SHORTID_TRACE_PROFILE_LIMIT).forEachIndexed { index, profile ->
            val persistedPayload = profile.normalizedJson ?: profile.sourceRaw
            val stage3 = extractProxyShortIdState(persistedPayload)
            Log.i(
                TAG,
                "SHORTID TRACE stage=3 id=$subscriptionId idx=$index profileId=${profile.id} " +
                    "profile='${profile.displayName}' type=${profile.type.name} shortId=${stage3.shortIdForLog()} " +
                    "reality=${stage3.realityEnabled} payload=${stage3.payloadKind} " +
                    "proxyTag=${stage3.proxyTagFound} outbounds=${stage3.outboundCount}"
            )
            if (shouldDumpTracePayload(profile.displayName, index)) {
                logTracePayloadChunks(
                    prefix = "SHORTID TRACE stage=3 payload id=$subscriptionId idx=$index profileId=${profile.id}",
                    payload = persistedPayload
                )
            }
        }
    }

    private fun shouldProbeAlternativeClientMode(
        subscriptionId: String,
        parsedData: ParsedSubscriptionData,
        selectedAttempt: FetchAttempt
    ): Boolean {
        if (selectedAttempt.clientMode == SubscriptionClientMode.HAPP) return false
        if (parsedData.connectableProfiles.isEmpty()) return false

        val realityWithoutShortId = parsedData.connectableProfiles.count { profile ->
            val payload = profile.normalizedJson ?: profile.sourceRaw
            val state = extractProxyShortIdState(payload)
            state.realityEnabled && state.shortId.isNullOrBlank()
        }
        if (realityWithoutShortId <= 0) return false

        Log.i(
            TAG,
            "SHORTID TRACE probe-needed id=$subscriptionId source=${selectedAttempt.endpointVariant} " +
                "mode=${selectedAttempt.clientMode.wireValue} connectable=${parsedData.connectableProfiles.size} " +
                "realityMissingShortId=$realityWithoutShortId"
        )
        return true
    }

    private fun extractProxyShortIdState(payload: String?): ProxyShortIdState {
        val text = payload?.trim().orEmpty()
        if (text.isBlank()) {
            return ProxyShortIdState(payloadKind = "empty")
        }

        if (text.startsWith("{") && text.endsWith("}")) {
            return parseProxyShortIdFromJson(text)
        }

        if (text.startsWith("vless://", ignoreCase = true)) {
            val uri = runCatching { Uri.parse(text) }.getOrNull()
            val security = uri?.queryParameterValue("security")?.trim()?.lowercase(Locale.US)
            val shortId = uri?.queryParameterValue("shortId", "shortid", "sid", "short_id")
            return ProxyShortIdState(
                payloadKind = "vless-uri",
                realityEnabled = security == "reality",
                shortId = shortId
            )
        }

        return ProxyShortIdState(payloadKind = "unsupported")
    }

    private fun parseProxyShortIdFromJson(jsonText: String): ProxyShortIdState {
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return ProxyShortIdState(payloadKind = "invalid-json")
        val outbounds = root.optJSONArray("outbounds")
            ?: return ProxyShortIdState(payloadKind = "json-no-outbounds")

        val matchedOutbound = findProxyLikeOutbound(outbounds)
            ?: return ProxyShortIdState(
                payloadKind = "json",
                outboundCount = outbounds.length(),
                proxyTagFound = false
            )

        val tag = matchedOutbound.optString("tag").trim()
        val streamSettings = matchedOutbound.optJSONObject("streamSettings")
        val security = streamSettings?.optString("security")?.trim()?.lowercase(Locale.US).orEmpty()
        val realitySettings = streamSettings?.optJSONObject("realitySettings")
        val shortId = realitySettings?.firstNonBlankString("shortId", "shortid", "sid", "short_id")
            ?: realitySettings?.optJSONArray("shortIds")
                ?.let { shortIds ->
                    (0 until shortIds.length())
                        .asSequence()
                        .map { shortIds.optString(it).trim() }
                        .firstOrNull { it.isNotBlank() }
                }
            ?: realitySettings?.optJSONArray("shortids")
                ?.let { shortIds ->
                    (0 until shortIds.length())
                        .asSequence()
                        .map { shortIds.optString(it).trim() }
                        .firstOrNull { it.isNotBlank() }
                }

        return ProxyShortIdState(
            payloadKind = "json",
            outboundCount = outbounds.length(),
            proxyTagFound = tag.equals("proxy", ignoreCase = true),
            matchedTag = tag.takeIf { it.isNotBlank() },
            realityEnabled = security == "reality",
            shortId = shortId
        )
    }

    private fun findProxyLikeOutbound(outbounds: JSONArray): JSONObject? {
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            if (outbound.optString("tag").equals("proxy", ignoreCase = true)) {
                return outbound
            }
        }
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val protocol = outbound.optString("protocol").trim()
            if (protocol.equals("vless", ignoreCase = true)) {
                return outbound
            }
        }
        return outbounds.optJSONObject(0)
    }

    private fun Uri.queryParameterValue(vararg names: String): String? {
        names.forEach { name ->
            getQueryParameter(name)?.takeIf { it.isNotBlank() }?.let { return it }
        }

        val nameSet = names.map { it.lowercase(Locale.US) }.toSet()
        return queryParameterNames
            .firstOrNull { candidate -> candidate.lowercase(Locale.US) in nameSet }
            ?.let { candidate -> getQueryParameter(candidate) }
            ?.takeIf { it.isNotBlank() }
    }

    private fun JSONObject.firstNonBlankString(vararg keys: String): String? {
        keys.forEach { key ->
            val value = optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun shouldDumpTracePayload(profileName: String, index: Int): Boolean {
        if (index == 0) return true
        val normalizedName = profileName.trim().lowercase(Locale.US)
        return normalizedName.contains("свободный интернет #1") || normalizedName.contains("svobod")
    }

    private fun logTracePayloadChunks(prefix: String, payload: String) {
        if (payload.isBlank()) {
            Log.i(TAG, "$prefix <empty>")
            return
        }
        Log.i(TAG, "$prefix len=${payload.length}")
        var cursor = 0
        var chunkIndex = 0
        while (cursor < payload.length) {
            val end = (cursor + SHORTID_TRACE_PAYLOAD_CHUNK).coerceAtMost(payload.length)
            val chunk = payload.substring(cursor, end)
            Log.i(TAG, "$prefix chunk=$chunkIndex $chunk")
            cursor = end
            chunkIndex += 1
        }
    }

    private fun buildParsedDataQuality(data: ParsedSubscriptionData): ParsedDataQuality {
        val connectable = data.connectableProfiles
        val nonPartialCount = connectable.count { !it.isPartialImport }
        val rawJsonCount = connectable.count { it.sourceRaw.trim().startsWith("{") }
        var realityPresentShortIdCount = 0
        var realityMissingShortIdCount = 0
        connectable.forEach { profile ->
            val payload = profile.normalizedJson ?: profile.sourceRaw
            val state = extractProxyShortIdState(payload)
            if (state.realityEnabled) {
                if (state.shortId.isNullOrBlank()) {
                    realityMissingShortIdCount += 1
                } else {
                    realityPresentShortIdCount += 1
                }
            }
        }
        return ParsedDataQuality(
            connectableCount = connectable.size,
            nonPartialCount = nonPartialCount,
            rawJsonCount = rawJsonCount,
            realityPresentShortIdCount = realityPresentShortIdCount,
            realityMissingShortIdCount = realityMissingShortIdCount,
            markerCount = data.markerEntriesCount
        )
    }

    private fun isRetryCandidateBetter(
        candidate: ParsedSubscriptionData,
        currentBest: ParsedSubscriptionData
    ): Boolean {
        val candidateQuality = buildParsedDataQuality(candidate)
        val bestQuality = buildParsedDataQuality(currentBest)

        return when {
            candidateQuality.connectableCount != bestQuality.connectableCount ->
                candidateQuality.connectableCount > bestQuality.connectableCount

            candidateQuality.nonPartialCount != bestQuality.nonPartialCount ->
                candidateQuality.nonPartialCount > bestQuality.nonPartialCount

            candidateQuality.rawJsonCount != bestQuality.rawJsonCount ->
                candidateQuality.rawJsonCount > bestQuality.rawJsonCount

            candidateQuality.realityMissingShortIdCount != bestQuality.realityMissingShortIdCount ->
                candidateQuality.realityMissingShortIdCount < bestQuality.realityMissingShortIdCount

            candidateQuality.realityPresentShortIdCount != bestQuality.realityPresentShortIdCount ->
                candidateQuality.realityPresentShortIdCount > bestQuality.realityPresentShortIdCount

            candidateQuality.markerCount != bestQuality.markerCount ->
                candidateQuality.markerCount < bestQuality.markerCount

            else -> false
        }
    }

    private fun isServiceMarkerProfile(profile: VpnProfile): Boolean {
        val raw = profile.sourceRaw.trim()
        val loweredName = profile.displayName.lowercase(Locale.US)
        if (raw.startsWith("vless://", ignoreCase = true) ||
            raw.startsWith("vmess://", ignoreCase = true) ||
            raw.startsWith("trojan://", ignoreCase = true)
        ) {
            val uri = runCatching { Uri.parse(raw) }.getOrNull()
            val host = uri?.host?.trim()?.lowercase(Locale.US).orEmpty()
            val port = uri?.port ?: -1
            val userInfo = uri?.userInfo?.trim()?.lowercase(Locale.US).orEmpty()
            val decodedFragment = Uri.decode(uri?.fragment.orEmpty()).lowercase(Locale.US)
            val markerEndpoint = host in MARKER_HOSTS && port in MARKER_PORTS

            if (markerEndpoint) return true
            if (userInfo == ZERO_UUID && host in MARKER_HOSTS) return true
            if (markerEndpoint && SERVICE_MARKER_KEYWORDS.any { decodedFragment.contains(it) }) return true
            if (markerEndpoint && SERVICE_MARKER_KEYWORDS.any { loweredName.contains(it) }) return true
        }

        if (raw.startsWith("{")) {
            val markerByJson = runCatching {
                val json = org.json.JSONObject(raw)
                val remarks = json.optString("remarks").lowercase(Locale.US)
                val outbounds = json.optJSONArray("outbounds")
                var address = ""
                var port = -1
                if (outbounds != null && outbounds.length() > 0) {
                    val outbound = outbounds.optJSONObject(0)
                    val settings = outbound?.optJSONObject("settings")
                    val vnext = settings?.optJSONArray("vnext")
                    val server = vnext?.optJSONObject(0)
                    address = server?.optString("address").orEmpty().lowercase(Locale.US)
                    port = server?.optInt("port") ?: -1
                }
                (address in MARKER_HOSTS && port in MARKER_PORTS) ||
                    SERVICE_MARKER_KEYWORDS.any { remarks.contains(it) }
            }.getOrDefault(false)

            if (markerByJson) return true
        }

        return false
    }

    private fun extractMarkerMetadata(markerProfiles: List<VpnProfile>): MarkerMetadata {
        if (markerProfiles.isEmpty()) return MarkerMetadata()

        val hints = linkedSetOf<String>()
        var providerSite: String? = null
        var compatibilityMode: String? = null

        markerProfiles.forEach { profile ->
            val decodedName = runCatching {
                val uri = Uri.parse(profile.sourceRaw)
                Uri.decode(uri.fragment ?: profile.displayName)
            }.getOrDefault(profile.displayName)
            val lowered = decodedName.lowercase(Locale.US)

            if (lowered.contains("android")) hints += "Android"
            if (lowered.contains("apple") || lowered.contains("ios")) hints += "Apple"
            if (lowered.contains("windows")) hints += "Windows"
            if (lowered.contains("happ")) hints += "Happ"
            if (lowered.contains("flclash")) hints += "FlClashX"
            if (lowered.contains("rabbithole")) hints += "RabbitHole"

            if (lowered.contains("приложение не поддерживается") ||
                lowered.contains("install app") ||
                lowered.contains("установите приложение")
            ) {
                compatibilityMode = COMPAT_MODE_PROVIDER_MARKER
            }

            val detectedUrl = URL_REGEX.find(decodedName)?.value
            if (!detectedUrl.isNullOrBlank()) {
                providerSite = detectedUrl
            }
        }

        return MarkerMetadata(
            compatibilityMode = compatibilityMode,
            providerSite = providerSite,
            platformHints = hints.toList()
        )
    }

    private fun extractProviderMetadata(headers: Map<String, String>): HeaderMetadata {
        if (headers.isEmpty()) return HeaderMetadata()

        val usedHeaderKeys = mutableSetOf<String>()
        fun pick(vararg names: String): String? {
            names.forEach { name ->
                val matched = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                    ?: return@forEach
                val normalized = matched.value.trim()
                if (normalized.isBlank()) return@forEach
                usedHeaderKeys += matched.key.lowercase(Locale.US)
                return normalized
            }
            return null
        }

        val metadataHeaders = headers.entries
            .filter { isMetadataHeaderName(it.key) }
            .sortedBy { it.key.lowercase(Locale.US) }

        val titleHeader = pick("Profile-Title", "X-Profile-Title", "Subscription-Name")
        val displayTitle = decodePossiblyBase64Header(titleHeader)

        val serviceName = decodePossiblyBase64Header(
            pick("Flclashx-Servicename", "Service-Name", "Provider-Name")
        )
        val logoUrl = extractUrlCandidate(
            decodePossiblyBase64Header(pick("Flclashx-Servicelogo", "Service-Logo", "Provider-Logo"))
        )
        val supportUrl = extractUrlCandidate(
            decodePossiblyBase64Header(pick("Support-Url", "X-Support-Url"))
        )
        val profileWebPageUrl = extractUrlCandidate(
            decodePossiblyBase64Header(
                pick("Profile-Web-Page-Url", "Profile-Web-Page-URL", "Home-Page-Url", "Provider-Url")
            )
        )
        val providerSite = extractUrlCandidate(
            decodePossiblyBase64Header(pick("Provider-Site", "Service-Url"))
        ) ?: profileWebPageUrl ?: supportUrl ?: extractUrlCandidate(displayTitle)
        val providerDomain = decodePossiblyBase64Header(pick("Provider-Domain"))
            ?: providerSite?.let { site ->
                runCatching { URL(site).host.removePrefix("www.") }.getOrNull()
            }
        val providerName = serviceName ?: providerDomain ?: displayTitle

        val announcementText = decodePossiblyBase64Header(pick("Announce", "Announcement", "Profile-Announcement"))
        val planId = decodePossiblyBase64Header(pick("Plan-Id", "Subscription-Plan", "X-Plan-Id"))
        val userId = decodePossiblyBase64Header(pick("User-Id", "X-User-Id", "Subscription-User-Id"))
        val note = decodePossiblyBase64Header(pick("Profile-Note", "Subscription-Note", "Note"))
        val badgeText = decodePossiblyBase64Header(pick("Badge", "Profile-Badge", "X-Profile-Badge"))
        val providerMessage = decodePossiblyBase64Header(pick("Provider-Message", "Service-Message"))

        val tags = decodePossiblyBase64Header(
            pick("Tag", "Tags", "Profile-Tag", "X-Profile-Tags", "Service-Tags", "Emoji-Tags")
        )
            ?.split(',', ';', '|')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val userInfo = parseSubscriptionUserInfo(
            decodePossiblyBase64Header(pick("Subscription-Userinfo", "Subscription-User-Info"))
        )
        val expireAt = userInfo.expireAt
        val expireText = userInfo.expireAt?.let(::formatExpireText)
        val explicitLabelLine = decodePossiblyBase64Header(
            pick(
                "Label-Line",
                "Emoji-Legend",
                "Legend",
                "Provider-Note",
                "Service-Note",
                "Service-Legend",
                "Legend-Line",
                "Emoji-Description",
                "Emoji-Meaning",
                "Provider-Remark",
                "Service-Remark",
                "Tag-Line"
            )
        ) ?: metadataHeaders
            .mapNotNull { header ->
                val key = header.key.lowercase(Locale.US)
                if (key.contains("emoji") ||
                    key.contains("legend") ||
                    key.contains("remark") ||
                    key.contains("tag-line")
                ) {
                    decodePossiblyBase64Header(header.value)
                } else {
                    null
                }
            }
            .firstOrNull { looksLikeLegendLine(it) }
        val labelLine = explicitLabelLine ?: buildList {
            userId?.takeIf { it.isNotBlank() }?.let { add("ID: $it") }
            planId?.takeIf { it.isNotBlank() }?.let { add("#$it") }
        }.takeIf { it.isNotEmpty() }?.joinToString(" • ")

        val hwidActive = pick(HWID_ACTIVE_HEADER)?.equals("true", ignoreCase = true) == true
        val hwidNotSupported = pick(HWID_NOT_SUPPORTED_HEADER)?.equals("true", ignoreCase = true) == true

        val hints = buildSet {
            if (hwidActive) add("HWID")
            if (hwidNotSupported) add("Compatibility Gate")
            if (!logoUrl.isNullOrBlank()) add("Logo")
        }.toList()

        val extractedSummary = buildList {
            displayTitle?.let { add("title=$it") }
            providerDomain?.let { add("provider=$it") }
            providerSite?.let { add("site=$it") }
            supportUrl?.let { add("support=$it") }
            profileWebPageUrl?.let { add("web=$it") }
            userInfo.totalBytes?.let { add("total=$it") }
            userInfo.usedBytes?.let { add("used=$it") }
            userInfo.remainingBytes?.let { add("remaining=$it") }
            expireText?.let { add("expire=$it") }
            if (tags.isNotEmpty()) add("tags=${tags.size}")
            labelLine?.let { add("label=$it") }
        }.joinToString("; ").take(500)

        val metadataHeadersSummary = metadataHeaders.joinToString("; ") { header ->
            "${header.key}=${sanitizeHeaderValueForDiag(header.value)}"
        }.take(700)
        val metadataHeaderKeys = metadataHeaders.map { it.key.lowercase(Locale.US) }.toSet()
        val ignoredHeadersSummary = (metadataHeaderKeys - usedHeaderKeys)
            .sorted()
            .joinToString(", ")
            .take(220)

        val compatibilityMode = when {
            hwidNotSupported -> COMPAT_MODE_PROVIDER_MARKER
            else -> null
        }

        return HeaderMetadata(
            displayTitle = displayTitle,
            providerName = providerName,
            providerDomain = providerDomain,
            providerSite = providerSite,
            supportUrl = supportUrl,
            profileWebPageUrl = profileWebPageUrl,
            announcementText = announcementText,
            planId = planId,
            userId = userId,
            note = note,
            badgeText = badgeText,
            trafficUploadBytes = userInfo.uploadBytes,
            trafficDownloadBytes = userInfo.downloadBytes,
            trafficTotalBytes = userInfo.totalBytes,
            trafficRemainingBytes = userInfo.remainingBytes,
            expireAt = expireAt,
            expireText = expireText,
            logoUrl = logoUrl,
            logoHint = serviceName?.take(2),
            tags = tags,
            labelLine = labelLine,
            providerMessage = providerMessage ?: announcementText,
            platformHints = hints,
            compatibilityMode = compatibilityMode,
            hwidActive = hwidActive,
            hwidNotSupported = hwidNotSupported,
            metadataHeadersSummary = metadataHeadersSummary.ifBlank { null },
            extractedMetadataSummary = extractedSummary.ifBlank { null },
            ignoredMetadataSummary = ignoredHeadersSummary.ifBlank { null }
        )
    }

    private fun parseSubscriptionUserInfo(rawHeader: String?): ParsedUserInfo {
        val raw = rawHeader?.trim()?.takeIf { it.isNotBlank() } ?: return ParsedUserInfo()
        val fields = mutableMapOf<String, Long>()
        USERINFO_FIELD_REGEX.findAll(raw).forEach { match ->
            val key = match.groupValues.getOrNull(1)?.lowercase(Locale.US).orEmpty()
            val value = match.groupValues.getOrNull(2)?.trim()?.toLongOrNull() ?: return@forEach
            fields[key] = value
        }

        val upload = fields["upload"]
        val download = fields["download"]
        val total = fields["total"]
        val expire = fields["expire"]
        val used = if (upload != null || download != null) (upload ?: 0L) + (download ?: 0L) else null
        val remaining = if (total != null && used != null) (total - used).coerceAtLeast(0L) else null
        return ParsedUserInfo(
            uploadBytes = upload,
            downloadBytes = download,
            totalBytes = total,
            expireAt = expire,
            usedBytes = used,
            remainingBytes = remaining
        )
    }

    private fun formatExpireText(expireAtEpochSeconds: Long): String {
        if (expireAtEpochSeconds <= 0L) return ""
        val millis = expireAtEpochSeconds * 1000L
        return runCatching {
            java.text.SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(java.util.Date(millis))
        }.getOrElse { expireAtEpochSeconds.toString() }
    }

    private fun sanitizeHeaderValueForDiag(value: String): String {
        return value
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
            .take(96)
    }

    private fun looksLikeLegendLine(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isBlank()) return false
        if (normalized.equals("quota", ignoreCase = true)) return false
        val lowered = normalized.lowercase(Locale.US)
        val hasEmoji = Regex("[\\p{So}\\p{Sk}]").containsMatchIn(normalized)
        val hasSeparator = normalized.contains("•") || normalized.contains("|") || normalized.contains(" - ")
        val hasLegendKeyword = listOf(
            "быстрый",
            "резерв",
            "торрент",
            "gemini",
            "youtube",
            "yt",
            "android",
            "apple",
            "windows"
        ).any { keyword -> lowered.contains(keyword) }
        return (hasEmoji && (hasSeparator || hasLegendKeyword)) || (hasSeparator && hasLegendKeyword)
    }

    private fun extractUrlCandidate(raw: String?): String? {
        val normalized = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true)
        ) {
            return normalized
        }
        return URL_REGEX.find(normalized)?.value
    }

    private fun isMetadataHeaderName(name: String): Boolean {
        val lowered = name.lowercase(Locale.US)
        return lowered in KNOWN_METADATA_HEADERS ||
            lowered.startsWith("profile-") ||
            lowered.startsWith("subscription-") ||
            lowered.startsWith("x-profile-") ||
            lowered.startsWith("provider-") ||
            lowered.startsWith("flclashx-")
    }

    private fun decodePossiblyBase64Header(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val normalized = when {
            raw.startsWith("base64:", ignoreCase = true) -> raw.substringAfter(':').trim()
            raw.startsWith("base64,", ignoreCase = true) -> raw.substringAfter(',').trim()
            else -> null
        }

        if (normalized == null) {
            return Uri.decode(raw).trim().takeIf { it.isNotBlank() }
        }

        val compact = normalized
            .replace('-', '+')
            .replace('_', '/')
            .replace("\r", "")
            .replace("\n", "")
        val padded = compact.padEnd((compact.length + 3) / 4 * 4, '=')

        return runCatching {
            String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
                .removePrefix(BOM)
                .trim()
                .takeIf { it.isNotBlank() }
        }.getOrNull() ?: Uri.decode(raw).trim().takeIf { it.isNotBlank() }
    }

    private fun headerValue(headers: Map<String, String>, name: String): String? {
        return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun shouldRetryWithCompatibilityHwid(
        response: SubscriptionHttpResponse,
        parsedData: ParsedSubscriptionData
    ): Boolean {
        if (appContext == null) return false
        if (parsedData.connectableProfiles.isNotEmpty()) return false

        val hwidActive = headerValue(response.headers, HWID_ACTIVE_HEADER)?.equals("true", ignoreCase = true) == true
        val hwidNotSupported = headerValue(response.headers, HWID_NOT_SUPPORTED_HEADER)
            ?.equals("true", ignoreCase = true) == true
        val markerOnly = parsedData.markerEntriesCount > 0

        return hwidNotSupported || (hwidActive && markerOnly)
    }

    private fun resolveCompatibilityHwid(): String? {
        val context = appContext ?: return null
        val prefs = context.getSharedPreferences(COMPAT_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(COMPAT_HWID_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

        val seed = if (androidId.isNotBlank()) {
            "${context.packageName}:$androidId"
        } else {
            "${context.packageName}:${UUID.randomUUID()}"
        }
        val hwid = sha256(seed).take(32)
        prefs.edit().putString(COMPAT_HWID_KEY, hwid).apply()
        return hwid
    }

    private fun resolveDeviceOsHeader(): String {
        val release = normalizeBuildValue(Build.VERSION.RELEASE) ?: "SDK ${Build.VERSION.SDK_INT}"
        return "Android $release"
    }

    private fun resolveDeviceModelHeader(): String {
        val manufacturer = normalizeBuildValue(Build.MANUFACTURER)
            ?: normalizeBuildValue(Build.BRAND)
        val model = normalizeBuildValue(Build.MODEL)
            ?: normalizeBuildValue(Build.PRODUCT)
            ?: normalizeBuildValue(Build.DEVICE)

        val combined = when {
            manufacturer != null && model != null && model.startsWith(manufacturer, ignoreCase = true) -> model
            manufacturer != null && model != null -> "$manufacturer $model"
            model != null -> model
            manufacturer != null -> manufacturer
            else -> "Android Device"
        }

        return combined.take(80)
    }

    private fun normalizeBuildValue(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) return null
        if (normalized.equals("unknown", ignoreCase = true)) return null
        if (normalized.equals("null", ignoreCase = true)) return null
        if (normalized.equals("generic", ignoreCase = true)) return null
        if (normalized.equals("sdk", ignoreCase = true)) return null
        return normalized
    }

    private fun resolveSyncStatus(
        discoveredEntries: Int,
        validProfiles: Int,
        invalidEntries: Int
    ): SubscriptionSyncStatus {
        return when {
            validProfiles > 0 && invalidEntries > 0 -> SubscriptionSyncStatus.PARTIAL
            validProfiles > 0 -> SubscriptionSyncStatus.SUCCESS
            discoveredEntries == 0 -> SubscriptionSyncStatus.EMPTY
            else -> SubscriptionSyncStatus.ERROR
        }
    }

    private fun buildErrorMessage(diagnostics: SubscriptionSyncDiagnostics, errorReason: String): String {
        return buildString {
            diagnostics.httpStatusCode?.let { append("HTTP ").append(it).append(". ") }
            diagnostics.detectedFormat?.let { append("format=").append(it).append(". ") }
            diagnostics.discoveredEntries?.let { append("entries=").append(it).append(". ") }
            diagnostics.parsedProfiles?.let { append("parsed=").append(it).append(". ") }
            append(errorReason)
        }.trim()
    }

    private fun buildChildId(subscriptionId: String, sourceRaw: String): String {
        val hash = sha1(sourceRaw).take(16)
        return "sub_${subscriptionId.replace("-", "").take(10)}_$hash"
    }

    private fun sha1(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun bodySignature(value: String): String {
        return "sha1:${sha1(value).take(12)} len=${value.length}"
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun isDueForAutoUpdate(source: SubscriptionSource, now: Long): Boolean {
        val baseline = source.lastSuccessAtMs ?: source.lastUpdatedAtMs ?: 0L
        if (baseline == 0L) return true
        val intervalMs = source.updateIntervalMinutes
            .coerceIn(MIN_UPDATE_INTERVAL_MINUTES, MAX_UPDATE_INTERVAL_MINUTES) * 60_000L
        return now - baseline >= intervalMs
    }

    private fun extractProviderName(url: String): String {
        return runCatching { URL(url).host.removePrefix("www.") }.getOrDefault("subscription")
    }

    private fun buildInitialFetchAttempt(
        sourceUrl: String,
        mode: SubscriptionClientMode,
        compatibilityHwid: String?
    ): FetchAttempt {
        return when (mode) {
            SubscriptionClientMode.GENERIC,
            SubscriptionClientMode.AUTO -> FetchAttempt(
                endpointUrl = sourceUrl,
                endpointVariant = "base",
                clientMode = mode,
                clientType = null,
                userAgent = USER_AGENT,
                useHwidHeaders = false,
                hwid = null
            )

            SubscriptionClientMode.MARZBAN_HWID -> FetchAttempt(
                endpointUrl = sourceUrl,
                endpointVariant = "base-hwid",
                clientMode = mode,
                clientType = null,
                userAgent = USER_AGENT,
                useHwidHeaders = !compatibilityHwid.isNullOrBlank(),
                hwid = compatibilityHwid
            )

            SubscriptionClientMode.HAPP -> {
                val happEndpoint = buildClientTypeEndpoint(sourceUrl, HAPP_CLIENT_TYPE) ?: sourceUrl
                FetchAttempt(
                    endpointUrl = happEndpoint,
                    endpointVariant = if (happEndpoint == sourceUrl) "base-happ-hwid" else "client-type-happ",
                    clientMode = mode,
                    clientType = if (happEndpoint == sourceUrl) null else HAPP_CLIENT_TYPE,
                    userAgent = HAPP_USER_AGENT,
                    useHwidHeaders = !compatibilityHwid.isNullOrBlank(),
                    hwid = compatibilityHwid
                )
            }
        }
    }

    private fun buildCompatibilityRetryAttempts(
        sourceUrl: String,
        compatibilityHwid: String?,
        configuredMode: SubscriptionClientMode,
        currentAttempt: FetchAttempt
    ): List<FetchAttempt> {
        if (compatibilityHwid.isNullOrBlank()) return emptyList()

        val attempts = mutableListOf<FetchAttempt>()

        attempts += FetchAttempt(
            endpointUrl = sourceUrl,
            endpointVariant = "retry-base-hwid",
            clientMode = SubscriptionClientMode.MARZBAN_HWID,
            clientType = null,
            userAgent = USER_AGENT,
            useHwidHeaders = true,
            hwid = compatibilityHwid
        )

        attempts += FetchAttempt(
            endpointUrl = sourceUrl,
            endpointVariant = "retry-base-happ-hwid",
            clientMode = SubscriptionClientMode.HAPP,
            clientType = null,
            userAgent = HAPP_USER_AGENT,
            useHwidHeaders = true,
            hwid = compatibilityHwid
        )

        val happEndpoint = buildClientTypeEndpoint(sourceUrl, HAPP_CLIENT_TYPE)
        if (!happEndpoint.isNullOrBlank() && !happEndpoint.equals(sourceUrl, ignoreCase = true)) {
            attempts += FetchAttempt(
                endpointUrl = happEndpoint,
                endpointVariant = "retry-client-type-happ",
                clientMode = SubscriptionClientMode.HAPP,
                clientType = HAPP_CLIENT_TYPE,
                userAgent = HAPP_USER_AGENT,
                useHwidHeaders = true,
                hwid = compatibilityHwid
            )
        }

        if (configuredMode == SubscriptionClientMode.HAPP &&
            !happEndpoint.isNullOrBlank() &&
            !happEndpoint.equals(sourceUrl, ignoreCase = true)
        ) {
            attempts += FetchAttempt(
                endpointUrl = sourceUrl,
                endpointVariant = "retry-fallback-base-hwid",
                clientMode = SubscriptionClientMode.MARZBAN_HWID,
                clientType = null,
                userAgent = USER_AGENT,
                useHwidHeaders = true,
                hwid = compatibilityHwid
            )
        }

        return attempts
            .distinctBy { "${it.endpointUrl}|${it.clientType}|${it.useHwidHeaders}|${it.userAgent}" }
            .filterNot { it == currentAttempt }
    }

    private fun buildClientTypeEndpoint(sourceUrl: String, clientType: String): String? {
        val uri = Uri.parse(sourceUrl)
        val segments = uri.pathSegments
        val subIndex = segments.indexOf("sub")
        if (subIndex < 0 || segments.size <= subIndex + 1) return null

        val tokenIndex = subIndex + 1
        val currentClientSegment = segments.getOrNull(tokenIndex + 1)
        if (segments.size > tokenIndex + 2) return null
        if (!currentClientSegment.isNullOrBlank() &&
            !KNOWN_CLIENT_TYPES.any { it.equals(currentClientSegment, ignoreCase = true) }
        ) {
            return null
        }
        val newSegments = buildList {
            addAll(segments.take(tokenIndex + 1))
            add(clientType)
        }

        if (currentClientSegment.equals(clientType, ignoreCase = true)) {
            return sourceUrl
        }

        val rebuiltPath = "/" + newSegments.joinToString("/")
        return uri.buildUpon()
            .path(rebuiltPath)
            .build()
            .toString()
    }

    private suspend fun fetchSubscriptionPayload(
        attempt: FetchAttempt,
        etag: String?,
        lastModified: String?,
        useConditionalCache: Boolean
    ): SubscriptionHttpResponse {
        return withContext(Dispatchers.IO) {
            val deviceOs = resolveDeviceOsHeader()
            val deviceModel = resolveDeviceModelHeader()
            val connection = (URL(attempt.endpointUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "*/*")
                setRequestProperty("User-Agent", attempt.userAgent)
                setRequestProperty(DEVICE_OS_HEADER, deviceOs)
                setRequestProperty(X_DEVICE_OS_HEADER, deviceOs)
                setRequestProperty(DEVICE_MODEL_HEADER, deviceModel)
                setRequestProperty(X_DEVICE_MODEL_HEADER, deviceModel)

                if (attempt.useHwidHeaders && !attempt.hwid.isNullOrBlank()) {
                    setRequestProperty(HWID_HEADER, attempt.hwid)
                    setRequestProperty("X-Hwid", attempt.hwid)
                }

                if (useConditionalCache && !etag.isNullOrBlank()) {
                    setRequestProperty("If-None-Match", etag)
                }
                if (useConditionalCache && !lastModified.isNullOrBlank()) {
                    setRequestProperty("If-Modified-Since", lastModified)
                }
            }

            val hwidLog = if (attempt.useHwidHeaders && !attempt.hwid.isNullOrBlank()) {
                attempt.hwid.take(8) + "***"
            } else {
                "<none>"
            }
            Log.i(
                TAG,
                "request headers endpoint=${attempt.endpointVariant} mode=${attempt.clientMode.wireValue} " +
                    "hwid=${if (attempt.useHwidHeaders) "on" else "off"} x-hwid=$hwidLog " +
                    "device-os='$deviceOs' device-model='$deviceModel'"
            )

            try {
                val statusCode = connection.responseCode
                val headers = extractHeaders(connection)
                val headersSummary = summarizeHeaders(headers)
                when (statusCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> SubscriptionHttpResponse(
                        statusCode = statusCode,
                        body = null,
                        bodyLength = 0,
                        contentType = connection.contentType,
                        headers = headers,
                        headersSummary = headersSummary,
                        bodyPreview = null,
                        etag = connection.getHeaderField("ETag"),
                        lastModified = connection.getHeaderField("Last-Modified"),
                        notModified = true
                    )

                    in 200..299 -> {
                        val responseBytes = connection.inputStream.use {
                            readResponseBytes(it, connection.getHeaderField("Content-Encoding"))
                        }
                        val body = decodeBody(responseBytes, connection.contentType)
                        SubscriptionHttpResponse(
                            statusCode = statusCode,
                            body = body,
                            bodyLength = responseBytes.size,
                            contentType = connection.contentType,
                            headers = headers,
                            headersSummary = headersSummary,
                            bodyPreview = buildSafePreview(body),
                            etag = connection.getHeaderField("ETag"),
                            lastModified = connection.getHeaderField("Last-Modified"),
                            notModified = false
                        )
                    }

                    else -> {
                        val errorBytes = runCatching {
                            connection.errorStream?.use {
                                readResponseBytes(
                                    it,
                                    connection.getHeaderField("Content-Encoding")
                                )
                            }
                        }.getOrNull()
                        val errorText = errorBytes?.let { decodeBody(it, connection.contentType) }.orEmpty()
                        val hwidNotSupported = headerValue(headers, HWID_NOT_SUPPORTED_HEADER)
                            ?.equals("true", ignoreCase = true) == true
                        val hwidActive = headerValue(headers, HWID_ACTIVE_HEADER)
                            ?.equals("true", ignoreCase = true) == true
                        val hwidLimit = headerValue(headers, HWID_LIMIT_HEADER)
                            ?.equals("true", ignoreCase = true) == true
                        val isCompatibilityGate = hwidNotSupported || (hwidActive && hwidLimit)
                        if (isCompatibilityGate) {
                            return@withContext SubscriptionHttpResponse(
                                statusCode = statusCode,
                                body = errorText,
                                bodyLength = errorBytes?.size ?: 0,
                                contentType = connection.contentType,
                                headers = headers,
                                headersSummary = headersSummary,
                                bodyPreview = buildSafePreview(errorText),
                                etag = connection.getHeaderField("ETag"),
                                lastModified = connection.getHeaderField("Last-Modified"),
                                notModified = false
                            )
                        }
                        throw IllegalStateException(
                            "HTTP $statusCode при обновлении подписки. ${errorText.take(200)}"
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun extractHeaders(connection: HttpURLConnection): Map<String, String> {
        val result = linkedMapOf<String, String>()
        connection.headerFields.forEach { (key, values) ->
            if (key.isNullOrBlank()) return@forEach
            val value = values
                ?.asSequence()
                ?.mapNotNull { it?.trim() }
                ?.filter { it.isNotBlank() }
                ?.joinToString(", ")
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            result[key] = value
        }
        return result
    }

    private fun summarizeHeaders(headers: Map<String, String>): String {
        if (headers.isEmpty()) return ""
        val interestingKeys = listOf(
            "Content-Encoding",
            "Cache-Control",
            "Server",
            "Date",
            "Content-Length",
            "Profile-Title",
            "Subscription-Userinfo",
            "Announce",
            "Flclashx-Servicename",
            "Flclashx-Servicelogo",
            "Profile-Web-Page-Url",
            "Support-Url",
            HWID_ACTIVE_HEADER,
            HWID_LIMIT_HEADER,
            HWID_NOT_SUPPORTED_HEADER
        )
        return interestingKeys.mapNotNull { key ->
            headerValue(headers, key)?.let { "$key=$it" }
        }.joinToString(separator = "; ").take(420)
    }

    private fun readResponseBytes(stream: InputStream, contentEncoding: String?): ByteArray {
        val encoding = contentEncoding?.lowercase(Locale.US).orEmpty()
        return if (encoding.contains("gzip")) {
            GZIPInputStream(stream).use { it.readBytes() }
        } else {
            stream.readBytes()
        }
    }

    private fun decodeBody(bytes: ByteArray, contentType: String?): String {
        val charset = extractCharset(contentType) ?: StandardCharsets.UTF_8
        return runCatching { String(bytes, charset) }
            .recoverCatching { String(bytes, StandardCharsets.UTF_8) }
            .recoverCatching { String(bytes, StandardCharsets.ISO_8859_1) }
            .getOrDefault(String(bytes, StandardCharsets.UTF_8))
            .removePrefix(BOM)
    }

    private fun extractCharset(contentType: String?): Charset? {
        val raw = contentType ?: return null
        val match = CHARSET_REGEX.find(raw) ?: return null
        val name = match.groupValues.getOrNull(1)?.trim().orEmpty().trim('"')
        if (name.isBlank()) return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    private fun buildSafePreview(body: String): String {
        val singleLine = body
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
        if (singleLine.isBlank()) return ""

        val masked = singleLine
            .replace(Regex("(?i)(vless://)([^@\\s]+)(@)"), "\$1***\$3")
            .replace(Regex("(?i)(trojan://)([^@\\s]+)(@)"), "\$1***\$3")
            .replace(Regex("(?i)(vmess://)([^\\s]{12})[^\\s]*"), "\$1\$2***")
            .replace(Regex("(?i)([?&](?:pbk|publicKey|password|sid|shortId)=)([^&#\\s]*)"), "\$1***")

        return masked.take(BODY_PREVIEW_LIMIT)
    }

    private data class ParsedSubscriptionData(
        val parseResult: SubscriptionParseResult,
        val allProfiles: List<VpnProfile>,
        val connectableProfiles: List<VpnProfile>,
        val markerEntriesCount: Int,
        val duplicateCount: Int,
        val compatibilityMode: String?,
        val providerSite: String?,
        val platformHints: List<String>
    )

    private data class ParsedDataQuality(
        val connectableCount: Int,
        val nonPartialCount: Int,
        val rawJsonCount: Int,
        val realityPresentShortIdCount: Int,
        val realityMissingShortIdCount: Int,
        val markerCount: Int
    ) {
        fun summary(): String {
            return "connectable=$connectableCount nonPartial=$nonPartialCount rawJson=$rawJsonCount " +
                "realityPresentShortId=$realityPresentShortIdCount realityMissingShortId=$realityMissingShortIdCount " +
                "markers=$markerCount"
        }
    }

    private data class ProxyShortIdState(
        val payloadKind: String,
        val outboundCount: Int? = null,
        val proxyTagFound: Boolean = false,
        val matchedTag: String? = null,
        val realityEnabled: Boolean = false,
        val shortId: String? = null
    ) {
        fun shortIdForLog(): String {
            val value = shortId?.trim().orEmpty()
            if (value.isBlank()) return "absent"
            return "present($value)"
        }
    }

    private data class BuildProfilesResult(
        val profiles: List<VpnProfile>,
        val duplicateCount: Int
    )

    private data class MarkerMetadata(
        val compatibilityMode: String? = null,
        val providerSite: String? = null,
        val platformHints: List<String> = emptyList()
    )

    private data class ParsedUserInfo(
        val uploadBytes: Long? = null,
        val downloadBytes: Long? = null,
        val totalBytes: Long? = null,
        val expireAt: Long? = null,
        val usedBytes: Long? = null,
        val remainingBytes: Long? = null
    )

    private data class HeaderMetadata(
        val displayTitle: String? = null,
        val providerName: String? = null,
        val providerDomain: String? = null,
        val providerSite: String? = null,
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
        val logoUrl: String? = null,
        val logoHint: String? = null,
        val tags: List<String> = emptyList(),
        val labelLine: String? = null,
        val providerMessage: String? = null,
        val platformHints: List<String> = emptyList(),
        val compatibilityMode: String? = null,
        val hwidActive: Boolean = false,
        val hwidNotSupported: Boolean = false,
        val metadataHeadersSummary: String? = null,
        val extractedMetadataSummary: String? = null,
        val ignoredMetadataSummary: String? = null
    )

    private data class FetchAttempt(
        val endpointUrl: String,
        val endpointVariant: String,
        val clientMode: SubscriptionClientMode,
        val clientType: String?,
        val userAgent: String,
        val useHwidHeaders: Boolean,
        val hwid: String?
    )

    private data class SubscriptionHttpResponse(
        val statusCode: Int,
        val body: String?,
        val bodyLength: Int,
        val contentType: String?,
        val headers: Map<String, String>,
        val headersSummary: String,
        val bodyPreview: String?,
        val etag: String?,
        val lastModified: String?,
        val notModified: Boolean
    )

    private companion object {
        private const val TAG = "SubscriptionRepo"
        private const val BOM = "\uFEFF"
        private const val DEFAULT_UPDATE_INTERVAL_MINUTES: Int = 60
        private const val MIN_UPDATE_INTERVAL_MINUTES: Int = 15
        private const val MAX_UPDATE_INTERVAL_MINUTES: Int = 24 * 60
        private const val HTTP_TIMEOUT_MS: Int = 12_000
        private const val USER_AGENT: String = "PrivateVPN-Android/1.0"
        private const val HAPP_USER_AGENT: String = "Happ/1.0"
        private const val HAPP_CLIENT_TYPE: String = "happ"
        private const val BODY_PREVIEW_LIMIT: Int = 180
        private const val SHORTID_TRACE_PROFILE_LIMIT: Int = 8
        private const val SHORTID_TRACE_PAYLOAD_CHUNK: Int = 3200
        private const val COMPAT_MODE_PROVIDER_MARKER: String = "provider-marker"
        private const val COMPAT_MODE_HWID_HEADER: String = "hwid-header"
        private const val COMPAT_MODE_HAPP_ENDPOINT: String = "happ-client-endpoint"
        private const val COMPAT_PREFS_NAME: String = "subscription_compat"
        private const val COMPAT_HWID_KEY: String = "compat_hwid"
        private const val HWID_HEADER: String = "x-hwid"
        private const val DEVICE_OS_HEADER: String = "device-os"
        private const val X_DEVICE_OS_HEADER: String = "x-device-os"
        private const val DEVICE_MODEL_HEADER: String = "device-model"
        private const val X_DEVICE_MODEL_HEADER: String = "x-device-model"
        private const val HWID_ACTIVE_HEADER: String = "X-Hwid-Active"
        private const val HWID_LIMIT_HEADER: String = "X-Hwid-Limit"
        private const val HWID_NOT_SUPPORTED_HEADER: String = "X-Hwid-Not-Supported"
        private const val HAPP_ROUTING_HEADER: String = "routing"
        private const val ZERO_UUID: String = "00000000-0000-0000-0000-000000000000"
        private val KNOWN_CLIENT_TYPES = setOf("happ", "v2rayng", "nekobox", "clash", "sing-box")
        private val MARKER_PORTS = 0..1
        private val MARKER_HOSTS = setOf("0.0.0.0", "127.0.0.1", "localhost")
        private val SERVICE_MARKER_KEYWORDS = listOf(
            "приложение не поддерживается",
            "установите приложение",
            "install app",
            "not supported",
            "happ",
            "flclash",
            "rabbithole",
            "android",
            "apple",
            "windows"
        )
        private val CHARSET_REGEX = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
        private val USERINFO_FIELD_REGEX = Regex("(upload|download|total|expire)\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val URL_REGEX = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
        private val KNOWN_METADATA_HEADERS = setOf(
            "profile-title",
            "x-profile-title",
            "subscription-userinfo",
            "subscription-user-info",
            "support-url",
            "x-support-url",
            "announce",
            "announcement",
            "profile-announcement",
            "profile-web-page-url",
            "profile-web-page-url",
            "home-page-url",
            "provider-url",
            "provider-site",
            "provider-domain",
            "service-name",
            "flclashx-servicename",
            "service-logo",
            "flclashx-servicelogo",
            "plan-id",
            "subscription-plan",
            "x-plan-id",
            "user-id",
            "x-user-id",
            "subscription-user-id",
            "profile-note",
            "subscription-note",
            "note",
            "badge",
            "profile-badge",
            "x-profile-badge",
            "provider-message",
            "service-message",
            "tag",
            "tags",
            "service-tags",
            "emoji-tags",
            "profile-tag",
            "x-profile-tags",
            "label-line",
            "emoji-legend",
            "legend",
            "service-legend",
            "legend-line",
            "emoji-description",
            "emoji-meaning",
            "provider-remark",
            "service-remark",
            "tag-line",
            "provider-note",
            "service-note"
        )
    }
}
