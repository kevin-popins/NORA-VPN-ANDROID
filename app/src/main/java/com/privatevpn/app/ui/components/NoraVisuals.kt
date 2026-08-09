package com.privatevpn.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privatevpn.app.R
import com.privatevpn.app.ui.theme.NoraAmber
import com.privatevpn.app.ui.theme.NoraDanger
import com.privatevpn.app.ui.theme.NoraGreen
import com.privatevpn.app.ui.theme.NoraInk
import com.privatevpn.app.ui.theme.NoraInkElevated
import com.privatevpn.app.ui.theme.NoraLine
import com.privatevpn.app.ui.theme.NoraMuted
import com.privatevpn.app.ui.theme.NoraText
import com.privatevpn.app.vpn.VpnConnectionStatus
import com.privatevpn.app.vpn.VpnTrafficState
import com.privatevpn.app.vpn.VpnTrafficSample
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NoraHomeAtmosphere(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(NoraInk)
            .drawWithCache {
                val radial = Brush.radialGradient(
                    colors = listOf(NoraAmber.copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.05f),
                    radius = size.width * 0.82f
                )
                onDrawBehind {
                    drawRect(radial)
                    val step = size.width / 9f
                    for (x in 0..9) {
                        drawLine(
                            color = NoraLine.copy(alpha = 0.18f),
                            start = Offset(x * step, 0f),
                            end = Offset(x * step, size.height * 0.74f),
                            strokeWidth = 1f
                        )
                    }
                    for (y in 0..13) {
                        drawLine(
                            color = NoraLine.copy(alpha = 0.14f),
                            start = Offset(0f, y * step),
                            end = Offset(size.width, y * step),
                            strokeWidth = 1f
                        )
                    }
                }
            }
    )
}

