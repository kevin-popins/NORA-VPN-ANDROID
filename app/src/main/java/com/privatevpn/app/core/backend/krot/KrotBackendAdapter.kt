package com.privatevpn.app.core.backend.krot

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.privatevpn.app.core.backend.adapter.BackendAdapter
import com.privatevpn.app.core.backend.adapter.BackendStartResult
import com.privatevpn.app.core.error.AppErrors
import com.privatevpn.app.core.error.AppException
import com.privatevpn.app.profiles.model.VpnProfile
import com.privatevpn.app.settings.SocksSettings
import com.privatevpn.app.vpn.VpnConnectionStatus
import com.privatevpn.app.vpn.VpnQuickSettingsTileService
import com.privatevpn.app.vpn.VpnRuntimeStateStore
import com.privatevpn.app.vpn.krot.KrotVpnService

class KrotBackendAdapter(
    appContext: Context
) : BackendAdapter {
    private val context = appContext.applicationContext

    override fun start(
        profile: VpnProfile,
        dnsServers: List<String>,
        privateSessionEnabled: Boolean,
        privateSessionTrustedPackages: Set<String>,
        socksSettings: SocksSettings
    ): Result<BackendStartResult> {
        return runCatching {
            if (VpnService.prepare(context) != null) {
                VpnRuntimeStateStore.setStatus(VpnConnectionStatus.NO_PERMISSION)
                throw AppException(
                    AppErrors.vpnPermissionRequired(
                        technicalReason = "VpnService.prepare != null in KrotBackendAdapter.start"
                    )
                )
            }

            val payload = profile.normalizedJson ?: profile.sourceRaw
            val spec = KrotConnectionSpec.parseProfilePayload(payload)

            VpnRuntimeStateStore.setStatus(VpnConnectionStatus.CONNECTING)
            VpnRuntimeStateStore.setLastSelectedProfileName(profile.displayName)
            val intent = Intent(context, KrotVpnService::class.java).apply {
                action = KrotVpnService.ACTION_CONNECT
                putExtra(KrotVpnService.EXTRA_PROFILE_JSON, spec.normalizedJson)
                putExtra(KrotVpnService.EXTRA_PROFILE_ID, profile.id)
                putExtra(KrotVpnService.EXTRA_PROFILE_NAME, profile.displayName)
                putStringArrayListExtra(KrotVpnService.EXTRA_DNS_SERVERS, ArrayList(dnsServers))
                putExtra(KrotVpnService.EXTRA_PRIVATE_SESSION_ENABLED, privateSessionEnabled)
                putStringArrayListExtra(
                    KrotVpnService.EXTRA_PRIVATE_SESSION_TRUSTED_PACKAGES,
                    ArrayList(privateSessionTrustedPackages)
                )
            }
            ContextCompat.startForegroundService(context, intent)
            VpnQuickSettingsTileService.requestTileStateRefresh(context)

            val notes = buildList {
                add("KRot transport: ${spec.transportProfile}")
                add("KRot server: ${spec.server.host}:${spec.server.port}")
                add("KRot TLS SNI: ${spec.server.tlsName}")
                if (socksSettings.enabled) {
                    add("В KRot-режиме пользовательский localhost SOCKS не используется backend-ом")
                }
            }
            BackendStartResult(
                runtimeConfigPreview = spec.normalizedJson,
                notes = notes
            )
        }
    }

    override fun stop(): Result<Unit> {
        return runCatching {
            val intent = Intent(context, KrotVpnService::class.java).apply {
                action = KrotVpnService.ACTION_DISCONNECT
            }
            context.startService(intent)
            VpnQuickSettingsTileService.requestTileStateRefresh(context)
            Unit
        }
    }
}
