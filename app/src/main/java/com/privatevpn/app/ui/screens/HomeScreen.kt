package com.privatevpn.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privatevpn.app.R
import com.privatevpn.app.profiles.model.SubscriptionMetadataCodec
import com.privatevpn.app.profiles.model.SubscriptionSource
import com.privatevpn.app.profiles.model.SubscriptionSyncStatus
import com.privatevpn.app.profiles.model.VpnProfile
import com.privatevpn.app.ui.components.AppSection
import com.privatevpn.app.ui.components.InlineStatusLabel
import com.privatevpn.app.ui.components.NoraActiveServerCard
import com.privatevpn.app.ui.components.NoraChangeServerCard
import com.privatevpn.app.ui.components.NoraHomeHero
import com.privatevpn.app.ui.components.NoraHomeLoadingScene
import com.privatevpn.app.ui.components.NoraTrafficWaitingPanel
import com.privatevpn.app.ui.components.NoraWelcomeScene
import com.privatevpn.app.ui.components.SectionTone
import com.privatevpn.app.ui.components.softClickable
import com.privatevpn.app.ui.theme.AppSpacing
import com.privatevpn.app.vpn.VpnConnectionStatus
import com.privatevpn.app.vpn.VpnTrafficState
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    vpnStatus: VpnConnectionStatus,
    traffic: VpnTrafficState,
    profilesLoaded: Boolean,
    connectionErrorMessage: String?,
    activeProfileName: String?,
    protocolLabel: String,
    serverAddress: String?,
    profiles: List<VpnProfile>,
    subscriptions: List<SubscriptionSource>,
    activeProfileId: String?,
    serverPingResults: Map<String, String>,
    pingInProgress: Boolean,
    refreshingSubscriptionIds: Set<String>,
    scrollToTopSignal: Int,
    onRequestVpnPermission: () -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onPingAllServers: () -> Unit,
    onSetActiveProfile: (String) -> Unit,
    onToggleSubscriptionCollapse: (String) -> Unit,
    onRefreshSubscription: (String) -> Unit,
    onTransientMessage: (String) -> Unit,
    onOpenProfiles: () -> Unit,
    onAddProfile: () -> Unit = onOpenProfiles
) {
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal > 0) {
            listState.animateScrollToItem(0)
        }
    }

    val directProfiles = remember(profiles) { profiles.filter { it.parentSubscriptionId == null } }
    val childrenBySubscription = remember(profiles) {
        profiles
            .filter { !it.parentSubscriptionId.isNullOrBlank() }
            .groupBy { it.parentSubscriptionId.orEmpty() }
            .mapValues { (_, value) -> value.sortedBy { it.sourceOrder } }
    }

    if (!profilesLoaded) {
        NoraHomeLoadingScene()
        return
    }

    if (directProfiles.isEmpty() && subscriptions.isEmpty()) {
        NoraWelcomeScene(onAddProfile = onAddProfile)
        return
    }

    val statusText = if (vpnStatus == VpnConnectionStatus.CONNECTED) {
        formatHomeDuration(traffic.connectedAtMs)
    } else {
        "00:00:00"
    }

    val primaryButtonLabel = when (vpnStatus) {
        VpnConnectionStatus.NO_PERMISSION -> "Разрешить VPN"
        VpnConnectionStatus.CONNECTED -> "Подключено"
        VpnConnectionStatus.CONNECTING -> "Подключение"
        VpnConnectionStatus.READY,
        VpnConnectionStatus.ERROR -> "Подключить"
    }

    val onPrimaryAction = when (vpnStatus) {
        VpnConnectionStatus.NO_PERMISSION -> onRequestVpnPermission
        VpnConnectionStatus.CONNECTED,
        VpnConnectionStatus.CONNECTING -> onDisconnectClick
        VpnConnectionStatus.READY,
        VpnConnectionStatus.ERROR -> onConnectClick
    }

    // Home is deliberately a fixed connection scene. Detailed traffic lives on its own tab.
    Column(
        modifier = Modifier.fillMaxSize().padding(AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        NoraHomeHero(
            vpnStatus = vpnStatus,
            actionLabel = primaryButtonLabel,
            statusLabel = statusText,
            onAction = onPrimaryAction
        )
        NoraActiveServerCard(
            profileName = activeProfileName ?: stringResource(R.string.home_profile_not_selected),
            protocolLabel = protocolLabel,
            endpoint = serverAddress,
            vpnStatus = vpnStatus,
            onClick = onOpenProfiles
        )
        NoraChangeServerCard(onClick = onOpenProfiles)
        if (!connectionErrorMessage.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.home_error_prefix, connectionErrorMessage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
    return

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        item(key = "status_card") {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                NoraHomeHero(
                    vpnStatus = vpnStatus,
                    actionLabel = primaryButtonLabel,
                    statusLabel = statusText,
                    onAction = onPrimaryAction
                )
                NoraActiveServerCard(
                    profileName = activeProfileName ?: stringResource(R.string.home_profile_not_selected),
                    protocolLabel = protocolLabel,
                    endpoint = serverAddress,
                    vpnStatus = vpnStatus,
                    onClick = onOpenProfiles
                )
                NoraTrafficWaitingPanel(
                    traffic = traffic,
                    connected = vpnStatus == VpnConnectionStatus.CONNECTED
                )
                if (!connectionErrorMessage.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.home_error_prefix, connectionErrorMessage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        item(key = "servers_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.home_servers_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onPingAllServers,
                    enabled = !pingInProgress,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = stringResource(R.string.home_ping_all_servers),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (directProfiles.isEmpty() && subscriptions.isEmpty()) {
            item(key = "servers_empty") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
                ) {
                    Text(
                        text = stringResource(R.string.home_servers_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.home_servers_empty_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            if (directProfiles.isNotEmpty()) {
                items(
                    items = directProfiles,
                    key = { profile -> profile.id }
                ) { profile ->
                    CompactServerRow(
                        title = profile.displayName,
                        selected = profile.id == activeProfileId,
                        pingText = serverPingResults[profile.id],
                        onClick = { onSetActiveProfile(profile.id) }
                    )
                }
            }

            if (subscriptions.isNotEmpty()) {
                item(key = "subscriptions_header") {
                    Text(
                        text = stringResource(R.string.home_subscriptions_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            items(
                items = subscriptions,
                key = { source -> source.id }
            ) { subscription ->
                val children = childrenBySubscription[subscription.id].orEmpty()
                val isRefreshing = refreshingSubscriptionIds.contains(subscription.id)
                SubscriptionGroup(
                    source = subscription,
                    profiles = children,
                    activeProfileId = activeProfileId,
                    isRefreshing = isRefreshing,
                    onToggleCollapse = { onToggleSubscriptionCollapse(subscription.id) },
                    onRefresh = { onRefreshSubscription(subscription.id) },
                    serverPingResults = serverPingResults,
                    onSelectProfile = onSetActiveProfile,
                    onTransientMessage = onTransientMessage
                )
            }
        }
    }
}

private fun formatHomeDuration(connectedAtMs: Long?): String {
    val seconds = connectedAtMs?.let { ((System.currentTimeMillis() - it) / 1_000L).coerceAtLeast(0L) } ?: 0L
    return "%02d:%02d:%02d".format(seconds / 3_600L, (seconds / 60L) % 60L, seconds % 60L)
}

@Composable
private fun SubscriptionGroup(
    source: SubscriptionSource,
    profiles: List<VpnProfile>,
    activeProfileId: String?,
    isRefreshing: Boolean,
    onToggleCollapse: () -> Unit,
    onRefresh: () -> Unit,
    serverPingResults: Map<String, String>,
    onSelectProfile: (String) -> Unit,
    onTransientMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by rememberSaveable(source.id) { mutableStateOf(false) }
    val metadata = remember(source.metadata) { SubscriptionMetadataCodec.decode(source.metadata) }
    val title = metadata.displayTitle ?: source.displayName
    val providerLabel = metadata.providerName
        ?: metadata.resolvedProviderDomain
        ?: metadata.resolvedProviderSiteUrl?.let { runCatching { URL(it).host.removePrefix("www.") }.getOrNull() }
    val providerLink = metadata.preferredExternalUrl()
    val trafficSummary = remember(metadata) { formatSubscriptionTraffic(metadata) }
    val expireSummary = remember(metadata) { formatSubscriptionExpiry(metadata) }
    val providerDomainLine = providerLabel
        ?: providerLink?.let { runCatching { URL(it).host.removePrefix("www.") }.getOrNull() }
    val serversLine = stringResource(R.string.home_subscription_meta, metadata.serverCount ?: profiles.size)
    val legacyLabelTokens = remember(metadata.labelLine) {
        parseLegacySubscriptionLabel(metadata.labelLine)
    }
    val resolvedUserId = metadata.userId ?: legacyLabelTokens.userId
    val resolvedPlanId = metadata.planId ?: legacyLabelTokens.planId
    val providerMetaLine = remember(metadata, providerLink, resolvedUserId, resolvedPlanId) {
        buildList {
            metadata.resolvedProviderSiteUrl
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { site ->
                    val shortened = site.removePrefix("https://").removePrefix("http://")
                    add("Наш сайт: $shortened")
                }
            resolvedUserId?.trim()?.takeIf { it.isNotBlank() }?.let { add("ID: $it") }
            resolvedPlanId?.trim()?.takeIf { it.isNotBlank() }?.let { add("#$it") }
        }.joinToString(" • ").takeIf { it.isNotBlank() }
    }
    val legendLine = buildLegendLine(
        labelLine = metadata.labelLine,
        tags = metadata.tags,
        fallbackCandidates = listOf(
            metadata.badgeText,
            metadata.providerMessage,
            metadata.announcementText,
            metadata.note
        )
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .softClickable(
                        shape = MaterialTheme.shapes.small,
                        onClick = onToggleCollapse
                    ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (!source.isCollapsed) {
                        SubscriptionStatusChip(status = source.syncStatus, refreshing = isRefreshing)
                    }
                }

                if (!source.isCollapsed) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                    ) {
                        providerDomainLine?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            text = serversLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!trafficSummary.isNullOrBlank() || !expireSummary.isNullOrBlank()) {
                        Text(
                            text = listOfNotNull(
                                trafficSummary?.takeIf { it.isNotBlank() },
                                expireSummary?.takeIf { it.isNotBlank() }
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    providerMetaLine?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    legendLine?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!providerLink.isNullOrBlank()) {
                    CompactActionIconButton(
                        onClick = {
                            val opened = openExternalLink(context = context, url = providerLink)
                            if (!opened) {
                                onTransientMessage("Не удалось открыть ссылку провайдера")
                            }
                        },
                        contentDescription = stringResource(R.string.subscription_open_provider_link)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource(R.string.subscription_open_provider_link)
                        )
                    }
                }
                CompactActionIconButton(
                    onClick = onRefresh,
                    contentDescription = stringResource(R.string.profiles_subscription_refresh)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.profiles_subscription_refresh)
                    )
                }
                CompactActionIconButton(
                    onClick = { menuExpanded = true },
                    contentDescription = stringResource(R.string.profiles_actions)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.profiles_actions)
                    )
                }
                CompactActionIconButton(
                    onClick = onToggleCollapse,
                    contentDescription = if (source.isCollapsed) {
                        stringResource(R.string.profiles_expand)
                    } else {
                        stringResource(R.string.profiles_collapse)
                    }
                ) {
                    Icon(
                        imageVector = if (source.isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = if (source.isCollapsed) {
                            stringResource(R.string.profiles_expand)
                        } else {
                            stringResource(R.string.profiles_collapse)
                        }
                    )
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                if (!providerLink.isNullOrBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.subscription_open_provider_link)) },
                        onClick = {
                            menuExpanded = false
                            val opened = openExternalLink(context = context, url = providerLink)
                            if (!opened) {
                                onTransientMessage("Не удалось открыть ссылку провайдера")
                            }
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profiles_subscription_refresh)) },
                    onClick = {
                        menuExpanded = false
                        onRefresh()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (source.isCollapsed) {
                                stringResource(R.string.profiles_expand)
                            } else {
                                stringResource(R.string.profiles_collapse)
                            }
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onToggleCollapse()
                    }
                )
            }

            AnimatedVisibility(visible = !source.isCollapsed) {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    if (profiles.isEmpty()) {
                        Text(
                            text = stringResource(R.string.home_subscription_group_empty),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        profiles.forEach { profile ->
                            CompactServerRow(
                                title = profile.displayName,
                                selected = profile.id == activeProfileId,
                                pingText = serverPingResults[profile.id],
                                onClick = { onSelectProfile(profile.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionActionButton(
    vpnStatus: VpnConnectionStatus,
    label: String,
    onClick: () -> Unit
) {
    val isConnected = vpnStatus == VpnConnectionStatus.CONNECTED
    val isConnecting = vpnStatus == VpnConnectionStatus.CONNECTING
    val targetColor = if (isConnected) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.86f)
    } else {
        MaterialTheme.colorScheme.primary
    }
    val animatedContainerColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 260),
        label = "connection_button_color"
    )

    val transition = rememberInfiniteTransition(label = "connection_button_transition")
    val connectingBorderAlpha by transition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.56f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "connection_button_connecting_border"
    )
    val connectedPulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.012f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1450),
            repeatMode = RepeatMode.Reverse
        ),
        label = "connection_button_connected_pulse"
    )

    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer {
                val scale = if (isConnected) connectedPulseScale else 1f
                scaleX = scale
                scaleY = scale
            },
        shape = MaterialTheme.shapes.medium,
        border = if (isConnecting) {
            BorderStroke(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = connectingBorderAlpha)
            )
        } else {
            null
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = animatedContainerColor,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

private fun openExternalLink(context: android.content.Context, url: String): Boolean {
    val normalized = url.trim()
    if (normalized.isBlank()) return false
    val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return false
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

private data class LegacyLabelTokens(
    val userId: String? = null,
    val planId: String? = null,
    val legendTokens: List<String> = emptyList()
)

private fun parseLegacySubscriptionLabel(raw: String?): LegacyLabelTokens {
    val normalized = raw?.trim().orEmpty()
    if (normalized.isBlank()) return LegacyLabelTokens()
    var userId: String? = null
    var planId: String? = null
    val legend = mutableListOf<String>()
    normalized.split('|', '•', ';').forEach { piece ->
        val token = piece.trim()
        if (token.isBlank()) return@forEach
        val lowered = token.lowercase(Locale.US)
        when {
            lowered == "quota" || lowered == "трафик" -> Unit
            lowered.startsWith("id=") -> userId = token.substringAfter('=').trim().ifBlank { null }
            lowered.startsWith("plan=") -> planId = token.substringAfter('=').trim().ifBlank { null }
            else -> legend += token
        }
    }
    return LegacyLabelTokens(userId = userId, planId = planId, legendTokens = legend)
}

private fun buildLegendLine(
    labelLine: String?,
    tags: List<String>,
    fallbackCandidates: List<String?>
): String? {
    val fromLabel = parseLegacySubscriptionLabel(labelLine).legendTokens
        .joinToString(" • ")
        .takeIf { it.isNotBlank() }
    if (!fromLabel.isNullOrBlank()) return fromLabel

    val fromTags = tags
        .map { it.trim() }
        .filter {
            it.isNotBlank() &&
                !it.equals("quota", ignoreCase = true) &&
                !it.equals("трафик", ignoreCase = true)
        }
        .joinToString(" • ")
        .takeIf { it.isNotBlank() }
    if (!fromTags.isNullOrBlank()) return fromTags

    fallbackCandidates.forEach { candidate ->
        val normalized = parseLegacySubscriptionLabel(candidate).legendTokens
            .joinToString(" • ")
            .takeIf { it.isNotBlank() }
        if (!normalized.isNullOrBlank()) return normalized
    }
    return null
}

@Composable
private fun CompactActionIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(34.dp)
    ) {
        content()
    }
}

private fun formatSubscriptionTraffic(metadata: com.privatevpn.app.profiles.model.SubscriptionMetadataPayload): String? {
    val used = metadata.usedTrafficBytes
    val total = metadata.trafficTotalBytes
    val remaining = metadata.resolvedTrafficRemainingBytes
    return when {
        used != null && total != null ->
            "Использовано ${formatBytesHumanReadable(used)} / ${formatBytesHumanReadable(total)}"

        remaining != null && total != null ->
            "Осталось ${formatBytesHumanReadable(remaining)} / ${formatBytesHumanReadable(total)}"

        remaining != null ->
            "Осталось ${formatBytesHumanReadable(remaining)}"

        else -> null
    }
}

private fun formatSubscriptionExpiry(metadata: com.privatevpn.app.profiles.model.SubscriptionMetadataPayload): String? {
    metadata.expireText?.takeIf { it.isNotBlank() }?.let { return "Истекает: $it" }
    val expireAt = metadata.expireAt ?: return null
    if (expireAt <= 0L) return null
    val dateText = runCatching {
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(expireAt * 1000L))
    }.getOrElse { expireAt.toString() }
    return "Истекает: $dateText"
}

private fun formatBytesHumanReadable(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    if (value < 1024.0) return "${value.toLong()} B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB")
    var scaled = value
    var unitIndex = -1
    while (scaled >= 1024.0 && unitIndex < units.lastIndex) {
        scaled /= 1024.0
        unitIndex += 1
    }
    return if (scaled >= 100 || scaled % 1.0 == 0.0) {
        String.format(Locale.US, "%.0f %s", scaled, units[unitIndex])
    } else {
        String.format(Locale.US, "%.1f %s", scaled, units[unitIndex])
    }
}

@Composable
private fun CompactServerRow(
    title: String,
    selected: Boolean,
    pingText: String?,
    onClick: () -> Unit
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .softClickable(onClick = onClick),
        color = containerColor,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            pingText?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (selected) {
                Text(
                    text = stringResource(R.string.home_server_selected),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SubscriptionStatusChip(
    status: SubscriptionSyncStatus,
    refreshing: Boolean
) {
    val (label, color) = when {
        refreshing -> stringResource(R.string.subscription_status_loading) to MaterialTheme.colorScheme.primary
        status == SubscriptionSyncStatus.SUCCESS -> stringResource(R.string.subscription_status_success) to MaterialTheme.colorScheme.primary
        status == SubscriptionSyncStatus.PARTIAL -> stringResource(R.string.subscription_status_partial) to MaterialTheme.colorScheme.tertiary
        status == SubscriptionSyncStatus.ERROR -> stringResource(R.string.subscription_status_error) to MaterialTheme.colorScheme.error
        status == SubscriptionSyncStatus.DISABLED -> stringResource(R.string.subscription_status_disabled) to MaterialTheme.colorScheme.outline
        status == SubscriptionSyncStatus.EMPTY -> stringResource(R.string.subscription_status_empty) to MaterialTheme.colorScheme.outline
        else -> stringResource(R.string.subscription_status_loading) to MaterialTheme.colorScheme.primary
    }
    InlineStatusLabel(text = label, color = color)
}
