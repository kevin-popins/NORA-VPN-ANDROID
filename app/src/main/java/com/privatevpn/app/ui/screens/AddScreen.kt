package com.privatevpn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.privatevpn.app.R
import com.privatevpn.app.ui.openNoraTelegramBot
import com.privatevpn.app.ui.theme.NoraAmber
import com.privatevpn.app.ui.theme.NoraInk
import com.privatevpn.app.ui.theme.NoraMuted

@Composable
fun AddScreen(
    onImportProfile: (String) -> Unit,
    onImportFile: () -> Unit,
    onAddSubscription: (String, String?) -> Unit
) {
    val context = LocalContext.current
    var value by rememberSaveable { mutableStateOf("") }
    val normalized = value.trim()
    Column(
        modifier = Modifier.fillMaxSize().padding(PaddingValues(horizontal = 20.dp, vertical = 16.dp)),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Добавить", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Добавьте ключ, конфиг или ссылку на подписку.", color = NoraMuted, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth().height(210.dp),
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
            enabled = normalized.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NoraAmber, contentColor = NoraInk)
        ) {
            androidx.compose.material3.Icon(Icons.Default.Add, null)
            Text("  Добавить в NORA VPN", fontWeight = FontWeight.Bold)
        }
        Button(onClick = onImportFile, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            androidx.compose.material3.Icon(Icons.Default.FileOpen, null)
            Text("  Импорт из файла")
        }
        TextButton(
            onClick = { openNoraTelegramBot(context) },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = NoraAmber)
        ) {
            Text(stringResource(R.string.nora_bot_add_cta), fontWeight = FontWeight.Medium)
            androidx.compose.material3.Icon(Icons.Default.OpenInNew, contentDescription = null)
        }
    }
}
