package com.privatevpn.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.privatevpn.app.R
import com.privatevpn.app.profiles.model.ProfileType
import com.privatevpn.app.profiles.model.SubscriptionMetadataCodec
import com.privatevpn.app.profiles.model.SubscriptionSource
import com.privatevpn.app.profiles.model.SubscriptionSyncStatus
import com.privatevpn.app.profiles.model.VpnProfile
import com.privatevpn.app.settings.SocksSettings
import com.privatevpn.app.ui.components.AppSection
import com.privatevpn.app.ui.components.InlineStatusLabel
import com.privatevpn.app.ui.components.SectionTone
import com.privatevpn.app.ui.components.softClickable
import com.privatevpn.app.ui.theme.AppSpacing
import com.privatevpn.app.ui.theme.NoraAmber
import com.privatevpn.app.ui.theme.NoraDanger
import com.privatevpn.app.ui.theme.NoraInkElevated
import com.privatevpn.app.ui.theme.NoraLine
import com.privatevpn.app.ui.theme.NoraMuted
import com.privatevpn.app.ui.theme.NoraText
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProfilesScreen(
    profiles: List<VpnProfile>,
    subscriptions: List<SubscriptionSource>,
    refreshingSubscriptionIds: Set<String>,
    activeProfileId: String?,
    serverPingResults: Map<String, String>,
    pingInProgress: Boolean,
    errorMessage: String?,
    scrollToTopSignal: Int,
    focusActiveSignal: Int,
    socksSettings: SocksSettings,
    splitTunnelingEnabled: Boolean,
    onImportProfile: (String) -> Unit,
    onImportProfileFile: () -> Unit,
    onAddSubscription: (sourceUrl: String, displayName: String?) -> Unit,
    onRefreshSubscription: (subscriptionId: String) -> Unit,
    onRefreshAllSubscriptions: () -> Unit,
    onPingAllServers: () -> Unit,
    onToggleSubscriptionCollapse: (subscriptionId: String) -> Unit,
    onRenameSubscription: (subscriptionId: String, displayName: String) -> Unit,
    onDeleteSubscription: (subscriptionId: String) -> Unit,
    onSetSubscriptionAutoUpdate: (subscriptionId: String, enabled: Boolean) -> Unit,
    onSetSubscriptionInterval: (subscriptionId: String, minutes: Int) -> Unit,
    onSetSubscriptionEnabled: (subscriptionId: String, enabled: Boolean) -> Unit,
    onSetActiveProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onRenameProfile: (profileId: String, displayName: String) -> Unit,
    onClearError: () -> Unit,
    onTransientMessage: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val regularProfiles = remember(profiles) { profiles.filter { it.parentSubscriptionId == null } }
    val childrenBySubscription = remember(profiles) {
        profiles
            .filter { !it.parentSubscriptionId.isNullOrBlank() }
            .groupBy { it.parentSubscriptionId.orEmpty() }
            .mapValues { (_, value) -> value.sortedBy { it.sourceOrder } }
    }

    var showImportPanel by rememberSaveable { mutableStateOf(false) }
    var showSubscriptionPanel by rememberSaveable { mutableStateOf(false) }
    var inputText by rememberSaveable { mutableStateOf("") }
    var detailsProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var configProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var renameProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var subscriptionDetails by remember { mutableStateOf<SubscriptionSource?>(null) }
    var renameSubscription by remember { mutableStateOf<SubscriptionSource?>(null) }
    var editIntervalSubscription by remember { mutableStateOf<SubscriptionSource?>(null) }
    var deleteSubscription by remember { mutableStateOf<SubscriptionSource?>(null) }
    var handledFocusSignal by remember { mutableIntStateOf(0) }

    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal > 0) {
            listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(focusActiveSignal, activeProfileId, profiles, subscriptions) {
        if (
            focusActiveSignal <= 0 ||
            focusActiveSignal == handledFocusSignal ||
            activeProfileId == null
        ) return@LaunchedEffect
        val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: return@LaunchedEffect
        val parentSubscriptionId = activeProfile.parentSubscriptionId
        val parentSubscription = parentSubscriptionId?.let { id ->
            subscriptions.firstOrNull { it.id == id }
        }
        if (parentSubscriptionId != null && parentSubscription?.isCollapsed == true) {
            return@LaunchedEffect
        }
        val targetIndex = if (parentSubscriptionId != null) {
            subscriptions.indexOfFirst { it.id == parentSubscriptionId }
                .takeIf { it >= 0 }
                ?.plus(2)
        } else {
            val directHeaderIndex = 1 + if (subscriptions.isEmpty()) 0 else subscriptions.size + 1
            regularProfiles.indexOfFirst { it.id == activeProfileId }
                .takeIf { it >= 0 }
                ?.plus(directHeaderIndex + 1)
        }
        targetIndex?.let {
            listState.animateScrollToItem(it)
            handledFocusSignal = focusActiveSignal
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = AppSpacing.md, vertical = AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        item {
            Text(
                text = "Серверы",
                style = MaterialTheme.typography.headlineMedium,
                color = NoraText,
                fontWeight = FontWeight.Bold
            )
        }

        if (subscriptions.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ПОДПИСКИ",
                        style = MaterialTheme.typography.labelSmall,
                        color = NoraAmber,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedIconButton(onClick = onPingAllServers, enabled = !pingInProgress) {
                        Icon(Icons.Default.Speed, contentDescription = "Измерить пинг серверов", tint = NoraAmber)
                    }
                    Spacer(Modifier.size(8.dp))
                    OutlinedIconButton(onClick = onRefreshAllSubscriptions) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить все подписки", tint = NoraAmber)
                    }
                }
            }
            items(subscriptions, key = { it.id }) { source ->
                SubscriptionCard(
                    source = source,
                    profiles = childrenBySubscription[source.id].orEmpty(),
                    activeProfileId = activeProfileId,
                    refreshing = refreshingSubscriptionIds.contains(source.id),
                    onToggleCollapse = { onToggleSubscriptionCollapse(source.id) },
                    onRefresh = { onRefreshSubscription(source.id) },
                    onShowInfo = { subscriptionDetails = source },
                    onRename = { renameSubscription = source },
                    onDelete = { deleteSubscription = source },
                    onToggleEnabled = { onSetSubscriptionEnabled(source.id, it) },
                    onToggleAutoUpdate = { onSetSubscriptionAutoUpdate(source.id, it) },
                    onEditInterval = { editIntervalSubscription = source },
                    onSelectProfile = onSetActiveProfile,
                    onTransientMessage = onTransientMessage
                )
            }
        }

        if (showImportPanel) {
            item {
                ProfileImportPanel(
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onImportClick = {
                        onImportProfile(inputText)
                        inputText = ""
                        showImportPanel = false
                    },
                    onImportFileClick = {
                        onImportProfileFile()
                        showImportPanel = false
                    },
                    onClearClick = { inputText = "" }
                )
            }
        }

        if (showSubscriptionPanel) {
            item {
                SubscriptionAddPanel(
                    onAddSubscription = { sourceUrl, displayName ->
                        onAddSubscription(sourceUrl, displayName)
                        showSubscriptionPanel = false
                    }
                )
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onClearError) {
                            Text(text = stringResource(R.string.profiles_error_close))
                        }
                    }
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ПРЯМЫЕ ПРОФИЛИ",
                    style = MaterialTheme.typography.labelSmall,
                    color = NoraAmber,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${regularProfiles.size} ${if (regularProfiles.size == 1) "профиль" else "профиля"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NoraMuted
                )
            }
        }

        if (regularProfiles.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.profiles_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            items(regularProfiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    isActive = profile.id == activeProfileId,
                    pingText = serverPingResults[profile.id],
                    onSetActiveProfile = { onSetActiveProfile(profile.id) },
                    onDeleteProfile = { onDeleteProfile(profile.id) },
                    onOpenDetails = { detailsProfile = profile },
                    onOpenConfig = { configProfile = profile },
                    onRenameProfile = { renameProfile = profile }
                )
            }
        }

    }

    detailsProfile?.let { profile ->
        ProfileDetailsDialog(profile = profile, onDismiss = { detailsProfile = null })
    }

    configProfile?.let { profile ->
        ProfileConfigDialog(profile = profile, onDismiss = { configProfile = null })
    }

    renameProfile?.let { profile ->
        RenameProfileDialog(
            initialName = profile.displayName,
            title = stringResource(R.string.profiles_rename_title),
            fieldLabel = stringResource(R.string.profiles_rename_label),
            onDismiss = { renameProfile = null },
            onConfirm = { newName ->
                onRenameProfile(profile.id, newName)
                renameProfile = null
            }
        )
    }

    subscriptionDetails?.let { source ->
        SubscriptionDetailsDialog(
            source = source,
            onDismiss = { subscriptionDetails = null }
        )
    }

    renameSubscription?.let { source ->
        RenameProfileDialog(
            initialName = source.displayName,
            title = stringResource(R.string.profiles_subscription_rename_title),
            fieldLabel = stringResource(R.string.profiles_subscription_name_label),
            onDismiss = { renameSubscription = null },
            onConfirm = { newName ->
                onRenameSubscription(source.id, newName)
                renameSubscription = null
            }
        )
    }

    editIntervalSubscription?.let { source ->
        SubscriptionIntervalDialog(
            currentMinutes = source.updateIntervalMinutes,
            onDismiss = { editIntervalSubscription = null },
            onConfirm = { minutes ->
                onSetSubscriptionInterval(source.id, minutes)
                editIntervalSubscription = null
            }
        )
    }

    deleteSubscription?.let { source ->
        AlertDialog(
            onDismissRequest = { deleteSubscription = null },
            title = { Text(text = stringResource(R.string.profiles_subscription_delete_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.profiles_subscription_delete_message,
                        source.displayName
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSubscription(source.id)
                        deleteSubscription = null
                    }
                ) {
                    Text(text = stringResource(R.string.profiles_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteSubscription = null }) {
                    Text(text = stringResource(R.string.profiles_error_close))
                }
            }
        )
    }
}

