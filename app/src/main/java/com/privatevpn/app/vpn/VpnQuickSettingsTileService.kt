package com.privatevpn.app.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.privatevpn.app.MainActivity
import com.privatevpn.app.R
import com.privatevpn.app.core.backend.adapter.BackendAdapter
import com.privatevpn.app.core.backend.awg.AmneziaWgBackendAdapter
import com.privatevpn.app.core.backend.awg.AmneziaWgRuntimeConfigBuilder
import com.privatevpn.app.core.backend.krot.KrotBackendAdapter
import com.privatevpn.app.core.backend.xray.XrayBackendAdapter
import com.privatevpn.app.core.backend.xray.XrayConfigNormalizer
import com.privatevpn.app.core.backend.xray.XrayRuntimeConfigPreparer
import com.privatevpn.app.core.dns.DefaultDnsProvider
import com.privatevpn.app.core.error.AppErrorCode
import com.privatevpn.app.core.error.AppErrors
import com.privatevpn.app.core.error.AppException
import com.privatevpn.app.profiles.db.PrivateVpnDatabase
import com.privatevpn.app.profiles.model.ProfileType
import com.privatevpn.app.profiles.model.VpnProfile
import com.privatevpn.app.profiles.repository.RoomProfilesRepository
import com.privatevpn.app.settings.DnsMode
import com.privatevpn.app.settings.storage.DataStoreUserSettingsRepository
import com.privatevpn.app.vpn.tunnel.AndroidVpnTunnelLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VpnQuickSettingsTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        serviceScope.launch {
            warmupLastProfileName()
            updateTileState()
        }
    }

    override fun onClick() {
        super.onClick()

        serviceScope.launch {
            val toggler = VpnQuickToggleExecutor(applicationContext)
            try {
                val result = toggler.toggle()
                if (result.isFailure) {
                    val appError = AppErrors.fromThrowable(
                        error = result.exceptionOrNull() ?: IllegalStateException("tile toggle failure"),
                        fallbackCode = AppErrorCode.TILE_002
                    )
                    VpnRuntimeStateStore.setError(appError.toUiMessage())
                    Log.w(TAG, appError.toLogMessage())
                }
                updateTileState()
            } finally {
                toggler.close()
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val status = VpnRuntimeStateStore.status.value
        val profile = VpnRuntimeStateStore.lastSelectedProfileName.value
            ?: getString(R.string.vpn_notification_profile_unknown)

        tile.label = getString(R.string.quick_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_vpn_foreground)

        val subtitle = when (status) {
            VpnConnectionStatus.CONNECTED -> getString(R.string.quick_tile_subtitle_connected, profile)
            VpnConnectionStatus.CONNECTING -> getString(R.string.quick_tile_subtitle_connecting, profile)
            VpnConnectionStatus.ERROR -> getString(R.string.quick_tile_subtitle_error, profile)
            else -> getString(R.string.quick_tile_subtitle_disconnected, profile)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        } else {
            tile.label = "${getString(R.string.quick_tile_label)}: $subtitle"
        }

        tile.state = when (status) {
            VpnConnectionStatus.CONNECTED,
            VpnConnectionStatus.CONNECTING -> Tile.STATE_ACTIVE

            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    private suspend fun warmupLastProfileName() {
        if (!VpnRuntimeStateStore.lastSelectedProfileName.value.isNullOrBlank()) return

        runCatching {
            val database = PrivateVpnDatabase.build(applicationContext)
            try {
                val profiles = RoomProfilesRepository(database.profileDao()).profiles.first()
                val settings = DataStoreUserSettingsRepository(applicationContext).settings.first()
                val activeName = profiles.firstOrNull { it.id == settings.activeProfileId }?.displayName
                if (!activeName.isNullOrBlank()) {
                    VpnRuntimeStateStore.setLastSelectedProfileName(activeName)
                }
            } finally {
                database.close()
            }
        }
    }

    companion object {
        private const val TAG = "NoraVpnTile"
        const val EXTRA_REQUEST_VPN_PERMISSION: String = "extra_request_vpn_permission"

        fun requestTileStateRefresh(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, VpnQuickSettingsTileService::class.java)
                )
            }
        }
    }
}

