package com.privatevpn.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsControllerCompat
import com.privatevpn.app.ui.PrivateVpnApp
import com.privatevpn.app.ui.theme.PrivateVpnTheme
import com.privatevpn.app.vpn.VpnQuickSettingsTileService

class MainActivity : ComponentActivity() {

    private var requestVpnPermissionFromIntent by mutableStateOf(false)
    private var externalImportIntent by mutableStateOf<Intent?>(null)
    private var externalImportIntentVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        consumeIntentFlags(intent)
        setContent {
            PrivateVpnTheme {
                PrivateVpnApp(
                    requestVpnPermissionOnStart = requestVpnPermissionFromIntent,
                    onRequestVpnPermissionConsumed = { requestVpnPermissionFromIntent = false },
                    externalImportIntent = externalImportIntent,
                    externalImportIntentVersion = externalImportIntentVersion,
                    onExternalImportIntentConsumed = { externalImportIntent = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntentFlags(intent)
    }

    private fun consumeIntentFlags(intent: Intent?) {
        if (intent?.getBooleanExtra(VpnQuickSettingsTileService.EXTRA_REQUEST_VPN_PERMISSION, false) == true) {
            requestVpnPermissionFromIntent = true
        }
        if (intent.isExternalImportIntent()) {
            externalImportIntent = Intent(intent)
            externalImportIntentVersion += 1
        }
    }

    private fun Intent?.isExternalImportIntent(): Boolean {
        this ?: return false
        return when (action) {
            Intent.ACTION_VIEW,
            Intent.ACTION_EDIT -> data != null

            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE -> true

            else -> false
        }
    }

    private fun configureSystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }
}