@Composable
private fun SubscriptionAddPanel(
    onAddSubscription: (sourceUrl: String, displayName: String?) -> Unit
) {
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var showValidation by rememberSaveable { mutableStateOf(false) }
    val normalizedUrl = url.trim()
    val invalid = showValidation && normalizedUrl.isEmpty()

    AppSection(tone = SectionTone.Secondary) {
        Text(
            text = stringResource(R.string.profiles_subscription_panel_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = stringResource(R.string.profiles_subscription_panel_hint),
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                showValidation = false
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = stringResource(R.string.profiles_subscription_url_label)) },
            isError = invalid
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = stringResource(R.string.profiles_subscription_name_label)) }
        )
        if (invalid) {
            Text(
                text = stringResource(R.string.profiles_subscription_url_required),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Button(
            onClick = {
                if (normalizedUrl.isBlank()) {
                    showValidation = true
                } else {
                    onAddSubscription(normalizedUrl, name.trim().ifBlank { null })
                    url = ""
                    name = ""
                    showValidation = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(R.string.profiles_subscription_load))
        }
    }
}

@Composable
private fun SubscriptionCard(
    source: SubscriptionSource,
    profiles: List<VpnProfile>,
    activeProfileId: String?,
    refreshing: Boolean,
    onToggleCollapse: () -> Unit,
    onRefresh: () -> Unit,
    onShowInfo: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleAutoUpdate: (Boolean) -> Unit,
    onEditInterval: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onTransientMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    val metadata = remember(source.metadata) { SubscriptionMetadataCodec.decode(source.metadata) }
    val title = metadata.displayTitle ?: source.displayName
    val providerLink = metadata.preferredExternalUrl()
    val expirySummary = remember(metadata) { formatSubscriptionExpiryLine(metadata) }
    val serversLine = stringResource(
        R.string.profiles_subscription_servers_count,
        metadata.serverCount ?: profiles.size
    )
    val legendTokens = remember(metadata.labelLine) {
        parseSubscriptionLegendTokens(metadata.labelLine)
    }
    val resolvedUserId = metadata.userId ?: legendTokens.userId
    val resolvedPlanId = metadata.planId ?: legendTokens.planId
    val providerMetaLine = remember(metadata, resolvedUserId, resolvedPlanId) {
        buildList {
            resolvedUserId?.trim()?.takeIf { it.isNotBlank() }?.let { add("ID: $it") }
            resolvedPlanId?.trim()?.takeIf { it.isNotBlank() }?.let { add("#$it") }
            metadata.note
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.equals("quota", ignoreCase = true) &&
                        !it.equals("трафик", ignoreCase = true)
                }
                ?.let { add(it) }
        }.joinToString(" • ").takeIf { it.isNotBlank() }
    }
    val legendLine = buildSubscriptionLegendLine(
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
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = NoraInkElevated,
        border = BorderStroke(
            1.dp,
            if (source.enabled) NoraLine else NoraLine.copy(alpha = 0.48f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .softClickable(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            onClick = onToggleCollapse
                        ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = NoraAmber.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, NoraAmber.copy(alpha = 0.38f))
                ) {
                    Icon(
                        imageVector = if (source.isCollapsed) {
                            Icons.Default.KeyboardArrowDown
                        } else {
                            Icons.Default.KeyboardArrowUp
                        },
                        contentDescription = if (source.isCollapsed) {
                            "Раскрыть подписку"
                        } else {
                            "Свернуть подписку"
                        },
                        tint = NoraAmber,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = NoraText,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$serversLine · обновление ${source.updateIntervalMinutes / 60} ч",
                        style = MaterialTheme.typography.labelSmall,
                        color = NoraMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            SubscriptionUsageMeter(metadata = metadata)

            if (!source.isCollapsed) {
                expirySummary?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                providerMetaLine?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = NoraMuted,
                        maxLines = Int.MAX_VALUE
                    )
                }

                legendLine?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = NoraMuted
                    )
                }
            }

            HorizontalDivider(color = NoraLine.copy(alpha = 0.82f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SubscriptionActionIcon(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Refresh,
                    contentDescription = "Обновить подписку",
                    onClick = onRefresh,
                    enabled = !refreshing
                )
                SubscriptionActionIcon(
                    modifier = Modifier.weight(1f),
                    icon = if (source.isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = if (source.isCollapsed) "Открыть подписку" else "Скрыть подписку",
                    onClick = onToggleCollapse
                )
                SubscriptionActionIcon(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.MoreHoriz,
                    contentDescription = "Дополнительные действия",
                    onClick = { menuExpanded = true }
                )
                SubscriptionActionIcon(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Delete,
                    contentDescription = "Удалить подписку",
                    tone = ActionTone.Danger,
                    onClick = onDelete
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                providerLink?.let { link ->
                    DropdownMenuItem(
                        text = { Text("Открыть сайт") },
                        onClick = {
                            menuExpanded = false
                            if (!openExternalLink(context, link)) {
                                onTransientMessage("Не удалось открыть ссылку провайдера")
                            }
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profiles_subscription_action_info)) },
                    onClick = {
                        menuExpanded = false
                        onShowInfo()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profiles_action_rename)) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (source.enabled) {
                                stringResource(R.string.profiles_subscription_disable)
                            } else {
                                stringResource(R.string.profiles_subscription_enable)
                            }
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onToggleEnabled(!source.enabled)
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (source.autoUpdateEnabled) {
                                stringResource(R.string.profiles_subscription_auto_update_disable)
                            } else {
                                stringResource(R.string.profiles_subscription_auto_update_enable)
                            }
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onToggleAutoUpdate(!source.autoUpdateEnabled)
                    },
                    enabled = source.enabled
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profiles_subscription_set_interval)) },
                    onClick = {
                        menuExpanded = false
                        onEditInterval()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profiles_delete)) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }

            androidx.compose.animation.AnimatedVisibility(visible = !source.isCollapsed) {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    if (profiles.isEmpty()) {
                        Text(
                            text = stringResource(R.string.profiles_subscription_group_empty),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        profiles.forEach { profile ->
                            SubscriptionChildProfileRow(
                                profile = profile,
                                activeProfileId = activeProfileId,
                                onSelectProfile = onSelectProfile
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionChildProfileRow(
    profile: VpnProfile,
    activeProfileId: String?,
    onSelectProfile: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (profile.id == activeProfileId) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .softClickable { onSelectProfile(profile.id) }
                .heightIn(min = 44.dp)
                .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = profile.id == activeProfileId,
                onClick = { onSelectProfile(profile.id) }
            )
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (profile.id == activeProfileId) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProfileImportPanel(
    inputText: String,
    onInputChange: (String) -> Unit,
    onImportClick: () -> Unit,
    onImportFileClick: () -> Unit,
    onClearClick: () -> Unit
) {
    AppSection(tone = SectionTone.Secondary) {
        Text(
            text = stringResource(R.string.profiles_import_panel_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = stringResource(R.string.profiles_import_hint),
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp, max = 260.dp),
            minLines = 5,
            maxLines = Int.MAX_VALUE,
            label = { Text(text = stringResource(R.string.profiles_input_label)) }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onImportClick,
                enabled = inputText.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(R.string.profiles_import_run))
            }
            OutlinedIconButton(
                onClick = onImportFileClick,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.profiles_import_file),
                    modifier = Modifier.size(20.dp)
                )
            }
            OutlinedIconButton(
                onClick = onClearClick,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.profiles_clear_input),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: VpnProfile,
    isActive: Boolean,
    pingText: String?,
    onSetActiveProfile: () -> Unit,
    onDeleteProfile: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenConfig: () -> Unit,
    onRenameProfile: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val countryFlag = remember(profile.displayName) { countryFlagForProfileName(profile.displayName) }

    Surface(
        modifier = Modifier.fillMaxWidth().softClickable(onClick = onSetActiveProfile),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = NoraInkElevated,
        border = BorderStroke(1.dp, if (isActive) NoraAmber.copy(alpha = 0.78f) else NoraLine),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Surface(
                        modifier = Modifier.size(36.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(11.dp),
                        color = if (isActive) NoraAmber.copy(alpha = 0.16f) else NoraLine.copy(alpha = 0.45f)
                    ) {
                        if (countryFlag != null) {
                            Text(
                                text = countryFlag,
                                modifier = Modifier.padding(7.dp),
                                style = MaterialTheme.typography.titleMedium
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = if (isActive) NoraAmber else NoraMuted,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    Column {
                        Text(text = profile.displayName, style = MaterialTheme.typography.titleMedium, color = NoraText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = profileTypeLabel(profile.type),
                            style = MaterialTheme.typography.labelSmall,
                            color = NoraMuted
                        )
                    }
                }

            }

            if (isActive) {
                Text(
                    text = "Активный сервер",
                    style = MaterialTheme.typography.labelSmall,
                    color = NoraAmber,
                    fontWeight = FontWeight.SemiBold
                )
            }

            pingText?.let { value ->
                Text(
                    text = value,
                    color = NoraAmber,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WideServerAction(
                    modifier = Modifier.weight(1f),
                    label = if (isActive) "Выбран" else "Выбрать",
                    icon = Icons.Default.Storage,
                    onClick = onSetActiveProfile
                )
                WideServerAction(
                    modifier = Modifier.weight(1f),
                    label = "Подробнее",
                    icon = Icons.Default.Info,
                    onClick = onOpenDetails
                )
                WideServerAction(
                    modifier = Modifier.weight(1f),
                    label = "Ещё",
                    icon = Icons.Default.MoreVert,
                    onClick = { menuExpanded = true }
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profiles_action_rename)) },
                    onClick = { menuExpanded = false; onRenameProfile() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profiles_action_view_config)) },
                    onClick = { menuExpanded = false; onOpenConfig() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profiles_delete)) },
                    onClick = { menuExpanded = false; onDeleteProfile() }
                )
            }
        }
    }
}