@Composable
fun NoraHomeLoadingScene() {
    val transition = rememberInfiniteTransition(label = "nora_home_loading")
    val drift by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            tween(1_900, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "nora_home_loading_drift"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        NoraHomeAtmosphere(Modifier.fillMaxSize())
        Image(
            painter = painterResource(R.drawable.nora_location_earth),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 64.dp)
                .height(194.dp)
                .graphicsLayer(scaleX = drift, scaleY = drift),
            contentScale = ContentScale.Fit,
            alpha = 0.2f
        )
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "NORA VPN",
                color = NoraText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Загружаем защищённое пространство",
                color = NoraMuted,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun NoraHomeHero(
    vpnStatus: VpnConnectionStatus,
    actionLabel: String,
    statusLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(306.dp)
            .clip(RoundedCornerShape(22.dp))
    ) {
        NoraHomeAtmosphere(Modifier.fillMaxSize())
        Image(
            painter = painterResource(R.drawable.nora_location_earth),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 20.dp)
                .height(238.dp),
            contentScale = ContentScale.Fit,
            alpha = 0.18f
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("NORA", color = NoraText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text("VPN", color = NoraAmber, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        NoraConnectionOrb(
            vpnStatus = vpnStatus,
            label = actionLabel,
            statusLabel = statusLabel,
            onClick = onAction,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 24.dp)
                .size(220.dp)
        )
    }
}

@Composable
fun NoraConnectionOrb(
    vpnStatus: VpnConnectionStatus,
    label: String,
    statusLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "nora_connection_orb")
    val motionDuration = when (vpnStatus) {
        VpnConnectionStatus.CONNECTING -> 1_350
        VpnConnectionStatus.CONNECTED -> 4_800
        else -> 7_200
    }
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(motionDuration, easing = FastOutSlowInEasing)),
        label = "nora_orb_rotation"
    )
    val connectedBreath by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(2_400, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "nora_orb_breath"
    )
    val accent = when (vpnStatus) {
        VpnConnectionStatus.CONNECTED -> NoraGreen
        VpnConnectionStatus.ERROR -> NoraDanger
        else -> NoraAmber
    }
    val isBusy = vpnStatus == VpnConnectionStatus.CONNECTING

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxSize()
                .scale(if (vpnStatus == VpnConnectionStatus.CONNECTED) connectedBreath else 1f)
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outer = size.minDimension * 0.455f
            val inner = size.minDimension * 0.34f
            drawCircle(accent.copy(alpha = 0.07f), radius = outer, center = center)
            drawCircle(NoraInkElevated, radius = inner, center = center)
            drawCircle(
                color = NoraLine.copy(alpha = 0.85f),
                radius = outer,
                center = center,
                style = Stroke(width = size.minDimension * 0.013f)
            )
            if (isBusy) {
                drawArc(
                    color = accent,
                    startAngle = rotation - 90f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(center.x - outer, center.y - outer),
                    size = Size(outer * 2f, outer * 2f),
                    style = Stroke(width = size.minDimension * 0.023f, cap = StrokeCap.Round)
                )
            } else {
                drawCircle(
                    color = accent,
                    radius = outer,
                    center = center,
                    style = Stroke(width = size.minDimension * 0.023f)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(45.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = NoraText,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = NoraMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun NoraActiveServerCard(
    profileName: String,
    protocolLabel: String,
    endpoint: String?,
    vpnStatus: VpnConnectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageIds = remember(profileName) { noraLocationPhotoIds(context, profileName) }
    var photoIndex by remember(profileName) { mutableIntStateOf(0) }
    var showEndpoint by rememberSaveable(profileName) { mutableStateOf(false) }
    LaunchedEffect(imageIds) {
        if (imageIds.size > 1) {
            while (true) {
                delay(6_000)
                photoIndex = (photoIndex + 1) % imageIds.size
            }
        }
    }
    val stateColor = when (vpnStatus) {
        VpnConnectionStatus.CONNECTED -> NoraGreen
        VpnConnectionStatus.ERROR -> NoraDanger
        else -> NoraAmber
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = NoraInkElevated,
        border = BorderStroke(1.dp, NoraLine)
    ) {
        Box(modifier = Modifier.height(190.dp).clickable(onClick = onClick)) {
            Crossfade(
                targetState = imageIds[photoIndex],
                animationSpec = tween(1_550, easing = FastOutSlowInEasing),
                label = "nora_server_photo"
            ) { imageId ->
                Image(
                    painter = painterResource(imageId),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = 0.62f
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(NoraInk.copy(alpha = 0.96f), NoraInk.copy(alpha = 0.32f))
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                .padding(horizontal = 21.dp, vertical = 17.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = stateColor.copy(alpha = 0.16f)) {
                        Box(modifier = Modifier.size(9.dp).padding(2.dp).background(stateColor, CircleShape))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("А К Т И В Н Ы Й   С Е Р В Е Р", color = NoraAmber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    ServerPill(text = noraCountryLabel(profileName), accent = NoraText)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = profileName,
                        color = NoraText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showEndpoint = !showEndpoint }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = if (showEndpoint) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showEndpoint) "Скрыть IP" else "Показать IP",
                                tint = NoraMuted,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Text(
                            text = if (showEndpoint) {
                                endpoint?.takeIf { it.isNotBlank() } ?: "Сервер не выбран"
                            } else {
                                "••••••••••••••"
                            },
                            color = NoraMuted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServerPill(
                        text = if (vpnStatus == VpnConnectionStatus.CONNECTED) "В сети" else "Готов",
                        accent = if (vpnStatus == VpnConnectionStatus.CONNECTED) NoraGreen else NoraAmber
                    )
                    Spacer(Modifier.weight(1f))
                    ServerPill(text = protocolLabel.ifBlank { "VPN" }, accent = NoraAmber)
                }
            }
        }
    }
}

@Composable
fun NoraChangeServerCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = NoraInkElevated,
        border = BorderStroke(1.dp, NoraAmber.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = NoraAmber, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Сменить сервер", color = NoraText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Открыть список доступных серверов", color = NoraMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = NoraAmber, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun ServerPill(text: String, accent: Color) {
    Surface(
        color = NoraInk.copy(alpha = 0.76f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.46f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            color = accent,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun NoraTrafficWaitingPanel(
    traffic: VpnTrafficState,
    connected: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "nora_waiting_traffic")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_400, easing = FastOutSlowInEasing)),
        label = "nora_waiting_traffic_phase"
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = NoraInkElevated,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, NoraLine)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ТРАФИК В РЕАЛЬНОМ ВРЕМЕНИ", color = NoraAmber, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Text(
                    if (connected) "${formatTraffic(traffic.downlinkBytesPerSecond)} /с" else "Ожидание VPN-трафика",
                    color = NoraMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.End)
                )
            }
            Spacer(Modifier.height(13.dp))
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(82.dp)) {
                val baseline = size.height * 0.72f
                repeat(5) { row ->
                    val y = size.height * (0.16f + row * 0.16f)
                    drawLine(NoraLine.copy(alpha = 0.52f), Offset(0f, y), Offset(size.width, y), 1f)
                }
                if (traffic.samples.isEmpty()) {
                    drawLine(NoraLine.copy(alpha = 0.9f), Offset(0f, baseline), Offset(size.width, baseline), 2f)
                } else {
                    val peak = traffic.samples.maxOf { maxOf(it.uplinkBytesPerSecond, it.downlinkBytesPerSecond) }.coerceAtLeast(1L)
                    fun drawSeries(selector: (VpnTrafficSample) -> Long, color: Color) {
                        val path = androidx.compose.ui.graphics.Path()
                        traffic.samples.forEachIndexed { index, sample ->
                            val x = if (traffic.samples.size == 1) 0f else size.width * index / (traffic.samples.size - 1).toFloat()
                            val y = baseline - (selector(sample).toFloat() / peak) * (size.height * 0.55f)
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
                    }
                    drawSeries({ it.downlinkBytesPerSecond }, NoraAmber)
                    drawSeries({ it.uplinkBytesPerSecond }, NoraGreen)
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                TrafficStat("ПОЛУЧЕНО", formatTraffic(traffic.downlinkBytes), Modifier.weight(1f))
                TrafficStat("ВРЕМЯ", formatDuration(traffic.connectedAtMs), Modifier.weight(1f))
                TrafficStat("ОТПРАВЛЕНО", formatTraffic(traffic.uplinkBytes), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TrafficStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = NoraMuted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Text(value, color = NoraText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatTraffic(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> "%.1f KB".format(bytes / 1_024.0)
    bytes < 1_073_741_824L -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.2f GB".format(bytes / 1_073_741_824.0)
}

private fun formatDuration(connectedAtMs: Long?): String {
    val seconds = connectedAtMs?.let { ((System.currentTimeMillis() - it) / 1_000L).coerceAtLeast(0L) } ?: 0L
    return "%02d:%02d:%02d".format(seconds / 3_600L, (seconds / 60L) % 60L, seconds % 60L)
}

@Composable
fun NoraWelcomeScene(
    onOpenProfiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "nora_welcome_ambient")
    val farShift by transition.animateFloat(
        initialValue = -0.012f,
        targetValue = 0.012f,
        animationSpec = infiniteRepeatable(tween(9_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "nora_welcome_far"
    )
    val nearShift by transition.animateFloat(
        initialValue = 0.016f,
        targetValue = -0.016f,
        animationSpec = infiniteRepeatable(tween(7_400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "nora_welcome_near"
    )
    Box(modifier = modifier.fillMaxSize().background(NoraInk)) {
        Image(
            painter = painterResource(R.drawable.nora_location_universe),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(1.08f).graphicsLayer { translationX = farShift * size.width },
            contentScale = ContentScale.Crop,
            alpha = 0.7f
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(NoraInk.copy(alpha = 0.08f), NoraInk.copy(alpha = 0.94f))))
        )
        Image(
            painter = painterResource(R.drawable.nora_location_moon),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 50.dp)
                .size(252.dp)
                .graphicsLayer { translationX = nearShift * 252.dp.toPx() }
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 28.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("NORA VPN", color = NoraText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                "Приватное соединение начинается с вашего профиля",
                color = NoraMuted,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onOpenProfiles,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NoraAmber, contentColor = NoraInk),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Добавить профиль", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun noraLocationPhotoIds(context: android.content.Context, profileName: String): List<Int> {
    val normalized = profileName.lowercase()
    val names = when {
        normalized.contains("нидерланд") || normalized.contains("netherland") || normalized.contains("amsterdam") -> listOf("netherlands1", "netherlands2", "netherlands3")
        normalized.contains("герман") || normalized.contains("germany") || normalized.contains("frankfurt") -> listOf("germany1", "germany2", "germany3")
        normalized.contains("франц") || normalized.contains("france") -> listOf("france1", "france2", "france3")
        normalized.contains("росси") || normalized.contains("москва") || normalized.contains("russia") -> listOf("russia1", "russia2", "russia3")
        normalized.contains("финлянд") || normalized.contains("finland") -> listOf("finland1", "finland2", "finland3")
        normalized.contains("сша") || normalized.contains("usa") || normalized.contains("united states") -> listOf("usa1", "usa2", "usa3")
        normalized.contains("британ") || normalized.contains("england") || normalized.contains("uk") -> listOf("uk1", "uk2", "uk3")
        normalized.contains("ирланд") || normalized.contains("ireland") -> listOf("ireland", "ireland1", "ireland3")
        normalized.contains("итал") || normalized.contains("italy") -> listOf("italy")
        normalized.contains("испан") || normalized.contains("spain") -> listOf("spain")
        normalized.contains("эстон") || normalized.contains("estonia") -> listOf("estonia")
        normalized.contains("литв") || normalized.contains("lithuania") -> listOf("lithuania1", "lithuania2", "lithuania3")
        else -> listOf("universal")
    }
    return names.mapNotNull { name ->
        context.resources.getIdentifier("nora_location_$name", "drawable", context.packageName).takeIf { it != 0 }
    }.ifEmpty { listOf(R.drawable.nora_location_universal) }
}

private fun noraCountryLabel(profileName: String): String = when {
    profileName.contains("нидерланд", true) || profileName.contains("netherland", true) || profileName.contains("amsterdam", true) -> "Нидерланды"
    profileName.contains("герман", true) || profileName.contains("germany", true) || profileName.contains("frankfurt", true) -> "Германия"
    profileName.contains("франц", true) || profileName.contains("france", true) -> "Франция"
    profileName.contains("росси", true) || profileName.contains("москва", true) || profileName.contains("russia", true) -> "Россия"
    profileName.contains("финлянд", true) || profileName.contains("finland", true) -> "Финляндия"
    profileName.contains("сша", true) || profileName.contains("usa", true) -> "США"
    else -> "Без региона"
}
