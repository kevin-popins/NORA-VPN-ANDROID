package com.privatevpn.app.ui

import com.privatevpn.app.ui.location.noraProfileDisplayName

import androidx.room.withTransaction
import com.privatevpn.app.ui.qr.QrImportCoordinator
import com.privatevpn.app.ui.qr.QrImportException
import com.privatevpn.app.ui.qr.QrImportFailureCode
import com.privatevpn.app.profiles.subscriptions.SubscriptionImportException
import com.privatevpn.app.profiles.subscriptions.SubscriptionImportFailure
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privatevpn.app.core.backend.adapter.BackendAdapter
import com.privatevpn.app.core.backend.adapter.BackendStartResult
import com.privatevpn.app.core.backend.awg.AmneziaWgBackendAdapter
import com.privatevpn.app.core.backend.awg.AmneziaWgRuntimeConfigBuilder
import com.privatevpn.app.core.backend.krot.KrotBackendAdapter
import com.privatevpn.app.core.backend.krot.KrotConnectionSpec
import com.privatevpn.app.core.backend.xray.XrayBackendAdapter
import com.privatevpn.app.core.backend.xray.XrayConfigNormalizer
import com.privatevpn.app.core.backend.xray.XrayRuntimeConfigPreparer
import com.privatevpn.app.core.dns.DefaultDnsProvider
import com.privatevpn.app.core.error.AppError
import com.privatevpn.app.core.error.AppErrorCode
import com.privatevpn.app.core.error.AppErrors
import com.privatevpn.app.core.log.EventLogEntry
import com.privatevpn.app.core.log.LogLevel
import com.privatevpn.app.private_session.AndroidInstalledAppsRepository
import com.privatevpn.app.private_session.AppIconRepository
import com.privatevpn.app.private_session.InstalledAppInfo
import com.privatevpn.app.private_session.PrivateSessionUiState
import com.privatevpn.app.private_session.SystemVpnIntegrationState
import com.privatevpn.app.private_session.VpnSystemSettingsRepository
import com.privatevpn.app.profiles.db.PrivateVpnDatabase
import com.privatevpn.app.profiles.deeplink.NoraDeepLinkImport
import com.privatevpn.app.profiles.deeplink.NoraDeepLinkParser
import com.privatevpn.app.profiles.importer.ProfileImportParser
import com.privatevpn.app.profiles.model.ImportedProfileDraft
import com.privatevpn.app.profiles.importer.ProfileInput
import com.privatevpn.app.profiles.importer.ProfileInputParser
import kotlinx.coroutines.CancellationException
import com.privatevpn.app.profiles.model.SubscriptionPresentation
import com.privatevpn.app.profiles.model.userFacingName
import com.privatevpn.app.profiles.model.ProfileType
import com.privatevpn.app.profiles.model.SubscriptionSource
import com.privatevpn.app.profiles.model.SubscriptionSyncStatus
import com.privatevpn.app.profiles.model.VpnProfile
import com.privatevpn.app.profiles.repository.RoomProfilesRepository
import com.privatevpn.app.profiles.subscriptions.HappCrypt5Decryptor
import com.privatevpn.app.profiles.subscriptions.HappRoutingCompat
import com.privatevpn.app.profiles.subscriptions.RoomSubscriptionRepository
import com.privatevpn.app.profiles.subscriptions.SubscriptionParser
import com.privatevpn.app.profiles.subscriptions.SubscriptionRefreshResult
import com.privatevpn.app.profiles.subscriptions.SubscriptionUpdateWorker
import com.privatevpn.app.profiles.subscriptions.SubscriptionServiceRecords
import com.privatevpn.app.settings.DnsMode
import com.privatevpn.app.settings.DnsResolvedSource
import com.privatevpn.app.settings.DnsState
import com.privatevpn.app.settings.SettingsState
import com.privatevpn.app.settings.SocksSettings
import com.privatevpn.app.settings.storage.DataStoreUserSettingsRepository
import com.privatevpn.app.vpn.PrivateSessionState
import com.privatevpn.app.vpn.AppTrafficMode
import com.privatevpn.app.vpn.VpnConnectionStatus
import com.privatevpn.app.vpn.VpnController
import com.privatevpn.app.vpn.VpnManager
import com.privatevpn.app.vpn.VpnRuntimeStateStore
import com.privatevpn.app.vpn.VpnSessionHistoryStore
import com.privatevpn.app.vpn.tunnel.AndroidVpnTunnelLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val database = PrivateVpnDatabase.build(application.applicationContext)

    private val profilesRepository = RoomProfilesRepository(database.profileDao())
    private val subscriptionRepository = RoomSubscriptionRepository(
        database = database,
        appContext = application.applicationContext
    )
    private val userSettingsRepository = DataStoreUserSettingsRepository(application.applicationContext)
    private val installedAppsRepository = AndroidInstalledAppsRepository(application.applicationContext)
    private val appIconRepository = AppIconRepository(application.applicationContext)
    private val vpnSystemSettingsRepository = VpnSystemSettingsRepository(application.applicationContext)
    private val profileInputParser = ProfileInputParser()
    private val profileImportParser = ProfileImportParser()
    private val externalSubscriptionParser = SubscriptionParser(profileImportParser)
    private val vpnManager = VpnManager(VpnController(application.applicationContext))

    private val xrayBackendAdapter = XrayBackendAdapter(
        configNormalizer = XrayConfigNormalizer(),
        runtimeConfigPreparer = XrayRuntimeConfigPreparer(),
        tunnelLifecycle = AndroidVpnTunnelLifecycle(vpnManager)
    )
    private val awgBackendAdapter = AmneziaWgBackendAdapter(
        appContext = application.applicationContext,
        runtimeConfigBuilder = AmneziaWgRuntimeConfigBuilder()
    )
    private val krotBackendAdapter = KrotBackendAdapter(
        appContext = application.applicationContext
    )

    @Volatile
    private var activeBackendAdapter: BackendAdapter? = null

    @Volatile
    private var activeBackendProfileType: ProfileType? = null

    @Volatile
    private var lastBackendProfileType: ProfileType? = null

    private val backendOperationMutex = Mutex()

    @Volatile
    private var backendSwitchInProgress = false

    @Volatile
    private var backendSwitchTargetType: ProfileType? = null

    private val _connectionError = MutableStateFlow<String?>(null)
    private val _logs = MutableStateFlow<List<EventLogEntry>>(emptyList())
    private val _refreshingSubscriptionIds = MutableStateFlow<Set<String>>(emptySet())
    private val _privateSessionInstalledApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val _privateSessionAppIcons = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    private val _privateSessionAppsLoading = MutableStateFlow(false)
    private val _privateSessionDraftTrustedPackages = MutableStateFlow<Set<String>>(emptySet())
    private val _privateSessionDraftDirty = MutableStateFlow(false)
    private val _systemVpnIntegrationState = MutableStateFlow(SystemVpnIntegrationState())
    private val _notificationPermissionGranted = MutableStateFlow(true)
    private val _transientMessage = MutableStateFlow<String?>(null)
    private val _addContentEvents = Channel<AddContentEvent>(capacity = Channel.BUFFERED)
    private val _serverPingResults = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _pingInProgress = MutableStateFlow(false)

    val addContentEvents = _addContentEvents.receiveAsFlow()
    private val qrPersistenceMutex = Mutex()
    private var currentQrScan: Long? = null
    private var acceptedQrImport: Long? = null
    private val qrImports = QrImportCoordinator(viewModelScope) { raw ->
        val token = acceptedQrImport ?: throw CancellationException()
        importQrPayloadInternal(raw, token)
    }
    val qrImportState = qrImports.state

    fun beginQrScan(): Long {
        acceptedQrImport = null
        return qrImports.beginScan().also { currentQrScan = it }
    }
    fun closeQrScan(token: Long) {
        qrImports.closeScan(token)
        if (currentQrScan == token && acceptedQrImport != token) currentQrScan = null
    }
    fun importScannedQr(token: Long, raw: String): Boolean {
        if (currentQrScan != token || acceptedQrImport == token) return false
        acceptedQrImport = token
        return qrImports.submit(token, raw).also { accepted ->
            if (!accepted && acceptedQrImport == token) acceptedQrImport = null
        }
    }
    fun cancelQrImport() {
        acceptedQrImport = null
        currentQrScan = null
        qrImports.cancelImport()
    }
    fun dismissQrImportError() = qrImports.dismissError()
    fun isCurrentQrImport(token: Long): Boolean = acceptedQrImport == token


    private var logCounter: Long = 0
    private var lastRuntimeErrorLogged: String? = null
    private var lastSocksOnboardingLogSignature: String? = null
    private var trustedAppsPersistJob: Job? = null
    private var profileSwitchJob: Job? = null
    private val profileSwitchRequests = ProfileSwitchRequestQueue()
    private var queuedDisconnectJob: Job? = null

    @Volatile
    private var disconnectQueued: Boolean = false

    private data class CoreUiState(
        val status: VpnConnectionStatus,
        val appTrafficMode: AppTrafficMode,
        val profiles: List<VpnProfile>,
        val subscriptions: List<SubscriptionSource>,
        val refreshingSubscriptionIds: Set<String>,
        val settings: SettingsState,
        val connectionError: String?,
        val logs: List<EventLogEntry>,
        val redact: (String) -> String
    )

    private data class CoreUiStateWithoutLogs(
        val status: VpnConnectionStatus,
        val appTrafficMode: AppTrafficMode,
        val profiles: List<VpnProfile>,
        val subscriptions: List<SubscriptionSource>,
        val refreshingSubscriptionIds: Set<String>,
        val settings: SettingsState,
        val connectionError: String?,
        val redact: (String) -> String
    )

    private data class PrivateSessionAuxState(
        val installedApps: List<InstalledAppInfo>,
        val appIcons: Map<String, ByteArray>,
        val loadingApps: Boolean,
        val draftTrustedPackages: Set<String>,
        val draftDirty: Boolean,
        val systemIntegration: SystemVpnIntegrationState
    )

    private data class UiAuxState(
        val notificationPermissionGranted: Boolean,
        val transientMessage: String?,
        val serverPingResults: Map<String, String>,
        val pingInProgress: Boolean
    )

    private data class RuntimeProfilesState(
        val status: VpnConnectionStatus,
        val appTrafficMode: AppTrafficMode,
        val profiles: List<VpnProfile>,
        val subscriptions: List<SubscriptionSource>,
        val refreshingSubscriptionIds: Set<String>,
        val redact: (String) -> String
    )

    private data class PresentedProfiles(val profiles: List<VpnProfile>, val sources: List<SubscriptionSource>, val redact: (String) -> String)

    private val presentedProfilesState = combine(profilesRepository.profiles, subscriptionRepository.subscriptions) { profiles, sources ->
        PresentedProfiles(profiles.map { SubscriptionPresentation.apply(it, sources) }, sources, SubscriptionPresentation.redactor(profiles, sources))
    }

    private val runtimeProfilesState = combine(
        vpnManager.status,
        vpnManager.appTrafficMode,
        presentedProfilesState,
        _refreshingSubscriptionIds
    ) { status,
        appTrafficMode,
        presentation,
        refreshingSubscriptionIds ->
        RuntimeProfilesState(
            status = status,
            appTrafficMode = appTrafficMode,
            profiles = presentation.profiles,
            subscriptions = presentation.sources,
            redact = presentation.redact,
            refreshingSubscriptionIds = refreshingSubscriptionIds
        )
    }

    private val coreUiStateWithoutLogs = combine(
        runtimeProfilesState,
        userSettingsRepository.settings,
        _connectionError
    ) { runtime,
        settings,
        connectionError ->
        CoreUiStateWithoutLogs(
            status = runtime.status,
            appTrafficMode = runtime.appTrafficMode,
            profiles = runtime.profiles,
            subscriptions = runtime.subscriptions,
            refreshingSubscriptionIds = runtime.refreshingSubscriptionIds,
            settings = settings,
            connectionError = connectionError,
            redact = runtime.redact
        )
    }

    private val coreUiState = combine(
        coreUiStateWithoutLogs,
        _logs
    ) { core, logs ->
        CoreUiState(
            status = core.status,
            appTrafficMode = core.appTrafficMode,
            profiles = core.profiles,
            subscriptions = core.subscriptions,
            refreshingSubscriptionIds = core.refreshingSubscriptionIds,
            settings = core.settings,
            connectionError = core.connectionError,
            logs = logs,
            redact = core.redact
        )
    }

    private val privateSessionAuxState = combine(
        combine(_privateSessionInstalledApps, _privateSessionAppIcons) { apps, icons ->
            apps to icons
        },
        _privateSessionAppsLoading,
        _privateSessionDraftTrustedPackages,
        _privateSessionDraftDirty,
        _systemVpnIntegrationState
    ) { appsAndIcons,
        privateSessionAppsLoading,
        privateSessionDraftTrustedPackages,
        privateSessionDraftDirty,
        systemVpnIntegration ->
        val privateSessionInstalledApps = appsAndIcons.first
        val privateSessionAppIcons = appsAndIcons.second
        PrivateSessionAuxState(
            installedApps = privateSessionInstalledApps,
            appIcons = privateSessionAppIcons,
            loadingApps = privateSessionAppsLoading,
            draftTrustedPackages = privateSessionDraftTrustedPackages,
            draftDirty = privateSessionDraftDirty,
            systemIntegration = systemVpnIntegration
        )
    }

    private val uiAuxState = combine(
        _notificationPermissionGranted,
        _transientMessage,
        _serverPingResults,
        _pingInProgress
    ) { notificationPermissionGranted,
        transientMessage,
        serverPingResults,
        pingInProgress ->
        UiAuxState(
            notificationPermissionGranted = notificationPermissionGranted,
            transientMessage = transientMessage,
            serverPingResults = serverPingResults,
            pingInProgress = pingInProgress
        )
    }

    val uiState: StateFlow<AppUiState> = combine(
        coreUiState,
        privateSessionAuxState,
        uiAuxState,
        vpnManager.traffic,
        VpnSessionHistoryStore.records
    ) { coreState,
        privateSessionAux,
        uiAux,
        traffic,
        sessionHistory ->
        val presentedProfiles = coreState.profiles
        val activeProfile = presentedProfiles.firstOrNull { it.id == coreState.settings.activeProfileId && it.isConnectable }
        val dnsState = resolveDnsState(settings = coreState.settings, activeProfile = activeProfile)
        val draftTrustedPackages = if (privateSessionAux.draftDirty) {
            privateSessionAux.draftTrustedPackages
        } else {
            coreState.settings.privateSessionTrustedPackages
        }

        AppUiState(
            // The initial StateFlow value is intentionally not treated as an empty database.
            // Home uses this to avoid showing onboarding while Room is still reading profiles.
            profilesLoaded = true,
            vpnStatus = coreState.status,
            profiles = presentedProfiles,
            subscriptions = coreState.subscriptions,
            refreshingSubscriptionIds = coreState.refreshingSubscriptionIds,
            activeProfileId = coreState.settings.activeProfileId,
            activeProfile = activeProfile,
            activeProfileServer = if (activeProfile?.endpointProtectionEnabled == true) activeProfile.protectedPublicLabel else resolveProfileServer(activeProfile),
            connectionError = coreState.connectionError?.let { coreState.redact(it) },
            serverPingResults = uiAux.serverPingResults,
            pingInProgress = uiAux.pingInProgress,
            eventLogs = coreState.logs.map { it.copy(message = coreState.redact(it.message)) },
            privateSessionState = PrivateSessionState(
                enabled = coreState.settings.privateSessionEnabled,
                startedAtMs = coreState.settings.privateSessionStartedAtMs
            ),
            privateSessionUiState = PrivateSessionUiState(
                enabled = coreState.settings.privateSessionEnabled,
                startedAtMs = coreState.settings.privateSessionStartedAtMs,
                trustedPackages = coreState.settings.privateSessionTrustedPackages,
                draftTrustedPackages = draftTrustedPackages,
                installedApps = privateSessionAux.installedApps,
                appIcons = privateSessionAux.appIcons,
                loadingInstalledApps = privateSessionAux.loadingApps,
                draftDirty = privateSessionAux.draftDirty,
                systemIntegration = privateSessionAux.systemIntegration
            ),
            settingsState = coreState.settings,
            dnsState = dnsState,
            appTrafficMode = coreState.appTrafficMode,
            traffic = traffic,
            sessionHistory = sessionHistory.map { it.copy(profileName = coreState.redact(it.profileName)) },
            notificationPermission = NotificationPermissionUiState(
                supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    uiAux.notificationPermissionGranted
                } else {
                    true
                },
                promptShown = coreState.settings.notificationPermissionPromptShown
            ),
            transientMessage = uiAux.transientMessage?.let(coreState.redact)
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppUiState()
    )

    init {
        VpnSessionHistoryStore.initialize(application.applicationContext)
        viewModelScope.launch {
            val current = userSettingsRepository.settings.first()
            if (current.socksSettings.login.isBlank() || current.socksSettings.password.isBlank()) {
                userSettingsRepository.setSocksSettings(
                    SocksSettings(
                        enabled = true,
                        port = 1080,
                        login = "nora_" + UUID.randomUUID().toString().replace("-", "").take(10),
                        password = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").take(8)
                    )
                )
                userSettingsRepository.setLocalhostSocksOnboardingShown(true)
                addLog(LogLevel.INFO, "Для localhost SOCKS созданы локальные учётные данные")
            }
        }
        addLog(LogLevel.INFO, "Приложение запущено")
        SubscriptionUpdateWorker.schedule(application.applicationContext)
        refreshVpnPermissionState()
        refreshNotificationPermissionState()
        refreshPrivateSessionData()
        viewModelScope.launch {
            vpnManager.runtimeError.collect { runtimeError ->
                if (!runtimeError.isNullOrBlank()) {
                    _connectionError.value = runtimeError
                    if (runtimeError != lastRuntimeErrorLogged) {
                        addLog(LogLevel.ERROR, runtimeError)
                        lastRuntimeErrorLogged = runtimeError
                    }
                }
            }
        }
        viewModelScope.launch {
            userSettingsRepository.settings.collect { settings ->
                if (!_privateSessionDraftDirty.value) {
                    _privateSessionDraftTrustedPackages.value = settings.privateSessionTrustedPackages
                }
            }
        }
        viewModelScope.launch {
            combine(
                presentedProfilesState,
                userSettingsRepository.settings
            ) { presentation, settings ->
                presentation.profiles.firstOrNull { it.id == settings.activeProfileId && it.isConnectable }?.userFacingName
            }.collect { activeName ->
                if (!activeName.isNullOrBlank()) {
                    vpnManager.updateActiveProfileName(activeName)
                    VpnRuntimeStateStore.setLastSelectedProfileName(activeName)
                }
            }
        }
        viewModelScope.launch {
            profilesRepository.profiles.collect { profiles ->
                val validIds = profiles.mapTo(mutableSetOf()) { it.id }
                _serverPingResults.update { current ->
                    current.filterKeys { profileId -> profileId in validIds }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { database.close() }
    }

    fun requestVpnPermissionIntent(): Intent? {
        val snapshot = uiState.value
        if (snapshot.profiles.none { it.isConnectable } ||
            snapshot.profiles.any { it.id == snapshot.activeProfileId && !it.isConnectable }) return null
        return vpnManager.getPrepareIntent()
    }

    fun refreshVpnPermissionState() {
        vpnManager.refreshPermissionState()
    }

    fun refreshNotificationPermissionState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            _notificationPermissionGranted.value = true
            return
        }
        _notificationPermissionGranted.value = ContextCompat.checkSelfPermission(
            getApplication<Application>().applicationContext,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun onVpnPermissionResult() {
        vpnManager.onPermissionResult()
        if (vpnManager.getPrepareIntent() == null) {
            addLog(LogLevel.INFO, "Разрешение VPN выдано")
            _connectionError.value = null
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            userSettingsRepository.setNotificationPermissionPromptShown(true)
        }
        _notificationPermissionGranted.value = granted
        if (granted) {
            addLog(LogLevel.INFO, "Разрешение на уведомления выдано")
        } else {
            val appError = AppErrors.notificationPermissionRequired(
                technicalReason = "POST_NOTIFICATIONS denied by user"
            )
            addLog(LogLevel.ERROR, appError.toLogMessage())
            emitTransientMessage(appError.toUiMessage())
        }
    }

    fun markNotificationPermissionPromptShown() {
        viewModelScope.launch {
            userSettingsRepository.setNotificationPermissionPromptShown(true)
        }
    }

    fun markLocalhostSocksOnboardingShown() {
        viewModelScope.launch {
            userSettingsRepository.setLocalhostSocksOnboardingShown(true)
        }
    }

    fun logLocalhostSocksOnboardingCheck(
        onboardingShown: Boolean,
        socksEnabled: Boolean,
        loginSet: Boolean,
        passwordSet: Boolean,
        portValid: Boolean,
        persistedConfigured: Boolean,
        shouldShowWarning: Boolean
    ) {
        val signature = listOf(
            onboardingShown,
            socksEnabled,
            loginSet,
            passwordSet,
            portValid,
            persistedConfigured,
            shouldShowWarning
        ).joinToString("|")
        if (signature == lastSocksOnboardingLogSignature) return
        lastSocksOnboardingLogSignature = signature

        addLog(
            LogLevel.INFO,
            "SOCKS onboarding check: shown=$onboardingShown enabled=$socksEnabled " +
                "loginSet=$loginSet passwordSet=$passwordSet portValid=$portValid " +
                "persistedConfigured=$persistedConfigured shouldShowWarning=$shouldShowWarning"
        )
    }

    fun buildOpenAppNotificationSettingsIntent(): Intent {
        val appContext = getApplication<Application>().applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", appContext.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun connectVpn() {
        val requestedType = resolveRequestedProfileType()
        val effectiveTargetType = backendSwitchTargetType ?: requestedType
        if (!backendOperationMutex.tryLock()) {
            val appError = AppErrors.backendSwitchInProgress(
                fromBackend = backendTypeLabel(resolveCurrentBackendType()),
                toBackend = backendTypeLabel(effectiveTargetType),
                technicalReason = "connectVpn вызван до завершения предыдущей операции; switchInProgress=$backendSwitchInProgress"
            )
            applyUiError(appError)
            addLog(
                LogLevel.INFO,
                "BACKEND SWITCH: connect получен раньше готовности backend (${backendTypeLabel(effectiveTargetType)})"
            )
            return
        }

        viewModelScope.launch {
            try {
                connectVpnInternal()
            } finally {
                backendOperationMutex.unlock()
            }
        }
    }

    fun disconnectVpn() {
        if (!backendOperationMutex.tryLock()) {
            disconnectQueued = true
            profileSwitchRequests.clear()
            addLog(LogLevel.INFO, "BACKEND SWITCH: disconnect поставлен в очередь до завершения текущей операции")
            if (queuedDisconnectJob?.isActive != true) {
                queuedDisconnectJob = viewModelScope.launch {
                    backendOperationMutex.lock()
                    try {
                        disconnectQueued = false
                        disconnectVpnInternal()
                    } finally {
                        backendOperationMutex.unlock()
                    }
                }
            }
            return
        }

        viewModelScope.launch {
            try {
                disconnectQueued = false
                disconnectVpnInternal()
            } finally {
                backendOperationMutex.unlock()
            }
        }
    }

    private suspend fun connectVpnInternal() {
        val snapshot = uiState.value
        val connectableProfiles = snapshot.profiles.filter { it.isConnectable }
        if (connectableProfiles.isEmpty()) {
            emitTransientMessage(
                "Нет доступных профилей. Перейдите в раздел «Профили», импортируйте профиль или добавьте подписку."
            )
            addLog(LogLevel.INFO, "Подключение отменено: нет доступных профилей")
            return
        }
        val storedSelection = snapshot.profiles.firstOrNull { it.id == snapshot.activeProfileId }
        if (storedSelection != null && !storedSelection.isConnectable) return
        var profile = snapshot.activeProfile ?: connectableProfiles.first()
        profile = recoverProfileWithMissingShortId(profile)
        logSelectedProfileShortIdTrace(profile)

        if (snapshot.vpnStatus == VpnConnectionStatus.NO_PERMISSION) {
            applyUiError(
                AppErrors.vpnPermissionRequired(
                    technicalReason = "vpnStatus=NO_PERMISSION до старта подключения"
                )
            )
            return
        }

        if (snapshot.privateSessionUiState.enabled && snapshot.privateSessionUiState.trustedPackages.isEmpty()) {
            applyUiError(
                AppErrors.splitTunnelingNoTrustedApps(
                    technicalReason = "privateSession enabled, trustedPackages empty"
                )
            )
            return
        }

        val targetType = profile.type
        val currentType = resolveCurrentBackendType()
        val switchRequired = requiresBackendSwitch(
            currentBackendType = currentType,
            targetBackendType = targetType,
            status = snapshot.vpnStatus
        )

        if (switchRequired) {
            val switchCompleted = performBackendSwitch(
                currentBackendType = currentType,
                targetBackendType = targetType,
                currentStatus = snapshot.vpnStatus
            )
            if (!switchCompleted) return
        } else if (snapshot.vpnStatus == VpnConnectionStatus.CONNECTED ||
            snapshot.vpnStatus == VpnConnectionStatus.CONNECTING
        ) {
            addLog(
                LogLevel.INFO,
                "VPN уже ${snapshot.vpnStatus.name.lowercase()} для backend ${backendTypeLabel(targetType)}"
            )
            return
        }

        startProfileBackend(
            profile = profile,
            snapshot = snapshot,
            switchPerformed = switchRequired
        )
    }

    private suspend fun startProfileBackend(
        profile: VpnProfile,
        snapshot: AppUiState,
        switchPerformed: Boolean
    ) {
        if (!profile.isConnectable) return
        val presentedProfile = SubscriptionPresentation.apply(profile, snapshot.subscriptions)
        val targetType = profile.type
        val backendAdapter = resolveBackendAdapter(targetType)
        val startResult = startBackendWithWarmupRetry(
            backendAdapter = backendAdapter,
            profile = presentedProfile.copy(displayName = presentedProfile.userFacingName),
            dnsServers = snapshot.dnsState.resolvedServers,
            privateSessionEnabled = snapshot.privateSessionUiState.enabled,
            trustedPackages = snapshot.privateSessionUiState.trustedPackages,
            socksSettings = snapshot.settingsState.socksSettings,
            targetBackendType = targetType,
            switchPerformed = switchPerformed
        )

        startResult.onSuccess { result ->
            activeBackendAdapter = backendAdapter
            activeBackendProfileType = profile.type
            lastBackendProfileType = profile.type
            _connectionError.value = null
            vpnManager.updateActiveProfileName(profile.userFacingName)
            VpnRuntimeStateStore.setLastSelectedProfileName(profile.userFacingName)
            addLog(LogLevel.INFO, "Запрошено подключение VPN")
            addLog(LogLevel.INFO, "Выбран backend: ${backendTypeLabel(profile.type)}")
            result.notes.forEach { note -> addLog(LogLevel.INFO, note) }
            viewModelScope.launch {
                if (snapshot.activeProfileId == null && snapshot.profiles.any { it.id == profile.id }) {
                    userSettingsRepository.setActiveProfile(profile.id)
                }
            }
        }.onFailure { error ->
            val appError = mapBackendStartError(
                backendType = targetType,
                error = error
            )
            applyUiError(appError)
        }
    }

    private suspend fun recoverProfileWithMissingShortId(profile: VpnProfile): VpnProfile {
        if (profile.type != ProfileType.XRAY_VLESS_REALITY) return profile

        val payload = profile.normalizedJson ?: profile.sourceRaw
        val state = extractProxyShortIdState(payload)
        if (!state.realityEnabled || !state.shortId.isNullOrBlank()) return profile
        addLog(
            LogLevel.INFO,
            "SHORTID TRACE recovery skipped profileId=${profile.id} reason=empty-shortId-supported-by-runtime"
        )
        return profile
    }

    private suspend fun disconnectVpnInternal() {
        val adapter = resolveDisconnectBackend()
        adapter.stop().onSuccess {
            activeBackendAdapter = null
            activeBackendProfileType = null
            _connectionError.value = null
            addLog(LogLevel.INFO, "Запрошено отключение VPN")
        }.onFailure { error ->
            handleError(
                userMessage = "Не удалось отключить VPN",
                error = error,
                fallbackCode = when (activeBackendProfileType) {
                    ProfileType.AMNEZIA_WG_20 -> AppErrorCode.AWG_102
                    ProfileType.KROT -> AppErrorCode.KROT_102
                    else -> AppErrorCode.XRAY_102
                }
            )
        }
    }

    fun clearError() {
        _connectionError.value = null
        lastRuntimeErrorLogged = null
        vpnManager.clearRuntimeError()
    }

    fun importProfile(rawInput: String) {
        viewModelScope.launch {
            importProfileInternal(rawInput = rawInput, sourceLabel = "текст")
        }
    }

    fun importProfileFromAddScreen(rawInput: String) {
        viewModelScope.launch {
            importProfileInternal(
                rawInput = rawInput,
                sourceLabel = "текст",
                reportToAddScreen = true
            )
        }
    }

    fun importProfileFromFile(uri: Uri) {
        viewModelScope.launch {
            val rawInput = withContext(Dispatchers.IO) { readProfileFile(uri) }.getOrElse { error ->
                handleError(
                    userMessage = "Не удалось прочитать файл профиля",
                    error = error,
                    fallbackCode = AppErrorCode.IMPORT_001
                )
                return@launch
            }

            importProfileInternal(rawInput = rawInput, sourceLabel = "файл")
        }
    }

    fun importProfileFromFileFromAddScreen(uri: Uri) {
        viewModelScope.launch {
            val rawInput = withContext(Dispatchers.IO) { readProfileFile(uri) }.getOrElse { error ->
                emitAddContentFailure(
                    AppErrors.fromThrowable(
                        error = error,
                        fallbackCode = AppErrorCode.IMPORT_001,
                        fallbackUserMessage = "Не удалось прочитать файл профиля"
                    )
                )
                return@launch
            }

            importProfileInternal(
                rawInput = rawInput,
                sourceLabel = "файл",
                reportToAddScreen = true
            )
        }
    }

    private fun readProfileFile(uri: Uri): Result<String> = runCatching {
        val resolver = getApplication<Application>().contentResolver
        resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("Файл пустой или недоступен")
    }

    fun importExternalIntent(intent: Intent) {
        viewModelScope.launch {
            val payloads = withContext(Dispatchers.IO) {
                extractExternalImportPayloads(intent)
            }
            if (payloads.isEmpty()) {
                applyUiError(
                    AppErrors.profileImportFailed(
                        technicalReason = "external intent does not contain readable text or uri"
                    )
                )
                return@launch
            }

            payloads.forEach { payload ->
                importExternalPayload(rawInput = payload.text, sourceLabel = payload.sourceLabel)
            }
        }
    }

    fun addSubscription(sourceUrl: String, displayName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            importProfileInternal(sourceUrl, "текст", displayName = displayName)
        }
    }

    private suspend fun importNoraDeepLink(rawUri: String) {
        runCatching {
            NoraDeepLinkParser.parse(rawUri)
        }.onSuccess { request ->
            when (request) {
                is NoraDeepLinkImport.Subscription -> addSubscriptionInternal(
                    sourceUrl = request.url,
                    displayName = request.displayName,
                    reportToAddScreen = true
                )

                is NoraDeepLinkImport.Profile -> importProfileInternal(
                    rawInput = request.payload,
                    sourceLabel = "deep link",
                    reportToAddScreen = true
                )
            }
        }.onFailure { error ->
            applyUiError(
                AppErrors.profileImportFailed(
                    technicalReason = "invalid NORA deep link: ${error.message}"
                )
            )
            emitTransientMessage("Не удалось открыть ссылку импорта NORA VPN.")
        }
    }

    fun addSubscriptionFromAddScreen(sourceUrl: String, displayName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            importProfileInternal(sourceUrl, "текст", reportToAddScreen = true, displayName = displayName)
        }
    }

    private suspend fun addSubscriptionInternal(
        sourceUrl: String,
        displayName: String?,
        reportToAddScreen: Boolean = false
    ) {
        runCatching {
            subscriptionRepository.addSubscription(sourceUrl = sourceUrl, displayName = displayName)
        }.onSuccess { source ->
            addLog(LogLevel.INFO, "Подписка '${source.displayName}' добавлена")
            val message = "Подписка '${source.displayName}' добавлена"
            if (reportToAddScreen) {
                _addContentEvents.send(
                    AddContentEvent.Success(
                        target = AddedServerTarget.Subscription(source.id),
                        message = message
                    )
                )
            } else {
                emitTransientMessage(message)
            }
            refreshSubscription(source.id, showSuccessMessage = false)
            SubscriptionUpdateWorker.schedule(getApplication<Application>().applicationContext)
        }.onFailure { error ->
            val appError = AppErrors.subscriptionAddFailed(
                technicalReason = error.message
            )
            if (reportToAddScreen) {
                emitAddContentFailure(appError)
            } else {
                applyUiError(appError)
            }
        }
    }

    fun refreshSubscription(subscriptionId: String, showSuccessMessage: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            mutateRefreshingSubscriptions(add = subscriptionId)
            val result = runCatching {
                subscriptionRepository.refreshSubscription(subscriptionId = subscriptionId, force = true)
            }.getOrElse { error ->
                mutateRefreshingSubscriptions(remove = subscriptionId)
                applyUiError(
                    AppErrors.subscriptionRefreshFailed(
                        technicalReason = error.message
                    )
                )
                return@launch
            }
            mutateRefreshingSubscriptions(remove = subscriptionId)
            handleSubscriptionRefreshResult(result, showSuccessMessage = showSuccessMessage)
            logSubscriptionUiMappingSnapshot(subscriptionId)
        }
    }

    fun refreshAllSubscriptions() {
        viewModelScope.launch(Dispatchers.IO) {
            val ids = uiState.value.subscriptions.map { it.id }.toSet()
            if (ids.isNotEmpty()) {
                mutateRefreshingSubscriptions(addAll = ids)
            }
            val results = runCatching {
                subscriptionRepository.refreshAllSubscriptions(force = true)
            }.getOrElse { error ->
                mutateRefreshingSubscriptions(removeAll = ids)
                applyUiError(
                    AppErrors.subscriptionRefreshFailed(
                        technicalReason = "refresh all subscriptions: ${error.message}"
                    )
                )
                return@launch
            }
            mutateRefreshingSubscriptions(removeAll = ids)
            if (results.isEmpty()) {
                emitTransientMessage("Нет подписок для обновления")
                return@launch
            }
            val successCount = results.count {
                it.status == SubscriptionSyncStatus.SUCCESS || it.status == SubscriptionSyncStatus.PARTIAL
            }
            val errorCount = results.count { it.status == SubscriptionSyncStatus.ERROR }
            emitTransientMessage("Подписки обновлены: успешно $successCount, с ошибкой $errorCount")
            results.forEach { result ->
                handleSubscriptionRefreshResult(result, showSuccessMessage = false)
                logSubscriptionUiMappingSnapshot(result.subscriptionId)
            }
        }
    }

    fun toggleSubscriptionCollapse(subscriptionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                subscriptionRepository.toggleCollapse(subscriptionId)
            }.onFailure { error ->
                applyUiError(
                    AppErrors.subscriptionMutationFailed(
                        userMessage = "Не удалось изменить состояние группы подписки",
                        technicalReason = error.message
                    )
                )
            }
        }
    }

    fun revealActiveProfileInServers() {
        viewModelScope.launch(Dispatchers.IO) {
            val activeProfile = uiState.value.activeProfile ?: return@launch
            val subscriptionId = activeProfile.parentSubscriptionId ?: return@launch
            val subscription = uiState.value.subscriptions.firstOrNull { it.id == subscriptionId } ?: return@launch
            if (subscription.isCollapsed) {
                runCatching { subscriptionRepository.toggleCollapse(subscriptionId) }
            }
        }
    }

    fun renameSubscription(subscriptionId: String, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                subscriptionRepository.renameSubscription(subscriptionId, displayName)
            }.onSuccess {
                addLog(LogLevel.INFO, "Подписка переименована: $displayName")
            }.onFailure { error ->
                applyUiError(
                    AppErrors.subscriptionMutationFailed(
                        userMessage = "Не удалось переименовать подписку",
                        technicalReason = error.message
                    )
                )
            }
        }
    }

    fun deleteSubscription(subscriptionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val selectedSubscription = uiState.value.subscriptions.firstOrNull { it.id == subscriptionId }
            val activeProfile = uiState.value.activeProfile
            runCatching {
                subscriptionRepository.deleteSubscription(subscriptionId)
                if (activeProfile?.parentSubscriptionId == subscriptionId) {
                    userSettingsRepository.setActiveProfile(null)
                }
            }.onSuccess {
                addLog(
                    LogLevel.INFO,
                    "Подписка '${selectedSubscription?.displayName ?: subscriptionId}' удалена"
                )
            }.onFailure { error ->
                applyUiError(
                    AppErrors.subscriptionMutationFailed(
                        userMessage = "Не удалось удалить подписку",
                        technicalReason = error.message
                    )
                )
            }
        }
    }

    fun setSubscriptionAutoUpdate(subscriptionId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                subscriptionRepository.setAutoUpdateEnabled(subscriptionId, enabled)
                SubscriptionUpdateWorker.schedule(getApplication<Application>().applicationContext)
            }.onSuccess {
                addLog(
                    LogLevel.INFO,
                    "Автообновление подписки: ${if (enabled) "включено" else "выключено"}"
                )
            }.onFailure { error ->
                applyUiError(
                    AppErrors.subscriptionMutationFailed(
                        userMessage = "Не удалось изменить автообновление подписки",
                        technicalReason = error.message
                    )
                )
            }
        }
    }

    fun setSubscriptionInterval(subscriptionId: String, minutes: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                subscriptionRepository.setUpdateIntervalMinutes(subscriptionId, minutes)
                SubscriptionUpdateWorker.schedule(getApplication<Application>().applicationContext)
            }.onSuccess {
                addLog(LogLevel.INFO, "Интервал обновления подписки установлен: $minutes мин")
            }.onFailure { error ->
                applyUiError(
                    AppErrors.subscriptionMutationFailed(
                        userMessage = "Не удалось изменить интервал подписки",
                        technicalReason = error.message
                    )
                )
            }
        }
    }

    fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                subscriptionRepository.setEnabled(subscriptionId, enabled)
            }.onSuccess {
                addLog(
                    LogLevel.INFO,
                    "Подписка ${if (enabled) "включена" else "отключена"}"
                )
            }.onFailure { error ->
                applyUiError(
                    AppErrors.subscriptionMutationFailed(
                        userMessage = "Не удалось изменить состояние подписки",
                        technicalReason = error.message
                    )
                )
            }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            runCatching {
                val profile = profilesRepository.getProfile(profileId)
                profilesRepository.deleteProfile(profileId)

                if (uiState.value.activeProfileId == profileId) {
                    userSettingsRepository.setActiveProfile(null)
                }

                profile
            }.onSuccess { deleted ->
                addLog(LogLevel.INFO, "Профиль '${deleted?.displayName ?: profileId}' удалён")
            }.onFailure { error ->
                handleError(
                    userMessage = "Не удалось удалить профиль",
                    error = error,
                    fallbackCode = AppErrorCode.UI_001
                )
            }
        }
    }

    fun renameProfile(profileId: String, displayName: String) {
        val normalized = displayName.trim()
        if (normalized.isBlank()) {
            applyUiError(
                AppErrors.genericUiStateError(
                    userMessage = "Имя профиля не должно быть пустым",
                    technicalReason = "rename profile validation failed: blank displayName"
                )
            )
            return
        }

        viewModelScope.launch {
            runCatching {
                profilesRepository.renameProfile(profileId, normalized)
            }.onSuccess {
                addLog(LogLevel.INFO, "Профиль переименован: $normalized")
            }.onFailure { error ->
                handleError(
                    userMessage = "Не удалось переименовать профиль",
                    error = error,
                    fallbackCode = AppErrorCode.UI_001
                )
            }
        }
    }

    fun setActiveProfile(profileId: String) {
        val snapshot = uiState.value
        val profile = snapshot.profiles.firstOrNull { it.id == profileId }
        if (profile == null) {
            applyUiError(
                AppErrors.genericUiStateError(
                    userMessage = "Выбранный сервер больше недоступен.",
                    technicalReason = "setActiveProfile profile not found: $profileId"
                )
            )
            return
        }

        if (!profile.isConnectable) return
        if (!isVpnConnectedOrConnecting(snapshot.vpnStatus)) {
            viewModelScope.launch {
                persistActiveProfileSelection(profile)
            }
            return
        }

        if (profileId == snapshot.activeProfileId && profileSwitchJob?.isActive != true) {
            return
        }

        profileSwitchRequests.offer(profileId)
        if (profileSwitchJob?.isActive == true) {
            addLog(LogLevel.INFO, "PROFILE SWITCH: в очередь поставлен последний выбор '${profile.displayName}'")
            return
        }

        profileSwitchJob = viewModelScope.launch {
            while (true) {
                val requestedProfileId = profileSwitchRequests.takeLatest() ?: break
                backendOperationMutex.lock()
                try {
                    switchActiveProfileInternal(requestedProfileId)
                } finally {
                    backendOperationMutex.unlock()
                }
            }
        }
    }

    private suspend fun switchActiveProfileInternal(requestedProfileId: String) {
        var profile = uiState.value.profiles.firstOrNull { it.id == requestedProfileId } ?: run {
            applyUiError(
                AppErrors.genericUiStateError(
                    userMessage = "Выбранный сервер больше недоступен.",
                    technicalReason = "hot switch profile not found: $requestedProfileId"
                )
            )
            return
        }

        if (!profile.isConnectable) return
        val beforeStop = uiState.value
        val currentStatus = vpnManager.status.value
        if (!isVpnConnectedOrConnecting(currentStatus)) {
            persistActiveProfileSelection(profile)
            return
        }

        if (beforeStop.privateSessionUiState.enabled && beforeStop.privateSessionUiState.trustedPackages.isEmpty()) {
            applyUiError(
                AppErrors.splitTunnelingNoTrustedApps(
                    technicalReason = "profile hot switch blocked: privateSession enabled, trustedPackages empty"
                )
            )
            return
        }

        addLog(
            LogLevel.INFO,
            "PROFILE SWITCH: '${beforeStop.activeProfile?.displayName ?: "<unknown>"}' -> '${profile.displayName}'"
        )
        val stopped = performBackendSwitch(
            currentBackendType = resolveCurrentBackendType(),
            targetBackendType = profile.type,
            currentStatus = currentStatus
        )
        if (!stopped) return

        profileSwitchRequests.takeLatest()?.let { latestId ->
            uiState.value.profiles.firstOrNull { it.id == latestId && it.isConnectable }?.let { latestProfile ->
                profile = latestProfile
                addLog(LogLevel.INFO, "PROFILE SWITCH: применён последний выбор '${latestProfile.displayName}'")
            }
        }

        profile = recoverProfileWithMissingShortId(profile)
        if (!persistActiveProfileSelection(profile)) return

        if (disconnectQueued) {
            addLog(LogLevel.INFO, "PROFILE SWITCH: запуск нового сервера отменён ожидающей командой отключения")
            return
        }

        when (val readyStatus = vpnManager.status.value) {
            VpnConnectionStatus.READY -> {
                startProfileBackend(
                    profile = profile,
                    snapshot = uiState.value,
                    switchPerformed = true
                )
            }

            VpnConnectionStatus.NO_PERMISSION -> {
                emitTransientMessage("Сервер выбран. Для подключения снова разрешите NORA VPN создать VPN-соединение.")
                addLog(LogLevel.INFO, "PROFILE SWITCH: запуск отменён, VPN-разрешение передано другому приложению")
            }

            VpnConnectionStatus.ERROR -> {
                addLog(LogLevel.ERROR, "PROFILE SWITCH: backend остановлен с ошибкой, новый сервер не запущен")
            }

            else -> {
                applyUiError(
                    AppErrors.backendSwitchTimeout(
                        fromBackend = backendTypeLabel(resolveCurrentBackendType()),
                        toBackend = backendTypeLabel(profile.type),
                        technicalReason = "unexpected post-stop status=${readyStatus.name}"
                    )
                )
            }
        }
    }

    private suspend fun persistActiveProfileSelection(profile: VpnProfile): Boolean {
        if (!profile.isConnectable) return false
        return runCatching {
            userSettingsRepository.setActiveProfile(profile.id)
            profile.parentSubscriptionId?.let { parentId ->
                runCatching {
                    subscriptionRepository.setLastSelectedProfile(
                        subscriptionId = parentId,
                        profileId = profile.id
                    )
                }.onFailure { error ->
                    addLog(
                        LogLevel.INFO,
                        "Не удалось обновить последний сервер подписки '${profile.displayName}': ${error.message}"
                    )
                }
            }
        }.onSuccess {
            vpnManager.updateActiveProfileName(profile.userFacingName)
            VpnRuntimeStateStore.setLastSelectedProfileName(profile.userFacingName)
            addLog(LogLevel.INFO, "Активный профиль: ${profile.displayName}")
        }.onFailure { error ->
            handleError(
                userMessage = "Не удалось выбрать активный профиль",
                error = error,
                fallbackCode = AppErrorCode.UI_002
            )
        }.isSuccess
    }

    fun refreshPrivateSessionData() {
        refreshPrivateSessionApps()
        refreshSystemVpnIntegrationState()
    }

    fun refreshPrivateSessionApps() {
        viewModelScope.launch {
            _privateSessionAppsLoading.value = true
            runCatching {
                installedAppsRepository.listInstalledApps()
            }.onSuccess { apps ->
                _privateSessionInstalledApps.value = apps
                _privateSessionAppIcons.value = appIconRepository.loadIcons(apps)
                addLog(LogLevel.INFO, "Список приложений для приватной сессии обновлён (${apps.size})")
            }.onFailure { error ->
                handleError(
                    userMessage = "Не удалось загрузить список приложений",
                    error = error,
                    fallbackCode = AppErrorCode.SPLIT_002
                )
            }
            _privateSessionAppsLoading.value = false
        }
    }

    fun refreshSystemVpnIntegrationState() {
        viewModelScope.launch {
            val state = runCatching {
                vpnSystemSettingsRepository.readSystemVpnIntegrationState()
            }.getOrElse { error ->
                Log.w("PrivateVPN", "Не удалось прочитать статус Always-on/Lockdown: ${error.message}")
                SystemVpnIntegrationState()
            }
            _systemVpnIntegrationState.value = state
        }
    }

    fun buildOpenSystemVpnSettingsIntent(): Intent {
        return vpnSystemSettingsRepository.buildOpenVpnSettingsIntent()
    }

    fun toggleTrustedAppSelection(packageName: String, selected: Boolean) {
        if (blockSplitTunnelingMutationWhileConnected(action = "toggle trusted app selection")) return

        val current = _privateSessionDraftTrustedPackages.value.toMutableSet()
        if (selected) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        _privateSessionDraftTrustedPackages.value = current
        _privateSessionDraftDirty.value = true
        scheduleTrustedAppsPersist()
    }

    fun applyPrivateSessionTrustedApps() {
        if (blockSplitTunnelingMutationWhileConnected(action = "apply trusted apps")) return
        scheduleTrustedAppsPersist(immediate = true)
    }

    private fun scheduleTrustedAppsPersist(immediate: Boolean = false) {
        trustedAppsPersistJob?.cancel()
        trustedAppsPersistJob = viewModelScope.launch {
            if (!immediate) {
                delay(TRUSTED_APPS_APPLY_DEBOUNCE_MS)
            }
            if (blockSplitTunnelingMutationWhileConnected(action = "persist trusted apps")) {
                return@launch
            }
            val selected = _privateSessionDraftTrustedPackages.value
            runCatching {
                userSettingsRepository.setPrivateSessionTrustedPackages(selected)
            }.onSuccess {
                _privateSessionDraftDirty.value = false
                addLog(LogLevel.INFO, "Список доверенных приложений сохранён: ${selected.size}")
                emitTransientMessage("Список доверенных приложений обновлён")
            }.onFailure { error ->
                handleError(
                    userMessage = "Не удалось сохранить доверенные приложения",
                    error = error,
                    fallbackCode = AppErrorCode.SPLIT_002
                )
            }
        }
    }

    fun togglePrivateSession() {
        val current = uiState.value.privateSessionUiState.enabled
        setPrivateSessionEnabled(!current)
    }

    fun setPrivateSessionEnabled(enabled: Boolean) {
        if (blockSplitTunnelingMutationWhileConnected(action = "toggle private session")) return

        viewModelScope.launch {
            val startedAt = if (enabled) {
                uiState.value.privateSessionUiState.startedAtMs ?: System.currentTimeMillis()
            } else {
                null
            }

            runCatching {
                userSettingsRepository.setPrivateSession(enabled, startedAt)
            }.onSuccess {
                if (enabled) {
                    addLog(LogLevel.INFO, "Приватная сессия включена")
                } else {
                    addLog(LogLevel.INFO, "Приватная сессия выключена")
                }
            }.onFailure { error ->
                handleError(
                    userMessage = "Не удалось изменить режим приватной сессии",
                    error = error,
                    fallbackCode = AppErrorCode.SPLIT_002
                )
            }
        }
    }

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch {
            userSettingsRepository.setAutoConnectOnLaunch(enabled)
            addLog(LogLevel.INFO, "Автоподключение при запуске: ${if (enabled) "включено" else "выключено"}")
        }
    }

    fun setVerboseLogs(enabled: Boolean) {
        viewModelScope.launch {
            userSettingsRepository.setVerboseLogs(enabled)
            addLog(LogLevel.INFO, "Подробные логи: ${if (enabled) "включены" else "выключены"}")
        }
    }

    fun setDnsMode(mode: DnsMode) {
        viewModelScope.launch {
            userSettingsRepository.setDnsMode(mode)
            addLog(LogLevel.INFO, "Режим DNS изменён: ${mode.name}")
        }
    }

    fun saveCustomDnsServers(servers: List<String>) {
        viewModelScope.launch {
            userSettingsRepository.setCustomDnsServers(servers)
            addLog(LogLevel.INFO, "Пользовательские DNS сохранены")
        }
    }

    fun saveSocksSettings(settings: SocksSettings) {
        if (settings.enabled) {
            if (settings.port !in 1..65535) {
                applyUiError(
                    AppErrors.socksInvalidPort(
                        technicalReason = "invalid socks port: ${settings.port}"
                    )
                )
                return
            }
            if (settings.login.isBlank() || settings.password.isBlank()) {
                applyUiError(
                    AppErrors.socksAuthRequired(
                        technicalReason = "missing socks login/password when enabled=true"
                    )
                )
                return
            }
        }

        viewModelScope.launch {
            runCatching {
                userSettingsRepository.setSocksSettings(settings)
            }.onSuccess {
                addLog(LogLevel.INFO, "Параметры localhost SOCKS сохранены")
                emitTransientMessage("Настройки localhost SOCKS успешно сохранены")
            }.onFailure { error ->
                addLog(
                    LogLevel.ERROR,
                    "Не удалось сохранить localhost SOCKS: ${error.message ?: "unknown error"}"
                )
                emitTransientMessage("Не удалось сохранить настройки localhost SOCKS")
            }
        }
    }

    fun emitTransientMessage(message: String) {
        _transientMessage.value = message
    }

    fun consumeTransientMessage() {
        _transientMessage.value = null
    }

    fun pingAllServers() {
        if (_pingInProgress.value) return
        _pingInProgress.value = true

        viewModelScope.launch {
            try {
                val snapshotProfiles = profilesRepository.profiles.first().filter { it.isConnectable }
                if (snapshotProfiles.isEmpty()) {
                    emitTransientMessage("Нет серверов для проверки пинга")
                    return@launch
                }

                _serverPingResults.value = snapshotProfiles.associate { it.id to "…" }
                val results = withContext(Dispatchers.IO) {
                    val concurrency = Semaphore(permits = 6)
                    snapshotProfiles
                        .map { profile ->
                            async {
                                concurrency.withPermit {
                                    profile.id to measureProfilePing(profile)
                                }
                            }
                        }
                        .awaitAll()
                        .toMap()
                }
                _serverPingResults.value = results
            } finally {
                _pingInProgress.value = false
            }
        }
    }

    private fun resolveDnsState(settings: SettingsState, activeProfile: VpnProfile?): DnsState {
        val profileServers = activeProfile?.dnsServers.orEmpty().filter { it.isNotBlank() }

        return when (settings.dnsMode) {
            DnsMode.FROM_PROFILE -> {
                if (profileServers.isNotEmpty()) {
                    DnsState(
                        mode = settings.dnsMode,
                        resolvedSource = DnsResolvedSource.PROFILE,
                        resolvedServers = profileServers,
                        customServers = settings.customDnsServers,
                        profileServers = profileServers,
                        activeProfileName = activeProfile?.displayName
                    )
                } else {
                    DnsState(
                        mode = settings.dnsMode,
                        resolvedSource = DnsResolvedSource.PROFILE_FALLBACK_DEFAULT,
                        resolvedServers = DefaultDnsProvider.defaultServers,
                        customServers = settings.customDnsServers,
                        profileServers = profileServers,
                        activeProfileName = activeProfile?.displayName
                    )
                }
            }

            DnsMode.APP_DEFAULT -> DnsState(
                mode = settings.dnsMode,
                resolvedSource = DnsResolvedSource.APP_DEFAULT,
                resolvedServers = DefaultDnsProvider.defaultServers,
                customServers = settings.customDnsServers,
                profileServers = profileServers,
                activeProfileName = activeProfile?.displayName
            )

            DnsMode.CUSTOM -> {
                val custom = settings.customDnsServers.filter { it.isNotBlank() }
                if (custom.isNotEmpty()) {
                    DnsState(
                        mode = settings.dnsMode,
                        resolvedSource = DnsResolvedSource.CUSTOM,
                        resolvedServers = custom,
                        customServers = custom,
                        profileServers = profileServers,
                        activeProfileName = activeProfile?.displayName
                    )
                } else {
                    DnsState(
                        mode = settings.dnsMode,
                        resolvedSource = DnsResolvedSource.CUSTOM_FALLBACK_DEFAULT,
                        resolvedServers = DefaultDnsProvider.defaultServers,
                        customServers = custom,
                        profileServers = profileServers,
                        activeProfileName = activeProfile?.displayName
                    )
                }
            }
        }
    }

    private fun resolveRequestedProfileType(): ProfileType {
        val snapshot = uiState.value
        return snapshot.activeProfile?.type
            ?: snapshot.profiles.firstOrNull { it.isConnectable }?.type
            ?: ProfileType.XRAY_JSON
    }

    private fun handleSubscriptionRefreshResult(
        result: SubscriptionRefreshResult,
        showSuccessMessage: Boolean
    ) {
        when (result.status) {
            SubscriptionSyncStatus.SUCCESS -> {
                addLog(
                    LogLevel.INFO,
                    "Подписка ${result.subscriptionId} обновлена. Импортировано ${result.importedProfilesCount}"
                )
                if (showSuccessMessage) {
                    emitTransientMessage("Подписка обновлена: ${result.importedProfilesCount} серверов")
                }
            }

            SubscriptionSyncStatus.PARTIAL -> {
                val message = result.message ?: "Подписка обновлена частично"
                addLog(
                    LogLevel.ERROR,
                    AppErrors.subscriptionPartialUpdate(
                        userMessage = message,
                        technicalReason = "subscription=${result.subscriptionId}, invalid=${result.invalidEntriesCount}"
                    ).toLogMessage()
                )
                emitTransientMessage(message)
            }

            SubscriptionSyncStatus.EMPTY -> {
                val message = result.message ?: "Подписка не содержит серверов"
                addLog(LogLevel.INFO, message)
                emitTransientMessage(message)
            }

            SubscriptionSyncStatus.ERROR -> {
                val message = result.message ?: "Ошибка обновления подписки"
                addLog(
                    LogLevel.ERROR,
                    AppErrors.subscriptionRefreshFailed("subscription=${result.subscriptionId}: $message").toLogMessage()
                )
                emitTransientMessage(message)
            }

            SubscriptionSyncStatus.DISABLED -> {
                addLog(LogLevel.INFO, "Подписка ${result.subscriptionId} отключена, обновление пропущено")
            }

            SubscriptionSyncStatus.LOADING -> Unit
        }
    }

    private fun logSubscriptionUiMappingSnapshot(subscriptionId: String) {
        val snapshot = uiState.value
        val rawEntries = snapshot.profiles.size
        val childProfiles = snapshot.profiles.count { it.parentSubscriptionId == subscriptionId }
        val groupedProfiles = snapshot.profiles
            .filter { !it.parentSubscriptionId.isNullOrBlank() }
            .groupBy { it.parentSubscriptionId.orEmpty() }
            .mapValues { it.value.size }
        val subscriptionCount = snapshot.subscriptions.firstOrNull { it.id == subscriptionId }?.profileCount

        Log.i(
            "SubscriptionUiMap",
            "subscription=$subscriptionId rawEntries=$rawEntries childProfiles=$childProfiles " +
                "groupedProfiles=$groupedProfiles subscriptionProfileCount=${subscriptionCount ?: -1}"
        )
    }

    private fun mutateRefreshingSubscriptions(
        add: String? = null,
        remove: String? = null,
        addAll: Set<String> = emptySet(),
        removeAll: Set<String> = emptySet()
    ) {
        _refreshingSubscriptionIds.update { current ->
            val next = current.toMutableSet()
            if (addAll.isNotEmpty()) next += addAll
            if (removeAll.isNotEmpty()) next.removeAll(removeAll)
            add?.let { next += it }
            remove?.let { next -= it }
            next
        }
    }

    private fun isVpnConnectedOrConnecting(status: VpnConnectionStatus = uiState.value.vpnStatus): Boolean {
        return status == VpnConnectionStatus.CONNECTED || status == VpnConnectionStatus.CONNECTING
    }

    private fun blockSplitTunnelingMutationWhileConnected(action: String): Boolean {
        val status = uiState.value.vpnStatus
        if (!isVpnConnectedOrConnecting(status)) return false

        applyUiError(
            AppError(
                code = AppErrorCode.SPLIT_002,
                userMessage = "Нельзя изменять раздельное туннелирование при активном VPN. Сначала отключите VPN.",
                technicalReason = "blocked split tunneling mutation '$action' while status=${status.name}",
                recoverable = true
            )
        )
        addLog(
            LogLevel.INFO,
            "SPLIT LOCK: действие '$action' отклонено при status=${status.name}"
        )
        return true
    }

    private fun resolveCurrentBackendType(): ProfileType? {
        return activeBackendProfileType ?: lastBackendProfileType
    }

    private fun requiresBackendSwitch(
        currentBackendType: ProfileType?,
        targetBackendType: ProfileType,
        status: VpnConnectionStatus
    ): Boolean {
        if (status == VpnConnectionStatus.CONNECTED || status == VpnConnectionStatus.CONNECTING) {
            if (currentBackendType == null) return true
            return currentBackendType != targetBackendType
        }

        return currentBackendType != null && currentBackendType != targetBackendType
    }

    private suspend fun performBackendSwitch(
        currentBackendType: ProfileType?,
        targetBackendType: ProfileType,
        currentStatus: VpnConnectionStatus
    ): Boolean {
        val fromBackend = backendTypeLabel(currentBackendType)
        val toBackend = backendTypeLabel(targetBackendType)

        backendSwitchInProgress = true
        backendSwitchTargetType = targetBackendType
        addLog(
            LogLevel.INFO,
            "BACKEND SWITCH: from=$fromBackend, to=$toBackend, status=${currentStatus.name}"
        )

        return try {
            addLog(LogLevel.INFO, "BACKEND SWITCH: stage=stop backend/container")
            val stopResult = stopCurrentBackendForSwitch(
                currentBackendType = currentBackendType,
                currentStatus = currentStatus
            )
            val stopError = stopResult.exceptionOrNull()
            if (stopError != null) {
                val appError = AppErrors.backendSwitchFailed(
                    fromBackend = fromBackend,
                    toBackend = toBackend,
                    technicalReason = stopError.message
                )
                applyUiError(appError)
                return false
            }

            addLog(LogLevel.INFO, "BACKEND SWITCH: stage=await backend readiness")
            val readyStatus = awaitBackendReadyStatus(BACKEND_SWITCH_READY_TIMEOUT_MS)
            if (readyStatus == null) {
                val appError = AppErrors.backendSwitchTimeout(
                    fromBackend = fromBackend,
                    toBackend = toBackend,
                    technicalReason = "status=${vpnManager.status.value.name}"
                )
                applyUiError(appError)
                return false
            }
            addLog(LogLevel.INFO, "BACKEND SWITCH: backend ready, status=${readyStatus.name}")
            delay(BACKEND_SWITCH_SETTLE_DELAY_MS)
            true
        } finally {
            backendSwitchInProgress = false
            backendSwitchTargetType = null
        }
    }

    private fun stopCurrentBackendForSwitch(
        currentBackendType: ProfileType?,
        currentStatus: VpnConnectionStatus
    ): Result<Unit> {
        return runCatching {
            when {
                currentBackendType != null -> {
                    resolveBackendAdapter(currentBackendType).stop().getOrThrow()
                }

                currentStatus == VpnConnectionStatus.CONNECTED ||
                    currentStatus == VpnConnectionStatus.CONNECTING -> {
                    val xrayStopError = runCatching { xrayBackendAdapter.stop().getOrThrow() }.exceptionOrNull()
                    val awgStopError = runCatching { awgBackendAdapter.stop().getOrThrow() }.exceptionOrNull()
                    val krotStopError = runCatching { krotBackendAdapter.stop().getOrThrow() }.exceptionOrNull()
                    if (xrayStopError != null && awgStopError != null && krotStopError != null) {
                        throw IllegalStateException(
                            "Не удалось остановить backend switch fallback. " +
                                "xray='${xrayStopError.message}', awg='${awgStopError.message}', " +
                                "krot='${krotStopError.message}'"
                        )
                    }
                }
            }

            activeBackendAdapter = null
            activeBackendProfileType = null
        }
    }

    private suspend fun awaitBackendReadyStatus(timeoutMs: Long): VpnConnectionStatus? {
        val startedAt = System.currentTimeMillis()
        var nextProgressLogAt = startedAt + BACKEND_SWITCH_PROGRESS_LOG_INTERVAL_MS

        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            val status = vpnManager.status.value
            if (
                status == VpnConnectionStatus.READY ||
                status == VpnConnectionStatus.NO_PERMISSION ||
                status == VpnConnectionStatus.ERROR
            ) {
                return status
            }

            val now = System.currentTimeMillis()
            if (now >= nextProgressLogAt) {
                addLog(LogLevel.INFO, "BACKEND SWITCH: waiting readiness, status=${status.name}")
                nextProgressLogAt = now + BACKEND_SWITCH_PROGRESS_LOG_INTERVAL_MS
            }
            delay(BACKEND_SWITCH_POLL_DELAY_MS)
        }

        return null
    }

    private suspend fun startBackendWithWarmupRetry(
        backendAdapter: BackendAdapter,
        profile: VpnProfile,
        dnsServers: List<String>,
        privateSessionEnabled: Boolean,
        trustedPackages: Set<String>,
        socksSettings: SocksSettings,
        targetBackendType: ProfileType,
        switchPerformed: Boolean
    ): Result<BackendStartResult> {
        val firstAttempt = backendAdapter.start(
            profile = profile,
            dnsServers = dnsServers,
            privateSessionEnabled = privateSessionEnabled,
            privateSessionTrustedPackages = trustedPackages,
            socksSettings = socksSettings
        )
        if (firstAttempt.isSuccess) return firstAttempt

        val firstError = firstAttempt.exceptionOrNull()
        val awgGuaranteedRetry = targetBackendType == ProfileType.AMNEZIA_WG_20
        val shouldWarmupRetry = switchPerformed || awgGuaranteedRetry
        if (!shouldWarmupRetry || firstError == null) {
            return firstAttempt
        }
        if (!awgGuaranteedRetry && !isBackendWarmupIssue(firstError)) {
            return firstAttempt
        }

        val maxRetries = 1
        var result = firstAttempt
        for (retryIndex in 1..maxRetries) {
            val attemptError = result.exceptionOrNull() ?: break
            if (!awgGuaranteedRetry && !isBackendWarmupIssue(attemptError)) break

            val attemptNumber = retryIndex + 1
            addLog(
                LogLevel.INFO,
                "BACKEND SWITCH: ${backendTypeLabel(targetBackendType)} ещё прогревается, " +
                    "автоповтор подключения (attempt=$attemptNumber)"
            )
            delay(BACKEND_SWITCH_RETRY_DELAY_MS * retryIndex)

            result = backendAdapter.start(
                profile = profile,
                dnsServers = dnsServers,
                privateSessionEnabled = privateSessionEnabled,
                privateSessionTrustedPackages = trustedPackages,
                socksSettings = socksSettings
            )
            if (result.isSuccess) return result
        }
        return result
    }

    private fun mapBackendStartError(
        backendType: ProfileType,
        error: Throwable
    ): AppError {
        if (isBackendWarmupIssue(error)) {
            return AppErrors.backendSwitchInProgress(
                fromBackend = backendTypeLabel(resolveCurrentBackendType()),
                toBackend = backendTypeLabel(backendType),
                technicalReason = error.message
            )
        }

        return when (backendType) {
            ProfileType.AMNEZIA_WG_20 -> AppErrors.awgRuntimeStartFailed(error.message)
            ProfileType.KROT -> AppErrors.krotRuntimeStartFailed(error.message)
            else -> AppErrors.xrayRuntimeStartFailed(error.message)
        }
    }

    private fun isBackendWarmupIssue(error: Throwable): Boolean {
        val text = error.message?.lowercase().orEmpty()
        if (text.isBlank()) return false

        return BACKEND_WARMUP_ERROR_MARKERS.any { marker ->
            text.contains(marker)
        }
    }

    private fun backendTypeLabel(type: ProfileType?): String {
        return when (type) {
            ProfileType.AMNEZIA_WG_20 -> "AmneziaWG 2.0"
            ProfileType.KROT -> "KRot"
            null -> "unknown"
            else -> "Xray"
        }
    }

    private fun applyUiError(appError: AppError) {
        addLog(LogLevel.ERROR, appError.toLogMessage())
        if (isConnectionError(appError.code)) {
            _connectionError.value = appError.toUiMessage()
        } else {
            emitTransientMessage(appError.toUiMessage())
        }
    }

    private suspend fun emitAddContentFailure(appError: AppError) {
        addLog(LogLevel.ERROR, appError.toLogMessage())
        _addContentEvents.send(AddContentEvent.Failure(appError.toUiMessage()))
    }

    private fun handleError(
        userMessage: String,
        error: Throwable,
        fallbackCode: AppErrorCode = AppErrorCode.UI_001
    ) {
        val mappedError = AppErrors.fromThrowable(
            error = error,
            fallbackCode = fallbackCode,
            fallbackUserMessage = userMessage
        )
        applyUiError(mappedError)
    }

    private fun isConnectionError(code: AppErrorCode): Boolean {
        return when (code) {
            AppErrorCode.VPN_001,
            AppErrorCode.BACKEND_001,
            AppErrorCode.BACKEND_002,
            AppErrorCode.BACKEND_003,
            AppErrorCode.XRAY_101,
            AppErrorCode.XRAY_102,
            AppErrorCode.AWG_101,
            AppErrorCode.AWG_102,
            AppErrorCode.KROT_101,
            AppErrorCode.KROT_102 -> true

            else -> false
        }
    }

    private suspend fun importQrPayloadInternal(raw: String, token: Long) = qrPersistenceMutex.withLock {
        try {
            val input = try {
                withContext(Dispatchers.IO) { profileInputParser.parse(raw) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw QrImportException(QrImportFailureCode.UNSUPPORTED)
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrentQrImport(token)) throw CancellationException()
            when (input) {
                is ProfileInput.Routing -> throw QrImportException(QrImportFailureCode.UNSUPPORTED)
                is ProfileInput.Profiles -> {
                    if (input.drafts.any { SubscriptionServiceRecords.isServiceRecord(it.sourceRaw, it.normalizedJson) &&
                            !SubscriptionServiceRecords.isKnownTechnicalEntry(it.sourceRaw, it.normalizedJson) }) {
                        throw QrImportException(QrImportFailureCode.UNSUPPORTED)
                    }
                    val imported = withContext(Dispatchers.IO) {
                        database.withTransaction {
                            val profiles = input.drafts.map { persistImportedDraft(it) }
                            currentCoroutineContext().ensureActive()
                            profiles
                        }
                    }
                    imported.forEachIndexed { index, profile ->
                        currentCoroutineContext().ensureActive()
                        if (!isCurrentQrImport(token)) throw CancellationException()
                        handleImportedProfileSuccess(profile, "QR", index == imported.lastIndex, token)
                    }
                }
                is ProfileInput.Subscription -> {
                    val source = try {
                        subscriptionRepository.importValidatedSubscription(input.url, input.displayName)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: SubscriptionImportException) {
                        throw QrImportException(if (error.reason == SubscriptionImportFailure.INVALID_INPUT)
                            QrImportFailureCode.UNSUPPORTED else QrImportFailureCode.LOAD_FAILED)
                    }
                    currentCoroutineContext().ensureActive()
                    if (!isCurrentQrImport(token)) throw CancellationException()
                    _addContentEvents.send(AddContentEvent.Success(
                        AddedServerTarget.Subscription(source.id), "Подписка добавлена", qrSession = token, clearManualDraft = false
                    ))
                    SubscriptionUpdateWorker.schedule(getApplication<Application>().applicationContext)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (safe: QrImportException) {
            throw safe
        } catch (_: Exception) {
            throw QrImportException(QrImportFailureCode.LOAD_FAILED)
        }
    }

    private suspend fun importProfileInternal(
        rawInput: String,
        sourceLabel: String,
        reportToAddScreen: Boolean = false,
        displayName: String? = null
    ) {
        try {
            when (val input = withContext(Dispatchers.IO) { profileInputParser.parse(rawInput, displayName) }) {
                is ProfileInput.Subscription -> addSubscriptionInternal(
                    sourceUrl = input.url, displayName = input.displayName, reportToAddScreen = reportToAddScreen
                )
                is ProfileInput.Profiles -> {
                    val imported = withContext(Dispatchers.IO) { input.drafts.map { persistImportedDraft(it) } }
                    imported.forEachIndexed { index, profile ->
                        handleImportedProfileSuccess(profile, sourceLabel, reportToAddScreen && index == imported.lastIndex)
                    }
                }
                is ProfileInput.Routing -> emitTransientMessage(
                    "HAPP Routing получен. Откройте ссылку профиля или подписки вместе с routing."
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Parser errors describe a category only; payloads and private URLs never enter diagnostics.
            val appError = AppErrors.profileImportFailed(technicalReason = "Input rejected: ${error.javaClass.simpleName}")
            if (reportToAddScreen) emitAddContentFailure(appError) else applyUiError(appError)
        }
    }

    private suspend fun importExternalPayload(rawInput: String, sourceLabel: String) {
        importProfileInternal(rawInput, sourceLabel)
    }

    private suspend fun persistImportedDraft(parsed: ImportedProfileDraft): VpnProfile {
        val profile = VpnProfile(
            id = UUID.randomUUID().toString(),
            displayName = parsed.displayName,
            type = parsed.type,
            sourceRaw = parsed.sourceRaw,
            normalizedJson = parsed.normalizedJson,
            dnsServers = parsed.dnsServers,
            dnsFallbackApplied = parsed.dnsFallbackApplied,
            isPartialImport = parsed.isPartialImport,
            importWarnings = parsed.importWarnings,
            importedAtMs = System.currentTimeMillis()
        )
        profilesRepository.addProfile(profile)
        return profile
    }

    private suspend fun handleImportedProfileSuccess(
        imported: VpnProfile,
        sourceLabel: String,
        reportToAddScreen: Boolean = false,
        qrSession: Long? = null
    ) {
        val settingsSnapshot = uiState.value.settingsState
        if (settingsSnapshot.activeProfileId == null && imported.isConnectable) {
            userSettingsRepository.setActiveProfile(imported.id)
            vpnManager.updateActiveProfileName(imported.displayName)
            VpnRuntimeStateStore.setLastSelectedProfileName(imported.displayName)
        }

        val displayName = noraProfileDisplayName(imported.displayName)
        addLog(LogLevel.INFO, "Профиль '$displayName' импортирован ($sourceLabel)")
        if (imported.dnsFallbackApplied) {
            addLog(LogLevel.INFO, "Для профиля '$displayName' подставлен DNS по умолчанию")
        }
        val message = if (imported.isPartialImport) {
            addLog(LogLevel.INFO, "Профиль '$displayName' импортирован частично")
            imported.importWarnings.forEach { warning -> addLog(LogLevel.INFO, warning) }
            "Профиль '$displayName' добавлен частично"
        } else {
            "Профиль '$displayName' успешно добавлен"
        }
        if (reportToAddScreen) {
            _addContentEvents.send(
                AddContentEvent.Success(
                    target = AddedServerTarget.Profile(imported.id),
                    message = message,
                    qrSession = qrSession,
                    clearManualDraft = qrSession == null
                )
            )
        } else {
            emitTransientMessage(message)
        }
    }

    private fun extractExternalImportPayloads(intent: Intent): List<ExternalImportPayload> {
        val payloads = mutableListOf<ExternalImportPayload>()

        fun addText(value: CharSequence?, label: String) {
            value?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { text ->
                payloads += ExternalImportPayload(text = text, sourceLabel = label)
            }
        }

        fun addUri(uri: Uri?, label: String) {
            uri ?: return
            val scheme = uri.scheme?.lowercase().orEmpty()
            if (scheme == "content" || scheme == "file") {
                readTextFromUri(uri)?.let { text ->
                    payloads += ExternalImportPayload(text = text, sourceLabel = label)
                }
            } else {
                uri.toString().takeIf { it.isNotBlank() }?.let { text ->
                    payloads += ExternalImportPayload(text = text, sourceLabel = label)
                }
            }
        }

        when (intent.action) {
            Intent.ACTION_VIEW,
            Intent.ACTION_EDIT -> addUri(intent.data, "внешняя ссылка")

            Intent.ACTION_SEND -> {
                addText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT), "поделиться")
                addUri(intent.streamUriExtra(), "поделиться файлом")
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                addText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT), "поделиться")
                intent.streamUriListExtra().forEach { uri -> addUri(uri, "поделиться файлами") }
            }
        }

        val clipData = intent.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                val item = clipData.getItemAt(index)
                addText(item.text, "clipboard")
                addUri(item.uri, "clipboard")
            }
        }

        return payloads.distinctBy { it.text }
    }

    private fun readTextFromUri(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun extractExternalImportCandidate(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("{") || looksLikeAwgPayload(trimmed)) return trimmed
        if (SUPPORTED_EXTERNAL_PREFIXES.any { trimmed.startsWith(it, ignoreCase = true) }) return trimmed
        return EXTERNAL_LINK_REGEX.find(trimmed)
            ?.value
            ?.trimEnd(',', ';', ')', ']', '}', '"', '\'')
            .orEmpty()
    }

    private fun isHappEncryptedLink(value: String): Boolean {
        return HappCrypt5Decryptor.isCrypt5Link(value)
    }

    private fun isHttpSubscriptionUrl(value: String): Boolean {
        return value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)
    }

    private fun looksLikeAwgPayload(value: String): Boolean {
        return value.contains("[Interface]", ignoreCase = true) && value.contains("[Peer]", ignoreCase = true)
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUriExtra(): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUriListExtra(): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
    }

    private fun resolveBackendAdapter(profileType: ProfileType): BackendAdapter {
        return when (profileType) {
            ProfileType.AMNEZIA_WG_20 -> awgBackendAdapter
            ProfileType.KROT -> krotBackendAdapter
            else -> xrayBackendAdapter
        }
    }

    private fun resolveDisconnectBackend(): BackendAdapter {
        activeBackendAdapter?.let { return it }

        return when (activeBackendProfileType ?: lastBackendProfileType ?: uiState.value.activeProfile?.type) {
            ProfileType.AMNEZIA_WG_20 -> awgBackendAdapter
            ProfileType.KROT -> krotBackendAdapter
            else -> xrayBackendAdapter
        }
    }

    private fun addLog(level: LogLevel, message: String) {
        val verboseLogsEnabled = uiState.value.settingsState.verboseLogs
        if (level == LogLevel.INFO && !verboseLogsEnabled) return

        logCounter += 1
        val entry = EventLogEntry(
            id = logCounter,
            timestampMs = System.currentTimeMillis(),
            level = level,
            message = SubscriptionPresentation.redact(message, uiState.value.profiles, uiState.value.subscriptions)
        )

        _logs.update { current -> listOf(entry) + current.take(199) }
    }

    private fun logSelectedProfileShortIdTrace(profile: VpnProfile) {
        val sourceState = extractProxyShortIdState(profile.sourceRaw)
        val normalizedPayload = profile.normalizedJson ?: profile.sourceRaw
        val normalizedState = extractProxyShortIdState(normalizedPayload)

        addLog(
            LogLevel.INFO,
            "SHORTID TRACE stage=3 selected profileId=${profile.id} profile='${profile.displayName}' " +
                "type=${profile.type.name} sourceRaw.shortId=${sourceState.shortIdForLog()} " +
                "normalized.shortId=${normalizedState.shortIdForLog()}"
        )
    }

    private fun extractProxyShortIdState(payload: String?): ProxyShortIdState {
        val text = payload?.trim().orEmpty()
        if (text.isBlank()) return ProxyShortIdState(payloadKind = "empty")

        if (text.startsWith("{") && text.endsWith("}")) {
            val root = runCatching { JSONObject(text) }.getOrNull()
                ?: return ProxyShortIdState(payloadKind = "invalid-json")
            val outbounds = root.optJSONArray("outbounds")
                ?: return ProxyShortIdState(payloadKind = "json-no-outbounds")

            var selected: JSONObject? = null
            for (index in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(index) ?: continue
                if (outbound.optString("tag").equals("proxy", ignoreCase = true)) {
                    selected = outbound
                    break
                }
            }
            if (selected == null) {
                for (index in 0 until outbounds.length()) {
                    val outbound = outbounds.optJSONObject(index) ?: continue
                    if (outbound.optString("protocol").equals("vless", ignoreCase = true)) {
                        selected = outbound
                        break
                    }
                }
            }
            selected = selected ?: outbounds.optJSONObject(0)
            if (selected == null) return ProxyShortIdState(payloadKind = "json")

            val streamSettings = selected.optJSONObject("streamSettings")
            val security = streamSettings?.optString("security")?.trim()?.lowercase().orEmpty()
            val realitySettings = streamSettings?.optJSONObject("realitySettings")
            val shortId = realitySettings?.firstNonBlankString("shortId", "shortid", "sid", "short_id")
                ?: realitySettings?.optJSONArray("shortIds")
                    ?.let { shortIds ->
                        (0 until shortIds.length())
                            .asSequence()
                            .map { shortIds.optString(it).trim() }
                            .firstOrNull { it.isNotBlank() }
                    }
                ?: realitySettings?.optJSONArray("shortids")
                    ?.let { shortIds ->
                        (0 until shortIds.length())
                            .asSequence()
                            .map { shortIds.optString(it).trim() }
                            .firstOrNull { it.isNotBlank() }
                    }
            return ProxyShortIdState(
                payloadKind = "json",
                realityEnabled = security == "reality",
                shortId = shortId
            )
        }

        if (text.startsWith("vless://", ignoreCase = true)) {
            val uri = runCatching { Uri.parse(text) }.getOrNull()
            val security = uri?.queryParameterValue("security")?.trim()?.lowercase()
            val shortId = uri?.queryParameterValue("shortId", "shortid", "sid", "short_id")
            return ProxyShortIdState(
                payloadKind = "vless-uri",
                realityEnabled = security == "reality",
                shortId = shortId
            )
        }

        return ProxyShortIdState(payloadKind = "unsupported")
    }

    private fun Uri.queryParameterValue(vararg names: String): String? {
        names.forEach { name ->
            getQueryParameter(name)?.takeIf { it.isNotBlank() }?.let { return it }
        }

        val nameSet = names.map { it.lowercase() }.toSet()
        return queryParameterNames
            .firstOrNull { candidate -> candidate.lowercase() in nameSet }
            ?.let { candidate -> getQueryParameter(candidate) }
            ?.takeIf { it.isNotBlank() }
    }

    private fun JSONObject.firstNonBlankString(vararg keys: String): String? {
        keys.forEach { key ->
            val value = optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private data class ProxyShortIdState(
        val payloadKind: String,
        val realityEnabled: Boolean = false,
        val shortId: String? = null
    ) {
        fun shortIdForLog(): String {
            val value = shortId?.trim().orEmpty()
            if (value.isBlank()) return "absent"
            return "present($value)"
        }
    }

    private data class ExternalImportPayload(
        val text: String,
        val sourceLabel: String
    )

    private data class ProfileEndpoint(
        val host: String,
        val port: Int
    )

    private fun resolveProfileServer(profile: VpnProfile?): String? {
        profile ?: return null
        return resolveProfileEndpoint(profile)?.host ?: profile.displayName.takeIf { it.isNotBlank() }
    }

    private fun resolveProfileEndpoint(profile: VpnProfile): ProfileEndpoint? {
        if (profile.type == ProfileType.AMNEZIA_WG_20) {
            extractAwgEndpoint(profile.sourceRaw)?.let { return it }
            extractAwgEndpoint(profile.normalizedJson)?.let { return it }
        }
        if (profile.type == ProfileType.KROT) {
            extractKrotEndpoint(profile.normalizedJson)?.let { return it }
            extractKrotEndpoint(profile.sourceRaw)?.let { return it }
        }

        extractUriEndpoint(profile.sourceRaw)?.let { return it }
        extractXrayEndpoint(profile.normalizedJson)?.let { return it }
        extractXrayEndpoint(profile.sourceRaw)?.let { return it }
        return null
    }

    private fun measureProfilePing(profile: VpnProfile): String {
        if (profile.type == ProfileType.AMNEZIA_WG_20) {
            val endpoint = resolveProfileEndpoint(profile) ?: return "—"
            if (endpoint.host.isBlank() || endpoint.port !in 1..65535) return "—"
            return measureAwgReachabilityPing(profile = profile, endpoint = endpoint)
        }

        val endpoint = resolveProfileEndpoint(profile) ?: return "—"
        if (endpoint.host.isBlank() || endpoint.port !in 1..65535) return "—"

        val addresses = runCatching {
            InetAddress.getAllByName(endpoint.host)
                .filterIsInstance<Inet4Address>()
                .distinctBy(InetAddress::getHostAddress)
                .take(4)
        }.getOrElse {
            return "dns err"
        }
        if (addresses.isEmpty()) return "—"

        var fastestMs: Long? = null
        var timedOut = false
        addresses.forEach { address ->
            try {
                val startedAtNs = System.nanoTime()
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(address, endpoint.port), 2_500)
                }
                val elapsedMs = ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(1L)
                fastestMs = fastestMs?.coerceAtMost(elapsedMs) ?: elapsedMs
            } catch (_: SocketTimeoutException) {
                timedOut = true
            } catch (_: Exception) {
                // Try the remaining IPv4 candidates before marking the endpoint offline.
            }
        }

        return fastestMs?.let { "$it мс" } ?: if (timedOut) "timeout" else "—"
    }

    private fun measureAwgReachabilityPing(profile: VpnProfile, endpoint: ProfileEndpoint): String {
        val address = runCatching { InetAddress.getByName(endpoint.host) }.getOrElse {
            addLog(
                LogLevel.INFO,
                "PING: AWG '${profile.displayName}' host '${endpoint.host}' не резолвится (${it.message ?: "dns error"})"
            )
            return "dns err"
        }

        val icmpMs = runCatching {
            val startedAtNs = System.nanoTime()
            if (address.isReachable(1_200)) {
                ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(1L)
            } else {
                null
            }
        }.getOrNull()

        if (icmpMs != null) {
            addLog(
                LogLevel.INFO,
                "PING: AWG '${profile.displayName}' endpoint ${endpoint.host}:${endpoint.port} " +
                    "измерен через ICMP reachability (${icmpMs}ms), это не WG handshake latency"
            )
            return "≈${icmpMs} мс"
        }

        val fallbackPorts = listOf(endpoint.port, 443, 80).distinct()
        fallbackPorts.forEach { port ->
            val tcpMs = runCatching {
                val startedAtNs = System.nanoTime()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), 1_200)
                }
                ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(1L)
            }.getOrNull()
            if (tcpMs != null) {
                addLog(
                    LogLevel.INFO,
                    "PING: AWG '${profile.displayName}' endpoint ${endpoint.host}:${endpoint.port} " +
                        "измерен через TCP fallback на порту $port (${tcpMs}ms), это не WG handshake latency"
                )
                return "≈${tcpMs} мс"
            }
        }

        addLog(
            LogLevel.INFO,
            "PING: AWG '${profile.displayName}' endpoint ${endpoint.host}:${endpoint.port} недоступен по ICMP/TCP fallback"
        )
        return "н/д"
    }

    private fun extractUriEndpoint(raw: String?): ProfileEndpoint? {
        val value = raw?.trim().orEmpty()
        if (!(value.startsWith("vless://", ignoreCase = true) ||
                value.startsWith("vmess://", ignoreCase = true) ||
                value.startsWith("trojan://", ignoreCase = true))
        ) {
            return null
        }

        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val host = uri.host?.trim().orEmpty()
        if (host.isBlank()) return null
        val port = uri.port.takeIf { it in 1..65535 } ?: when (uri.scheme?.lowercase()) {
            "vless", "vmess", "trojan" -> 443
            else -> return null
        }
        return ProfileEndpoint(host = host, port = port)
    }

    private fun extractAwgEndpoint(raw: String?): ProfileEndpoint? {
        raw ?: return null
        val match = Regex("(?im)^\\s*Endpoint\\s*=\\s*([^\\s#;]+)").find(raw) ?: return null
        val endpoint = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (endpoint.isBlank()) return null
        return parseHostPort(endpoint)
    }

    private fun extractKrotEndpoint(raw: String?): ProfileEndpoint? {
        val payload = raw?.trim().orEmpty()
        if (payload.isBlank()) return null
        val spec = runCatching { KrotConnectionSpec.parseProfilePayload(payload) }.getOrNull()
            ?: return null
        return ProfileEndpoint(host = spec.server.host, port = spec.server.port)
    }

    private fun extractXrayEndpoint(raw: String?): ProfileEndpoint? {
        raw ?: return null
        val text = raw.trim()
        if (!text.startsWith("{")) return null

        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val outbounds = json.optJSONArray("outbounds") ?: return null
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val settings = outbound.optJSONObject("settings") ?: continue
            val vnext = settings.optJSONArray("vnext") ?: continue
            if (vnext.length() == 0) continue
            val first = vnext.optJSONObject(0) ?: continue
            val address = first.optString("address").trim()
            val port = first.optInt("port", -1)
            if (address.isNotBlank() && port in 1..65535) {
                return ProfileEndpoint(host = address, port = port)
            }
        }
        return null
    }

    private fun parseHostPort(rawEndpoint: String): ProfileEndpoint? {
        val endpoint = rawEndpoint.trim()
        if (endpoint.isBlank()) return null

        if (endpoint.startsWith("[")) {
            val closingIndex = endpoint.indexOf(']')
            if (closingIndex <= 1 || closingIndex >= endpoint.lastIndex) return null
            val host = endpoint.substring(1, closingIndex).trim()
            val portText = endpoint.substring(closingIndex + 1).removePrefix(":").trim()
            val port = portText.toIntOrNull() ?: return null
            if (host.isBlank() || port !in 1..65535) return null
            return ProfileEndpoint(host = host, port = port)
        }

        val separator = endpoint.lastIndexOf(':')
        if (separator <= 0 || separator >= endpoint.lastIndex) return null
        val host = endpoint.substring(0, separator).trim()
        val port = endpoint.substring(separator + 1).trim().toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return ProfileEndpoint(host = host, port = port)
    }

    private companion object {
        const val TRUSTED_APPS_APPLY_DEBOUNCE_MS = 350L
        const val BACKEND_SWITCH_READY_TIMEOUT_MS = 5_000L
        const val BACKEND_SWITCH_SETTLE_DELAY_MS = 250L
        const val BACKEND_SWITCH_RETRY_DELAY_MS = 300L
        const val BACKEND_SWITCH_POLL_DELAY_MS = 75L
        const val BACKEND_SWITCH_PROGRESS_LOG_INTERVAL_MS = 500L
        val SUPPORTED_EXTERNAL_PREFIXES = listOf(
            "vless://",
            "vmess://",
            "trojan://",
            "happ://",
            "nora1.",
            "http://",
            "https://"
        )
        val EXTERNAL_LINK_REGEX = Regex(
            pattern = "\\b(?:vless|vmess|trojan|happ|https?)://\\S+|\\bnora1\\.[A-Za-z0-9_-]+={0,2}",
            options = setOf(RegexOption.IGNORE_CASE)
        )
        val BACKEND_WARMUP_ERROR_MARKERS = listOf(
            "unknown error",
            "неизвест",
            "already",
            "in progress",
            "already выполняется",
            "busy",
            "resource busy",
            "try again",
            "tun2proxy уже запущен"
        )
    }
}
