package com.privatevpn.app.profiles.model

import com.privatevpn.app.profiles.subscriptions.SubscriptionServiceRecords

data class VpnProfile(
    val id: String,
    val displayName: String,
    val type: ProfileType,
    val sourceRaw: String,
    val normalizedJson: String?,
    val dnsServers: List<String>,
    val dnsFallbackApplied: Boolean,
    val isPartialImport: Boolean,
    val importWarnings: List<String>,
    val importedAtMs: Long,
    val parentSubscriptionId: String? = null,
    val sourceOrder: Int = 0,
    val endpointProtectionEnabled: Boolean = false,
    val protectedPublicLabel: String? = null,
    val presentationName: String? = null,
    val isTechnicalEntry: Boolean = SubscriptionServiceRecords.isKnownTechnicalEntry(sourceRaw, normalizedJson)
) {
    private val isCachedServiceMarker = parentSubscriptionId != null &&
        SubscriptionServiceRecords.isServiceRecord(sourceRaw, normalizedJson)
    val isConnectable: Boolean get() = !isTechnicalEntry && !isCachedServiceMarker
}
