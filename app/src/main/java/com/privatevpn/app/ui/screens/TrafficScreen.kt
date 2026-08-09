package com.privatevpn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.privatevpn.app.ui.components.NoraTrafficWaitingPanel
import com.privatevpn.app.ui.theme.NoraMuted
import com.privatevpn.app.vpn.VpnConnectionStatus
import com.privatevpn.app.vpn.VpnTrafficState
import com.privatevpn.app.vpn.VpnSessionRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrafficScreen(
    traffic: VpnTrafficState,
    vpnStatus: VpnConnectionStatus,
    sessionHistory: List<VpnSessionRecord>
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = "Трафик VPN",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (vpnStatus == VpnConnectionStatus.CONNECTED) {
                        "Данные этой VPN-сессии обновляются каждую секунду"
                    } else {
                        "Подключите VPN, чтобы начать измерение сессии"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = NoraMuted
                )
            }
        }
        item {
            NoraTrafficWaitingPanel(
                traffic = traffic,
                connected = vpnStatus == VpnConnectionStatus.CONNECTED,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (sessionHistory.isNotEmpty()) {
            item {
                Text(
                    text = "Последние сессии",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            items(sessionHistory.size) { index ->
                SessionRow(sessionHistory[index])
            }
        }
    }
}

@Composable
private fun SessionRow(record: VpnSessionRecord) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(record.profileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    SimpleDateFormat("dd.MM  HH:mm", Locale.getDefault()).format(Date(record.endedAtMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = NoraMuted
                )
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(formatDuration(record.durationSeconds), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatBytes(record.downlinkBytes)} получено · ${formatBytes(record.uplinkBytes)} отправлено",
                    style = MaterialTheme.typography.bodySmall,
                    color = NoraMuted
                )
            }
        }
    }
}

private fun formatDuration(seconds: Long): String = "%02d:%02d:%02d".format(seconds / 3_600L, (seconds / 60L) % 60L, seconds % 60L)

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> "%.1f KB".format(bytes / 1_024.0)
    bytes < 1_073_741_824L -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.2f GB".format(bytes / 1_073_741_824.0)
}
