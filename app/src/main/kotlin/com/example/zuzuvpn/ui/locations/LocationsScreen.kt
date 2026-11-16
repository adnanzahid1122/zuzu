package com.example.zuzuvpn.ui.locations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.zuzuvpn.R
import com.example.zuzuvpn.ui.components.DevelopmentTag
import com.example.zuzuvpn.ui.components.LoadIndicator
import com.example.zuzuvpn.ui.components.LocationBadge
import com.example.zuzuvpn.ui.components.MascotMood
import com.example.zuzuvpn.ui.components.RabbitMascot
import com.example.zuzuvpn.ui.components.LatencyIndicator
import com.example.zuzuvpn.BuildConfig
import com.example.zuzuvpn.data.servers.CountryGroup
import com.example.zuzuvpn.data.servers.DirectoryError
import com.example.zuzuvpn.data.servers.ServerDirectory
import com.example.zuzuvpn.data.servers.ServerInfo
import com.example.zuzuvpn.ui.components.PremiumTag
import com.example.zuzuvpn.vpn.VpnProtocol
import com.example.zuzuvpn.ui.theme.ZuzuColors
import com.example.zuzuvpn.ui.theme.ZuzuTheme

/** Stateful entry point. [onLocationChosen] runs after the selection is stored (e.g. go Home). */
@Composable
fun LocationsRoute(
    onLocationChosen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocationsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LocationsScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onRefresh = viewModel::onRefresh,
        onSelectServer = { id ->
            viewModel.onSelectServer(id)
            onLocationChosen()
        },
        onSelectCountry = { group ->
            if (viewModel.onSelectCountry(group)) onLocationChosen()
        },
        modifier = modifier,
    )
}

