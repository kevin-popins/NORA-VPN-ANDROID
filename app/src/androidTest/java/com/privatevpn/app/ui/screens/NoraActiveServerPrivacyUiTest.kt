package com.privatevpn.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.privatevpn.app.ui.components.NoraActiveServerCard
import com.privatevpn.app.ui.theme.PrivateVpnTheme
import com.privatevpn.app.vpn.VpnConnectionStatus
import org.junit.Rule
import org.junit.Test

class NoraActiveServerPrivacyUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun protectedPublicLabelHasNoRevealAndUnprotectedEyeKeepsExistingBehavior() {
        var protectionEnabled by mutableStateOf(true)
        rule.setContent { PrivateVpnTheme {
            NoraActiveServerCard(
                profileName = "Synthetic node", protocolLabel = "VLESS",
                endpoint = if (protectionEnabled) "NORA custom public label" else "synthetic.example.invalid",
                vpnStatus = VpnConnectionStatus.READY, onClick = {}, endpointProtected = protectionEnabled
            )
        } }
        rule.onNodeWithText("NORA custom public label").assertIsDisplayed()
        rule.onNodeWithContentDescription("Адрес защищён", useUnmergedTree = true).assertHasNoClickAction()
        rule.onNodeWithContentDescription("Показать IP").assertDoesNotExist()
        rule.onNodeWithContentDescription("Скрыть IP").assertDoesNotExist()
        rule.onNodeWithText("••••••••••••••").assertDoesNotExist()

        rule.runOnIdle { protectionEnabled = false }
        rule.onNodeWithContentDescription("Адрес защищён").assertDoesNotExist()
        rule.onNodeWithText("••••••••••••••").assertExists()
        rule.onNodeWithText("synthetic.example.invalid").assertDoesNotExist()
        rule.onNodeWithContentDescription("Показать IP").performClick()
        rule.onNodeWithText("synthetic.example.invalid").assertIsDisplayed()
        rule.onNodeWithContentDescription("Скрыть IP").assertExists()

        rule.runOnIdle { protectionEnabled = true }
        rule.onNodeWithText("NORA custom public label").assertIsDisplayed()
        rule.onNodeWithText("synthetic.example.invalid").assertDoesNotExist()
        rule.onNodeWithContentDescription("Показать IP").assertDoesNotExist()
        rule.onNodeWithContentDescription("Скрыть IP").assertDoesNotExist()
    }
}
