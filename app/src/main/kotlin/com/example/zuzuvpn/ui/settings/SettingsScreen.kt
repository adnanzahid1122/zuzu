package com.example.zuzuvpn.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.zuzuvpn.BuildConfig
import com.example.zuzuvpn.R
import com.example.zuzuvpn.engine.EngineInfo
import com.example.zuzuvpn.ui.theme.ZuzuColors

@Composable
fun SettingsScreen(
    onOpenLicenses: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val engineVersion by produceState<Result<String>?>(initialValue = null) {
        value = EngineInfo.wireguardGoVersion(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )

            SettingsSection(stringResource(R.string.settings_section_app)) {
                SettingsRow(stringResource(R.string.settings_app_version), BuildConfig.VERSION_NAME)
                HorizontalDivider(color = ZuzuColors.NavyHigh)
                SettingsRow(stringResource(R.string.settings_app_id), BuildConfig.APPLICATION_ID)
            }

            SettingsSection(stringResource(R.string.settings_section_engine)) {
                val versionText = when (val v = engineVersion) {
                    null -> stringResource(R.string.settings_engine_loading)
                    else -> v.fold(
                        onSuccess = { it },
                        onFailure = { stringResource(R.string.settings_engine_error, it.message ?: it.javaClass.simpleName) },
                    )
                }
                SettingsRow(stringResource(R.string.settings_engine_version), versionText)
                Text(
                    stringResource(R.string.settings_engine_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = ZuzuColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            SettingsSection(stringResource(R.string.settings_section_legal)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onOpenLicenses)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.settings_licenses_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = ZuzuColors.TextSecondary)
                }
            }

            Text(
                stringResource(R.string.settings_account_note),
                style = MaterialTheme.typography.bodySmall,
                color = ZuzuColors.TextMuted,
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = ZuzuColors.ElectricBlueBright)
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = ZuzuColors.NavyRaised),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
