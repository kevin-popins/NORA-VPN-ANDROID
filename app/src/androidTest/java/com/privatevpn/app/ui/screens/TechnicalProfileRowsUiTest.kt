package com.privatevpn.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.privatevpn.app.profiles.model.*
import com.privatevpn.app.settings.SocksSettings
import com.privatevpn.app.ui.theme.PrivateVpnTheme
import com.privatevpn.app.vpn.VpnConnectionStatus
import com.privatevpn.app.vpn.VpnTrafficState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic non-capture guards: ordinary rows only, no ViewModel/database/network/VPN. */
class TechnicalProfileRowsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val exactTechnicalTitle = "🇬🇧 Bermuda — служебная запись"
    private val cancelledBanner = "THIS_SERVICE_MESSAGE_MUST_NOT_BECOME_A_HOME_BANNER"
    private fun profile(technical: Boolean = false) = VpnProfile(
        id = if (technical) "synthetic-technical" else "synthetic-real",
        displayName = if (technical) exactTechnicalTitle else "NORA 🇳🇱 Нидерланды 01",
        type = ProfileType.VLESS, sourceRaw = "synthetic-local-only", normalizedJson = null,
        dnsServers = emptyList(), dnsFallbackApplied = false, isPartialImport = false,
        importWarnings = emptyList(), importedAtMs = 1, parentSubscriptionId = "synthetic-source",
        sourceOrder = if (technical) 1 else 0, isTechnicalEntry = technical
    )
    private fun source(profiles: List<VpnProfile>) = SubscriptionSource(
        id = "synthetic-source", displayName = "NORA test", sourceUrl = "https://example.invalid/test",
        sourceType = SubscriptionSourceType.HTTP_TEXT, enabled = true, autoUpdateEnabled = true,
        updateIntervalMinutes = 60, lastUpdatedAtMs = null, lastSuccessAtMs = null, lastError = null,
        isCollapsed = false, profileCount = profiles.size, etag = null, lastModified = null,
        metadata = SubscriptionMetadataCodec.encode(SubscriptionMetadataPayload(
            providerMessage = cancelledBanner, announcementText = cancelledBanner,
            clientPolicy = SubscriptionClientPolicy(`protected` = true, publicLabel = "NORA private"))),
        childProfileIds = profiles.map { it.id }, lastSelectedProfileId = "synthetic-real",
        syncStatus = SubscriptionSyncStatus.SUCCESS, createdAtMs = 1, updatedAtMs = 1
    )
    private fun servers(profiles: List<VpnProfile>, sources: List<SubscriptionSource>, onSelect: (String) -> Unit = {}, activeId: String? = "synthetic-real") {
        rule.setContent { PrivateVpnTheme {
            ProfilesScreen(profiles = profiles, subscriptions = sources, refreshingSubscriptionIds = emptySet(),
                activeProfileId = activeId, serverPingResults = emptyMap(), pingInProgress = false,
                errorMessage = null, scrollToTopSignal = 0, focusActiveSignal = 0,
                addedProfileFocusId = null, addedSubscriptionFocusId = null, onAddedItemFocusConsumed = {},
                socksSettings = SocksSettings(), splitTunnelingEnabled = false, onImportProfile = {},
                onImportProfileFile = {}, onAddSubscription = { _, _ -> }, onRefreshSubscription = {},
                onRefreshAllSubscriptions = {}, onPingAllServers = {}, onToggleSubscriptionCollapse = {},
                onRenameSubscription = { _, _ -> }, onDeleteSubscription = {},
                onSetSubscriptionAutoUpdate = { _, _ -> }, onSetSubscriptionInterval = { _, _ -> },
                onSetSubscriptionEnabled = { _, _ -> }, onSetActiveProfile = onSelect,
                onDeleteProfile = {}, onRenameProfile = { _, _ -> }, onClearError = {}, onTransientMessage = {})
        } }
    }

    @Test fun technicalTitleStaysExactAndDisabledWhileRealRowRemainsEnabled() {
        val real = profile()
        val technical = profile(true)
        var selected: String? = null
        servers(listOf(real, technical), listOf(source(listOf(real, technical))), onSelect = { selected = it })
        rule.onNodeWithText(exactTechnicalTitle).performScrollTo().assertExists()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)).assertIsNotEnabled()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertIsEnabled()
        rule.onNodeWithText(exactTechnicalTitle).performTouchInput { click() }
        rule.runOnIdle { assertEquals(null, selected) }
        rule.onNodeWithText(real.displayName).performTouchInput { click() }
        rule.runOnIdle { assertEquals(real.id, selected) }
        rule.onNodeWithText(cancelledBanner).assertDoesNotExist()
        rule.onNodeWithText("Сохранён · недоступен").assertDoesNotExist()
    }

    @Test fun directTechnicalCardDisablesExistingSelectButKeepsInformationControl() {
        val technical = profile(true).copy(parentSubscriptionId = null)
        servers(listOf(technical), emptyList())
        rule.onNodeWithText(exactTechnicalTitle).performScrollTo().assertExists()
        rule.onNodeWithText("Выбрать").assertIsNotEnabled()
        rule.onNodeWithText("Подробнее").assertIsEnabled()
    }

    @Test fun homeHasBaselineConnectActionAndNoServiceBannerEvenWithTechnicalEntry() {
        val real = profile()
        val profiles = listOf(real, profile(true))
        var connects = 0
        rule.setContent { PrivateVpnTheme {
            HomeScreen(vpnStatus = VpnConnectionStatus.READY, traffic = VpnTrafficState(), profilesLoaded = true,
                connectionErrorMessage = null, activeProfileName = real.displayName, protocolLabel = "VLESS",
                serverAddress = "NORA private", profiles = profiles, subscriptions = listOf(source(profiles)),
                activeProfileId = real.id, serverPingResults = emptyMap(), pingInProgress = false,
                refreshingSubscriptionIds = emptySet(), scrollToTopSignal = 0, onRequestVpnPermission = {},
                onConnectClick = { connects++ }, onDisconnectClick = {}, onPingAllServers = {},
                onSetActiveProfile = {}, onToggleSubscriptionCollapse = {}, onRefreshSubscription = {},
                onTransientMessage = {}, onOpenProfiles = {})
        } }
        rule.onNodeWithText(cancelledBanner).assertDoesNotExist()
        rule.onNodeWithText(exactTechnicalTitle).assertDoesNotExist()
        rule.onNodeWithText("Обновить").assertDoesNotExist()
        rule.onNodeWithText("Недоступно").assertDoesNotExist()
        rule.onNodeWithText("Подключить").performClick()
        rule.runOnIdle { assertEquals(1, connects) }
    }
    @Test fun retainedTechnicalIdIsNeverPresentedAsASelectedTransport() {
        val technical=profile(true)
        servers(listOf(technical),listOf(source(listOf(technical))),activeId=technical.id)
        rule.onNodeWithText(exactTechnicalTitle).performScrollTo().assertExists()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.RadioButton)).assertIsNotSelected().assertIsNotEnabled()
    }
}