@Composable
private fun SubscriptionDetailsDialog(
    source: SubscriptionSource,
    onDismiss: () -> Unit
) {
    val metadata = remember(source.metadata) {
        SubscriptionMetadataCodec.decode(source.metadata)
    }
    val diagnostics = metadata.diagnostics

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.profiles_error_close))
            }
        },
        title = { Text(text = stringResource(R.string.profiles_subscription_details_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
            ) {
                Text(text = stringResource(R.string.profiles_subscription_info_name, source.displayName))
                metadata.displayTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_title, it))
                }
                Text(text = stringResource(R.string.profiles_subscription_info_url, source.sourceUrl))
                Text(
                    text = stringResource(
                        R.string.profiles_subscription_info_status,
                        syncStatusLabel(source.syncStatus)
                    )
                )
                Text(
                    text = stringResource(
                        R.string.profiles_subscription_info_profiles,
                        source.profileCount
                    )
                )
                Text(
                    text = stringResource(
                        R.string.profiles_subscription_info_updated_at,
                        formatTimestamp(source.lastUpdatedAtMs)
                    )
                )
                Text(
                    text = stringResource(
                        R.string.profiles_subscription_info_success_at,
                        formatTimestamp(source.lastSuccessAtMs)
                    )
                )
                source.metadata?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = stringResource(
                            R.string.profiles_subscription_info_provider,
                            metadata.providerName ?: source.displayName
                        )
                    )
                }
                metadata.providerDomain?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_provider_domain, it))
                }
                metadata.providerSite?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_provider_site, it))
                }
                metadata.supportUrl?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_support_url, it))
                }
                metadata.profileWebPageUrl?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_profile_page_url, it))
                }
                metadata.announcementText?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_announcement, it))
                }
                metadata.planId?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_plan_id, it))
                }
                metadata.userId?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_user_id, it))
                }
                metadata.badgeText?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_badge, it))
                }
                metadata.note?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_note, it))
                }
                metadata.providerMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_provider_message, it))
                }
                if (metadata.tags.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.profiles_subscription_info_tags,
                            metadata.tags.joinToString(", ")
                        )
                    )
                }
                metadata.labelLine?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_label_line, it))
                }
                metadata.logoUrl?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_logo_url, it))
                }
                metadata.logoHint?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_logo_hint, it))
                }
                metadata.serverCount?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_server_count_meta, it))
                }
                formatSubscriptionTrafficLine(metadata)?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_traffic_summary, it))
                }
                formatSubscriptionExpiryLine(metadata)?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_expiry_summary, it))
                }
                metadata.clientMode?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_client_mode, it))
                }
                if (metadata.platformHints.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.profiles_subscription_info_platform_hints,
                            metadata.platformHints.joinToString(", ")
                        )
                    )
                }
                source.etag?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_etag, it))
                }
                source.lastModified?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_last_modified, it))
                }
                diagnostics?.httpStatusCode?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_http_status, it))
                }
                diagnostics?.contentType?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_content_type, it))
                }
                diagnostics?.responseBodyLength?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_body_length, it))
                }
                diagnostics?.detectedFormat?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_payload_format, it))
                }
                diagnostics?.compatibilityMode?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_compat_mode, it))
                }
                diagnostics?.selectedResponseSource?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_selected_source, it))
                }
                diagnostics?.selectedStatus?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_selected_status, it))
                }
                diagnostics?.selectedConnectableCount?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_selected_connectable, it))
                }
                diagnostics?.selectedMarkerCount?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_selected_marker, it))
                }
                diagnostics?.selectedBodySignature?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_selected_signature, it))
                }
                diagnostics?.selectedRawLineCount?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_selected_raw_lines, it))
                }
                diagnostics?.requestEndpoint?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_endpoint, it))
                }
                diagnostics?.endpointVariant?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_endpoint_variant, it))
                }
                diagnostics?.usedClientType?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_client_type, it))
                }
                diagnostics?.hwidActive?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_hwid_active, if (it) "true" else "false"))
                }
                diagnostics?.hwidNotSupported?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_hwid_not_supported, if (it) "true" else "false"))
                }
                diagnostics?.retryWithHwid?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_retry_hwid, if (it) "true" else "false"))
                }
                diagnostics?.discoveredEntries?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_found_entries, it))
                }
                diagnostics?.parsedProfiles?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_parsed_entries, it))
                }
                diagnostics?.savedProfiles?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_saved_entries, it))
                }
                diagnostics?.invalidEntries?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_invalid_entries, it))
                }
                diagnostics?.markerEntries?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_marker_entries, it))
                }
                diagnostics?.connectableEntries?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_connectable_entries, it))
                }
                diagnostics?.insertedProfiles?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_inserted_profiles, it))
                }
                diagnostics?.updatedProfiles?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_updated_profiles, it))
                }
                diagnostics?.filteredOutProfiles?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_filtered_profiles, it))
                }
                diagnostics?.duplicateCount?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_duplicate_count, it))
                }
                diagnostics?.headersSummary?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_http_headers, it))
                }
                diagnostics?.metadataHeadersReceived?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_metadata_headers, it))
                }
                diagnostics?.metadataExtracted?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_metadata_extracted, it))
                }
                diagnostics?.metadataIgnored?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_metadata_ignored, it))
                }
                diagnostics?.payloadPreview?.takeIf { it.isNotBlank() }?.let {
                    Text(text = stringResource(R.string.profiles_subscription_info_payload_preview, it))
                }
                diagnostics?.shortError?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = stringResource(R.string.profiles_subscription_info_short_error, it),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                source.lastError?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = stringResource(R.string.profiles_subscription_info_last_error, it),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}

