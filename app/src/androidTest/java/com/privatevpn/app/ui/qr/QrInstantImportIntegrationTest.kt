package com.privatevpn.app.ui.qr

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevpn.app.MainActivity
import com.privatevpn.app.ui.AppViewModel
import com.privatevpn.app.profiles.db.PrivateVpnDatabase
import com.privatevpn.app.settings.storage.DataStoreUserSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the real VM/channel/navigation with a synthetic inline profile, never a VPN connection. */
class QrInstantImportIntegrationTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val raw="vless://00000000-0000-4000-8000-000000000098@example.invalid:443?security=tls&type=tcp&sni=example.invalid#NORA%20QR%20AUTO%20TEST"

    @Test fun inlineQrImmediatelyPersistsExactlyOnceAndPreservesExistingSelection() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=PrivateVpnDatabase.build(context)
        val settings=DataStoreUserSettingsRepository(context)
        val original=database.profileDao().observeAll().first()
        val oldSelection=settings.settings.first().activeProfileId
        lateinit var vm:AppViewModel
        rule.runOnIdle { vm=ViewModelProvider(rule.activity)[AppViewModel::class.java] }
        try {
            rule.onNodeWithContentDescription("Добавить").performClick()
            var accepted=false
            var duplicateAccepted=true
            rule.runOnIdle {
                val token=vm.beginQrScan()
                accepted=vm.importScannedQr(token,raw)
                duplicateAccepted=vm.importScannedQr(token,raw)
            }
            assertTrue(accepted)
            assertFalse(duplicateAccepted)
            rule.waitUntil(15000) { vm.qrImportState.value is QrImportState.Success }
            rule.waitUntil(15000) { rule.onAllNodesWithText("NORA QR AUTO TEST",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("NORA QR AUTO TEST",useUnmergedTree=true).assertIsDisplayed()
            val after=database.profileDao().observeAll().first()
            assertEquals(original.size+1,after.size)
            assertEquals(1,after.count{it.sourceRaw==raw})
            assertTrue("Existing selected ID must remain unchanged",oldSelection==settings.settings.first().activeProfileId)
        } finally {
            // Remove only the exact synthetic material created by this test; never clear application data.
            database.profileDao().observeAll().first().filter{it.sourceRaw==raw && original.none{old->old.id==it.id}}.forEach{database.profileDao().deleteById(it.id)}
            settings.setActiveProfile(oldSelection)
            database.close()
        }
    }

    @Test fun unsupportedQrHasStableSafeFailureAndNoDataOrVpnAction() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=PrivateVpnDatabase.build(context)
        val before=database.profileDao().observeAll().first().size
        lateinit var vm:AppViewModel
        rule.runOnIdle { vm=ViewModelProvider(rule.activity)[AppViewModel::class.java] }
        try {
            rule.onNodeWithContentDescription("Добавить").performClick()
            for (raw in listOf("SYNTHETIC_UNSUPPORTED_QR", "vless://00000000-0000-0000-0000-000000000000@localhost:1?security=none&type=tcp#Unsupported")) {
                rule.runOnIdle { vm.importScannedQr(vm.beginQrScan(),raw) }
                rule.waitUntil(10000) { vm.qrImportState.value is QrImportState.Failure }
                val state=vm.qrImportState.value as QrImportState.Failure
                assertTrue(state.safeMessage.isNotBlank())
                rule.onNodeWithText(state.safeMessage).assertIsDisplayed()
                rule.runOnIdle { }
                rule.onNodeWithText(state.safeMessage).assertIsDisplayed()
                assertFalse(state.safeMessage.contains("SYNTHETIC_UNSUPPORTED_QR"))
                assertFalse(state.safeMessage.contains("localhost"))
                assertEquals(before,database.profileDao().observeAll().first().size)
            }
        } finally { database.close() }
    }
}
