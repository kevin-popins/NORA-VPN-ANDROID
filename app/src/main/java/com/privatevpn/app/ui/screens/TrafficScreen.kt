package com.privatevpn.app.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privatevpn.app.ui.theme.NoraAmber
import com.privatevpn.app.ui.theme.NoraGreen
import com.privatevpn.app.ui.theme.NoraInkElevated
import com.privatevpn.app.ui.theme.NoraLine
import com.privatevpn.app.ui.theme.NoraMuted
import com.privatevpn.app.ui.theme.NoraText
import com.privatevpn.app.vpn.VpnConnectionStatus
import com.privatevpn.app.vpn.VpnSessionRecord
import com.privatevpn.app.vpn.VpnTrafficSample
import com.privatevpn.app.vpn.VpnTrafficState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private val TrafficUploadBlue = Color(0xFF91ACEB)
private val TrafficLiveGreen = Color(0xFF58BD88)

@Composable
fun TrafficScreen(
    traffic: VpnTrafficState,
    vpnStatus: VpnConnectionStatus,
    sessionHistory: List<VpnSessionRecord>
) {
    val connected = vpnStatus == VpnConnectionStatus.CONNECTED
    val hasTraffic = traffic.samples.any {
        it.uplinkBytesPerSecond > 0L || it.downlinkBytesPerSecond > 0L
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            TrafficHeader(connected = connected, hasTraffic = hasTraffic)
        }
        item {
            LiveTrafficBoard(
                traffic = traffic,
                connected = connected,
                hasTraffic = hasTraffic
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "История сессий",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = NoraText,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (sessionHistory.isEmpty()) "пока пусто" else "${sessionHistory.size} из 10",
                    style = MaterialTheme.typography.labelMedium,
                    color = NoraMuted
                )
            }
        }
        item {
            if (sessionHistory.isEmpty()) {
                EmptySessionHistory()
            } else {
                SessionHistoryList(records = sessionHistory.take(10))
            }
        }
    }
}

@Composable
private fun TrafficHeader(
    connected: Boolean,
    hasTraffic: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Трафик",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = NoraText,
                modifier = Modifier.weight(1f)
            )
            TrafficStatusPill(connected = connected)
        }
        Text(
            text = when {
                connected && hasTraffic -> "Только данные, прошедшие через VPN в текущей сессии"
                connected -> "VPN подключен. Ожидаем первые защищенные пакеты"
                else -> "Поток появится после подключения к VPN"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = NoraMuted
        )
    }
}

