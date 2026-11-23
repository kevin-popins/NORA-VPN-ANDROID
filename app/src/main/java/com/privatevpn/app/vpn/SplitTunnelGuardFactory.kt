package com.privatevpn.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.os.Build
import com.privatevpn.app.vpn.dataplane.SplitTunnelPacketPolicy
import com.privatevpn.app.vpn.dataplane.TunPacketPolicy

/** One immutable admission policy for the same applications accepted by VpnService.Builder. */
object SplitTunnelGuardFactory {
    fun create(
        context: Context,
        enabled: Boolean,
        trustedPackages: Set<String>,
        dnsServers: List<String>
    ): TunPacketPolicy? {
        if (!enabled) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("Безопасный раздельный туннель требует Android 10 или новее")
        }
        val packageManager = context.packageManager
        val packages = trustedPackages.map { it.trim() }.filter { it.isNotEmpty() && it != context.packageName }.toSet()
        val trustedUids = packages.mapNotNull { name ->
            runCatching { packageManager.getApplicationInfo(name, 0).uid }.getOrNull()
        }.toSet()
        check(trustedUids.isNotEmpty()) { "Нет доступных доверенных приложений для защищённого туннеля" }
        val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        val dns = dnsServers.mapNotNull { runCatching { InetAddresses.parseNumericAddress(it) }.getOrNull() }.toSet()
        val ownUid = context.applicationInfo.uid
        return SplitTunnelPacketPolicy(
            ownerLookup = { protocol, local, remote -> connectivity.getConnectionOwnerUid(protocol, local, remote) },
            uidAllowed = { uid ->
                uid != ownUid && (uid in trustedUids || runCatching {
                    // OEM clone/work profiles may use another UID for an explicitly selected package.
                    packageManager.getPackagesForUid(uid).orEmpty().any { it in packages }
                }.getOrDefault(false))
            },
            configuredDns = dns
        )
    }
}