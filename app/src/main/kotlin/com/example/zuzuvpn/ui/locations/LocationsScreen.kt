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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.zuzuvpn.R
import com.example.zuzuvpn.ui.components.DemoBadge
import com.example.zuzuvpn.ui.components.FlagBadge
import com.example.zuzuvpn.ui.components.LatencyIndicator
import com.example.zuzuvpn.ui.components.SampleTag
import com.example.zuzuvpn.ui.home.SampleServers
import com.example.zuzuvpn.ui.home.ServerLocation
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
        onSelect = { id ->
            viewModel.onSelect(id)
            onLocationChosen()
        },
        modifier = modifier,
    )
}

/**
 * Location picker with search, a Recommended section and the full list.
 * Every entry is SAMPLE data: latency reads "— ms" and status reads "Not checked".
 */
@Composable
fun LocationsScreen(
    state: LocationsUiState,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // Below ~340dp the per-row status label is dropped (the grey dot stays).
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.locations_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.isDemo) DemoBadge()
                }
            }
            if (state.isDemo) {
                item(key = "demo-note") { DemoNotice() }
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
                items(state.recommended, key = { "rec-" + it.id }) { location ->
                    LocationRow(
                        location = location,
                        selected = location.id == state.selectedId,
                        narrow = narrow,
                        onClick = { onSelect(location.id) },
                    )
                }
            }

            item(key = "h-all") {
                SectionHeader(
                    if (state.query.isBlank()) stringResource(R.string.locations_section_all)
                    else stringResource(R.string.locations_section_results, state.locations.size),
                )
            }
            if (state.locations.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.locations_empty, state.query.trim()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(),
                    )
                }
            } else {
                items(state.locations, key = { "all-" + it.id }) { location ->
                    LocationRow(
                        location = location,
                        selected = location.id == state.selectedId,
                        narrow = narrow,
                        onClick = { onSelect(location.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DemoNotice() {
    Surface(
        color = ZuzuColors.Warning.copy(alpha = 0.08f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, ZuzuColors.Warning.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.locations_demo_notice),
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
 * One location row. Selected rows get an electric-blue border, tint and a filled radio mark.
 * The right-hand metrics are placeholders: "— ms" and a grey "Not checked" status.
 */
@Composable
fun LocationRow(
    location: ServerLocation,
    selected: Boolean,
    narrow: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) ZuzuColors.ElectricBlue.copy(alpha = 0.12f) else ZuzuColors.NavyRaised)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) ZuzuColors.ElectricBlue else ZuzuColors.Outline.copy(alpha = 0.5f),
                shape = shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagBadge(countryCode = location.countryCode, size = 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = location.countryName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = location.cityName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (location.isSample && !narrow) {
                    Spacer(Modifier.width(6.dp))
                    SampleTag()
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LatencyIndicator(latencyMs = location.latencyMs, compact = true)
            StatusLabel(showText = !narrow)
        }
        Spacer(Modifier.width(10.dp))
        SelectionMark(selected = selected)
    }
}

/** Neutral availability placeholder: grey dot + "Not checked". Never green in Phase 1. */
@Composable
private fun StatusLabel(showText: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(ZuzuColors.TextMuted, CircleShape))
        if (showText) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.locations_status_unchecked),
                style = MaterialTheme.typography.labelSmall,
                color = ZuzuColors.TextMuted,
                maxLines = 1,
                softWrap = false,
            )
        }
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

@Preview(name = "Locations · 360dp", widthDp = 360, heightDp = 780, showBackground = true, backgroundColor = 0xFF060B1F)
@Composable
private fun LocationsPreview() {
    ZuzuTheme {
        LocationsScreen(
            state = LocationsUiState(
                selectedId = SampleServers.default.id,
                recommended = SampleServers.all.filter { it.isRecommended },
                locations = SampleServers.all,
            ),
            onQueryChange = {},
            onSelect = {},
        )
    }
}

@Preview(name = "Locations · 320dp", widthDp = 320, heightDp = 640, showBackground = true, backgroundColor = 0xFF060B1F)
@Composable
private fun LocationsNarrowPreview() {
    ZuzuTheme {
        LocationsScreen(
            state = LocationsUiState(
                query = "un",
                selectedId = SampleServers.default.id,
                locations = SampleServers.all.filter { it.countryName.contains("un", true) },
            ),
            onQueryChange = {},
            onSelect = {},
        )
    }
}