@Composable
private fun TrafficStatusPill(connected: Boolean) {
    val color = if (connected) TrafficLiveGreen else NoraMuted
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(99.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.34f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(color, androidx.compose.foundation.shape.CircleShape)
            )
            Text(
                text = if (connected) "В СЕССИИ" else "НЕ ПОДКЛЮЧЕН",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
private fun LiveTrafficBoard(
    traffic: VpnTrafficState,
    connected: Boolean,
    hasTraffic: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        color = NoraInkElevated,
        border = BorderStroke(1.dp, NoraLine)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ShowChart,
                        contentDescription = null,
                        tint = NoraAmber,
                        modifier = Modifier.size(21.dp)
                    )
                    Text(
                        text = "ТРАФИК СЕЙЧАС",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = NoraAmber
                    )
                }
                Text(
                    text = if (connected && hasTraffic) "ОБНОВЛЯЕТСЯ" else "ОЖИДАНИЕ",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (connected) TrafficLiveGreen else NoraMuted
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                TrafficRate(
                    modifier = Modifier.weight(1f),
                    label = "ПОЛУЧАЕМ",
                    rate = formatRate(traffic.downlinkBytesPerSecond),
                    icon = Icons.Default.ArrowDownward,
                    color = NoraAmber
                )
                TrafficRate(
                    modifier = Modifier.weight(1f),
                    label = "ОТПРАВЛЯЕМ",
                    rate = formatRate(traffic.uplinkBytesPerSecond),
                    icon = Icons.Default.ArrowUpward,
                    color = TrafficUploadBlue
                )
            }

            Spacer(Modifier.height(16.dp))
            TrafficGraph(
                samples = traffic.samples,
                connected = connected,
                hasTraffic = hasTraffic,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = NoraLine.copy(alpha = 0.76f))
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TrafficMetric(
                    modifier = Modifier.weight(1f),
                    label = "ПОЛУЧЕНО",
                    value = formatBytes(traffic.downlinkBytes),
                    color = NoraAmber
                )
                MetricDivider()
                TrafficMetric(
                    modifier = Modifier.weight(1f),
                    label = "В СЕССИИ",
                    value = formatSessionDuration(traffic.connectedAtMs),
                    color = NoraText,
                    textAlign = TextAlign.Center
                )
                MetricDivider()
                TrafficMetric(
                    modifier = Modifier.weight(1f),
                    label = "ОТПРАВЛЕНО",
                    value = formatBytes(traffic.uplinkBytes),
                    color = TrafficUploadBlue,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun TrafficRate(
    label: String,
    rate: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = NoraMuted,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = rate,
            style = MaterialTheme.typography.titleLarge,
            color = NoraText,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TrafficGraph(
    samples: List<VpnTrafficSample>,
    connected: Boolean,
    hasTraffic: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "traffic_graph_motion")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_900, easing = FastOutSlowInEasing)),
        label = "traffic_graph_phase"
    )
    val visibleSamples = samples.takeLast(32)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = when {
                connected && hasTraffic -> "Поток за последние секунды"
                connected -> "Первые защищенные пакеты появятся здесь"
                else -> "Подключите VPN, чтобы увидеть поток"
            },
            style = MaterialTheme.typography.bodySmall,
            color = NoraMuted
        )
        Canvas(modifier = modifier.height(132.dp)) {
            val top = 8.dp.toPx()
            val bottom = size.height - 8.dp.toPx()
            val plotHeight = bottom - top
            val baseline = top + plotHeight * 0.74f

            repeat(4) { index ->
                val y = top + plotHeight * index / 3f
                drawLine(
                    color = NoraLine.copy(alpha = 0.46f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
            }
            repeat(5) { index ->
                val x = size.width * index / 4f
                drawLine(
                    color = NoraLine.copy(alpha = 0.25f),
                    start = Offset(x, top),
                    end = Offset(x, bottom),
                    strokeWidth = 1f
                )
            }

            if (!connected || !hasTraffic || visibleSamples.size < 2) {
                drawLine(
                    color = NoraMuted.copy(alpha = 0.58f),
                    start = Offset(0f, baseline),
                    end = Offset(size.width, baseline),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                val scanColor = if (connected) NoraAmber else NoraMuted
                val scanX = (size.width + 76.dp.toPx()) * phase - 38.dp.toPx()
                drawLine(
                    color = scanColor.copy(alpha = if (connected) 0.78f else 0.46f),
                    start = Offset((scanX - 28.dp.toPx()).coerceAtLeast(0f), baseline),
                    end = Offset((scanX + 28.dp.toPx()).coerceAtMost(size.width), baseline),
                    strokeWidth = 2.6.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = scanColor.copy(alpha = if (connected) 0.26f else 0.14f),
                    radius = 10.dp.toPx(),
                    center = Offset(scanX.coerceIn(0f, size.width), baseline)
                )
                drawCircle(
                    color = scanColor,
                    radius = 3.5.dp.toPx(),
                    center = Offset(scanX.coerceIn(0f, size.width), baseline)
                )
                return@Canvas
            }

            val peak = visibleSamples.maxOf {
                max(it.downlinkBytesPerSecond, it.uplinkBytesPerSecond)
            }.coerceAtLeast(1L).toFloat() * 1.12f
            val pointsFor: (Boolean) -> List<Offset> = { incoming ->
                visibleSamples.mapIndexed { index, sample ->
                    val x = size.width * index / visibleSamples.lastIndex.toFloat()
                    val value = if (incoming) sample.downlinkBytesPerSecond else sample.uplinkBytesPerSecond
                    val y = bottom - 5.dp.toPx() - (value.toFloat() / peak) * (plotHeight - 14.dp.toPx())
                    Offset(x, y.coerceIn(top + 3.dp.toPx(), bottom - 4.dp.toPx()))
                }
            }
            val downPoints = pointsFor(true)
            val upPoints = pointsFor(false)
            val downPath = smoothPath(downPoints)
            val areaPath = smoothAreaPath(downPoints, bottom)
            val upPath = smoothPath(upPoints)

            drawPath(
                path = areaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(NoraAmber.copy(alpha = 0.30f), NoraAmber.copy(alpha = 0.02f)),
                    startY = top,
                    endY = bottom
                )
            )
            drawPath(
                path = downPath,
                color = NoraAmber.copy(alpha = 0.18f),
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            )
            drawPath(
                path = downPath,
                color = NoraAmber,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )
            drawPath(
                path = upPath,
                color = TrafficUploadBlue,
                style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
            )
            val head = downPoints.last()
            val pulse = 4.dp.toPx() + phase * 3.dp.toPx()
            drawCircle(NoraAmber.copy(alpha = 0.20f), radius = pulse + 5.dp.toPx(), center = head)
            drawCircle(NoraAmber, radius = 3.5.dp.toPx(), center = head)
        }
    }
}

