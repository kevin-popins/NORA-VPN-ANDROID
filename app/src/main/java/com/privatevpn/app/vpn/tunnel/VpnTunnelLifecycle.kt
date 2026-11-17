package com.privatevpn.app.vpn.tunnel

data class TunnelDataPlaneOptions(
    val socksHost: String,
    val socksPort: Int,
    val socksUsername: String,
    val socksPassword: String
)

data class TunnelConnectRequest(
    val dnsServers: List<String>,
    val runtimeConfig: String,
    val profileId: String,
    val profileName: String?,
    val privateSessionEnabled: Boolean,
    val trustedPackages: Set<String>,
    val dataPlane: TunnelDataPlaneOptions,
    val endpointProtectionEnabled: Boolean = false
)

interface VpnTunnelLifecycle {
    fun connect(request: TunnelConnectRequest): Result<Unit>
    fun disconnect(): Result<Unit>
}