/**
 * Country list with flags, cities, availability and load (only when the source supplies them),
 * search, and a Recommended section when the source marks servers as recommended. Tapping a
 * country picks its best available server; countries with several servers also list each one.
 * Never shows addresses, ports, ciphers or credentials, and never invents data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen(
    state: LocationsUiState,
    onQueryChange: (String) -> Unit,
    onSelectServer: (String) -> Unit,
    onSelectCountry: (CountryGroup) -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val narrow = maxWidth < 340.dp
            val sidePadding = if (maxWidth < 360.dp) 12.dp else 16.dp
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
                    .imePadding(),
                contentPadding = PaddingValues(start = sidePadding, end = sidePadding, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "header") {
                    Text(
                        text = stringResource(R.string.locations_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                when (state.status) {
                    DirectoryStatus.Loading -> {
                        item(key = "loading") { CenteredNote(stringResource(R.string.locations_loading)) }
                        return@LazyColumn
                    }
                    DirectoryStatus.NotConfigured -> {
                        item(key = "unavailable") { UnavailableState() }
                        return@LazyColumn
                    }
                    DirectoryStatus.Failed -> {
                        item(key = "failed") { FailedState(state.error, retrying = state.isRefreshing, onRetry = onRefresh) }
                        return@LazyColumn
                    }
                    DirectoryStatus.Available -> Unit
                }
                if (state.error != null) {
                    item(key = "refresh-failed") { RefreshFailedBanner(retrying = state.isRefreshing, onRetry = onRefresh) }
                }
                if (state.hasDevelopment) {
                    item(key = "dev-note") { DevelopmentNotice() }
                }
                item(key = "search") {
                    LocationSearchField(
                        query = state.query,
                        onQueryChange = onQueryChange,
                        onDone = { focusManager.clearFocus() },
                    )
                }

                if (state.recommended.isNotEmpty()) {
                    item(key = "h-recommended") { SectionHeader(stringResource(R.string.locations_section_recommended)) }
                    items(state.recommended, key = { "rec-" + it.id }) { server ->
                        LocationRow(
                            countryCode = server.countryCode,
                            title = server.name,
                            subtitle = server.city ?: server.country.takeIf { it != server.name },
                            server = server,
                            selected = server.id == state.selectedId,
                            narrow = narrow,
                            onClick = { onSelectServer(server.id) },
                        )
                    }
                }

                item(key = "h-all") {
                    SectionHeader(
                        if (state.query.isBlank()) stringResource(R.string.locations_section_all)
                        else stringResource(R.string.locations_section_results, state.countries.size),
                    )
                }
                if (state.countries.isEmpty()) {
                    item(key = "empty") {
                        CenteredNote(
                            when {
                                state.query.isNotBlank() -> stringResource(R.string.locations_empty, state.query.trim())
                                BuildConfig.DEBUG -> stringResource(R.string.locations_none_debug)
                                else -> stringResource(R.string.locations_none)
                            },
                        )
                    }
                } else {
                    state.countries.forEach { group ->
                        val single = group.servers.singleOrNull()
                        item(key = "c-" + group.key) {
                            LocationRow(
                                countryCode = group.countryCode,
                                title = group.country,
                                subtitle = when {
                                    single != null -> single.city
                                    else -> pluralStringResource(R.plurals.locations_server_count, group.servers.size, group.servers.size)
                                },
                                server = single,
                                selected = group.servers.any { it.id == state.selectedId },
                                narrow = narrow,
                                onClick = { onSelectCountry(group) },
                                isDevelopment = group.isDevelopment,
                                enabled = group.availableCount > 0,
                            )
                        }
                        if (single == null) {
                            items(group.servers, key = { "s-" + it.id }) { server ->
                                LocationRow(
                                    countryCode = null,
                                    title = server.city ?: server.name,
                                    subtitle = server.name.takeIf { server.city != null && it != group.country },
                                    server = server,
                                    selected = server.id == state.selectedId,
                                    narrow = narrow,
                                    onClick = { onSelectServer(server.id) },
                                    indent = true,
                                    enabled = server.isAvailable,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.StringRes
private fun errorMessage(error: DirectoryError?): Int = when (error) {
    DirectoryError.Network -> R.string.locations_error_network
    DirectoryError.RateLimited -> R.string.locations_error_rate_limited
    DirectoryError.Server -> R.string.locations_error_server
    is DirectoryError.Http, DirectoryError.InvalidResponse, null -> R.string.locations_error_generic
}

/** The directory could not be loaded and there is nothing to show yet. Never shows fallback data. */
@Composable
private fun FailedState(error: DirectoryError?, retrying: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RabbitMascot(mood = MascotMood.Sleepy, modifier = Modifier.size(140.dp))
        Text(
            stringResource(R.string.locations_error_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(errorMessage(error)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onRetry, enabled = !retrying, border = BorderStroke(1.dp, ZuzuColors.ElectricBlue)) {
            Text(stringResource(R.string.locations_retry), color = ZuzuColors.ElectricBlueBright)
        }
    }
}

/** The latest refresh failed; the list below is the last one that loaded. */
@Composable
private fun RefreshFailedBanner(retrying: Boolean, onRetry: () -> Unit) {
    Surface(
        color = ZuzuColors.Warning.copy(alpha = 0.08f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, ZuzuColors.Warning.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.locations_refresh_failed),
                style = MaterialTheme.typography.bodySmall,
                color = ZuzuColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = onRetry, enabled = !retrying) {
                Text(stringResource(R.string.locations_retry), color = ZuzuColors.ElectricBlueBright)
            }
        }
    }
}

/** Deliberate state for builds without a location source. */
@Composable
private fun UnavailableState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RabbitMascot(mood = MascotMood.Sleepy, modifier = Modifier.size(140.dp))
        Text(
            stringResource(R.string.locations_unavailable_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.locations_unavailable_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CenteredNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(),
    )
}

