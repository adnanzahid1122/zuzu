package com.example.zuzuvpn.ui.servers

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zuzuvpn.R
import com.example.zuzuvpn.data.servers.CountryCodes
import com.example.zuzuvpn.data.servers.ServerInfo
import com.example.zuzuvpn.ui.components.MascotMood
import com.example.zuzuvpn.ui.components.RabbitMascot
import com.example.zuzuvpn.ui.theme.ZuzuColors
import com.zuzuvpn.shadowsocks.ProfileProblem
import com.zuzuvpn.shadowsocks.ShadowsocksProfile
import com.zuzuvpn.shadowsocks.ShadowsocksUriException

// ---------------------------------------------------------------------------------------------
// Server list
// ---------------------------------------------------------------------------------------------

@Composable
fun ServerListScreen(
    servers: List<ServerInfo>,
    selectedId: String?,
    loaded: Boolean,
    connected: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        ScreenHeader(R.string.servers_title, onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                header()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onAdd, modifier = Modifier.weight(1f), colors = primaryButtonColors()) {
                        Text(stringResource(R.string.servers_add), maxLines = 1)
                    }
                    OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, ZuzuColors.Outline)) {
                        Text(stringResource(R.string.servers_import), maxLines = 1, color = ZuzuColors.ElectricBlueBright)
                    }
                }
                if (connected) Note(stringResource(R.string.servers_active_note))

                if (loaded && servers.isEmpty()) {
                    EmptyServers()
                } else {
                    servers.forEach { server ->
                        ServerRow(
                            server = server,
                            selected = server.id == selectedId,
                            onSelect = { onSelect(server.id) },
                            onEdit = { onEdit(server.id) },
                        )
                    }
                }
                Note(stringResource(R.string.servers_storage_note))
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: ServerInfo,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = ZuzuColors.NavyRaised),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) ZuzuColors.ElectricBlue else ZuzuColors.Outline.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val selectLabel = stringResource(R.string.servers_select_cd, server.name)
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(selectedColor = ZuzuColors.ElectricBlueBright, unselectedColor = ZuzuColors.TextMuted),
                modifier = Modifier.semanticsLabel(selectLabel),
            )
            Column(Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(server.countryCode?.let { CountryCodes.flagEmoji(it) + " " + server.country }, server.city, server.protocol.name)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Text(stringResource(R.string.servers_selected), style = MaterialTheme.typography.labelSmall, color = ZuzuColors.ElectricBlueBright)
                }
            }
            IconButton(onClick = onEdit) {
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = stringResource(R.string.servers_edit_cd, server.name),
                    tint = ZuzuColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun EmptyServers() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RabbitMascot(mood = MascotMood.Sleepy, modifier = Modifier.size(120.dp))
        Text(stringResource(R.string.servers_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.servers_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Editor (add / edit)
// ---------------------------------------------------------------------------------------------

@Composable
fun ServerEditorScreen(
    state: EditorUiState,
    connected: Boolean,
    onChange: ((ServerForm) -> ServerForm) -> Unit,
    onToggleKey: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val form = state.form

    Column(modifier.fillMaxSize().imePadding()) {
        ScreenHeader(if (state.editingId == null) R.string.ss_editor_add_title else R.string.ss_editor_edit_title, onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.loadFailed) {
                    Text(stringResource(R.string.ss_error_load), color = ZuzuColors.Warning)
                    return@Column
                }
                ZuzuField(
                    value = form.name,
                    onValueChange = { v -> onChange { it.copy(name = v) } },
                    label = R.string.ss_editor_name,
                    supporting = stringResource(R.string.ss_editor_name_hint),
                )
                ZuzuField(
                    value = form.host,
                    onValueChange = { v -> onChange { it.copy(host = v) } },
                    label = R.string.ss_editor_host,
                    error = state.errors[FormField.Host],
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                )
                ZuzuField(
                    value = form.port,
                    onValueChange = { v -> onChange { it.copy(port = v.filter(Char::isDigit).take(5)) } },
                    label = R.string.ss_editor_port,
                    error = state.errors[FormField.Port],
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                )
                CipherSelector(
                    selected = form.method,
                    error = state.errors[FormField.Method],
                    onSelect = { m -> onChange { it.copy(method = m) } },
                )
                val is2022 = ShadowsocksProfile.isAead2022(form.method)
                ZuzuField(
                    value = form.key,
                    onValueChange = { v -> onChange { it.copy(key = v) } },
                    label = if (is2022) R.string.ss_editor_key else R.string.ss_editor_password,
                    supporting = stringResource(if (is2022) R.string.ss_editor_key_hint else R.string.ss_editor_password_hint),
                    error = state.errors[FormField.Key],
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    hidden = !state.keyVisible,
                    trailing = {
                        TextButton(onClick = onToggleKey) {
                            Text(stringResource(if (state.keyVisible) R.string.ss_editor_key_hide else R.string.ss_editor_key_show))
                        }
                    },
                )
                UdpSwitch(form.udpEnabled) { enabled -> onChange { it.copy(udpEnabled = enabled) } }

                Button(
                    onClick = onSave,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = primaryButtonColors(),
                ) { Text(stringResource(R.string.ss_editor_save)) }
                if (state.saveFailed) Text(stringResource(R.string.ss_error_save), color = ZuzuColors.Warning)
                if (state.editingId != null) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ss_editor_delete), color = ZuzuColors.Danger)
                    }
                }
                if (connected) Note(stringResource(R.string.ss_editor_reconnect_note))
                Note(stringResource(R.string.ss_editor_storage_note))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.ss_delete_title)) },
            text = { Text(stringResource(R.string.ss_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.ss_editor_delete), color = ZuzuColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.ss_editor_cancel)) } },
            containerColor = ZuzuColors.NavyRaised,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// ss:// import
