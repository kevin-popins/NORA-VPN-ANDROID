package com.privatevpn.app.ui

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.privatevpn.app.R
import com.privatevpn.app.core.log.LogLevel
import com.privatevpn.app.navigation.AppDestination
import com.privatevpn.app.profiles.model.ProfileType
import com.privatevpn.app.settings.SettingsState
import com.privatevpn.app.ui.screens.DnsScreen
import com.privatevpn.app.ui.screens.HomeScreen
import com.privatevpn.app.ui.screens.LogsScreen
import com.privatevpn.app.ui.screens.PrivateSessionScreen
import com.privatevpn.app.ui.screens.ProfilesScreen
import com.privatevpn.app.ui.screens.SettingsScreen
import com.privatevpn.app.vpn.VpnQuickSettingsTileService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateVpnApp(
    appViewModel: AppViewModel = viewModel(),
    requestVpnPermissionOnStart: Boolean = false,
    onRequestVpnPermissionConsumed: () -> Unit = {},
    externalImportIntent: Intent? = null,
    externalImportIntentVersion: Int = 0,
    onExternalImportIntentConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val navController = rememberNavController()
    val uiState by appViewModel.uiState.collectAsStateWithLifecycle()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val composeScope = rememberCoroutineScope()
    var homeReselectSignal by remember { mutableIntStateOf(0) }
    var profilesReselectSignal by remember { mutableIntStateOf(0) }
    var privateSessionReselectSignal by remember { mutableIntStateOf(0) }
    var settingsReselectSignal by remember { mutableIntStateOf(0) }
    var settingsSocksFocusSignal by remember { mutableIntStateOf(0) }
    var showNotificationOnboarding by remember { mutableStateOf(false) }
    var showLocalhostSocksOnboarding by remember { mutableStateOf(false) }

    val currentRoute = navBackStackEntry?.destination?.route
    val currentDestination = AppDestination.fromRouteOrNull(currentRoute) ?: AppDestination.Home
    val selectedBottomDestination = AppDestination.topLevelForRoute(currentRoute)

    fun navigateToTopLevel(destination: AppDestination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = false
            }
            launchSingleTop = true
            restoreState = false
        }
    }

    fun navigateToSecondary(destination: AppDestination) {
        navController.navigate(destination.route) {
            launchSingleTop = true
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        appViewModel.onVpnPermissionResult()
    }
    val profileFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            appViewModel.importProfileFromFile(uri)
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        appViewModel.onNotificationPermissionResult(granted)
    }

    LaunchedEffect(Unit) {
        appViewModel.refreshVpnPermissionState()
        appViewModel.refreshNotificationPermissionState()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                appViewModel.refreshNotificationPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState.transientMessage) {
        val message = uiState.transientMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        appViewModel.consumeTransientMessage()
    }

    LaunchedEffect(uiState.notificationPermission.shouldShowOnboardingPrompt) {
        if (uiState.notificationPermission.shouldShowOnboardingPrompt) {
            showNotificationOnboarding = true
        }
    }

    LaunchedEffect(uiState.settingsState) {
        val readiness = evaluateLocalhostSocksReadiness(uiState.settingsState)
        showLocalhostSocksOnboarding = readiness.shouldShowWarning
        appViewModel.logLocalhostSocksOnboardingCheck(
            onboardingShown = readiness.onboardingShown,
            socksEnabled = readiness.socksEnabled,
            loginSet = readiness.loginSet,
            passwordSet = readiness.passwordSet,
            portValid = readiness.portValid,
            persistedConfigured = readiness.persistedConfigured,
            shouldShowWarning = readiness.shouldShowWarning
        )
    }

    LaunchedEffect(requestVpnPermissionOnStart) {
        if (requestVpnPermissionOnStart) {
            val intent = appViewModel.requestVpnPermissionIntent()
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                appViewModel.onVpnPermissionResult()
            }
            onRequestVpnPermissionConsumed()
        }
    }

    LaunchedEffect(externalImportIntentVersion) {
        val intent = externalImportIntent ?: return@LaunchedEffect
        appViewModel.importExternalIntent(intent)
        onExternalImportIntentConsumed()
        navigateToTopLevel(AppDestination.Profiles)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(currentDestination.titleRes)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            androidx.compose.foundation.layout.Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    AppDestination.topLevelItems.forEach { destination ->
                        val selected = selectedBottomDestination.route == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (selected) {
                                    when (destination) {
                                        AppDestination.Home -> homeReselectSignal += 1
                                        AppDestination.Profiles -> profilesReselectSignal += 1
                                        AppDestination.PrivateSession -> privateSessionReselectSignal += 1
                                        AppDestination.Settings -> settingsReselectSignal += 1
                                        else -> Unit
                                    }
                                }
                                navigateToTopLevel(destination)
                            },
                            icon = {
                                val icon = destination.icon
                                if (icon != null) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = stringResource(destination.bottomTitleRes)
                                    )
                                }
                            },
                            label = { Text(text = stringResource(destination.bottomTitleRes)) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(AppDestination.Home.route) {
                HomeScreen(
                    vpnStatus = uiState.vpnStatus,
                    connectionErrorMessage = uiState.connectionError,
                    activeProfileName = uiState.activeProfile?.displayName,
                    protocolLabel = profileTypeLabel(uiState.activeProfile?.type),
                    serverAddress = uiState.activeProfileServer,
                    profiles = uiState.profiles,
                    subscriptions = uiState.subscriptions,
                    activeProfileId = uiState.activeProfileId,
                    serverPingResults = uiState.serverPingResults,
                    pingInProgress = uiState.pingInProgress,
                    refreshingSubscriptionIds = uiState.refreshingSubscriptionIds,
                    scrollToTopSignal = homeReselectSignal,
                    onRequestVpnPermission = {
                        val intent = appViewModel.requestVpnPermissionIntent()
                        if (intent != null) {
                            vpnPermissionLauncher.launch(intent)
                        } else {
                            appViewModel.onVpnPermissionResult()
                        }
                    },
                    onConnectClick = appViewModel::connectVpn,
                    onDisconnectClick = appViewModel::disconnectVpn,
                    onPingAllServers = appViewModel::pingAllServers,
                    onSetActiveProfile = appViewModel::setActiveProfile,
                    onToggleSubscriptionCollapse = appViewModel::toggleSubscriptionCollapse,
                    onRefreshSubscription = { appViewModel.refreshSubscription(it, showSuccessMessage = true) },
                    onTransientMessage = appViewModel::emitTransientMessage
                )
            }

            composable(AppDestination.Profiles.route) {
                ProfilesScreen(
                    profiles = uiState.profiles,
                    subscriptions = uiState.subscriptions,
                    refreshingSubscriptionIds = uiState.refreshingSubscriptionIds,
                    activeProfileId = uiState.activeProfileId,
                    errorMessage = null,
                    scrollToTopSignal = profilesReselectSignal,
                    socksSettings = uiState.settingsState.socksSettings,
                    splitTunnelingEnabled = uiState.privateSessionUiState.enabled,
                    onImportProfile = appViewModel::importProfile,
                    onImportProfileFile = {
                        profileFileLauncher.launch(arrayOf("*/*"))
                    },
                    onAddSubscription = appViewModel::addSubscription,
                    onRefreshSubscription = { appViewModel.refreshSubscription(it, showSuccessMessage = true) },
                    onRefreshAllSubscriptions = appViewModel::refreshAllSubscriptions,
                    onToggleSubscriptionCollapse = appViewModel::toggleSubscriptionCollapse,
                    onRenameSubscription = appViewModel::renameSubscription,
                    onDeleteSubscription = appViewModel::deleteSubscription,
                    onSetSubscriptionAutoUpdate = appViewModel::setSubscriptionAutoUpdate,
                    onSetSubscriptionInterval = appViewModel::setSubscriptionInterval,
                    onSetSubscriptionEnabled = appViewModel::setSubscriptionEnabled,
                    onSetActiveProfile = appViewModel::setActiveProfile,
                    onDeleteProfile = appViewModel::deleteProfile,
                    onRenameProfile = appViewModel::renameProfile,
                    onClearError = appViewModel::clearError,
                    onTransientMessage = appViewModel::emitTransientMessage
                )
            }

            composable(AppDestination.PrivateSession.route) {
                PrivateSessionScreen(
                    state = uiState.privateSessionUiState,
                    scrollToTopSignal = privateSessionReselectSignal,
                    onRefreshApps = appViewModel::refreshPrivateSessionData,
                    onSessionEnabledChange = appViewModel::setPrivateSessionEnabled,
                    onToggleTrustedApp = appViewModel::toggleTrustedAppSelection
                )
            }

            composable(AppDestination.Logs.route) {
                LogsScreen(
                    logs = uiState.eventLogs,
                    levelToLabel = { level ->
                        when (level) {
                            LogLevel.INFO -> stringResource(R.string.log_level_info)
                            LogLevel.ERROR -> stringResource(R.string.log_level_error)
                        }
                    }
                )
            }

            composable(AppDestination.Dns.route) {
                DnsScreen(
                    dnsState = uiState.dnsState,
                    onDnsModeSelected = appViewModel::setDnsMode,
                    onSaveCustomDns = appViewModel::saveCustomDnsServers
                )
            }

            composable(AppDestination.Settings.route) {
                SettingsScreen(
                    settingsState = uiState.settingsState,
                    splitTunnelingEnabled = uiState.privateSessionUiState.enabled,
                    systemVpnIntegration = uiState.privateSessionUiState.systemIntegration,
                    scrollToTopSignal = settingsReselectSignal,
                    focusSocksSignal = settingsSocksFocusSignal,
                    onAutoConnectChanged = appViewModel::setAutoConnect,
                    onVerboseLogsChanged = appViewModel::setVerboseLogs,
                    onSaveSocksSettings = appViewModel::saveSocksSettings,
                    notificationPermission = uiState.notificationPermission,
                    onRequestNotificationsPermission = {
                        if (!uiState.notificationPermission.granted && uiState.notificationPermission.supported) {
                            appViewModel.markNotificationPermissionPromptShown()
                            if (uiState.notificationPermission.shouldOpenSystemSettings) {
                                val intent = appViewModel.buildOpenAppNotificationSettingsIntent()
                                runCatching { context.startActivity(intent) }
                            } else {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    },
                    onAddTileClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context is Activity) {
                            val statusBarManager = context.getSystemService(StatusBarManager::class.java)
                            statusBarManager?.requestAddTileService(
                                ComponentName(context, VpnQuickSettingsTileService::class.java),
                                context.getString(R.string.quick_tile_label),
                                Icon.createWithResource(context, R.drawable.ic_qs_vpn_foreground),
                                context.mainExecutor
                            ) { resultCode ->
                                if (resultCode == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                                    resultCode == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                                ) {
                                    composeScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.settings_tile_added_hint))
                                    }
                                }
                            }
                        } else {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_SETTINGS))
                            }
                            appViewModel.emitTransientMessage(context.getString(R.string.settings_tile_manual_hint))
                        }
                    },
                    onOpenLogs = { navigateToSecondary(AppDestination.Logs) },
                    onOpenDns = { navigateToSecondary(AppDestination.Dns) },
                    onOpenSystemVpnSettings = {
                        val intent = appViewModel.buildOpenSystemVpnSettingsIntent()
                        runCatching { context.startActivity(intent) }
                    },
                    onTransientMessage = appViewModel::emitTransientMessage
                )
            }
        }
    }

    if (showLocalhostSocksOnboarding) {
        AlertDialog(
            onDismissRequest = {
                appViewModel.markLocalhostSocksOnboardingShown()
                showLocalhostSocksOnboarding = false
            },
            title = { Text(text = stringResource(R.string.localhost_onboarding_title)) },
            text = { Text(text = stringResource(R.string.localhost_onboarding_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        appViewModel.markLocalhostSocksOnboardingShown()
                        showLocalhostSocksOnboarding = false
                        settingsSocksFocusSignal += 1
                        navigateToTopLevel(AppDestination.Settings)
                    }
                ) {
                    Text(text = stringResource(R.string.localhost_onboarding_open_settings))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        appViewModel.markLocalhostSocksOnboardingShown()
                        showLocalhostSocksOnboarding = false
                    }
                ) {
                    Text(text = stringResource(R.string.localhost_onboarding_later))
                }
            }
        )
    }

    if (!showLocalhostSocksOnboarding && showNotificationOnboarding) {
        AlertDialog(
            onDismissRequest = {
                appViewModel.markNotificationPermissionPromptShown()
                showNotificationOnboarding = false
            },
            title = { Text(text = stringResource(R.string.notifications_onboarding_title)) },
            text = { Text(text = stringResource(R.string.notifications_onboarding_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        appViewModel.markNotificationPermissionPromptShown()
                        showNotificationOnboarding = false
                        if (uiState.notificationPermission.supported) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                ) {
                    Text(text = stringResource(R.string.notifications_onboarding_allow))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        appViewModel.markNotificationPermissionPromptShown()
                        showNotificationOnboarding = false
                    }
                ) {
                    Text(text = stringResource(R.string.notifications_onboarding_later))
                }
            }
        )
    }
}

