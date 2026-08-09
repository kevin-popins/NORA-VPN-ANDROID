package com.privatevpn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.privatevpn.app.private_session.SystemVpnIntegrationState
import com.privatevpn.app.R
import com.privatevpn.app.settings.SettingsState
import com.privatevpn.app.settings.SocksSettings
import com.privatevpn.app.ui.NotificationPermissionUiState
import com.privatevpn.app.ui.components.AppSection
import com.privatevpn.app.ui.components.InlineStatusLabel
import com.privatevpn.app.ui.components.SectionTone
import com.privatevpn.app.ui.theme.AppSpacing
import com.privatevpn.app.ui.theme.AppWarning
import java.security.SecureRandom

@Composable
fun SettingsScreen(
    settingsState: SettingsState,
    splitTunnelingEnabled: Boolean,
    systemVpnIntegration: SystemVpnIntegrationState,
    scrollToTopSignal: Int,
    focusSocksSignal: Int,
    onAutoConnectChanged: (Boolean) -> Unit,
    onVerboseLogsChanged: (Boolean) -> Unit,
    onSaveSocksSettings: (SocksSettings) -> Unit,
    notificationPermission: NotificationPermissionUiState,
    onRequestNotificationsPermission: () -> Unit,
    onAddTileClick: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenDns: () -> Unit,
    onOpenPrivateSession: () -> Unit,
    onOpenSystemVpnSettings: () -> Unit,
    onTransientMessage: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var socksEnabled by rememberSaveable { mutableStateOf(settingsState.socksSettings.enabled) }
    var socksPortText by rememberSaveable { mutableStateOf(settingsState.socksSettings.port.toString()) }
    var socksLogin by rememberSaveable { mutableStateOf(settingsState.socksSettings.login) }
    var socksPassword by rememberSaveable { mutableStateOf(settingsState.socksSettings.password) }
    var socksError by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(settingsState.socksSettings) {
        socksEnabled = settingsState.socksSettings.enabled
        socksPortText = settingsState.socksSettings.port.toString()
        socksLogin = settingsState.socksSettings.login
        socksPassword = settingsState.socksSettings.password
    }
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal > 0) {
            listState.animateScrollToItem(0)
        }
    }
    LaunchedEffect(focusSocksSignal) {
        if (focusSocksSignal > 0) {
            listState.animateScrollToItem(1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.padding(AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        item {
            AppSection(
                tone = SectionTone.Primary
            ) {
                Text(
                    text = stringResource(R.string.settings_block_general),
                    style = MaterialTheme.typography.titleSmall
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_autoconnect),
                    checked = settingsState.autoConnectOnLaunch,
                    onCheckedChange = onAutoConnectChanged
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_verbose_logs),
                    checked = settingsState.verboseLogs,
                    onCheckedChange = onVerboseLogsChanged
                )
            }
        }

        item {
            AppSection(
                tone = SectionTone.Secondary
            ) {
                Text(
                    text = stringResource(R.string.settings_block_socks),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.settings_socks_short_description),
                    style = MaterialTheme.typography.bodySmall
                )
                InlineStatusLabel(
                    text = stringResource(R.string.settings_split_warning),
                    color = if (splitTunnelingEnabled) AppWarning else MaterialTheme.colorScheme.onSurfaceVariant
                )

                SettingSwitchRow(
                    title = stringResource(R.string.settings_socks_enabled),
                    checked = socksEnabled,
                    onCheckedChange = {
                        socksEnabled = it
                        socksError = null
                    }
                )

                OutlinedTextField(
                    value = socksPortText,
                    onValueChange = {
                        socksPortText = it
                        socksError = null
                    },
                    label = { Text(text = stringResource(R.string.settings_socks_port)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = socksLogin,
                    onValueChange = {
                        socksLogin = it
                        socksError = null
                    },
                    label = { Text(text = stringResource(R.string.settings_socks_login)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = socksPassword,
                    onValueChange = {
                        socksPassword = it
                        socksError = null
                    },
                    label = { Text(text = stringResource(R.string.settings_socks_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                TextButton(
                    onClick = {
                        val generated = generateLocalhostSocksCredentials()
                        socksEnabled = true
                        socksLogin = generated.first
                        socksPassword = generated.second
                        socksError = null
                        onTransientMessage(context.getString(R.string.settings_socks_generated))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.settings_socks_generate_credentials))
                }

                if (!socksError.isNullOrBlank()) {
                    Text(
                        text = socksError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Button(
                    onClick = {
                        val port = socksPortText.toIntOrNull()
                        if (port == null || port !in 1..65535) {
                            socksError = context.getString(R.string.settings_socks_port_invalid)
                            onTransientMessage(socksError ?: "")
                            return@Button
                        }
                        if (socksEnabled && (socksLogin.isBlank() || socksPassword.isBlank())) {
                            socksError = context.getString(R.string.settings_socks_auth_required)
                            onTransientMessage(socksError ?: "")
                            return@Button
                        }

                        onSaveSocksSettings(
                            SocksSettings(
                                enabled = socksEnabled,
                                port = port,
                                login = socksLogin,
                                password = socksPassword
                            )
                        )
                        socksError = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.settings_socks_save))
                }
            }
        }

        item {
            AppSection(
                tone = SectionTone.Primary
            ) {
                Text(
                    text = stringResource(R.string.settings_block_notifications),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.settings_notifications_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = onRequestNotificationsPermission,
                    enabled = notificationPermission.supported && !notificationPermission.granted,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (notificationPermission.granted || !notificationPermission.supported) {
                            stringResource(R.string.settings_notifications_granted)
                        } else {
                            stringResource(R.string.settings_notifications_request)
                        }
                    )
                }
                if (notificationPermission.shouldOpenSystemSettings) {
                    Text(
                        text = stringResource(R.string.settings_notifications_open_settings_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(R.string.settings_tile_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onAddTileClick, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.settings_tile_add))
                }
            }
        }

        item {
            AppSection(
                tone = SectionTone.Primary
            ) {
                Text(
                    text = stringResource(R.string.settings_block_tools),
                    style = MaterialTheme.typography.titleSmall
                )
                Button(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.settings_open_logs))
                }
                Button(onClick = onOpenDns, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.settings_open_dns))
                }
                Button(onClick = onOpenPrivateSession, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.nav_private_session))
                }
            }
        }

        item {
            AppSection(
                tone = SectionTone.Secondary
            ) {
                Text(
                    text = stringResource(R.string.session_lockdown_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.session_lockdown_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = systemStateText(systemVpnIntegration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onOpenSystemVpnSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.session_open_system_vpn_settings))
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun systemStateText(state: SystemVpnIntegrationState): String {
    if (!state.readable) {
        return stringResource(R.string.session_lockdown_status_unavailable)
    }

    return if (state.privateVpnAlwaysOn && state.lockdownEnabled) {
        stringResource(R.string.session_lockdown_status_ready)
    } else {
        val alwaysOnPackage = state.alwaysOnPackage ?: stringResource(R.string.session_lockdown_none)
        stringResource(
            R.string.session_lockdown_status_partial,
            alwaysOnPackage,
            if (state.lockdownEnabled) stringResource(R.string.session_lockdown_on) else stringResource(R.string.session_lockdown_off)
        )
    }
}

private val socksCredentialRandom = SecureRandom()

private fun generateLocalhostSocksCredentials(): Pair<String, String> {
    val loginSuffix = randomStringFromAlphabet(length = 8, alphabet = LOGIN_ALPHABET)
    val password = randomStringFromAlphabet(length = 16, alphabet = PASSWORD_ALPHABET)
    return "user_$loginSuffix" to password
}

private fun randomStringFromAlphabet(length: Int, alphabet: String): String {
    if (length <= 0 || alphabet.isBlank()) return ""
    return buildString(length) {
        repeat(length) {
            val index = socksCredentialRandom.nextInt(alphabet.length)
            append(alphabet[index])
        }
    }
}

private const val LOGIN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
private const val PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
