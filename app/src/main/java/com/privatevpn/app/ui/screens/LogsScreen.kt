package com.privatevpn.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.privatevpn.app.R
import com.privatevpn.app.core.log.EventLogEntry
import com.privatevpn.app.core.log.LogLevel
import com.privatevpn.app.core.util.TimeFormatter
import com.privatevpn.app.ui.components.AppSection
import com.privatevpn.app.ui.components.SectionTone
import com.privatevpn.app.ui.theme.AppSpacing

@Composable
fun LogsScreen(
    logs: List<EventLogEntry>,
    levelToLabel: @Composable (LogLevel) -> String
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        if (logs.isEmpty()) {
            item {
                AppSection(tone = SectionTone.Primary) {
                    Text(
                        text = stringResource(R.string.logs_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            return@LazyColumn
        }

        items(logs, key = { it.id }) { entry ->
            LogRow(
                title = "${TimeFormatter.format(entry.timestampMs)} • ${levelToLabel(entry.level)}",
                message = entry.message
            )
        }
    }
}

@Composable
private fun LogRow(
    title: String,
    message: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
