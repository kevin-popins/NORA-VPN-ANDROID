package com.privatevpn.app.ui.qr

import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.privatevpn.app.profiles.importer.ProfileInput
import com.privatevpn.app.profiles.importer.ProfileInputParser
import com.privatevpn.app.ui.screens.AddScreen
import com.privatevpn.app.ui.theme.PrivateVpnTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Production draft, Add screen and coordinator with real NavHost; persistence stays synthetic. */
@OptIn(ExperimentalTestApi::class)
class AddScanDraftStateTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun manualDraftSurvivesScannerCancelAddEntryReplacementRecompositionAndRestoration() {
        val probe = HarnessProbe()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { PrivateVpnTheme { DraftHarness(probe) } }
        openAdd()
        rule.onNode(hasSetTextAction()).performTextReplacement(MANUAL_DRAFT)
        openScanner()
        rule.onNodeWithText(CANCEL_CAMERA).performClick()
        assertManualText(MANUAL_DRAFT)

        val originalEntry = rule.runOnIdle { probe.navController.currentBackStackEntry!!.id }
        replaceAddEntry(probe)
        rule.runOnIdle { assertNotEquals(originalEntry, probe.navController.currentBackStackEntry!!.id) }
        assertManualText(MANUAL_DRAFT)
        rule.runOnIdle { probe.recomposition.intValue += 1 }
        rule.waitForIdle()
        assertManualText(MANUAL_DRAFT)

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertManualText(MANUAL_DRAFT)
        val edited = "$MANUAL_DRAFT\n exact edit + %2F "
        rule.onNode(hasSetTextAction()).performTextReplacement(edited)
        assertManualText(edited)
        rule.runOnIdle {
            assertEquals(edited, probe.draft.text)
            assertEquals(0, probe.qrImports.get())
            assertEquals(0, probe.manualCallbacks.get())
        }
    }

    @Test
    fun badQrShowsSafeErrorWithoutReplacingManualDraftAndRetryImportsExactlyOnce() {
        val probe = HarnessProbe()
        rule.setContent { PrivateVpnTheme { DraftHarness(probe) } }
        openAdd()
        rule.onNode(hasSetTextAction()).performTextReplacement(MANUAL_DRAFT)
        openScanner()
        rule.onNodeWithText(DECODE_BAD).performClick()
        rule.onNodeWithText(QrImportFailureCode.UNSUPPORTED.safeMessage).assertIsDisplayed()
        rule.onNodeWithText(BAD_QR).assertDoesNotExist()
        assertManualText(MANUAL_DRAFT)
        rule.runOnIdle {
            assertEquals(0, probe.qrImports.get())
            assertEquals(0, probe.manualCallbacks.get())
        }

        rule.onNodeWithText("Сканировать ещё раз").performScrollTo().performClick()
        rule.onNodeWithText(DECODE_GOOD).performClick()
        rule.onNodeWithText(SERVERS_ROUTE).assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(1, probe.qrImports.get())
            assertEquals(0, probe.manualCallbacks.get())
            assertEquals(MANUAL_DRAFT, probe.draft.text)
        }
        replaceAddEntry(probe)
        assertManualText(MANUAL_DRAFT)
    }

    @Test
    fun cancellingValidatedQrImportKeepsEditableManualDraftAndNeverPublishesSuccess() {
        val probe = HarnessProbe().apply { importBarrier = CompletableDeferred() }
        rule.setContent { PrivateVpnTheme { DraftHarness(probe) } }
        openAdd()
        rule.onNode(hasSetTextAction()).performTextReplacement(MANUAL_DRAFT)
        openScanner()
        rule.onNodeWithText(DECODE_GOOD).performClick()
        rule.onNodeWithText("Добавляем из QR-кода").assertIsDisplayed()
        rule.onNodeWithText("Добавить в NORA VPN", substring = true).assertIsNotEnabled()
        assertManualText(MANUAL_DRAFT)
        val edited = "manual edit while QR loads"
        rule.onNode(hasSetTextAction()).performTextReplacement(edited)
        rule.onNodeWithText("Отменить добавление").performScrollTo().performClick()
        rule.runOnIdle { probe.importBarrier!!.complete(Unit) }
        rule.waitForIdle()
        assertManualText(edited)
        rule.onNodeWithText(SERVERS_ROUTE).assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(QrImportState.Idle, probe.coordinator.state.value)
            assertEquals(0, probe.qrImports.get())
            assertEquals(0, probe.manualCallbacks.get())
        }
    }

    private fun openAdd() {
        rule.onNodeWithText(OPEN_ADD).performClick()
    }

    private fun openScanner() {
        rule.onNodeWithText("Сканировать QR-код", substring = true).performScrollTo().performClick()
    }

    private fun assertManualText(expected: String) {
        rule.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected))
        )
    }

    private fun replaceAddEntry(probe: HarnessProbe) {
        rule.runOnIdle {
            probe.navController.navigate("home") {
                popUpTo("home") { inclusive = false }
                launchSingleTop = true
            }
            probe.navController.navigate("add")
        }
        rule.waitForIdle()
    }

    private class HarnessProbe {
        lateinit var navController: NavHostController
        lateinit var draft: AddImportDraftState
        lateinit var coordinator: QrImportCoordinator
        val qrImports = AtomicInteger()
        val manualCallbacks = AtomicInteger()
        val recomposition = mutableIntStateOf(0)
        var observedRecomposition = 0
        var importBarrier: CompletableDeferred<Unit>? = null
    }

    @Composable
    private fun DraftHarness(probe: HarnessProbe) {
        // This is deliberately outside NavHost and cannot be consumed by an old Add entry.
        val draft = rememberAddImportDraftState()
        val navController = rememberNavController()
        val scope = rememberCoroutineScope()
        val coordinator = remember(scope) {
            QrImportCoordinator(scope) { raw ->
                when (ProfileInputParser().parse(raw)) {
                    is ProfileInput.Profiles -> {
                        probe.importBarrier?.await()
                        probe.qrImports.incrementAndGet()
                    }
                    else -> throw QrImportException(QrImportFailureCode.UNSUPPORTED)
                }
            }
        }
        val qrState by coordinator.state.collectAsState()
        var scannerVisible by remember { mutableStateOf(false) }
        var sessionToken by remember { mutableStateOf<Long?>(null) }
        val version = probe.recomposition.intValue
        SideEffect {
            probe.navController = navController
            probe.draft = draft
            probe.coordinator = coordinator
            probe.observedRecomposition = version
        }
        LaunchedEffect(qrState) {
            if (qrState == QrImportState.Success) navController.navigate("servers")
        }

        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.fillMaxSize(),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            composable("home") {
                Button(onClick = { navController.navigate("add") }) { Text(OPEN_ADD) }
            }
            composable("add") {
                AddScreen(
                    value = draft.text,
                    onValueChange = { draft.text = it },
                    onImportProfile = { probe.manualCallbacks.incrementAndGet() },
                    onImportFile = { probe.manualCallbacks.incrementAndGet() },
                    onAddSubscription = { _, _ -> probe.manualCallbacks.incrementAndGet() },
                    onScanQr = {
                        sessionToken = coordinator.beginScan()
                        scannerVisible = true
                    },
                    qrImportState = qrState,
                    onCancelQrImport = coordinator::cancelImport,
                    onDismissQrError = coordinator::dismissError
                )
            }
            composable("servers") { Text(SERVERS_ROUTE) }
        }
        if (scannerVisible) {
            fun closeCamera() {
                sessionToken?.let(coordinator::closeScan)
                scannerVisible = false
            }
            fun decoded(raw: String) {
                sessionToken?.let { coordinator.submit(it, raw) }
                closeCamera()
            }
            Dialog(onDismissRequest = ::closeCamera) {
                Column {
                    Button(onClick = { decoded(VALID_QR) }) { Text(DECODE_GOOD) }
                    Button(onClick = { decoded(BAD_QR) }) { Text(DECODE_BAD) }
                    Button(onClick = ::closeCamera) { Text(CANCEL_CAMERA) }
                }
            }
        }
    }

    companion object {
        private const val OPEN_ADD = "Open Add test route"
        private const val SERVERS_ROUTE = "Synthetic Servers route"
        private const val DECODE_GOOD = "Decode synthetic profile"
        private const val DECODE_BAD = "Decode unsupported payload"
        private const val CANCEL_CAMERA = "Cancel synthetic camera"
        private const val MANUAL_DRAFT = " \nunfinished manual + %2F value \n "
        private const val BAD_QR = "<html>synthetic-private-token.invalid</html>"
        private const val VALID_QR = """{"remarks":"Synthetic local QR","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"192.0.2.44","port":443,"users":[{"id":"11111111-1111-4111-8111-111111111111","encryption":"none"}]}]}}]}"""
    }
}