@Composable
private fun MetricDivider() {
    Spacer(
        modifier = Modifier
            .height(38.dp)
            .width(1.dp)
            .background(NoraLine.copy(alpha = 0.76f))
    )
}

@Composable
private fun TrafficMetric(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = NoraMuted,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            fontWeight = FontWeight.Bold,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EmptySessionHistory() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = NoraInkElevated,
        border = BorderStroke(1.dp, NoraLine)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = NoraLine.copy(alpha = 0.42f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.History, contentDescription = null, tint = NoraMuted)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Пока нет завершенных сессий", color = NoraText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("После отключения VPN здесь сохранится время и объем переданных данных.", color = NoraMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SessionHistoryList(records: List<VpnSessionRecord>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = NoraInkElevated,
        border = BorderStroke(1.dp, NoraLine)
    ) {
        Column {
            records.forEachIndexed { index, record ->
                SessionHistoryRow(record)
                if (index < records.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 64.dp),
                        color = NoraLine.copy(alpha = 0.72f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionHistoryRow(record: VpnSessionRecord) {
    val total = record.downlinkBytes + record.uplinkBytes
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(11.dp),
            color = NoraAmber.copy(alpha = 0.10f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.History, contentDescription = null, tint = NoraAmber, modifier = Modifier.size(19.dp))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = record.profileName,
                style = MaterialTheme.typography.bodyMedium,
                color = NoraText,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = SimpleDateFormat("dd MMMM, HH:mm", Locale("ru")).format(Date(record.endedAtMs)),
                style = MaterialTheme.typography.bodySmall,
                color = NoraMuted
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("↓ ${formatBytes(record.downlinkBytes)}", style = MaterialTheme.typography.labelSmall, color = NoraAmber)
                Text("↑ ${formatBytes(record.uplinkBytes)}", style = MaterialTheme.typography.labelSmall, color = TrafficUploadBlue)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = formatBytes(total),
                style = MaterialTheme.typography.bodyMedium,
                color = NoraText,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = formatDuration(record.durationSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = NoraMuted
            )
        }
    }
}

private fun smoothPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points.first().x, points.first().y)
    appendSmoothCurve(points)
}

private fun smoothAreaPath(points: List<Offset>, baseline: Float): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points.first().x, baseline)
    lineTo(points.first().x, points.first().y)
    appendSmoothCurve(points)
    lineTo(points.last().x, baseline)
    close()
}

private fun Path.appendSmoothCurve(points: List<Offset>) {
    if (points.size < 2) return
    for (index in 1 until points.size) {
        val previous = points[index - 1]
        val current = points[index]
        val midpoint = Offset((previous.x + current.x) / 2f, (previous.y + current.y) / 2f)
        quadraticBezierTo(previous.x, previous.y, midpoint.x, midpoint.y)
    }
    lineTo(points.last().x, points.last().y)
}

private fun formatRate(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/с"

private fun formatSessionDuration(connectedAtMs: Long?): String {
    val seconds = connectedAtMs?.let { ((System.currentTimeMillis() - it) / 1_000L).coerceAtLeast(0L) } ?: 0L
    return formatDuration(seconds)
}

private fun formatDuration(seconds: Long): String = "%02d:%02d:%02d".format(
    seconds / 3_600L,
    (seconds / 60L) % 60L,
    seconds % 60L
)

private fun formatBytes(bytes: Long): String {
    val normalized = bytes.coerceAtLeast(0L)
    return when {
        normalized < 1_024L -> "$normalized Б"
        normalized < 1_048_576L -> "%.1f КБ".format(Locale.US, normalized / 1_024.0)
        normalized < 1_073_741_824L -> "%.1f МБ".format(Locale.US, normalized / 1_048_576.0)
        else -> "%.2f ГБ".format(Locale.US, normalized / 1_073_741_824.0)
    }
}
