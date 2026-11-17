package com.privatevpn.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.privatevpn.app.R
import com.privatevpn.app.ui.openNoraPurchaseWebsite
import com.privatevpn.app.ui.qr.QrImportState
import com.privatevpn.app.ui.theme.NoraAmber
import com.privatevpn.app.ui.theme.NoraInk
import com.privatevpn.app.ui.theme.NoraInkElevated
import com.privatevpn.app.ui.theme.NoraMuted

@Composable
fun AddScreen(
    value: String,
    onValueChange: (String) -> Unit,
    onImportProfile: (String) -> Unit,
    onImportFile: () -> Unit,
    onAddSubscription: (String, String?) -> Unit,
    onScanQr: () -> Unit,
    qrImportState: QrImportState = QrImportState.Idle,
    onCancelQrImport: () -> Unit = {},
    onDismissQrError: () -> Unit = {}
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputFocusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val normalized = value.trim()
    val importingQr = qrImportState == QrImportState.Loading
    LaunchedEffect(qrImportState) {
        if (qrImportState == QrImportState.Loading || qrImportState is QrImportState.Failure) {
            scrollState.scrollTo(0)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(scrollState)
            .padding(PaddingValues(horizontal = 20.dp, vertical = 16.dp)),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Добавить", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Добавьте ключ, конфиг или ссылку на подписку.", color = NoraMuted, style = MaterialTheme.typography.bodyMedium)
        when (qrImportState) {
            QrImportState.Loading -> QrImportPanel {
                Text("Добавляем из QR-кода", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Проверяем профиль или подписку. Это может занять несколько секунд.", color = NoraMuted,
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onCancelQrImport, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Отменить добавление")
                }
            }
            is QrImportState.Failure -> QrImportPanel {
                Text("Не удалось добавить QR", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(qrImportState.safeMessage, color = NoraMuted, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = {
                        onDismissQrError()
                        onScanQr()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Сканировать ещё раз") }
                TextButton(
                    onClick = {
                        onDismissQrError()
                        inputFocusRequester.requestFocus()
                        keyboardController?.show()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Ввести вручную") }
            }
            else -> Unit
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().height(210.dp).focusRequester(inputFocusRequester),
            label = { Text("krot:// · nora1. · vless:// · https://") },
            minLines = 7,
            maxLines = 9
        )
        Button(
            onClick = {
                if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
                    onAddSubscription(normalized, null)
                } else if (normalized.isNotBlank()) {
                    onImportProfile(normalized)
                }
            },
            enabled = normalized.isNotBlank() && !importingQr,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NoraAmber, contentColor = NoraInk)
        ) {
            androidx.compose.material3.Icon(Icons.Default.Add, null)
            Text("  Добавить в NORA VPN", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                onScanQr()
            },
            enabled = !importingQr,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            androidx.compose.material3.Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Text("  Сканировать QR-код")
        }
        Button(onClick = onImportFile, enabled = !importingQr, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            androidx.compose.material3.Icon(Icons.Default.FileOpen, null)
            Text("  Импорт из файла")
        }
        TextButton(
            onClick = { openNoraPurchaseWebsite(context) },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = NoraAmber)
        ) {
            Text(stringResource(R.string.nora_site_add_cta), fontWeight = FontWeight.Medium)
            androidx.compose.material3.Icon(Icons.Default.OpenInNew, contentDescription = null)
        }
    }
}

@Composable
private fun QrImportPanel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(NoraInkElevated, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) { content() }
}