@Composable
private fun ProfileDetailsDialog(
    profile: VpnProfile,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.profiles_error_close))
            }
        },
        title = { Text(text = stringResource(R.string.profiles_details)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = stringResource(R.string.profiles_type_label, profileTypeLabel(profile.type)))
                Text(text = stringResource(R.string.profiles_details_dns, profile.dnsServers.joinToString()))
                Text(
                    text = stringResource(
                        R.string.profiles_details_partial,
                        if (profile.isPartialImport) {
                            stringResource(R.string.profiles_partial_yes)
                        } else {
                            stringResource(R.string.profiles_partial_no)
                        }
                    )
                )
                if (profile.importWarnings.isNotEmpty()) {
                    Text(text = stringResource(R.string.profiles_details_warnings))
                    profile.importWarnings.forEach { warning ->
                        Text(text = "- $warning", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    )
}

@Composable
private fun ProfileConfigDialog(
    profile: VpnProfile,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var hideSecrets by rememberSaveable { mutableStateOf(true) }

    val configText = remember(profile, hideSecrets) {
        buildConfigView(profile = profile, hideSecrets = hideSecrets)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.profiles_error_close))
            }
        },
        title = { Text(text = stringResource(R.string.profiles_config_viewer_title, profile.displayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { hideSecrets = !hideSecrets },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (hideSecrets) {
                                stringResource(R.string.profiles_show_secrets)
                            } else {
                                stringResource(R.string.profiles_hide_secrets)
                            }
                        )
                    }
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(configText)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.profiles_copy_config))
                    }
                }

                Button(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, configText)
                        }
                        context.startActivity(
                            Intent.createChooser(
                                shareIntent,
                                context.getString(R.string.profiles_share_config)
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.profiles_share_config))
                }

                Text(
                    text = configText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    )
}