private fun profileTypeLabel(type: ProfileType?): String = when (type) {
    ProfileType.VLESS -> "VLESS"
    ProfileType.VMESS -> "VMESS"
    ProfileType.TROJAN -> "Trojan"
    ProfileType.XRAY_JSON -> "Xray JSON"
    ProfileType.XRAY_VLESS_REALITY -> "VLESS + REALITY"
    ProfileType.AMNEZIA_WG_20 -> "AmneziaWG 2.0"
    ProfileType.KROT -> "KRot"
    null -> "Не выбран"
}

private data class LocalhostSocksReadiness(
    val onboardingShown: Boolean,
    val socksEnabled: Boolean,
    val loginSet: Boolean,
    val passwordSet: Boolean,
    val portValid: Boolean,
    val persistedConfigured: Boolean,
    val shouldShowWarning: Boolean
)

private fun evaluateLocalhostSocksReadiness(settings: SettingsState): LocalhostSocksReadiness {
    val socks = settings.socksSettings
    val loginSet = socks.login.trim().isNotEmpty()
    val passwordSet = socks.password.trim().isNotEmpty()
    val portValid = socks.port in 1..65535
    val persistedConfigured = socks.enabled && loginSet && passwordSet && portValid
    val shouldShowWarning = !settings.localhostSocksOnboardingShown && !persistedConfigured

    return LocalhostSocksReadiness(
        onboardingShown = settings.localhostSocksOnboardingShown,
        socksEnabled = socks.enabled,
        loginSet = loginSet,
        passwordSet = passwordSet,
        portValid = portValid,
        persistedConfigured = persistedConfigured,
        shouldShowWarning = shouldShowWarning
    )
}
