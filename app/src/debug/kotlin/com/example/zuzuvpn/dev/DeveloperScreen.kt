package com.example.zuzuvpn.dev

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.zuzuvpn.R
import com.example.zuzuvpn.engine.EngineInfo
import com.example.zuzuvpn.ui.servers.ServerEditorScreen
import com.example.zuzuvpn.ui.servers.ServerImportScreen
import com.example.zuzuvpn.ui.servers.ServerListScreen
import com.example.zuzuvpn.ui.servers.ServersEvent
import com.example.zuzuvpn.ui.servers.ServersViewModel
import com.example.zuzuvpn.ui.theme.ZuzuColors
import com.example.zuzuvpn.vpn.VpnState

private enum class DevRoute { List, Editor, Import }

/**
 * Debug-only: development servers (manual entry / ss:// import, stored encrypted on the device)
 * and native engine versions. Reached from Settings → Developer; absent from release builds.
 */
@Composable
internal fun DeveloperScreen(onClose: () -> Unit, onServerReady: () -> Unit) {
    val servers: ServersViewModel = viewModel()
    var route by remember { mutableStateOf(DevRoute.List) }
    val store by servers.servers.collectAsStateWithLifecycle()
    val selectedServerId by servers.selectedServerId.collectAsStateWithLifecycle()
    val vpn by servers.vpnState.collectAsStateWithLifecycle()
    val connected = vpn is VpnState.Connected || vpn is VpnState.Connecting

    BackHandler {
        when (route) {
            DevRoute.Editor -> servers.closeEditor().also { route = DevRoute.List }
            DevRoute.Import -> servers.closeImport().also { route = DevRoute.List }
            DevRoute.List -> onClose()
        }
    }
    LaunchedEffect(servers) {
        servers.uiEvents.collect { event ->
            when (event) {
                ServersEvent.SavedAndSelected -> {
                    route = DevRoute.List
                    onServerReady()
                }
                ServersEvent.BackToList -> route = DevRoute.List
            }
        }
    }

    when (route) {
        DevRoute.List -> ServerListScreen(
            servers = store.servers,
            selectedId = selectedServerId,
            loaded = store.loaded,
            connected = connected,
            onBack = onClose,
            onAdd = {
                servers.beginAdd()
                route = DevRoute.Editor
            },
            onImport = {
                servers.beginImport()
                route = DevRoute.Import
            },
            onSelect = servers::select,
            onEdit = { id ->
                servers.beginEdit(id)
                route = DevRoute.Editor
            },
            header = {
                DevelopmentNotice()
                EngineVersions()
            },
        )
        DevRoute.Editor -> {
            val editor by servers.editor.collectAsStateWithLifecycle()
            ServerEditorScreen(
                state = editor,
                connected = connected,
                onChange = servers::updateForm,
                onToggleKey = servers::toggleKeyVisible,
                onSave = servers::save,
                onDelete = servers::delete,
                onBack = {
                    servers.closeEditor()
                    route = DevRoute.List
                },
            )
        }
        DevRoute.Import -> {
            val import by servers.importUi.collectAsStateWithLifecycle()
            ServerImportScreen(
                state = import,
                onTextChange = servers::setImportText,
                onCheck = servers::checkImport,
                onUdpChange = servers::setImportUdp,
                onSave = servers::saveImport,
                onEdit = {
                    servers.editImport()
                    route = DevRoute.Editor
                },
                onBack = {
                    servers.closeImport()
                    route = DevRoute.List
                },
            )
        }
    }
}

/** Settings section (debug builds only) that opens [DeveloperScreen]. */
@Composable
internal fun DeveloperSettingsEntry(onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.dev_section).uppercase(), style = MaterialTheme.typography.labelMedium, color = ZuzuColors.Warning)
        Card(
            onClick = onOpen,
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = ZuzuColors.NavyRaised),
            border = BorderStroke(1.dp, ZuzuColors.Warning.copy(alpha = 0.35f)),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dev_entry), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.dev_entry_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = ZuzuColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun DevelopmentNotice() {
    Surface(
        color = ZuzuColors.Warning.copy(alpha = 0.08f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, ZuzuColors.Warning.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.dev_notice),
            style = MaterialTheme.typography.bodySmall,
            color = ZuzuColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** Loading these also proves the native libraries load on the device. */
@Composable
private fun EngineVersions() {
    val context = LocalContext.current
    val shadowsocks by produceState<Result<String>?>(null) { value = EngineInfo.shadowsocksVersion() }
    val hev by produceState<Result<String>?>(null) { value = EngineInfo.hevVersion() }
    val wireguard by produceState<Result<String>?>(null) { value = EngineInfo.wireguardGoVersion(context) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.settings_section_engine).uppercase(), style = MaterialTheme.typography.labelMedium, color = ZuzuColors.ElectricBlueBright)
        VersionRow(stringResource(R.string.settings_engine_shadowsocks), shadowsocks)
        VersionRow(stringResource(R.string.settings_engine_tun2socks), hev)
        VersionRow(stringResource(R.string.settings_engine_version), wireguard)
    }
}

@Composable
private fun VersionRow(label: String, version: Result<String>?) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            when (version) {
                null -> stringResource(R.string.settings_engine_loading)
                else -> version.fold({ it }, { stringResource(R.string.settings_engine_error, it.javaClass.simpleName) })
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
