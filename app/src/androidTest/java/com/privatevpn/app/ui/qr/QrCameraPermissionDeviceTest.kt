package com.privatevpn.app.ui.qr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.ContextCompat
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevpn.app.MainActivity
import com.privatevpn.app.R
import com.privatevpn.app.profiles.db.PrivateVpnDatabase
import com.privatevpn.app.vpn.VpnConnectionStatus
import com.privatevpn.app.vpn.VpnRuntimeStateStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Actual shipping activity/system permission UI; the parent resets CAMERA before EACH method.
 * Never revoke permission inside instrumentation: Android can terminate the target runner.
 * The device camera must face a neutral scene without a QR; no frames or accessibility trees are saved.
 */
class QrCameraPermissionDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun firstAndSecondDenialOfferRetryThenSettings() {
        assertInitialCameraDenied()
        val before = snapshot()
        assertNoVpn(before)
        openScannerFromAdd()
        clickNativePermissionButton(DENY_LABELS)
        waitForAppText("Разрешить камеру")
        rule.onNodeWithText("Разрешить камеру").assertIsEnabled().performScrollTo().performClick()
        clickNativePermissionButton(DENY_LABELS)
        waitForAppText("Открыть настройки")
        rule.onNodeWithText("Открыть настройки").assertIsEnabled()
        assertEquals(PackageManager.PERMISSION_DENIED, cameraPermission())
        rule.onNodeWithContentDescription("Закрыть сканер").performClick()
        waitForAppText("Сканировать QR-код", substring = true)
        assertEquals(before, snapshot())
    }

    @Test
    fun grantBindsCameraTorchStopsOnCloseWithoutImportOrVpn() {
        assertInitialCameraDenied()
        val before = snapshot()
        assertNoVpn(before)
        assertTrue("Samsung device must provide a flash for this mandatory device test",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val rearFlashIds = cameraManager.cameraIdList.filter { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }.toSet()
        assertTrue("A rear flash camera is required for the actual Samsung torch test", rearFlashIds.isNotEmpty())
        val torchOffCallbacks = AtomicInteger()
        val torchCallback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId in rearFlashIds && !enabled) torchOffCallbacks.incrementAndGet()
            }
        }
        cameraManager.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
        try {
            openScannerFromAdd()
            clickNativePermissionButton(GRANT_LABELS)
            waitForAppText("Включить фонарь", substring = true)
            assertEquals(PackageManager.PERMISSION_GRANTED, cameraPermission())
            rule.onNodeWithText("Включить фонарь", substring = true)
                .assertIsEnabled().performScrollTo().performClick()
            // This label is driven by the real CameraInfo.torchState observer, not optimistic UI state.
            waitForAppText("Выключить фонарь", substring = true)
            val offCountBeforeClose = torchOffCallbacks.get()
            Espresso.pressBack()
            waitForAppText("Сканировать QR-код", substring = true)
            rule.waitUntil(TIMEOUT_MS) { torchOffCallbacks.get() > offCountBeforeClose }
            assertEquals(before, snapshot())

            // Warm re-entry must not retain the previous torch, and explicit Close also returns to Add.
            rule.onNodeWithText("Сканировать QR-код", substring = true).performScrollTo().performClick()
            waitForAppText("Включить фонарь", substring = true)
            assertTrue(rule.onAllNodesWithText("Выключить фонарь", substring = true).fetchSemanticsNodes().isEmpty())
            rule.onNodeWithContentDescription("Закрыть сканер").performClick()
            waitForAppText("Сканировать QR-код", substring = true)
            assertEquals(before, snapshot())
        } finally {
            cameraManager.unregisterTorchCallback(torchCallback)
        }
    }

    @After
    fun closeAnyRemainingPermissionOrScannerDialog() {
        try {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val permissionUi = try { root?.packageName?.toString().isPermissionPackage() } finally { root?.recycle() }
            if (permissionUi) Espresso.pressBackUnconditionally()
            if (rule.onAllNodesWithContentDescription("Закрыть сканер").fetchSemanticsNodes().isNotEmpty()) {
                Espresso.pressBackUnconditionally()
            }
        } catch (_: Exception) {
            // Cleanup must not replace the original device assertion failure.
        } catch (_: AssertionError) {
            // The activity can already be closing after a failed system-dialog assertion.
        }
    }

    private fun assertInitialCameraDenied() {
        assertEquals("Parent must revoke CAMERA and clear user-set/user-fixed flags BEFORE this method",
            PackageManager.PERMISSION_DENIED, cameraPermission())
    }

    private fun cameraPermission(): Int = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)

    private fun openScannerFromAdd() {
        // Refresh singleTask setContent after the Compose rule has registered its root observer.
        rule.activityRule.scenario.recreate()
        val addDescription = rule.activity.getString(R.string.nav_add)
        rule.waitUntil(TIMEOUT_MS) {
            try {
                rule.onAllNodesWithContentDescription(addDescription).fetchSemanticsNodes().isNotEmpty()
            } catch (_: IllegalStateException) {
                false // Activity/window restoration can briefly have no registered Compose root.
            }
        }
        rule.onNodeWithContentDescription(addDescription).performClick()
        waitForAppText("Сканировать QR-код", substring = true)
        rule.onNodeWithText("Сканировать QR-код", substring = true).performScrollTo().performClick()
    }

    private fun waitForAppText(text: String, substring: Boolean = false) {
        rule.waitUntil(TIMEOUT_MS) {
            try {
                rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
            } catch (_: IllegalStateException) {
                false // Native permission UI can dismiss before the app root is registered again.
            }
        }
    }

    private fun clickNativePermissionButton(labels: List<String>) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            try {
                if (root != null && root.packageName?.toString().isPermissionPackage()) {
                    for (label in labels) {
                        val candidates = root.findAccessibilityNodeInfosByText(label)
                        try {
                            for (candidate in candidates) {
                                if (candidate.text?.toString()?.normalizedLabel() == label.normalizedLabel() &&
                                    candidate.isVisibleToUser && clickNearestEnabledParent(candidate)
                                ) return
                            }
                        } finally { candidates.forEach { it.recycle() } }
                    }
                }
            } catch (_: IllegalStateException) {
                // A system window can be replaced between querying a node and performing its action.
            } finally { root?.recycle() }
            SystemClock.sleep(50)
        }
        throw AssertionError("System camera permission decision button was not available within 10 seconds")
    }

    private fun clickNearestEnabledParent(candidate: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = candidate
        try {
            repeat(8) {
                val node = current ?: return false
                if (node.isClickable && node.isEnabled && node.isVisibleToUser &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) return true
                val parent = node.parent
                if (node !== candidate) node.recycle()
                current = parent
            }
            return false
        } finally {
            if (current !== candidate) current?.recycle()
        }
    }

    private data class Snapshot(val profiles: Int, val sources: Int, val vpnStatus: VpnConnectionStatus)

    private fun snapshot(): Snapshot = runBlocking {
        val database = PrivateVpnDatabase.build(context)
        try {
            Snapshot(database.profileDao().observeAll().first().size,
                database.subscriptionSourceDao().observeAll().first().size, VpnRuntimeStateStore.status.value)
        } finally { database.close() }
    }

    private fun assertNoVpn(snapshot: Snapshot) {
        assertFalse("Permission QA must start without an active or connecting VPN",
            snapshot.vpnStatus == VpnConnectionStatus.CONNECTED || snapshot.vpnStatus == VpnConnectionStatus.CONNECTING)
    }

    private fun String.normalizedLabel(): String = trim().replace(Regex("\\s+"), " ").lowercase()
    private fun String?.isPermissionPackage(): Boolean =
        this?.let { it.contains("permissioncontroller", ignoreCase = true) || it.contains("packageinstaller", ignoreCase = true) } == true

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private val DENY_LABELS = listOf("Запретить", "Не разрешать", "Don't allow", "Don’t allow", "Deny")
        private val GRANT_LABELS = listOf("При использовании приложения", "Во время использования приложения",
            "Разрешить при использовании приложения", "While using the app", "While using this app")
    }
}
