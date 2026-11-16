package com.example.zuzuvpn.ui.settings

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.zuzuvpn.delivery.AccountState
import com.example.zuzuvpn.ui.account.AccountViewModel
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.zuzuvpn.BuildConfig
import com.example.zuzuvpn.R
import com.example.zuzuvpn.ui.theme.ZuzuColors

/**
 * Consumer settings. Contains no server, protocol or credential configuration.
 * [developerEntry] is provided only by debug builds.
 */
@Composable
fun SettingsScreen(
    onOpenLicenses: () -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
    developerEntry: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current

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

            AccountSection(onOpenAccount)

            SettingsSection(stringResource(R.string.settings_section_connection)) {
                LinkRow(
                    title = stringResource(R.string.settings_notifications),
                    summary = stringResource(R.string.settings_notifications_summary),
                    onClick = { openNotificationSettings(context) },
                )
            }

            SettingsSection(stringResource(R.string.settings_section_app)) {
                SettingsRow(stringResource(R.string.settings_app_version), BuildConfig.VERSION_NAME)
                HorizontalDivider(color = ZuzuColors.NavyHigh)
                SettingsRow(stringResource(R.string.settings_app_id), BuildConfig.APPLICATION_ID)
            }

            SettingsSection(stringResource(R.string.settings_section_legal)) {
                LinkRow(
                    title = stringResource(R.string.settings_licenses),
                    summary = stringResource(R.string.settings_licenses_summary),
                    onClick = onOpenLicenses,
                )
            }

            developerEntry?.invoke()
        }
    }
}

/** Signed out: opens sign-in. Signed in: shows the e-mail and offers sign out (deletes this device's keys). */
@Composable
private fun AccountSection(onOpenAccount: () -> Unit, viewModel: AccountViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.formState.collectAsStateWithLifecycle()
    SettingsSection(stringResource(R.string.settings_section_account)) {
        when (val s = state) {
            is AccountState.SignedIn -> {
                SettingsRow(stringResource(R.string.account_email), s.email)
                HorizontalDivider(color = ZuzuColors.NavyHigh)
                LinkRow(
                    title = stringResource(if (form.busy) R.string.account_working else R.string.account_sign_out),
                    summary = stringResource(R.string.account_sign_out_summary),
                    onClick = { if (!form.busy) viewModel.signOut() },
                )
            }
            AccountState.Loading -> SettingsRow(stringResource(R.string.settings_section_account), stringResource(R.string.account_working))
            is AccountState.SignedOut -> LinkRow(
                title = stringResource(R.string.account_sign_in),
                summary = stringResource(R.string.account_signed_out_summary),
                onClick = onOpenAccount,
            )
        }
    }
}

/** Opens Android's notification settings for this app (the ongoing VPN notification lives there). */
private fun openNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
private fun LinkRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = ZuzuColors.TextSecondary)
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
