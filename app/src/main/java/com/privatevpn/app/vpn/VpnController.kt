package com.privatevpn.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.privatevpn.app.core.error.AppErrorCode
import com.privatevpn.app.core.error.AppErrors
import com.privatevpn.app.core.error.AppException
import com.privatevpn.app.vpn.tunnel.TunnelConnectRequest
import kotlinx.coroutines.flow.StateFlow

class VpnController(private val appContext: Context) {

    val status: StateFlow<VpnConnectionStatus> = VpnRuntimeStateStore.status
    val runtimeError: StateFlow<String?> = VpnRuntimeStateStore.lastError
    val appTrafficMode: StateFlow<AppTrafficMode> = VpnRuntimeStateStore.appTrafficMode

    init {
        refreshPermissionState()
    }

    fun getPrepareIntent(): Intent? = VpnService.prepare(appContext)

    fun refreshPermissionState() {
        if (getPrepareIntent() != null) {
            VpnRuntimeStateStore.stopTrafficSampling()
            VpnRuntimeStateStore.setInternalDataPlanePort(null)
            VpnRuntimeStateStore.setAppTrafficMode(AppTrafficMode.UNKNOWN)
            VpnRuntimeStateStore.setStatus(VpnConnectionStatus.NO_PERMISSION)
            VpnQuickSettingsTileService.requestTileStateRefresh(appContext)
            return
        }

        if (status.value == VpnConnectionStatus.NO_PERMISSION) {
            VpnRuntimeStateStore.setStatus(VpnConnectionStatus.READY)
        }
    }

    fun connect(request: TunnelConnectRequest): Result<Unit> {
        if (getPrepareIntent() != null) {
            VpnRuntimeStateStore.setStatus(VpnConnectionStatus.NO_PERMISSION)
            val appError = AppErrors.vpnPermissionRequired(
                technicalReason = "VpnService.prepare != null в VpnController.connect"
            )
            VpnRuntimeStateStore.setError(appError.toUiMessage())
            return Result.failure(AppException(appError))
        }

        if (status.value == VpnConnectionStatus.CONNECTED) {
            return Result.success(Unit)
        }
        if (status.value == VpnConnectionStatus.CONNECTING) {
            return Result.success(Unit)
        }

        return runCatching {
            VpnRuntimeStateStore.setStatus(VpnConnectionStatus.CONNECTING)
            val intent = Intent(appContext, PrivateVpnService::class.java).apply {
                action = PrivateVpnService.ACTION_CONNECT
                putStringArrayListExtra(
                    PrivateVpnService.EXTRA_DNS_SERVERS,
                    ArrayList(request.dnsServers)
                )
                putExtra(PrivateVpnService.EXTRA_RUNTIME_CONFIG, request.runtimeConfig)
                putExtra(PrivateVpnService.EXTRA_PROFILE_ID, request.profileId)
                putExtra(PrivateVpnService.EXTRA_PROFILE_NAME, request.profileName)
                putExtra(PrivateVpnService.EXTRA_PRIVATE_SESSION_ENABLED, request.privateSessionEnabled)
                putStringArrayListExtra(
                    PrivateVpnService.EXTRA_PRIVATE_SESSION_TRUSTED_PACKAGES,
                    ArrayList(request.trustedPackages)
                )
                putExtra(PrivateVpnService.EXTRA_DATAPLANE_SOCKS_HOST, request.dataPlane.socksHost)
                putExtra(PrivateVpnService.EXTRA_DATAPLANE_SOCKS_PORT, request.dataPlane.socksPort)
                putExtra(PrivateVpnService.EXTRA_DATAPLANE_SOCKS_USERNAME, request.dataPlane.socksUsername)
                putExtra(PrivateVpnService.EXTRA_DATAPLANE_SOCKS_PASSWORD, request.dataPlane.socksPassword)
            }
            ContextCompat.startForegroundService(appContext, intent)
            Unit
        }.onFailure { error ->
            val appError = AppErrors.fromThrowable(
                error = error,
                fallbackCode = AppErrorCode.BACKEND_003,
                fallbackUserMessage = "Не удалось запустить VPN сервис"
            )
            VpnRuntimeStateStore.setError(appError.toUiMessage())
        }
    }

    fun disconnect(): Result<Unit> {
        return runCatching {
            val intent = Intent(appContext, PrivateVpnService::class.java).apply {
                action = PrivateVpnService.ACTION_DISCONNECT
            }
            appContext.startService(intent)
            Unit
        }.onFailure { error ->
            val appError = AppErrors.fromThrowable(
                error = error,
                fallbackCode = AppErrorCode.BACKEND_003,
                fallbackUserMessage = "Не удалось отправить команду отключения"
            )
            VpnRuntimeStateStore.setError(appError.toUiMessage())
        }
    }

    fun updateActiveProfileName(profileName: String?) {
        VpnRuntimeStateStore.setLastSelectedProfileName(profileName)

        if (status.value != VpnConnectionStatus.CONNECTED && status.value != VpnConnectionStatus.CONNECTING) {
            return
        }
        if (!PrivateVpnService.isRunning()) {
            return
        }

        runCatching {
            val intent = Intent(appContext, PrivateVpnService::class.java).apply {
                action = PrivateVpnService.ACTION_UPDATE_NOTIFICATION_PROFILE
                putExtra(PrivateVpnService.EXTRA_PROFILE_NAME, profileName)
            }
            appContext.startService(intent)
        }
    }

    fun onPermissionResult() {
        refreshPermissionState()
    }

    fun clearRuntimeError() {
        VpnRuntimeStateStore.clearError()
    }
}
