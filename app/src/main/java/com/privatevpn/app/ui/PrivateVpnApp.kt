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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import com.privatevpn.app.ui.screens.AddScreen
import com.privatevpn.app.ui.screens.HomeScreen
import com.privatevpn.app.ui.screens.LogsScreen
import com.privatevpn.app.ui.screens.PrivateSessionScreen
import com.privatevpn.app.ui.screens.ProfilesScreen
import com.privatevpn.app.ui.screens.SettingsScreen
import com.privatevpn.app.ui.screens.NoraSettingsScreen
import com.privatevpn.app.ui.screens.TrafficScreen
import com.privatevpn.app.ui.theme.NoraInk
import com.privatevpn.app.ui.theme.NoraInkElevated
import com.privatevpn.app.ui.theme.NoraLine
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
    var profilesFocusActiveSignal by remember { mutableIntStateOf(0) }
    var privateSessionReselectSignal by remember { mutableIntStateOf(0) }
    var settingsReselectSignal by remember { mutableIntStateOf(0) }
    var settingsSocksFocusSignal by remember { mutableIntStateOf(0) }
    var showNotificationOnboarding by remember { mutableStateOf(false) }

    val currentRoute = navBackStackEntry?.destination?.route
    val currentDestination = AppDestination.fromRouteOrNull(currentRoute) ?: AppDestination.Home
    val selectedBottomDestination = AppDestination.topLevelForRoute(currentRoute)
    val welcomeHome = currentDestination == AppDestination.Home &&
        uiState.profilesLoaded &&
        uiState.profiles.isEmpty() && uiState.subscriptions.isEmpty()

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

    fun openActiveServerInProfiles() {
        profilesFocusActiveSignal += 1
        appViewModel.revealActiveProfileInServers()
        navigateToTopLevel(AppDestination.Profiles)
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
            if (!currentDestination.showInBottomBar) {
                TopAppBar(
                    title = { Text(text = stringResource(currentDestination.titleRes)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = NoraInk,
                        scrolledContainerColor = NoraInk,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        },
        bottomBar = {
            androidx.compose.foundation.layout.Column(Modifier.zIndex(10f)) {
                HorizontalDivider(
                    color = if (welcomeHome || currentDestination in setOf(
                            AppDestination.Traffic,
                            AppDestination.Settings,
                            AppDestination.PrivateSession,
                            AppDestination.Logs,
                            AppDestination.Dns
                        )
                    ) {
                        Color.Transparent
                    } else {
                        NoraLine
                    }
                )
                NavigationBar(
                    containerColor = if (welcomeHome) NoraInk else NoraInkElevated,
                    tonalElevation = 0.dp
                ) {
                    AppDestination.topLevelItems.forEach { destination ->
                        val selected = selectedBottomDestination.route == destination.route
                        NavigationBarItem(
                            selected = selected,
                            alwaysShowLabel = destination != AppDestination.Add,
                            onClick = {
                                if (selected) {
                                    when (destination) {
                                        AppDestination.Home -> homeReselectSignal += 1
                                        AppDestination.Profiles -> profilesReselectSignal += 1
                                        AppDestination.Add -> Unit
                                        AppDestination.Traffic -> Unit
                                        AppDestination.Settings -> settingsReselectSignal += 1
                                        else -> Unit
                                    }
                                }
                                navigateToTopLevel(destination)
                            },
                            icon = {
                                val icon = destination.icon
                                if (icon != null) {
                                    if (destination == AppDestination.Add) {
                                        Surface(
                                            modifier = Modifier.padding(top = 1.dp),
                                            shape = androidx.compose.foundation.shape.CircleShape,
                                            color = com.privatevpn.app.ui.theme.NoraAmber
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = stringResource(destination.bottomTitleRes),
                                                tint = NoraInk,
                                                modifier = Modifier.padding(13.dp)
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = stringResource(destination.bottomTitleRes)
                                        )
                                    }
                                }
                            },
                            label = {
                                if (destination != AppDestination.Add) {
                                    Text(text = stringResource(destination.bottomTitleRes))
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
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
                    traffic = uiState.traffic,
                    profilesLoaded = uiState.profilesLoaded,
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
                    onTransientMessage = appViewModel::emitTransientMessage,
                    onOpenProfiles = ::openActiveServerInProfiles
                )
            }

            composable(AppDestination.Profiles.route) {
                ProfilesScreen(
                    profiles = uiState.profiles,
                    subscriptions = uiState.subscriptions,
                    refreshingSubscriptionIds = uiState.refreshingSubscriptionIds,
                    activeProfileId = uiState.activeProfileId,
                    serverPingResults = uiState.serverPingResults,
                    pingInProgress = uiState.pingInProgress,
                    errorMessage = null,
                    scrollToTopSignal = profilesReselectSignal,
                    focusActiveSignal = profilesFocusActiveSignal,
                    socksSettings = uiState.settingsState.socksSettings,
                    splitTunnelingEnabled = uiState.privateSessionUiState.enabled,
                    onImportProfile = appViewModel::importProfile,
                    onImportProfileFile = {
                        profileFileLauncher.launch(arrayOf("*/*"))
                    },
                    onAddSubscription = appViewModel::addSubscription,
                    onRefreshSubscription = { appViewModel.refreshSubscription(it, showSuccessMessage = true) },
                    onRefreshAllSubscriptions = appViewModel::refreshAllSubscriptions,
                    onPingAllServers = appViewModel::pingAllServers,
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

            composable(AppDestination.Add.route) {
                AddScreen(
                    onImportProfile = appViewModel::importProfile,
                    onImportFile = { profileFileLauncher.launch(arrayOf("*/*")) },
                    onAddSubscription = appViewModel::addSubscription
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

            composable(AppDestination.Traffic.route) {
                TrafficScreen(
                    traffic = uiState.traffic,
                    vpnStatus = uiState.vpnStatus,
                    sessionHistory = uiState.sessionHistory
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
                NoraSettingsScreen(
                    settings = uiState.settingsState,
                    notificationPermission = uiState.notificationPermission,
                    onOpenPrivateSession = { navigateToSecondary(AppDestination.PrivateSession) },
                    onOpenLogs = { navigateToSecondary(AppDestination.Logs) },
                    onOpenDns = { navigateToSecondary(AppDestination.Dns) },
                    onRequestNotifications = {
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
                    onSaveSocks = appViewModel::saveSocksSettings
                )
            }
            }
        }

    if (showNotificationOnboarding) {
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