private class VpnQuickToggleExecutor(
    appContext: Context
) {
    private val context = appContext.applicationContext
    private val database by lazy {
        PrivateVpnDatabase.build(context)
    }
    private val profilesRepository by lazy { RoomProfilesRepository(database.profileDao()) }
    private val userSettingsRepository by lazy { DataStoreUserSettingsRepository(context) }
    private val vpnManager by lazy { VpnManager(VpnController(context)) }

    private val xrayBackendAdapter by lazy {
        XrayBackendAdapter(
            configNormalizer = XrayConfigNormalizer(),
            runtimeConfigPreparer = XrayRuntimeConfigPreparer(),
            tunnelLifecycle = AndroidVpnTunnelLifecycle(vpnManager)
        )
    }
    private val awgBackendAdapter by lazy {
        AmneziaWgBackendAdapter(
            appContext = context,
            runtimeConfigBuilder = AmneziaWgRuntimeConfigBuilder()
        )
    }
    private val krotBackendAdapter by lazy {
        KrotBackendAdapter(appContext = context)
    }

    suspend fun toggle(): Result<Unit> {
        return when (vpnManager.status.value) {
            VpnConnectionStatus.CONNECTED,
            VpnConnectionStatus.CONNECTING -> disconnect()

            else -> connect()
        }
    }

    private suspend fun connect(): Result<Unit> {
        if (VpnService.prepare(context) != null) {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(VpnQuickSettingsTileService.EXTRA_REQUEST_VPN_PERMISSION, true)
            }
            context.startActivity(openIntent)
            return Result.failure(
                AppException(
                    AppErrors.tilePermissionRequired(
                        technicalReason = "VpnService.prepare != null in quick tile connect"
                    )
                )
            )
        }

        val settings = userSettingsRepository.settings.first()
        val profiles = profilesRepository.profiles.first()
        val profile = resolveProfile(settings.activeProfileId, profiles)
            ?: return Result.failure(
                AppException(
                    AppErrors.tileToggleFailed(
                        technicalReason = "Нет импортированного профиля для подключения из quick tile"
                    )
                )
            )

        val dnsServers = resolveDns(settings.dnsMode, settings.customDnsServers, profile)
        val adapter = resolveBackend(profile.type)

        return adapter.start(
            profile = profile,
            dnsServers = dnsServers,
            privateSessionEnabled = settings.privateSessionEnabled,
            privateSessionTrustedPackages = settings.privateSessionTrustedPackages,
            socksSettings = settings.socksSettings
        ).map {
            VpnRuntimeStateStore.setLastSelectedProfileName(profile.displayName)
            if (settings.activeProfileId == null) {
                userSettingsRepository.setActiveProfile(profile.id)
            }
            Unit
        }
    }

    private suspend fun disconnect(): Result<Unit> {
        val xrayResult = runCatching { xrayBackendAdapter.stop().getOrThrow() }
        val awgResult = runCatching { awgBackendAdapter.stop().getOrThrow() }
        val krotResult = runCatching { krotBackendAdapter.stop().getOrThrow() }

        return if (xrayResult.isSuccess || awgResult.isSuccess || krotResult.isSuccess) {
            Result.success(Unit)
        } else {
            val firstError = xrayResult.exceptionOrNull()
                ?: awgResult.exceptionOrNull()
                ?: krotResult.exceptionOrNull()
            Result.failure(firstError ?: IllegalStateException("Не удалось отключить VPN"))
        }
    }

    private fun resolveBackend(type: ProfileType): BackendAdapter {
        return when (type) {
            ProfileType.AMNEZIA_WG_20 -> awgBackendAdapter
            ProfileType.KROT -> krotBackendAdapter
            else -> xrayBackendAdapter
        }
    }

    private fun resolveProfile(activeProfileId: String?, profiles: List<VpnProfile>): VpnProfile? {
        if (profiles.isEmpty()) return null
        return profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()
    }

    private fun resolveDns(mode: DnsMode, customDns: List<String>, activeProfile: VpnProfile): List<String> {
        val profileServers = activeProfile.dnsServers.filter { it.isNotBlank() }

        return when (mode) {
            DnsMode.FROM_PROFILE -> profileServers.ifEmpty { DefaultDnsProvider.defaultServers }
            DnsMode.APP_DEFAULT -> DefaultDnsProvider.defaultServers
            DnsMode.CUSTOM -> customDns.filter { it.isNotBlank() }.ifEmpty { DefaultDnsProvider.defaultServers }
        }
    }

    fun close() {
        runCatching { database.close() }
    }
}
