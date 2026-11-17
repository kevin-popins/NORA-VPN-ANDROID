package com.privatevpn.app.profiles.subscriptions

import com.privatevpn.app.profiles.model.SubscriptionSyncStatus

enum class SubscriptionContentAcceptance {
    NONE, FRESH_PROFILES, TECHNICAL_ENTRIES;
    val accepted: Boolean get() = this != NONE

    companion object {
        fun classify(validatedDisplayBody: Boolean, connectableProfilesCount: Int, technicalEntriesCount: Int): SubscriptionContentAcceptance = when {
            !validatedDisplayBody -> NONE
            connectableProfilesCount == 0 && technicalEntriesCount > 0 -> TECHNICAL_ENTRIES
            connectableProfilesCount > 0 -> FRESH_PROFILES
            else -> NONE
        }
    }
}

data class SubscriptionRefreshResult(
    val subscriptionId: String,
    val status: SubscriptionSyncStatus,
    val importedProfilesCount: Int,
    val invalidEntriesCount: Int,
    val message: String? = null,
    val acceptance: SubscriptionContentAcceptance = SubscriptionContentAcceptance.NONE
)
