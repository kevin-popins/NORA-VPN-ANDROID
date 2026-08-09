package com.privatevpn.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.privatevpn.app.R
import com.privatevpn.app.settings.DnsMode
import com.privatevpn.app.settings.DnsResolvedSource
import com.privatevpn.app.settings.DnsState
import com.privatevpn.app.ui.components.AppSection
import com.privatevpn.app.ui.components.SectionTone
import com.privatevpn.app.ui.theme.AppSpacing

@Composable
fun DnsScreen(
    dnsState: DnsState,
    onDnsModeSelected: (DnsMode) -> Unit,
    onSaveCustomDns: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var customDnsInput by rememberSaveable { mutableStateOf(dnsState.customServers.joinToString("\n")) }
    var validationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dnsState.customServers) {
        customDnsInput = dnsState.customServers.joinToString("\n")
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        item {
            AppSection(tone = SectionTone.Primary) {
                Text(
                    text = stringResource(R.string.dns_mode_current, dnsModeLabel(dnsState.mode)),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(
                        R.string.dns_source_current,
                        dnsSourceLabel(dnsState.resolvedSource, dnsState.activeProfileName)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            AppSection(tone = SectionTone.Secondary) {
                Text(
                    text = stringResource(R.string.dns_title),
                    style = MaterialTheme.typography.titleSmall
                )
                DnsModeOption(
                    selected = dnsState.mode == DnsMode.FROM_PROFILE,
                    label = stringResource(R.string.dns_mode_from_profile),
                    onClick = { onDnsModeSelected(DnsMode.FROM_PROFILE) }
                )
                DnsModeOption(
                    selected = dnsState.mode == DnsMode.APP_DEFAULT,
                    label = stringResource(R.string.dns_mode_app_default),
                    onClick = { onDnsModeSelected(DnsMode.APP_DEFAULT) }
                )
                DnsModeOption(
                    selected = dnsState.mode == DnsMode.CUSTOM,
                    label = stringResource(R.string.dns_mode_custom),
                    onClick = { onDnsModeSelected(DnsMode.CUSTOM) }
                )
            }
        }

        item {
            AppSection(tone = SectionTone.Secondary) {
                Text(
                    text = stringResource(R.string.dns_effective_servers_title),
                    style = MaterialTheme.typography.titleSmall
                )
                if (dnsState.resolvedServers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_info_ip_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    dnsState.resolvedServers.forEach { server ->
                        Text(
                            text = stringResource(R.string.dns_server_item, server),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            AppSection(tone = SectionTone.Primary) {
                Text(
                    text = stringResource(R.string.dns_custom_input_label),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.dns_custom_input_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = customDnsInput,
                    onValueChange = {
                        customDnsInput = it
                        validationError = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 132.dp, max = 260.dp),
                    minLines = 4,
                    maxLines = Int.MAX_VALUE
                )

                if (!validationError.isNullOrBlank()) {
                    Text(
                        text = validationError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Button(
                    onClick = {
                        val parsed = customDnsInput
                            .split('\n', ',', ';')
                            .map { it.trim() }
                            .filter { it.isNotBlank() }

                        val invalid = parsed.firstOrNull { !isValidIpv4(it) }
                        if (invalid != null) {
                            validationError = context.getString(R.string.dns_invalid_ipv4, invalid)
                            return@Button
                        }

                        onSaveCustomDns(parsed)
                        validationError = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.dns_save_custom))
                }
            }
        }
    }
}

@Composable
private fun DnsModeOption(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AppSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun dnsModeLabel(mode: DnsMode): String = when (mode) {
    DnsMode.FROM_PROFILE -> stringResource(R.string.dns_mode_from_profile)
    DnsMode.APP_DEFAULT -> stringResource(R.string.dns_mode_app_default)
    DnsMode.CUSTOM -> stringResource(R.string.dns_mode_custom)
}

@Composable
private fun dnsSourceLabel(source: DnsResolvedSource, activeProfileName: String?): String = when (source) {
    DnsResolvedSource.PROFILE -> stringResource(
        R.string.dns_source_profile,
        activeProfileName ?: stringResource(R.string.dns_profile_unknown)
    )

    DnsResolvedSource.PROFILE_FALLBACK_DEFAULT -> stringResource(R.string.dns_source_profile_fallback)
    DnsResolvedSource.APP_DEFAULT -> stringResource(R.string.dns_source_app_default)
    DnsResolvedSource.CUSTOM -> stringResource(R.string.dns_source_custom)
    DnsResolvedSource.CUSTOM_FALLBACK_DEFAULT -> stringResource(R.string.dns_source_custom_fallback)
}

private fun isValidIpv4(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4) return false

    return parts.all { part ->
        if (part.isBlank()) return@all false
        if (part.length > 1 && part.startsWith('0')) return@all false
        val number = part.toIntOrNull() ?: return@all false
        number in 0..255
    }
}
