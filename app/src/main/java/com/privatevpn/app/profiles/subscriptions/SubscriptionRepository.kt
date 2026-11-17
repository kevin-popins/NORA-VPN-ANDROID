package com.privatevpn.app.profiles.subscriptions

import com.privatevpn.app.profiles.model.SubscriptionSource
import kotlinx.coroutines.flow.Flow

interface SubscriptionRepository {
    val subscriptions: Flow<List<SubscriptionSource>>

    suspend fun addSubscription(sourceUrl: String, displayName: String?): SubscriptionSource
    suspend fun importValidatedSubscription(sourceUrl: String, displayName: String?): SubscriptionSource
    suspend fun deleteSubscription(subscriptionId: String)
    suspend fun renameSubscription(subscriptionId: String, displayName: String)
    suspend fun toggleCollapse(subscriptionId: String)
    suspend fun setEnabled(subscriptionId: String, enabled: Boolean)
    suspend fun setAutoUpdateEnabled(subscriptionId: String, enabled: Boolean)
    suspend fun setUpdateIntervalMinutes(subscriptionId: String, minutes: Int)
    suspend fun setLastSelectedProfile(subscriptionId: String, profileId: String?)
    suspend fun refreshSubscription(subscriptionId: String, force: Boolean = true): SubscriptionRefreshResult
    suspend fun refreshAllSubscriptions(force: Boolean = true): List<SubscriptionRefreshResult>
    suspend fun getSubscription(subscriptionId: String): SubscriptionSource?
}
