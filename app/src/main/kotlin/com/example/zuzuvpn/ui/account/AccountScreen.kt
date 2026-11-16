package com.example.zuzuvpn.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.zuzuvpn.R
import com.example.zuzuvpn.delivery.AccountState
import com.example.zuzuvpn.delivery.SignOutReason
import com.example.zuzuvpn.ui.theme.ZuzuColors

/** Sign in / create account. Device enrollment happens automatically after a successful sign-in. */
@Composable
fun AccountScreen(onBack: () -> Unit, viewModel: AccountViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.formState.collectAsStateWithLifecycle()
    var register by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    // Not saveable: the password is never written to saved instance state.
    var password by androidx.compose.runtime.remember { mutableStateOf("") }

    // Signed in (enrollment done): return to Settings.
    LaunchedEffect(state) { if (state is AccountState.SignedIn) onBack() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, tint = ZuzuColors.TextSecondary)
                }
                Text(
                    stringResource(if (register) R.string.account_title_create else R.string.account_title_sign_in),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            val ended = (state as? AccountState.SignedOut)?.reason
            endedText(ended)?.let { Text(stringResource(it), color = ZuzuColors.Warning, style = MaterialTheme.typography.bodyMedium) }

            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = ZuzuColors.NavyRaised,
                unfocusedContainerColor = ZuzuColors.NavyRaised,
                focusedBorderColor = ZuzuColors.ElectricBlue,
                unfocusedBorderColor = ZuzuColors.Outline,
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.account_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.account_password)) },
                supportingText = if (register) ({ Text(stringResource(R.string.account_password_hint)) }) else null,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            form.error?.let { Text(stringResource(errorText(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = { viewModel.signIn(email, password, register) },
                enabled = !form.busy && email.isNotBlank() && password.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = ZuzuColors.ElectricBlue),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(stringResource(if (form.busy) R.string.account_working else if (register) R.string.account_create else R.string.account_sign_in))
            }
            TextButton(onClick = { register = !register }, enabled = !form.busy) {
                Text(stringResource(if (register) R.string.account_switch_to_sign_in else R.string.account_switch_to_create), color = ZuzuColors.ElectricBlueBright)
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.account_device_note), style = MaterialTheme.typography.bodySmall, color = ZuzuColors.TextMuted)
        }
    }

    if (form.limitDevices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::cancelReplace,
            title = { Text(stringResource(R.string.account_limit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pluralStringResource(R.plurals.account_limit_body, form.limit, form.limit))
                    form.limitDevices.forEach { device ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(device.label, style = MaterialTheme.typography.bodyMedium)
                                device.lastSeenAt?.let { Text(it.take(10), style = MaterialTheme.typography.bodySmall, color = ZuzuColors.TextMuted) }
                            }
                            TextButton(onClick = { viewModel.replace(device) }, enabled = !form.busy) { Text(stringResource(R.string.account_limit_replace)) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::cancelReplace) { Text(stringResource(R.string.account_limit_cancel)) } },
        )
    }
}

private fun endedText(reason: SignOutReason?): Int? = when (reason) {
    SignOutReason.DeviceRevoked -> R.string.account_ended_revoked
    SignOutReason.AccountDisabled -> R.string.account_ended_disabled
    SignOutReason.SessionExpired -> R.string.account_ended_expired
    SignOutReason.SignedOut, null -> null
}

private fun errorText(code: String): Int = when (code) {
    "INVALID_CREDENTIALS" -> R.string.account_error_invalid_credentials
    "EMAIL_IN_USE" -> R.string.account_error_email_in_use
    "WEAK_PASSWORD", "BAD_REQUEST" -> R.string.account_error_weak_password
    "ACCOUNT_DISABLED" -> R.string.account_error_disabled
    "TOO_MANY_REQUESTS" -> R.string.account_error_rate_limited
    "NETWORK" -> R.string.account_error_network
    "KEYSTORE" -> R.string.account_error_keystore
    else -> R.string.account_error_generic
}
