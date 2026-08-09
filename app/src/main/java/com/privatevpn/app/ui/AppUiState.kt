package com.privatevpn.app.ui

import com.privatevpn.app.core.log.EventLogEntry
import com.privatevpn.app.private_session.PrivateSessionUiState
import com.privatevpn.app.profiles.model.SubscriptionSource
import com.privatevpn.app.profiles.model.VpnProfile
import com.privatevpn.app.settings.DnsState
import com.privatevpn.app.settings.SettingsState
import com.privatevpn.app.vpn.PrivateSessionState
import com.privatevpn.app.vpn.AppTrafficMode
import com.privatevpn.app.vpn.VpnConnectionStatus
import com.privatevpn.app.vpn.VpnTrafficState
import com.privatevpn.app.vpn.VpnSessionRecord

data class AppUiState(
    val profilesLoaded: Boolean = false,
    val vpnStatus: VpnConnectionStatus = VpnConnectionStatus.NO_PERMISSION,
    val profiles: List<VpnProfile> = emptyList(),
    val subscriptions: List<SubscriptionSource> = emptyList(),
    val refreshingSubscriptionIds: Set<String> = emptySet(),
    val activeProfileId: String? = null,
    val activeProfile: VpnProfile? = null,
    val activeProfileServer: String? = null,
    val connectionError: String? = null,
    val serverPingResults: Map<String, String> = emptyMap(),
    val pingInProgress: Boolean = false,
    val eventLogs: List<EventLogEntry> = emptyList(),
    val privateSessionState: PrivateSessionState = PrivateSessionState(),
    val privateSessionUiState: PrivateSessionUiState = PrivateSessionUiState(),
    val settingsState: SettingsState = SettingsState(),
    val dnsState: DnsState = DnsState(),
    val appTrafficMode: AppTrafficMode = AppTrafficMode.UNKNOWN,
    val traffic: VpnTrafficState = VpnTrafficState(),
    val sessionHistory: List<VpnSessionRecord> = emptyList(),
    val notificationPermission: NotificationPermissionUiState = NotificationPermissionUiState(),
    val transientMessage: String? = null
)
