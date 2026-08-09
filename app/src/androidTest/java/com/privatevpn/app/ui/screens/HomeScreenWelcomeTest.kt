package com.privatevpn.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.privatevpn.app.ui.theme.PrivateVpnTheme
import com.privatevpn.app.vpn.VpnConnectionStatus
import com.privatevpn.app.vpn.VpnTrafficState
import org.junit.Rule
import org.junit.Test

class HomeScreenWelcomeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun addProfileButtonUsesAddDestinationCallback() {
        var destination by mutableStateOf("home")

        composeRule.setContent {
            PrivateVpnTheme {
                if (destination == "home") {
                    HomeScreen(
                        vpnStatus = VpnConnectionStatus.READY,
                        traffic = VpnTrafficState(),
                        profilesLoaded = true,
                        connectionErrorMessage = null,
                        activeProfileName = null,
                        protocolLabel = "",
                        serverAddress = null,
                        profiles = emptyList(),
                        subscriptions = emptyList(),
                        activeProfileId = null,
                        serverPingResults = emptyMap(),
                        pingInProgress = false,
                        refreshingSubscriptionIds = emptySet(),
                        scrollToTopSignal = 0,
                        onRequestVpnPermission = {},
                        onConnectClick = {},
                        onDisconnectClick = {},
                        onPingAllServers = {},
                        onSetActiveProfile = {},
                        onToggleSubscriptionCollapse = {},
                        onRefreshSubscription = {},
                        onTransientMessage = {},
                        onOpenProfiles = { destination = "profiles" },
                        onAddProfile = { destination = "add" }
                    )
                } else {
                    Text(destination, modifier = Modifier.testTag("destination"))
                }
            }
        }

        composeRule.onNodeWithText("Добавить профиль").performClick()
        composeRule.onNodeWithTag("destination").assertTextEquals("add")
    }
}
