package com.privatevpn.app.core.backend.xray

import com.privatevpn.app.core.backend.adapter.BackendAdapter
import com.privatevpn.app.core.backend.adapter.BackendStartResult
import com.privatevpn.app.core.backend.config.ConfigNormalizer
import com.privatevpn.app.core.backend.runtime.DataPlaneProxy
import com.privatevpn.app.core.backend.runtime.RuntimeConfigPreparer
import com.privatevpn.app.core.backend.runtime.RuntimePreparationInput
import com.privatevpn.app.profiles.model.VpnProfile
import com.privatevpn.app.settings.SocksSettings
import com.privatevpn.app.vpn.tunnel.TunnelConnectRequest
import com.privatevpn.app.vpn.tunnel.TunnelDataPlaneOptions
import com.privatevpn.app.vpn.tunnel.VpnTunnelLifecycle
import java.util.UUID

class XrayBackendAdapter(
    private val configNormalizer: ConfigNormalizer,
    private val runtimeConfigPreparer: RuntimeConfigPreparer,
    private val tunnelLifecycle: VpnTunnelLifecycle
) : BackendAdapter {

    override fun start(
        profile: VpnProfile,
        dnsServers: List<String>,
        privateSessionEnabled: Boolean,
        privateSessionTrustedPackages: Set<String>,
        socksSettings: SocksSettings
    ): Result<BackendStartResult> {
        return runCatching {
            check(profile.isConnectable) { "Запись недоступна для подключения" }
            val normalized = configNormalizer.normalize(profile)
            val dataPlaneProxy = createDataPlaneProxy(excludePort = socksSettings.port.takeIf { socksSettings.enabled })
            val runtime = runtimeConfigPreparer.prepare(
                RuntimePreparationInput(
                    normalizedConfig = normalized,
                    dnsServers = dnsServers,
                    privateSessionEnabled = privateSessionEnabled,
                    socksSettings = socksSettings,
                    dataPlaneProxy = dataPlaneProxy
                )
            )

            tunnelLifecycle.connect(
                TunnelConnectRequest(
                    dnsServers = dnsServers,
                    runtimeConfig = runtime.runtimeConfig,
                    profileId = profile.id,
                    profileName = profile.displayName,
                    endpointProtectionEnabled = profile.endpointProtectionEnabled,
                    privateSessionEnabled = privateSessionEnabled,
                    trustedPackages = privateSessionTrustedPackages,
                    dataPlane = TunnelDataPlaneOptions(
                        socksHost = runtime.dataPlaneProxy.host,
                        socksPort = runtime.dataPlaneProxy.port,
                        socksUsername = runtime.dataPlaneProxy.username,
                        socksPassword = runtime.dataPlaneProxy.password
                    )
                )
            ).getOrThrow()

            BackendStartResult(
                runtimeConfigPreview = runtime.runtimeConfig,
                notes = runtime.notes
            )
        }
    }

    override fun stop(): Result<Unit> {
        return tunnelLifecycle.disconnect()
    }

    private fun createDataPlaneProxy(excludePort: Int?): DataPlaneProxy {
        val candidatePort = generateSequence {
            INTERNAL_SOCKS_PORT_RANGE.random()
        }.first { candidate ->
            candidate != excludePort
        }
        val candidateHost = buildInternalLoopbackHost()

        val username = "vpn${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val password = UUID.randomUUID().toString().replace("-", "")

        return DataPlaneProxy(
            host = candidateHost,
            port = candidatePort,
            username = username,
            password = password
        )
    }

    private fun buildInternalLoopbackHost(): String {
        val b2 = (10..220).random()
        val b3 = (10..220).random()
        val b4 = (10..220).random()
        return "127.$b2.$b3.$b4"
    }

    private companion object {
        val INTERNAL_SOCKS_PORT_RANGE = 20000..64000
    }
}