/** Debug builds list development test servers; make clear they are not Zuzu locations. */
@Composable
private fun DevelopmentNotice() {
    Surface(
        color = ZuzuColors.Warning.copy(alpha = 0.08f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, ZuzuColors.Warning.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.locations_dev_notice),
            style = MaterialTheme.typography.bodySmall,
            color = ZuzuColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun LocationSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.locations_search_hint), maxLines = 1) },
        leadingIcon = {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = ZuzuColors.TextSecondary)
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.locations_search_clear),
                        tint = ZuzuColors.TextSecondary,
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onDone() }),
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = ZuzuColors.NavyRaised,
            unfocusedContainerColor = ZuzuColors.NavyRaised,
            focusedBorderColor = ZuzuColors.ElectricBlue,
            unfocusedBorderColor = ZuzuColors.Outline,
            cursorColor = ZuzuColors.ElectricBlueBright,
        ),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = ZuzuColors.ElectricBlueBright,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

/**
 * One country or server row. Selected rows get an electric-blue border, tint and a filled radio
 * mark. Load and availability appear only when [server] carries them (from the source).
 */
@Composable
fun LocationRow(
    countryCode: String?,
    title: String,
    subtitle: String?,
    server: ServerInfo?,
    selected: Boolean,
    narrow: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDevelopment: Boolean = server?.isDevelopment == true,
    indent: Boolean = false,
    enabled: Boolean = server?.isAvailable ?: true,
) {
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (indent) 28.dp else 0.dp)
            .clip(shape)
            .background(if (selected) ZuzuColors.ElectricBlue.copy(alpha = 0.12f) else ZuzuColors.NavyRaised)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) ZuzuColors.ElectricBlue else ZuzuColors.Outline.copy(alpha = 0.5f),
                shape = shape,
            )
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!indent) {
            LocationBadge(countryCode = countryCode, size = 38.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else ZuzuColors.TextMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (server?.isPremium == true && !narrow) {
                    Spacer(Modifier.width(6.dp))
                    PremiumTag()
                }
                if (isDevelopment && !narrow) {
                    Spacer(Modifier.width(6.dp))
                    DevelopmentTag()
                }
            }
            val detail = when {
                !enabled -> stringResource(R.string.server_unavailable)
                isDevelopment && subtitle == null -> stringResource(R.string.location_development_subtitle)
                else -> subtitle
            }
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (server != null && enabled && (server.latencyMs != null || server.loadPercent != null)) {
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                server.latencyMs?.let { LatencyIndicator(latencyMs = it, compact = true) }
                if (!narrow) server.loadPercent?.let { LoadIndicator(loadPercent = it) }
            }
        }
        Spacer(Modifier.width(10.dp))
        SelectionMark(selected = selected)
    }
}

/** Radio-style mark: outlined ring, filled electric-blue with a check when selected. */
@Composable
private fun SelectionMark(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) ZuzuColors.ElectricBlue else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.5.dp, if (selected) ZuzuColors.ElectricBlue else ZuzuColors.Outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = ZuzuColors.TextPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

// Preview-only data; never used at runtime.
private val previewServers = listOf(
    ServerInfo("p1", "Frankfurt", "Germany", "DE", "Frankfurt", VpnProtocol.Shadowsocks, isRecommended = true, loadPercent = 31),
    ServerInfo("p2", "Berlin", "Germany", "DE", "Berlin", VpnProtocol.Shadowsocks, loadPercent = 62),
    ServerInfo("p3", "Japan", "Japan", "JP", "Tokyo", VpnProtocol.Shadowsocks, isPremium = true),
)

@Preview(name = "Locations · 360dp", widthDp = 360, heightDp = 780, showBackground = true, backgroundColor = 0xFF060B1F)
@Composable
private fun LocationsPreview() {
    ZuzuTheme {
        LocationsScreen(
            state = LocationsUiState.from(ServerDirectory.Available(previewServers), "", "p1"),
            onQueryChange = {},
            onSelectServer = {},
            onSelectCountry = {},
        )
    }
}

@Preview(name = "Locations · unavailable", widthDp = 360, heightDp = 780, showBackground = true, backgroundColor = 0xFF060B1F)
@Composable
private fun LocationsUnavailablePreview() {
    ZuzuTheme {
        LocationsScreen(state = LocationsUiState(status = DirectoryStatus.NotConfigured), onQueryChange = {}, onSelectServer = {}, onSelectCountry = {})
    }
}
