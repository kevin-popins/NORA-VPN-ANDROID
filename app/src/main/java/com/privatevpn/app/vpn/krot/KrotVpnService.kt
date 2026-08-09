package com.privatevpn.app.vpn.krot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.privatevpn.app.MainActivity
import com.privatevpn.app.R
import com.privatevpn.app.core.backend.krot.KrotConnectionSpec
import com.privatevpn.app.core.dns.DefaultDnsProvider
import com.privatevpn.app.core.error.AppError
import com.privatevpn.app.core.error.AppErrors
import com.privatevpn.app.vpn.AppTrafficMode
import com.privatevpn.app.vpn.VpnConnectionStatus
import com.privatevpn.app.vpn.VpnQuickSettingsTileService
import com.privatevpn.app.vpn.VpnRuntimeStateStore
import com.privatevpn.app.vpn.VpnSessionHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class KrotVpnService : VpnService() {

    private data class PrivateSessionPolicy(
        val enabled: Boolean,
        val trustedPackages: Set<String>
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val connectLock = Any()
    private val runtimeLogTail = ArrayDeque<String>()
    private val networkAvailable = AtomicBoolean(true)

    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var session: KrotTunnelSession? = null

    @Volatile
    private var handlingFailure = false

    @Volatile
    private var connectInProgress = false

    @Volatile
    private var currentProfileName: String? = null

    override fun onCreate() {
        super.onCreate()
        registerNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val profileJson = intent.getStringExtra(EXTRA_PROFILE_JSON).orEmpty()
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
                val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME)?.trim()
                    ?.takeIf { it.isNotBlank() }
                val dns = intent.getStringArrayListExtra(EXTRA_DNS_SERVERS)?.toList().orEmpty()
                val privateSessionPolicy = PrivateSessionPolicy(
                    enabled = intent.getBooleanExtra(EXTRA_PRIVATE_SESSION_ENABLED, false),
                    trustedPackages = intent.getStringArrayListExtra(EXTRA_PRIVATE_SESSION_TRUSTED_PACKAGES)
                        ?.toSet()
                        .orEmpty()
                )

                serviceScope.launch {
                    connectVpn(
                        profileJson = profileJson,
                        profileId = profileId,
                        profileName = profileName,
                        requestedDns = dns,
                        privateSessionPolicy = privateSessionPolicy
                    )
                }
            }

            ACTION_DISCONNECT -> {
                serviceScope.launch {
                    disconnectVpn()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterNetworkMonitor()
        cleanupResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun connectVpn(
        profileJson: String,
        profileId: String,
        profileName: String?,
        requestedDns: List<String>,
        privateSessionPolicy: PrivateSessionPolicy
    ) {
        if (profileJson.isBlank()) {
            failWithError(
                AppErrors.krotRuntimeStartFailed(
                    technicalReason = "KRot profile JSON is blank for profile '$profileId'"
                )
            )
            return
        }

        synchronized(connectLock) {
            if (connectInProgress) {
                appendRuntimeLog("Повторный KRot ACTION_CONNECT проигнорирован: подключение уже выполняется")
                return
            }
            if (
                session != null &&
                VpnRuntimeStateStore.status.value == VpnConnectionStatus.CONNECTED
            ) {
                appendRuntimeLog("Повторный KRot ACTION_CONNECT проигнорирован: VPN уже подключён")
                return
            }
            connectInProgress = true
        }

        handlingFailure = false
        clearRuntimeLog()
        currentProfileName = profileName
        VpnRuntimeStateStore.setLastSelectedProfileName(currentProfileName)
        VpnRuntimeStateStore.setStatus(VpnConnectionStatus.CONNECTING)
        VpnQuickSettingsTileService.requestTileStateRefresh(this)
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            runCatching {
                val spec = KrotConnectionSpec.parseProfilePayload(profileJson)
                val dnsServers = requestedDns.ifEmpty { spec.dnsServers }.ifEmpty { DefaultDnsProvider.defaultServers }
                appendRuntimeLog(
                    "KRot profile '${spec.profileId}' transport=${spec.transportProfile} " +
                        "server=${spec.server.host}:${spec.server.port}"
                )
                appendRuntimeLog("KRot DNS: ${dnsServers.joinToString()}")

                val openedSession = KrotTunnelSession(
                    spec = spec,
                    establishTunInterface = {
                        establishTunInterface(
                            spec = spec,
                            requestedDns = dnsServers,
                            privateSessionPolicy = privateSessionPolicy
                        )
                    },
                    protectSocket = { socket -> protect(socket) },
                    isNetworkAvailable = { networkAvailable.get() },
                    log = ::appendRuntimeLog,
                    onFailure = { error ->
                        failWithError(
                            AppErrors.krotRuntimeStartFailed(
                                technicalReason = error.message ?: "KRot data-plane loop failed"
                            )
                        )
                    }
                )
                session = openedSession
                openedSession.start()

                VpnRuntimeStateStore.setInternalDataPlanePort(null)
                VpnRuntimeStateStore.setStatus(VpnConnectionStatus.CONNECTED)
                VpnRuntimeStateStore.startTrafficSampling(applicationInfo.uid)
                updateForegroundNotification()
            }.onFailure { error ->
                failWithError(
                    AppErrors.krotRuntimeStartFailed(
                        technicalReason = error.message ?: "Не удалось запустить KRot runtime"
                    )
                )
            }
        } finally {
            synchronized(connectLock) {
                connectInProgress = false
            }
        }
    }

    private fun disconnectVpn() {
        runCatching {
            cleanupResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            updateReadyOrNoPermission()
            VpnQuickSettingsTileService.requestTileStateRefresh(this)
        }.onFailure { error ->
            val appError = AppErrors.krotRuntimeStopFailed(
                technicalReason = error.message ?: "Ошибка при отключении KRot VPN"
            )
            VpnRuntimeStateStore.setError(appError.toUiMessage())
            Log.w(TAG, appError.toLogMessage(), error)
        }
    }

    private fun establishTunInterface(
        spec: KrotConnectionSpec,
        requestedDns: List<String>,
        privateSessionPolicy: PrivateSessionPolicy
    ): ParcelFileDescriptor {
        val builder = Builder()
            .setSession(getString(R.string.vpn_notification_title, "KRot"))
            .setMtu(DEFAULT_MTU)
            .addAddress(spec.tunnel.clientIp, TUN_ADDRESS_PREFIX)
            .addRoute(DEFAULT_ROUTE, DEFAULT_ROUTE_PREFIX)

        applyRoutingPolicy(builder, requestedDns, privateSessionPolicy)

        val tun = builder.establish()
            ?: throw IllegalStateException("Не удалось установить KRot TUN интерфейс")
        appendRuntimeLog(
            "KRot VPN interface established: fd=${tun.fd} " +
                "client_ip=${spec.tunnel.clientIp}/$TUN_ADDRESS_PREFIX " +
                "peer=${spec.tunnel.serverIp} route=$DEFAULT_ROUTE/$DEFAULT_ROUTE_PREFIX " +
                "dns=${requestedDns.joinToString()} mtu=$DEFAULT_MTU"
        )
        return tun
    }

    private fun applyRoutingPolicy(
        builder: Builder,
        requestedDns: List<String>,
        privateSessionPolicy: PrivateSessionPolicy
    ) {
        requestedDns.forEach { dns ->
            runCatching { builder.addDnsServer(dns) }
        }

        if (privateSessionPolicy.enabled) {
            val allowedPackages = privateSessionPolicy.trustedPackages
                .map { it.trim() }
                .filter { it.isNotBlank() && it != packageName }
                .distinct()

            val appliedPackages = mutableListOf<String>()
            allowedPackages.forEach { packageNameCandidate ->
                runCatching {
                    builder.addAllowedApplication(packageNameCandidate)
                    appliedPackages += packageNameCandidate
                }.onFailure { error ->
                    appendRuntimeLog(
                        "KRot Private Session: пакет '$packageNameCandidate' пропущен: " +
                            (error.message ?: "ошибка")
                    )
                }
            }

            if (appliedPackages.isEmpty()) {
                throw IllegalStateException(
                    "KRot Private Session включён, но нет валидных доверенных приложений"
                )
            }

            VpnRuntimeStateStore.setAppTrafficMode(AppTrafficMode.PRIVATE_SESSION_APP_EXCLUDED)
            appendRuntimeLog("KRot Private Session активен: trusted packages=${appliedPackages.size}")
            return
        }

        runCatching {
            builder.addDisallowedApplication(packageName)
            VpnRuntimeStateStore.setAppTrafficMode(AppTrafficMode.FULL_TUNNEL_BACKEND_BYPASS)
            appendRuntimeLog("KRot full tunnel: пакет $packageName исключён из VPN как loop-guard")
        }.onFailure { error ->
            val reason = if (error is PackageManager.NameNotFoundException) {
                "пакет не найден"
            } else {
                error.message ?: "неизвестная ошибка"
            }
            appendRuntimeLog("KRot loop-guard не применён: $reason")
        }
    }

    private fun cleanupResources() {
        synchronized(connectLock) {
            connectInProgress = false
        }
        val traffic = VpnRuntimeStateStore.traffic.value
        if (traffic.connectedAtMs != null) {
            VpnSessionHistoryStore.recordCompletedSession(this, traffic, currentProfileName)
        }
        session?.stop()
        session = null
        VpnRuntimeStateStore.setInternalDataPlanePort(null)
        VpnRuntimeStateStore.stopTrafficSampling()
        VpnRuntimeStateStore.setAppTrafficMode(AppTrafficMode.UNKNOWN)
    }

    private fun registerNetworkMonitor() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshNetworkAvailability("network available")
            }

            override fun onLost(network: Network) {
                refreshNetworkAvailability("network lost")
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                refreshNetworkAvailability("network capabilities changed")
            }
        }
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
            refreshNetworkAvailability("network monitor registered")
        }.onFailure { error ->
            Log.w(TAG, "KRot network monitor registration failed", error)
        }
    }

    private fun unregisterNetworkMonitor() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }
    }

    private fun refreshNetworkAvailability(reason: String) {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val usable = manager.allNetworks.any { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@any false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        if (networkAvailable.getAndSet(usable) != usable) {
            appendRuntimeLog("KRot network availability=$usable ($reason)")
        }
    }

    private fun failWithError(appError: AppError) {
        if (handlingFailure) return
        handlingFailure = true

        val details = synchronized(runtimeLogTail) {
            runtimeLogTail.joinToString(" | ")
        }
        val technicalReason = buildString {
            appError.technicalReason?.takeIf { it.isNotBlank() }?.let { append(it) }
            if (details.isNotBlank()) {
                if (isNotEmpty()) append(" | ")
                val trimmedTail = if (details.length <= MAX_RUNTIME_TAIL_IN_ERROR) {
                    details
                } else {
                    details.takeLast(MAX_RUNTIME_TAIL_IN_ERROR)
                }
                append("runtime_tail=").append(trimmedTail)
            }
        }.ifBlank { appError.technicalReason }

        val enrichedError = appError.copy(technicalReason = technicalReason)
        Log.w(TAG, enrichedError.toLogMessage())
        appendRuntimeLog("ERROR ${enrichedError.toLogMessage()}")

        cleanupResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        VpnRuntimeStateStore.setError(enrichedError.toUiMessage())
        VpnQuickSettingsTileService.requestTileStateRefresh(this)
    }

    private fun updateReadyOrNoPermission() {
        if (VpnService.prepare(this) == null) {
            VpnRuntimeStateStore.setStatus(VpnConnectionStatus.READY)
        } else {
            VpnRuntimeStateStore.setStatus(VpnConnectionStatus.NO_PERMISSION)
        }
        VpnQuickSettingsTileService.requestTileStateRefresh(this)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = Intent(this, KrotVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            102,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val profileLabel = currentProfileName
            ?: VpnRuntimeStateStore.lastSelectedProfileName.value
            ?: getString(R.string.vpn_notification_profile_unknown)
        val statusLabel = when (VpnRuntimeStateStore.status.value) {
            VpnConnectionStatus.NO_PERMISSION -> getString(R.string.home_status_no_permission)
            VpnConnectionStatus.READY -> getString(R.string.home_status_ready)
            VpnConnectionStatus.CONNECTING -> getString(R.string.home_status_connecting)
            VpnConnectionStatus.CONNECTED -> getString(R.string.home_status_connected)
            VpnConnectionStatus.ERROR -> getString(R.string.home_status_error)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_nora)
            .setContentTitle(getString(R.string.vpn_notification_title, statusLabel))
            .setContentText(getString(R.string.vpn_notification_text, profileLabel))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.vpn_notification_action_disconnect),
                disconnectPendingIntent
            )
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.vpn_notification_channel_description)
        }

        manager.createNotificationChannel(channel)
    }

    private fun updateForegroundNotification() {
        ensureNotificationChannel()
        val manager = getSystemService(NotificationManager::class.java)
        runCatching {
            manager.notify(NOTIFICATION_ID, buildNotification())
        }.onFailure { error ->
            appendRuntimeLog("Не удалось обновить KRot foreground notification: ${error.message ?: "ошибка"}")
        }
        VpnQuickSettingsTileService.requestTileStateRefresh(this)
    }

    private fun appendRuntimeLog(message: String) {
        Log.i(TAG, message)
        synchronized(runtimeLogTail) {
            if (runtimeLogTail.size >= MAX_LOG_LINES) {
                runtimeLogTail.removeFirst()
            }
            runtimeLogTail.addLast(message.take(MAX_LOG_CHARS))
        }
    }

    private fun clearRuntimeLog() {
        synchronized(runtimeLogTail) {
            runtimeLogTail.clear()
        }
    }

    companion object {
        private const val TAG: String = "KrotVpnService"
        const val ACTION_CONNECT: String = "com.privatevpn.app.vpn.krot.ACTION_CONNECT"
        const val ACTION_DISCONNECT: String = "com.privatevpn.app.vpn.krot.ACTION_DISCONNECT"
        const val EXTRA_PROFILE_JSON: String = "extra_krot_profile_json"
        const val EXTRA_PROFILE_ID: String = "extra_profile_id"
        const val EXTRA_PROFILE_NAME: String = "extra_profile_name"
        const val EXTRA_DNS_SERVERS: String = "extra_dns_servers"
        const val EXTRA_PRIVATE_SESSION_ENABLED: String = "extra_private_session_enabled"
        const val EXTRA_PRIVATE_SESSION_TRUSTED_PACKAGES: String = "extra_private_session_trusted_packages"

        private const val NOTIFICATION_ID: Int = 102
        private const val CHANNEL_ID: String = "privatevpn_service"
        private const val DEFAULT_MTU: Int = 1400
        private const val TUN_ADDRESS_PREFIX: Int = 32
        private const val DEFAULT_ROUTE: String = "0.0.0.0"
        private const val DEFAULT_ROUTE_PREFIX: Int = 0
        private const val MAX_LOG_LINES: Int = 80
        private const val MAX_LOG_CHARS: Int = 3000
        private const val MAX_RUNTIME_TAIL_IN_ERROR: Int = 1800
    }
}
