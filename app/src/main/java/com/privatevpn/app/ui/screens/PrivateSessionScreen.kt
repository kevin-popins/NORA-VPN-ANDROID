package com.privatevpn.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.privatevpn.app.R
import com.privatevpn.app.private_session.InstalledAppInfo
import com.privatevpn.app.private_session.PrivateSessionUiState
import com.privatevpn.app.ui.components.AppSection
import com.privatevpn.app.ui.components.SectionTone
import com.privatevpn.app.ui.theme.AppSpacing
import kotlinx.coroutines.delay

@Composable
fun PrivateSessionScreen(
    state: PrivateSessionUiState,
    scrollToTopSignal: Int,
    onRefreshApps: () -> Unit,
    onSessionEnabledChange: (Boolean) -> Unit,
    onToggleTrustedApp: (String, Boolean) -> Unit
) {
    val listState = rememberLazyListState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        onRefreshApps()
    }
    LaunchedEffect(searchQuery) {
        delay(200)
        debouncedQuery = searchQuery.trim()
    }
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal > 0) {
            listState.animateScrollToItem(0)
        }
    }

    val filteredApps by remember(state.installedApps, debouncedQuery) {
        derivedStateOf {
            if (debouncedQuery.isBlank()) {
                state.installedApps
            } else {
                state.installedApps.filter { app ->
                    app.appName.contains(debouncedQuery, ignoreCase = true) ||
                        app.packageName.contains(debouncedQuery, ignoreCase = true)
                }
            }
        }
    }
    val selectedApps by remember(filteredApps, state.draftTrustedPackages) {
        derivedStateOf {
            filteredApps.filter { state.draftTrustedPackages.contains(it.packageName) }
        }
    }
    val unselectedApps by remember(filteredApps, state.draftTrustedPackages) {
        derivedStateOf {
            filteredApps.filterNot { state.draftTrustedPackages.contains(it.packageName) }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        item {
            AppSection(
                tone = SectionTone.Primary
            ) {
                Text(
                    text = stringResource(R.string.session_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            if (state.enabled) R.string.session_status_on else R.string.session_status_off
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = onSessionEnabledChange
                    )
                }
                Text(
                    text = stringResource(R.string.session_trusted_count, state.draftTrustedPackages.size),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (state.draftDirty) {
                    Text(
                        text = stringResource(R.string.session_changes_syncing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(text = stringResource(R.string.session_search_apps)) },
                    singleLine = true
                )
                IconButton(
                    onClick = onRefreshApps,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.session_refresh_apps)
                    )
                }
            }
        }

        item {
            Text(
                text = stringResource(
                    R.string.session_apps_count,
                    filteredApps.size,
                    state.installedApps.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (state.loadingInstalledApps) {
            item {
                Text(text = stringResource(R.string.session_apps_loading))
            }
        }

        if (!state.loadingInstalledApps && filteredApps.isEmpty()) {
            item {
                Text(text = stringResource(R.string.session_no_apps_found))
            }
        }

        if (selectedApps.isNotEmpty()) {
            item {
                SectionHeader(text = stringResource(R.string.session_selected_section))
            }
            items(selectedApps, key = { it.packageName }) { app ->
                AppSelectionRow(
                    app = app,
                    checked = true,
                    iconBytes = state.appIcons[app.packageName],
                    onToggle = onToggleTrustedApp
                )
            }
        }

        if (unselectedApps.isNotEmpty()) {
            item {
                SectionHeader(text = stringResource(R.string.session_all_apps_section))
            }
            items(unselectedApps, key = { it.packageName }) { app ->
                AppSelectionRow(
                    app = app,
                    checked = false,
                    iconBytes = state.appIcons[app.packageName],
                    onToggle = onToggleTrustedApp
                )
            }
        }

    }
}

@Composable
private fun SectionHeader(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun AppSelectionRow(
    app: InstalledAppInfo,
    checked: Boolean,
    iconBytes: ByteArray?,
    onToggle: (String, Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(app.packageName, !checked) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(iconBytes = iconBytes, appName = app.appName)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = AppSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(text = app.appName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Checkbox(
                checked = checked,
                onCheckedChange = { selected -> onToggle(app.packageName, selected) }
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun AppIcon(iconBytes: ByteArray?, appName: String) {
    val imageBitmap = remember(iconBytes) {
        iconBytes?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(36.dp)
            )
        } else {
            Surface(
                modifier = Modifier.size(36.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = appName.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.Unspecified
                    )
                }
            }
        }
    }
}