@Composable
private fun RenameProfileDialog(
    initialName: String,
    title: String,
    fieldLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var showValidation by rememberSaveable { mutableStateOf(false) }
    val invalid = showValidation && value.trim().isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        showValidation = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    isError = invalid,
                    label = { Text(text = fieldLabel) }
                )
                if (invalid) {
                    Text(
                        text = stringResource(R.string.profiles_rename_error_empty),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val normalized = value.trim()
                if (normalized.isEmpty()) {
                    showValidation = true
                } else {
                    onConfirm(normalized)
                }
            }) {
                Text(text = stringResource(R.string.profiles_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.profiles_error_close))
            }
        }
    )
}

@Composable
private fun SubscriptionIntervalDialog(
    currentMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var value by rememberSaveable(currentMinutes) { mutableStateOf(currentMinutes.toString()) }
    var showValidation by rememberSaveable { mutableStateOf(false) }
    val parsed = value.toIntOrNull()
    val invalid = showValidation && (parsed == null || parsed < 15 || parsed > 1440)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.profiles_subscription_set_interval)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it.filter { ch -> ch.isDigit() }
                        showValidation = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    isError = invalid,
                    label = { Text(text = stringResource(R.string.profiles_subscription_interval_label)) }
                )
                if (invalid) {
                    Text(
                        text = stringResource(R.string.profiles_subscription_interval_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (parsed == null || parsed < 15 || parsed > 1440) {
                        showValidation = true
                    } else {
                        onConfirm(parsed)
                    }
                }
            ) {
                Text(text = stringResource(R.string.profiles_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.profiles_error_close))
            }
        }
    )
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