// ---------------------------------------------------------------------------------------------

@Composable
fun ServerImportScreen(
    state: ImportUiState,
    onTextChange: (String) -> Unit,
    onCheck: () -> Unit,
    onUdpChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().imePadding()) {
        ScreenHeader(R.string.ss_import_title, onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The link contains the key, so it is masked like a password field.
                ZuzuField(
                    value = state.text,
                    onValueChange = onTextChange,
                    label = R.string.ss_editor_import_label,
                    supporting = stringResource(R.string.ss_editor_import_hint),
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    hidden = true,
                    error = null,
                    errorText = importErrorText(state.outcome),
                )
                Button(
                    onClick = onCheck,
                    enabled = state.text.isNotBlank() && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = primaryButtonColors(),
                ) { Text(stringResource(R.string.ss_import_parse)) }

                (state.outcome as? ServerImport.Outcome.Success)?.let { success ->
                    ImportPreviewCard(success.preview)
                    UdpSwitch(state.udpEnabled, onUdpChange)
                    Note(stringResource(R.string.ss_import_udp_note))
                    Button(onClick = onSave, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), colors = primaryButtonColors()) {
                        Text(stringResource(R.string.ss_editor_save))
                    }
                    if (state.saveFailed) Text(stringResource(R.string.ss_error_save), color = ZuzuColors.Warning)
                    OutlinedButton(onClick = onEdit, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ZuzuColors.Outline)) {
                        Text(stringResource(R.string.ss_import_edit), color = ZuzuColors.ElectricBlueBright)
                    }
                }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ss_editor_cancel)) }
            }
        }
    }
}

@Composable
private fun ImportPreviewCard(preview: ImportPreview) {
    val p = preview.profile
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = ZuzuColors.NavyRaised),
        border = BorderStroke(1.dp, ZuzuColors.ElectricBlue),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ss_import_preview).uppercase(), style = MaterialTheme.typography.labelMedium, color = ZuzuColors.ElectricBlueBright)
            PreviewRow(R.string.ss_editor_name, p.name)
            PreviewRow(R.string.ss_editor_host, p.host)
            PreviewRow(R.string.ss_editor_port, p.port.toString())
            PreviewRow(R.string.ss_editor_method, p.method)
            PreviewRow(
                if (preview.isPassword) R.string.ss_editor_password else R.string.ss_editor_key,
                if (preview.isPassword) {
                    stringResource(R.string.ss_import_password_hidden)
                } else if (preview.keyCount > 1) {
                    pluralStringResource(R.plurals.ss_import_key_chain, preview.keyCount, preview.keyCount)
                } else {
                    stringResource(R.string.ss_import_key_single, preview.keyBytes)
                },
            )
        }
    }
}

