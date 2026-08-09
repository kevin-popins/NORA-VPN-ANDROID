package com.privatevpn.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.privatevpn.app.MainActivity
import com.privatevpn.app.R
import com.privatevpn.app.core.error.AppError
import com.privatevpn.app.core.error.AppErrors
import com.privatevpn.app.core.backend.xray.XrayBackendLauncher
import com.privatevpn.app.core.backend.xray.XrayBinaryManager
import com.privatevpn.app.core.dns.DefaultDnsProvider
import com.privatevpn.app.vpn.dataplane.Tun2ProxyDataPlane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateVpnService : VpnService() {

    private data class DataPlaneRequest(
        val socksHost: String,
        val socksPort: Int,
        val socksUsername: String,
        val socksPassword: String
    )

    private data class PrivateSessionPolicy(
        val enabled: Boolean,
        val trustedPackages: Set<String>
    )

    private var tunInterface: ParcelFileDescriptor? = null
    private var backendProcess: Process? = null
    private var backendLogReader: Thread? = null
    private var backendExitWatcher: Thread? = null
    private val runtimeLogTail = ArrayDeque<String>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val xrayBinaryManager by lazy { XrayBinaryManager(this) }
    private val xrayBackendLauncher by lazy { XrayBackendLauncher() }
    private val dataPlaneManager = Tun2ProxyDataPlane(::appendRuntimeLog)
    private val connectLock = Any()

    @Volatile
    private var handlingFailure = false

    @Volatile
    private var connectInProgress = false

    @Volatile
    private var currentProfileName: String? = null

    override fun onCreate() {
        super.onCreate()
        serviceRunning.set(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val dns = intent.getStringArrayListExtra(EXTRA_DNS_SERVERS)?.toList().orEmpty()
                val runtimeConfig = intent.getStringExtra(EXTRA_RUNTIME_CONFIG).orEmpty()
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
                val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME)?.trim()
                    ?.takeIf { it.isNotBlank() }
                val dataPlaneRequest = DataPlaneRequest(
                    socksHost = intent.getStringExtra(EXTRA_DATAPLANE_SOCKS_HOST).orEmpty(),
                    socksPort = intent.getIntExtra(EXTRA_DATAPLANE_SOCKS_PORT, -1),
                    socksUsername = intent.getStringExtra(EXTRA_DATAPLANE_SOCKS_USERNAME).orEmpty(),
                    socksPassword = intent.getStringExtra(EXTRA_DATAPLANE_SOCKS_PASSWORD).orEmpty()
                )
                val privateSessionPolicy = PrivateSessionPolicy(
                    enabled = intent.getBooleanExtra(EXTRA_PRIVATE_SESSION_ENABLED, false),
                    trustedPackages = intent.getStringArrayListExtra(EXTRA_PRIVATE_SESSION_TRUSTED_PACKAGES)
                        ?.toSet()
                        .orEmpty()
                )

                serviceScope.launch {
                    connectVpn(
                        requestedDns = dns,
                        runtimeConfig = runtimeConfig,
                        profileId = profileId,
                        profileName = profileName,
                        dataPlane = dataPlaneRequest,
                        privateSessionPolicy = privateSessionPolicy
                    )
                }
            }

            ACTION_DISCONNECT -> {
                serviceScope.launch {
                    disconnectVpn()
                }
            }

            ACTION_UPDATE_NOTIFICATION_PROFILE -> {
                currentProfileName = intent.getStringExtra(EXTRA_PROFILE_NAME)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                VpnRuntimeStateStore.setLastSelectedProfileName(currentProfileName)
                updateForegroundNotification()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        serviceRunning.set(false)
        cleanupResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun connectVpn(
        requestedDns: List<String>,
        runtimeConfig: String,
        profileId: String,
        profileName: String?,
        dataPlane: DataPlaneRequest,
        privateSessionPolicy: PrivateSessionPolicy
    ) {
        if (runtimeConfig.isBlank()) {
            failWithError(
                AppErrors.genericUiStateError(
                    userMessage = "Получен пустой runtime конфиг для запуска VPN.",
                    technicalReason = "runtimeConfig is blank for profile '$profileId'"
                )
            )
            return
        }
        if (
            dataPlane.socksHost.isBlank() ||
            dataPlane.socksPort !in 1..65535 ||
            dataPlane.socksUsername.isBlank() ||
            dataPlane.socksPassword.isBlank()
        ) {
            failWithError(
                AppErrors.genericUiStateError(
                    userMessage = "Получены некорректные параметры data plane SOCKS.",
                    technicalReason = "invalid socks parameters for profile '$profileId'"
                )
            )
            return
        }

        synchronized(connectLock) {
            if (connectInProgress) {
                appendRuntimeLog("Повторный ACTION_CONNECT проигнорирован: подключение уже выполняется")
                return
            }
            if (
                tunInterface != null &&
                backendProcess?.isAlive == true &&
                dataPlaneManager.isRunning() &&
                VpnRuntimeStateStore.status.value == VpnConnectionStatus.CONNECTED
            ) {
                appendRuntimeLog("Повторный ACTION_CONNECT проигнорирован: VPN уже подключён")
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

        val dnsServers = requestedDns.ifEmpty { DefaultDnsProvider.defaultServers }

        try {
            runCatching {
                val preparedBinary = xrayBinaryManager.prepareBinary()
                appendRuntimeLog(
                    "Xray binary выбран для ABI: ${preparedBinary.abi}. " +
                        "Путь: ${preparedBinary.executable.absolutePath}"
                )
                appendRuntimeLog("Xray runtime source: ${preparedBinary.sourceDescription}")
                appendRuntimeLog("Xray runtime version: ${preparedBinary.versionOutput.replace('\n', ' ')}")
                appendRuntimeLog(
                    "Xray asset location: ${preparedBinary.runtimeDir.absolutePath} " +
                        "geoip=${preparedBinary.geoIpFile?.absolutePath ?: "<missing>"} " +
                        "geosite=${preparedBinary.geoSiteFile?.absolutePath ?: "<missing>"}"
                )

                appendRuntimeConfig(runtimeConfig)
                val configFile = writeRuntimeConfig(
                    runtimeConfig = runtimeConfig,
                    runtimeDir = preparedBinary.runtimeDir
                )

                startBackendProcess(
                    executable = preparedBinary.executable,
                    configFile = configFile,
                    workingDirectory = preparedBinary.runtimeDir
                )

                val tun = establishTunInterface(
                    requestedDns = dnsServers,
                    privateSessionPolicy = privateSessionPolicy
                )

                VpnRuntimeStateStore.setInternalDataPlanePort(dataPlane.socksPort)
                dataPlaneManager.start(
                    socksHost = dataPlane.socksHost,
                    socksPort = dataPlane.socksPort,
                    socksUsername = dataPlane.socksUsername,
                    socksPassword = dataPlane.socksPassword,
                    tunFd = tun.fd,
                    mtu = DEFAULT_MTU
                ).getOrThrow()

                VpnRuntimeStateStore.setStatus(VpnConnectionStatus.CONNECTED)
                VpnRuntimeStateStore.startTrafficSampling(applicationInfo.uid)
                updateForegroundNotification()
            }.onFailure { error ->
                failWithError(
                    AppErrors.xrayRuntimeStartFailed(
                        technicalReason = error.message
                            ?: "Не удалось запустить backend/data plane для профиля '$profileId'"
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
            val appError = AppErrors.xrayRuntimeStopFailed(
                technicalReason = error.message ?: "Ошибка при отключении VPN"
            )
            VpnRuntimeStateStore.setError(appError.toUiMessage())
            Log.w(TAG, appError.toLogMessage(), error)
        }
    }

    private fun establishTunInterface(
        requestedDns: List<String>,
        privateSessionPolicy: PrivateSessionPolicy
    ): ParcelFileDescriptor {
        tunInterface?.let { return it }

        val builder = Builder()
            .setSession(getString(R.string.vpn_notification_title))
            .setMtu(DEFAULT_MTU)
            .addAddress(TUN_ADDRESS, TUN_PREFIX)
            .addRoute(DEFAULT_ROUTE, DEFAULT_ROUTE_PREFIX)
        applyRoutingPolicy(builder, requestedDns, privateSessionPolicy)

        val tun = builder.establish()
            ?: throw IllegalStateException("Не удалось установить TUN интерфейс")
        tunInterface = tun
        appendRuntimeLog("TUN интерфейс установлен, fd=${tun.fd}")
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
                        "Private Session: пакет '$packageNameCandidate' пропущен: ${error.message ?: "ошибка"}"
                    )
                }
            }

            if (appliedPackages.isEmpty()) {
                throw IllegalStateException(
                    "Приватная сессия включена, но нет валидных доверенных приложений для addAllowedApplication"
                )
            }

            VpnRuntimeStateStore.setAppTrafficMode(AppTrafficMode.PRIVATE_SESSION_APP_EXCLUDED)
            appendRuntimeLog(
                "Private Session активен: VPN доступен только доверенным приложениям (${appliedPackages.size})"
            )
            appendRuntimeLog("Private Session trusted packages: ${appliedPackages.joinToString()}")
            return
        }

        runCatching {
            builder.addDisallowedApplication(packageName)
            VpnRuntimeStateStore.setAppTrafficMode(AppTrafficMode.FULL_TUNNEL_BACKEND_BYPASS)
            appendRuntimeLog(
                "Full tunnel: пакет $packageName исключён из VPN как loop-guard для backend/data plane"
            )
        }.onFailure { error ->
            val reason = if (error is PackageManager.NameNotFoundException) {
                "пакет не найден"
            } else {
                error.message ?: "неизвестная ошибка"
            }
            appendRuntimeLog("Не удалось применить backend loop-guard: $reason")
        }
    }

    private fun startBackendProcess(
        executable: File,
        configFile: File,
        workingDirectory: File
    ) {
        stopBackendProcess()
        val launchResult = xrayBackendLauncher.start(
            executable = executable,
            configFile = configFile,
            workingDirectory = workingDirectory
        )
        val process = launchResult.process
        backendProcess = process
        startBackendLogReader(process)
        startBackendExitWatcher(process)
        appendRuntimeLog("Xray backend запущен: ${launchResult.command.joinToString(" ")}")
    }

    private fun stopBackendProcess() {
        backendLogReader?.interrupt()
        backendLogReader = null

        backendExitWatcher?.interrupt()
        backendExitWatcher = null

        backendProcess?.let { process ->
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(900, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(600, TimeUnit.MILLISECONDS)
                }
            }
        }
        backendProcess = null
    }

    private fun cleanupResources() {
        synchronized(connectLock) {
            connectInProgress = false
        }
        val traffic = VpnRuntimeStateStore.traffic.value
        if (traffic.connectedAtMs != null) {
            VpnSessionHistoryStore.recordCompletedSession(this, traffic, currentProfileName)
        }
        dataPlaneManager.stop()
        stopBackendProcess()
        runCatching {
            tunInterface?.close()
        }
        tunInterface = null
        VpnRuntimeStateStore.setInternalDataPlanePort(null)
        VpnRuntimeStateStore.stopTrafficSampling()
        VpnRuntimeStateStore.setAppTrafficMode(AppTrafficMode.UNKNOWN)
    }

    private fun writeRuntimeConfig(runtimeConfig: String, runtimeDir: File): File {
        if (!runtimeDir.exists()) {
            runtimeDir.mkdirs()
        }
        return File(runtimeDir, "runtime-config.json").apply {
            writeText(runtimeConfig)
        }
    }

    private fun startBackendLogReader(process: Process) {
        backendLogReader?.interrupt()
        backendLogReader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    appendRuntimeLog(line)
                }
            }
        }.apply {
            isDaemon = true
            name = "privatevpn-xray-log-reader"
            start()
        }
    }

    private fun startBackendExitWatcher(process: Process) {
        backendExitWatcher?.interrupt()
        backendExitWatcher = Thread {
            runCatching {
                val exitCode = process.waitFor()
                if (process != backendProcess) return@runCatching
                if (VpnRuntimeStateStore.status.value == VpnConnectionStatus.CONNECTED ||
                    VpnRuntimeStateStore.status.value == VpnConnectionStatus.CONNECTING
                ) {
                    failWithError(
                        AppErrors.xrayRuntimeStartFailed(
                            technicalReason = "Xray backend неожиданно завершился с кодом $exitCode"
                        )
                    )
                }
            }
        }.apply {
            isDaemon = true
            name = "privatevpn-xray-exit-watcher"
            start()
        }
    }

    private fun appendRuntimeLog(message: String) {
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

    private fun appendRuntimeConfig(runtimeConfig: String) {
        appendRuntimeLog("Финальный runtime config передан в backend. Размер=${runtimeConfig.length} символов")
        val chunks = runtimeConfig.chunked(RUNTIME_CONFIG_LOG_CHUNK_SIZE)
        chunks.forEachIndexed { index, chunk ->
            appendRuntimeLog("runtime-config[${index + 1}/${chunks.size}]: $chunk")
        }
    }

    private fun failWithError(appError: AppError) {
        if (handlingFailure) return
        handlingFailure = true

        val details = synchronized(runtimeLogTail) {
            runtimeLogTail
                .filterNot { line ->
                    line.startsWith("runtime-config[") ||
                        line.startsWith("Финальный runtime config передан в backend.")
                }
                .joinToString(" | ")
                .ifBlank { runtimeLogTail.joinToString(" | ") }
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
        logErrorTechnicalChunks(enrichedError)
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
        val disconnectIntent = Intent(this, PrivateVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            101,
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
            .setSmallIcon(R.mipmap.ic_launcher_source)
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
            appendRuntimeLog("Не удалось обновить foreground notification: ${error.message ?: "ошибка"}")
        }
        VpnQuickSettingsTileService.requestTileStateRefresh(this)
    }

    companion object {
        private const val TAG: String = "PrivateVpnService"
        fun isRunning(): Boolean = serviceRunning.get()

        const val FOREGROUND_NOTIFICATION_ID: Int = 101
        const val ACTION_CONNECT: String = "com.privatevpn.app.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT: String = "com.privatevpn.app.vpn.ACTION_DISCONNECT"
        const val EXTRA_DNS_SERVERS: String = "extra_dns_servers"
        const val EXTRA_RUNTIME_CONFIG: String = "extra_runtime_config"
        const val EXTRA_PROFILE_ID: String = "extra_profile_id"
        const val EXTRA_PROFILE_NAME: String = "extra_profile_name"
        const val EXTRA_PRIVATE_SESSION_ENABLED: String = "extra_private_session_enabled"
        const val EXTRA_PRIVATE_SESSION_TRUSTED_PACKAGES: String = "extra_private_session_trusted_packages"
        const val EXTRA_DATAPLANE_SOCKS_HOST: String = "extra_dataplane_socks_host"
        const val EXTRA_DATAPLANE_SOCKS_PORT: String = "extra_dataplane_socks_port"
        const val EXTRA_DATAPLANE_SOCKS_USERNAME: String = "extra_dataplane_socks_username"
        const val EXTRA_DATAPLANE_SOCKS_PASSWORD: String = "extra_dataplane_socks_password"
        const val ACTION_UPDATE_NOTIFICATION_PROFILE: String =
            "com.privatevpn.app.vpn.ACTION_UPDATE_NOTIFICATION_PROFILE"

        private const val NOTIFICATION_ID: Int = FOREGROUND_NOTIFICATION_ID
        private const val CHANNEL_ID: String = "privatevpn_service"
        private const val DEFAULT_MTU: Int = 1500
        private const val TUN_ADDRESS: String = "10.16.0.2"
        private const val TUN_PREFIX: Int = 24
        private const val DEFAULT_ROUTE: String = "0.0.0.0"
        private const val DEFAULT_ROUTE_PREFIX: Int = 0
        private const val MAX_LOG_LINES: Int = 80
        private const val MAX_LOG_CHARS: Int = 3000
        private const val RUNTIME_CONFIG_LOG_CHUNK_SIZE: Int = 1500
        private const val MAX_RUNTIME_TAIL_IN_ERROR: Int = 1800
        private const val ERROR_LOG_CHUNK_SIZE: Int = 900
        private val serviceRunning = AtomicBoolean(false)
    }

    private fun logErrorTechnicalChunks(appError: AppError) {
        val tech = appError.technicalReason?.trim().orEmpty()
        if (tech.isBlank()) return
        if (tech.length <= ERROR_LOG_CHUNK_SIZE) return

        val chunks = tech.chunked(ERROR_LOG_CHUNK_SIZE)
        chunks.forEachIndexed { index, chunk ->
            Log.w(TAG, "[${appError.code.code}] tech[$index/${chunks.size}] $chunk")
        }
    }
}
