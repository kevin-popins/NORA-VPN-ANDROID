package com.privatevpn.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
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
    private fun servers(profiles: List<VpnProfile>, sources: List<SubscriptionSource>, onSelect: (String) -> Unit = {}, activeId: String? = "synthetic-real", externalContext: Context? = null) {
        rule.setContent { PrivateVpnTheme {
            CompositionLocalProvider(LocalContext provides (externalContext ?: LocalContext.current)) {
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
            }
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
    @Test fun protectedSafeAnnounceUsesExistingExpandedSubscriptionDescription() {
        val instruction = "Нажмите 🔄 и поменяйте сервер если не работает."
        val real = profile()
        val source = source(listOf(real)).copy(metadata = SubscriptionMetadataCodec.encode(SubscriptionMetadataPayload(
            announcementText = instruction, userId = "SYNTHETIC_PRIVATE_ID", providerSite = "https://provider.example.invalid",
            clientPolicy = SubscriptionClientPolicy(`protected` = true, publicLabel = "NORA private"))))
        servers(listOf(real), listOf(source))
        rule.onNodeWithText(instruction).performScrollTo().assertExists()
        rule.onNodeWithText("SYNTHETIC_PRIVATE_ID", substring = true).assertDoesNotExist()
        rule.onNodeWithText("provider.example.invalid", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Наш сайт", substring = true).assertDoesNotExist()
    }

    @Test fun collapsedProtectedSubscriptionKeepsSafeAnnouncementHidden() {
        val instruction = "Нажмите 🔄 и поменяйте сервер если не работает."
        val real = profile()
        val source = source(listOf(real)).copy(isCollapsed = true, metadata = SubscriptionMetadataCodec.encode(
            SubscriptionMetadataPayload(announcementText = instruction,
                clientPolicy = SubscriptionClientPolicy(`protected` = true, publicLabel = "NORA private"))))
        servers(listOf(real), listOf(source))
        rule.onNodeWithText(instruction).assertDoesNotExist()
    }

    @Test fun protectedUnsafeAnnounceNeverReopensTheDescriptionSurface() {
        val unsafe = "shortId=0123456789abcdef"
        val real = profile()
        val source = source(listOf(real)).copy(metadata = SubscriptionMetadataCodec.encode(
            SubscriptionMetadataPayload(announcementText = unsafe,
                clientPolicy = SubscriptionClientPolicy(`protected` = true, publicLabel = "NORA private"))))
        servers(listOf(real), listOf(source))
        rule.onNodeWithText(unsafe).assertDoesNotExist()
    }

    @Test fun protectedFriendlyTitleAndInformationKeepProviderConnectNavigation() {
        val real = profile()
        val connect = "https://connect.example.invalid/sub/synthetic?view=connect"
        val friendlyTitle = "NORA friendly subscription"
        val source = source(listOf(real)).copy(displayName = "https://example.invalid/test", metadata = SubscriptionMetadataCodec.encode(
            SubscriptionMetadataPayload(displayTitle = friendlyTitle, profileWebPageUrl = connect,
                providerSite = "https://provider.example.invalid", userId = "SYNTHETIC_PRIVATE_ID",
                clientPolicy = SubscriptionClientPolicy(`protected` = true, publicLabel = "Custom address label"))))
        val opened = mutableListOf<Intent>()
        val context = object : ContextWrapper(rule.activity) {
            override fun startActivity(intent: Intent) { opened += intent }
        }
        val presented = SubscriptionPresentation.apply(real, listOf(source))
        servers(listOf(presented), listOf(source), externalContext = context)
        rule.onNodeWithText(friendlyTitle).performScrollTo().assertExists()
        rule.onNodeWithText(real.displayName).performScrollTo().assertExists()
        rule.onNodeWithText("Custom address label").assertDoesNotExist()
        rule.onNodeWithText("SYNTHETIC_PRIVATE_ID", substring = true).assertDoesNotExist()
        rule.onNodeWithText(source.sourceUrl, substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Дополнительные действия").performScrollTo().performClick()
        rule.onNodeWithText("Открыть сайт").assertExists()
        rule.onNodeWithText("Информация").performClick()
        rule.runOnIdle {
            assertEquals(1, opened.size)
            assertEquals(Intent.ACTION_VIEW, opened.single().action)
            assertEquals(connect, opened.single().dataString)
        }
        rule.onNodeWithText("Провайдер защитил адреса этой подписки.", substring = true).assertDoesNotExist()
    }

    @Test fun protectedInformationWithoutProviderUrlKeepsSafeLocalDetails() {
        val real = profile()
        val source = source(listOf(real))
        servers(listOf(SubscriptionPresentation.apply(real, listOf(source))), listOf(source))
        rule.onNodeWithContentDescription("Дополнительные действия").performScrollTo().performClick()
        rule.onNodeWithText("Открыть сайт").assertDoesNotExist()
        rule.onNodeWithText("Информация").performClick()
        rule.onNodeWithText("NORA private").assertExists()
        rule.onNodeWithText(source.sourceUrl, substring = true).assertDoesNotExist()
        rule.onNodeWithText("Провайдер защитил адреса этой подписки.", substring = true).assertExists()
    }

    @Test fun protectedInformationFailedExternalActivityFallsBackWithoutShowingSourceMaterial() {
        val real = profile()
        val source = source(listOf(real)).copy(metadata = SubscriptionMetadataCodec.encode(SubscriptionMetadataPayload(
            profileWebPageUrl = "https://connect.example.invalid/sub/synthetic",
            clientPolicy = SubscriptionClientPolicy(`protected` = true, publicLabel = "NORA private"))))
        val context = object : ContextWrapper(rule.activity) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException("Synthetic unavailable browser") }
        }
        servers(listOf(SubscriptionPresentation.apply(real, listOf(source))), listOf(source), externalContext = context)
        rule.onNodeWithContentDescription("Дополнительные действия").performScrollTo().performClick()
        rule.onNodeWithText("Информация").performClick()
        rule.onNodeWithText("NORA private").assertExists()
        rule.onNodeWithText(source.sourceUrl, substring = true).assertDoesNotExist()
        rule.onNodeWithText("Провайдер защитил адреса этой подписки.", substring = true).assertExists()
    }
}