@Composable
private fun PreviewRow(@StringRes label: Int, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = ZuzuColors.TextSecondary, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun importErrorText(outcome: ServerImport.Outcome?): String? = when (outcome) {
    null, is ServerImport.Outcome.Success -> null
    is ServerImport.Outcome.UriError -> stringResource(
        when (outcome.reason) {
            ShadowsocksUriException.Reason.PluginNotSupported -> R.string.ss_error_uri_plugin
            ShadowsocksUriException.Reason.MissingCredentials, ShadowsocksUriException.Reason.MissingHost -> R.string.ss_error_uri_missing
            ShadowsocksUriException.Reason.InvalidPort -> R.string.ss_error_uri_port
            ShadowsocksUriException.Reason.NotShadowsocksUri, ShadowsocksUriException.Reason.Malformed -> R.string.ss_error_uri
        },
    )
    is ServerImport.Outcome.InvalidProfile -> stringResource(problemText(outcome.problem))
}

// ---------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------

@StringRes
fun problemText(problem: ProfileProblem): Int = when (problem) {
    ProfileProblem.EmptyHost, ProfileProblem.InvalidHost -> R.string.ss_error_host
    ProfileProblem.InvalidPort -> R.string.ss_error_port
    ProfileProblem.UnsupportedMethod -> R.string.ss_error_method
    ProfileProblem.EmptyKey -> R.string.ss_error_key_empty
    ProfileProblem.InvalidKeyEncoding -> R.string.ss_error_key_encoding
    ProfileProblem.WrongKeyLength -> R.string.ss_error_key_length
    ProfileProblem.InvalidPasswordCharacters -> R.string.ss_error_password_chars
}

/** The supported ciphers as radio options; the recommended AEAD-2022 cipher comes first. */
@Composable
private fun CipherSelector(selected: String, error: ProfileProblem?, onSelect: (String) -> Unit) {
    Column {
        Text(stringResource(R.string.ss_editor_method), style = MaterialTheme.typography.titleSmall)
        ShadowsocksProfile.SUPPORTED_METHODS.forEach { method ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = method == selected, role = Role.RadioButton, onClick = { onSelect(method) })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = method == selected,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = ZuzuColors.ElectricBlueBright, unselectedColor = ZuzuColors.TextMuted),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(method, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(if (ShadowsocksProfile.isAead2022(method)) R.string.ss_cipher_2022_summary else R.string.ss_cipher_classic_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (error != null) Text(stringResource(problemText(error)), style = MaterialTheme.typography.bodySmall, color = ZuzuColors.Warning)
    }
}

@Composable
private fun ScreenHeader(@StringRes title: Int, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
        }
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ZuzuField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    error: ProfileProblem? = null,
    errorText: String? = error?.let { stringResource(problemText(it)) },
    supporting: String? = null,
    keyboard: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    readOnly: Boolean = false,
    hidden: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) },
        singleLine = true,
        readOnly = readOnly,
        isError = errorText != null,
        supportingText = (errorText ?: supporting)?.let { text -> { Text(text) } },
        keyboardOptions = keyboard,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = trailing,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = ZuzuColors.NavyRaised,
            unfocusedContainerColor = ZuzuColors.NavyRaised,
            focusedBorderColor = ZuzuColors.ElectricBlue,
            unfocusedBorderColor = ZuzuColors.Outline,
            cursorColor = ZuzuColors.ElectricBlueBright,
            errorBorderColor = ZuzuColors.Warning,
            errorSupportingTextColor = ZuzuColors.Warning,
            errorLabelColor = ZuzuColors.Warning,
        ),
    )
}

@Composable
private fun UdpSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ss_editor_udp), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.ss_editor_udp_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = ZuzuColors.ElectricBlue),
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = ZuzuColors.TextMuted)
}

@Composable
private fun primaryButtonColors() = ButtonDefaults.buttonColors(containerColor = ZuzuColors.ElectricBlue)

private fun Modifier.semanticsLabel(label: String): Modifier = semantics { contentDescription = label }
