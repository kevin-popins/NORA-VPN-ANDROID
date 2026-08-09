package com.privatevpn.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privatevpn.app.settings.SettingsState
import com.privatevpn.app.settings.SocksSettings
import com.privatevpn.app.ui.NotificationPermissionUiState
import com.privatevpn.app.ui.theme.NoraAmber
import com.privatevpn.app.ui.theme.NoraInkElevated
import com.privatevpn.app.ui.theme.NoraLine
import com.privatevpn.app.ui.theme.NoraMuted
import com.privatevpn.app.ui.theme.NoraText

@Composable
fun NoraSettingsScreen(
    settings: SettingsState,
    notificationPermission: NotificationPermissionUiState,
    onOpenPrivateSession: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenDns: () -> Unit,
    onRequestNotifications: () -> Unit,
    onSaveSocks: (SocksSettings) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Управление маршрутизацией и локальными инструментами", style = MaterialTheme.typography.bodyMedium, color = NoraMuted)
            }
        }
        item { SectionCaption("УПРАВЛЕНИЕ VPN") }
        item { SettingsAction(Icons.Default.Security, "Раздельное туннелирование", "Выберите приложения для VPN", onOpenPrivateSession) }
        item { SettingsAction(Icons.Default.Terminal, "Логи", "События и диагностика подключения", onOpenLogs) }
        item { SettingsAction(Icons.Default.Dns, "DNS", "Серверы и режим разрешения имён", onOpenDns) }
        item { SectionCaption("СИСТЕМА") }
        item {
            SettingsToggle(
                icon = Icons.Default.Notifications,
                title = "Шторка и уведомления",
                subtitle = if (notificationPermission.granted) "Разрешение выдано" else "Разрешить системные уведомления",
                checked = notificationPermission.granted,
                enabled = notificationPermission.supported && !notificationPermission.granted,
                onClick = onRequestNotifications
            )
        }
        item { SectionCaption("ЛОКАЛЬНЫЙ SOCKS") }
        item {
            SocksCard(settings.socksSettings, onSaveSocks)
        }
    }
}

@Composable
private fun SectionCaption(text: String) {
    Text(text, color = NoraAmber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
}

@Composable
private fun SettingsAction(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = NoraInkElevated, border = androidx.compose.foundation.BorderStroke(1.dp, NoraLine), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = NoraAmber)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, color = NoraText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = NoraMuted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.OpenInNew, null, tint = NoraMuted)
        }
    }
}

@Composable
private fun SettingsToggle(icon: ImageVector, title: String, subtitle: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = NoraInkElevated, border = androidx.compose.foundation.BorderStroke(1.dp, NoraLine), modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        Row(modifier = Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = NoraAmber)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, color = NoraText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = NoraMuted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = if (enabled) { { onClick() } } else null)
        }
    }
}

@Composable
private fun SocksCard(socks: SocksSettings, onSaveSocks: (SocksSettings) -> Unit) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = NoraInkElevated, border = androidx.compose.foundation.BorderStroke(1.dp, NoraLine), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SOCKS на localhost", color = NoraText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Логин и пароль созданы автоматически", color = NoraMuted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = socks.enabled, onCheckedChange = { onSaveSocks(socks.copy(enabled = it)) })
            }
            HorizontalDivider(color = NoraLine)
            SocksField("Адрес", "127.0.0.1:${socks.port}")
            SocksField("Логин", socks.login)
            SocksField("Пароль", "••••••••••••••••")
        }
    }
}

@Composable
private fun SocksField(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = NoraMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.35f))
        Text(value, color = NoraText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.65f))
    }
}