@Composable
private fun CompactSubscriptionActionIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .background(NoraAmber.copy(alpha = 0.09f), androidx.compose.foundation.shape.RoundedCornerShape(11.dp))
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides NoraAmber
        ) { content() }
    }
}

private enum class ActionTone { Accent, Danger }

@Composable
private fun WideServerAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: ActionTone = ActionTone.Accent
) {
    val accent = if (tone == ActionTone.Danger) NoraDanger else NoraAmber
    Surface(
        modifier = modifier
            .height(48.dp)
            .softClickable(
                enabled = enabled,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                onClick = onClick
            ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = accent.copy(alpha = if (enabled) 0.10f else 0.04f),
        border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) 0.32f else 0.13f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            Text(
                text = label,
                color = accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SubscriptionActionIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: ActionTone = ActionTone.Accent
) {
    val accent = if (tone == ActionTone.Danger) NoraDanger else NoraAmber
    Surface(
        modifier = modifier
            .height(40.dp)
            .softClickable(
                enabled = enabled,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                onClick = onClick
            ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = accent.copy(alpha = if (enabled) 0.10f else 0.04f),
        border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) 0.32f else 0.13f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SubscriptionUsageMeter(
    metadata: com.privatevpn.app.profiles.model.SubscriptionMetadataPayload
) {
    val totalBytes = metadata.trafficTotalBytes?.takeIf { it > 0L }
    val usedBytes = metadata.usedTrafficBytes?.coerceAtLeast(0L) ?: 0L
    val unlimited = totalBytes == null
    val targetRatio = if (unlimited) 1f else {
        (usedBytes.toDouble() / totalBytes!!.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }
    val animatedRatio by animateFloatAsState(
        targetValue = targetRatio,
        animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing),
        label = "subscription_usage_fill"
    )
    val fillBrush = Brush.horizontalGradient(
        colors = when {
            unlimited -> listOf(Color(0xFF4FAE80), Color(0xFF88CFA5))
            targetRatio >= 0.85f -> listOf(NoraDanger, Color(0xFFFFAA73))
            else -> listOf(NoraAmber, Color(0xFFFFC15C))
        }
    )
    val usageLabel = if (unlimited) {
        "∞  Безлимитный трафик"
    } else {
        "${formatBytesForUi(usedBytes)} / ${formatBytesForUi(totalBytes)}"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(6.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(99.dp),
            color = NoraLine.copy(alpha = 0.84f)
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedRatio)
                        .background(fillBrush, androidx.compose.foundation.shape.RoundedCornerShape(99.dp))
                )
            }
        }
        Text(
            text = usageLabel,
            style = MaterialTheme.typography.labelSmall,
            color = NoraMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class CountryFlagDefinition(
    val flag: String,
    val aliases: Set<String>,
    val phrases: Set<String> = emptySet()
)

private val countryFlagDefinitions = listOf(
    CountryFlagDefinition("🇳🇱", setOf("nl", "нл", "netherlands", "nederland", "нидерланды", "голландия", "amsterdam", "амстердам", "rotterdam", "роттердам")),
    CountryFlagDefinition("🇩🇪", setOf("de", "deu", "ger", "germany", "deutschland", "германия", "berlin", "берлин", "frankfurt", "франкфурт", "munich", "мюнхен", "dusseldorf", "дюссельдорф")),
    CountryFlagDefinition("🇷🇺", setOf("ru", "rus", "russia", "россия", "рф", "moscow", "москва", "spb", "питер", "петербург", "санкт")),
    CountryFlagDefinition("🇫🇷", setOf("fr", "fra", "france", "франция", "paris", "париж")),
    CountryFlagDefinition("🇫🇮", setOf("fi", "fin", "finland", "финляндия", "helsinki", "хельсинки")),
    CountryFlagDefinition("🇬🇧", setOf("uk", "gb", "gbr", "england", "britain", "великобритания", "англия", "london", "лондон"), setOf("united kingdom")),
    CountryFlagDefinition("🇺🇸", setOf("us", "usa", "america", "америка", "сша", "newyork", "losangeles", "ny"), setOf("united states", "new york", "лос анджелес")),
    CountryFlagDefinition("🇮🇪", setOf("ie", "irl", "ireland", "ирландия", "dublin", "дублин")),
    CountryFlagDefinition("🇮🇹", setOf("it", "ita", "italy", "италия", "rome", "рим", "milan", "милан")),
    CountryFlagDefinition("🇪🇸", setOf("es", "esp", "spain", "испания", "madrid", "мадрид", "barcelona", "барселона")),
    CountryFlagDefinition("🇪🇪", setOf("ee", "est", "estonia", "эстония", "tallinn", "таллин")),
    CountryFlagDefinition("🇱🇹", setOf("lt", "ltu", "lithuania", "литва", "vilnius", "вильнюс")),
    CountryFlagDefinition("🇱🇻", setOf("lv", "lva", "latvia", "латвия", "riga", "рига")),
    CountryFlagDefinition("🇵🇱", setOf("pl", "pol", "poland", "польша", "warsaw", "варшава")),
    CountryFlagDefinition("🇸🇪", setOf("se", "swe", "sweden", "швеция", "stockholm", "стокгольм")),
    CountryFlagDefinition("🇳🇴", setOf("no", "nor", "norway", "норвегия", "oslo", "осло")),
    CountryFlagDefinition("🇨🇭", setOf("ch", "che", "switzerland", "швейцария", "zurich", "цюрих")),
    CountryFlagDefinition("🇦🇹", setOf("at", "aut", "austria", "австрия", "vienna", "вена")),
    CountryFlagDefinition("🇹🇷", setOf("tr", "tur", "turkey", "турция", "istanbul", "стамбул")),
    CountryFlagDefinition("🇸🇬", setOf("sg", "sgp", "singapore", "сингапур")),
    CountryFlagDefinition("🇯🇵", setOf("jp", "jpn", "japan", "япония", "tokyo", "токио")),
    CountryFlagDefinition("🇨🇦", setOf("ca", "can", "canada", "канада", "toronto", "торонто")),
    CountryFlagDefinition("🇨🇿", setOf("cz", "cze", "czech", "чехия", "prague", "прага")),
    CountryFlagDefinition("🇺🇦", setOf("ua", "ukr", "ukraine", "украина", "kyiv", "kiev", "киев")),
    CountryFlagDefinition("🇧🇾", setOf("by", "blr", "belarus", "беларусь", "минск", "minsk")),
    CountryFlagDefinition("🇰🇿", setOf("kz", "kaz", "kazakhstan", "казахстан", "almaty", "алматы")),
    CountryFlagDefinition("🇦🇪", setOf("ae", "uae", "dubai", "дубай", "emirates", "эмираты")),
    CountryFlagDefinition("🇮🇱", setOf("il", "isr", "israel", "израиль", "telaviv", "тельавив")),
    CountryFlagDefinition("🇵🇹", setOf("pt", "prt", "portugal", "португалия", "lisbon", "лиссабон")),
    CountryFlagDefinition("🇩🇰", setOf("dk", "dnk", "denmark", "дания", "copenhagen", "копенгаген")),
    CountryFlagDefinition("🇧🇪", setOf("be", "bel", "belgium", "бельгия", "brussels", "брюссель")),
    CountryFlagDefinition("🇷🇴", setOf("ro", "rou", "romania", "румыния", "bucharest", "бухарест")),
    CountryFlagDefinition("🇧🇬", setOf("bg", "bgr", "bulgaria", "болгария", "sofia", "софия")),
    CountryFlagDefinition("🇷🇸", setOf("rs", "srb", "serbia", "сербия", "belgrade", "белград")),
    CountryFlagDefinition("🇬🇷", setOf("gr", "grc", "greece", "греция", "athens", "афины")),
    CountryFlagDefinition("🇭🇺", setOf("hu", "hun", "hungary", "венгрия", "budapest", "будапешт"))
)

private fun countryFlagForProfileName(profileName: String): String? {
    val normalizedName = profileName.lowercase(Locale.ROOT)
    val tokens = Regex("[\\p{L}\\p{N}]+")
        .findAll(normalizedName)
        .map { it.value }
        .toSet()
    if (tokens.isEmpty()) return null
    return countryFlagDefinitions
        .filter { definition ->
            definition.aliases.any(tokens::contains) ||
                definition.phrases.any { phrase -> normalizedName.contains(phrase) }
        }
        .map(CountryFlagDefinition::flag)
        .singleOrNull()
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

private data class SubscriptionLegendTokens(
    val userId: String? = null,
    val planId: String? = null,
    val legendTokens: List<String> = emptyList()
)

private fun parseSubscriptionLegendTokens(raw: String?): SubscriptionLegendTokens {
    val normalized = raw?.trim().orEmpty()
    if (normalized.isBlank()) return SubscriptionLegendTokens()
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
    return SubscriptionLegendTokens(userId = userId, planId = planId, legendTokens = legend)
}

private fun buildSubscriptionLegendLine(
    labelLine: String?,
    tags: List<String>,
    fallbackCandidates: List<String?>
): String? {
    val fromLabel = parseSubscriptionLegendTokens(labelLine).legendTokens
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
        val normalized = parseSubscriptionLegendTokens(candidate).legendTokens
            .joinToString(" • ")
            .takeIf { it.isNotBlank() }
        if (!normalized.isNullOrBlank()) return normalized
    }

    return null
}

private fun formatSubscriptionTrafficLine(
    metadata: com.privatevpn.app.profiles.model.SubscriptionMetadataPayload
): String? {
    val used = metadata.usedTrafficBytes
    val total = metadata.trafficTotalBytes
    val remaining = metadata.resolvedTrafficRemainingBytes
    return when {
        used != null && total != null ->
            "Использовано ${formatBytesForUi(used)} / ${formatBytesForUi(total)}"

        remaining != null && total != null ->
            "Осталось ${formatBytesForUi(remaining)} / ${formatBytesForUi(total)}"

        remaining != null ->
            "Осталось ${formatBytesForUi(remaining)}"

        else -> null
    }
}

private fun formatSubscriptionExpiryLine(
    metadata: com.privatevpn.app.profiles.model.SubscriptionMetadataPayload
): String? {
    metadata.expireText?.takeIf { it.isNotBlank() }?.let { return "Истекает: $it" }
    val expireAt = metadata.expireAt ?: return null
    if (expireAt <= 0L) return null
    val dateText = runCatching {
        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(expireAt * 1000L))
    }.getOrElse { expireAt.toString() }
    return "Истекает: $dateText"
}

private fun formatBytesForUi(bytes: Long): String {
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
        String.format(java.util.Locale.US, "%.0f %s", scaled, units[unitIndex])
    } else {
        String.format(java.util.Locale.US, "%.1f %s", scaled, units[unitIndex])
    }
}

private fun formatTimestamp(value: Long?): String {
    value ?: return "-"
    val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return runCatching { format.format(Date(value)) }.getOrDefault(value.toString())
}

private fun syncStatusLabel(status: SubscriptionSyncStatus): String {
    return when (status) {
        SubscriptionSyncStatus.LOADING -> "loading"
        SubscriptionSyncStatus.SUCCESS -> "success"
        SubscriptionSyncStatus.PARTIAL -> "partial"
        SubscriptionSyncStatus.ERROR -> "error"
        SubscriptionSyncStatus.DISABLED -> "disabled"
        SubscriptionSyncStatus.EMPTY -> "empty"
    }
}

private fun buildConfigView(profile: VpnProfile, hideSecrets: Boolean): String {
    val rawPretty = when {
        profile.sourceRaw.trim().startsWith("{") -> prettyJson(profile.sourceRaw) ?: profile.sourceRaw
        else -> profile.sourceRaw
    }
    val normalizedPretty = profile.normalizedJson?.let { jsonOrText ->
        if (jsonOrText.trim().startsWith("{")) {
            prettyJson(jsonOrText) ?: jsonOrText
        } else {
            jsonOrText
        }
    }

    val rawVisible = if (hideSecrets) maskSecrets(rawPretty) else rawPretty
    val normalizedVisible = normalizedPretty?.let { if (hideSecrets) maskSecrets(it) else it }

    return buildString {
        appendLine("=== Исходный конфиг ===")
        appendLine(rawVisible)

        if (!normalizedVisible.isNullOrBlank()) {
            appendLine()
            appendLine("=== Нормализованное представление ===")
            append(normalizedVisible)
        }
    }
}

private fun prettyJson(text: String): String? {
    return runCatching { JSONObject(text).toString(2) }.getOrNull()
}

private fun maskSecrets(text: String): String {
    return text
        .replace(
            Regex("(?im)^\\s*(PrivateKey|PresharedKey|PublicKey|Password|H1|H2|H3|H4|I1|I2|I3|I4|I5)\\s*=\\s*.*$"),
            "\$1 = <скрыто>"
        )
        .replace(
            Regex("(?i)(\\\"(?:id|uuid|privateKey|publicKey|password|pass|presharedKey|shortId)\\\"\\s*:\\s*\\\")([^\\\"]*)(\\\")"),
            "\$1<скрыто>\$3"
        )
        .replace(Regex("(?i)(vless://)([^@]+)(@)"), "\$1<скрыто>\$3")
        .replace(Regex("(?i)([?&](?:pbk|publicKey|password|sid|shortId)=)([^&#]*)"), "\$1<скрыто>")
}

@Composable
private fun profileTypeLabel(type: ProfileType): String = when (type) {
    ProfileType.VLESS -> "VLESS"
    ProfileType.VMESS -> "VMESS"
    ProfileType.TROJAN -> "Trojan"
    ProfileType.XRAY_JSON -> "Xray JSON"
    ProfileType.XRAY_VLESS_REALITY -> "Xray VLESS + REALITY"
    ProfileType.AMNEZIA_WG_20 -> "AmneziaWG 2.0"
    ProfileType.KROT -> "KRot"
}

private fun socksStatusLabel(
    socksSettings: SocksSettings,
    splitTunnelingEnabled: Boolean
): String {
    if (splitTunnelingEnabled) return "Отключён"
    if (!socksSettings.enabled) return "Отключён"
    return if (socksSettings.login.isNotBlank() && socksSettings.password.isNotBlank()) {
        "Защищён"
    } else {
        "Не настроен"
    }
}
