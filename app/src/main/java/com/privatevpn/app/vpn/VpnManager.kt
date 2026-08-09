package com.privatevpn.app.vpn

import android.content.Intent
import com.privatevpn.app.vpn.tunnel.TunnelConnectRequest
import kotlinx.coroutines.flow.StateFlow

class VpnManager(private val controller: VpnController) {
    val status: StateFlow<VpnConnectionStatus> = controller.status
    val runtimeError: StateFlow<String?> = controller.runtimeError
    val appTrafficMode: StateFlow<AppTrafficMode> = controller.appTrafficMode
    val traffic: StateFlow<VpnTrafficState> = VpnRuntimeStateStore.traffic

    fun getPrepareIntent(): Intent? = controller.getPrepareIntent()

    fun refreshPermissionState() {
        controller.refreshPermissionState()
    }

    fun onPermissionResult() {
        controller.onPermissionResult()
    }

    fun connect(request: TunnelConnectRequest): Result<Unit> {
        return controller.connect(request)
    }

    fun disconnect(): Result<Unit> {
        return controller.disconnect()
    }

    fun clearRuntimeError() {
        controller.clearRuntimeError()
    }

    fun updateActiveProfileName(profileName: String?) {
        controller.updateActiveProfileName(profileName)
    }
}
