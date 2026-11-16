package com.example.zuzuvpn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zuzuvpn.R
import com.example.zuzuvpn.data.servers.CountryCodes
import com.example.zuzuvpn.data.servers.ServerDirectory
import com.example.zuzuvpn.data.servers.ServerInfo
import com.example.zuzuvpn.ui.theme.ZuzuColors

/**
 * Tappable Home card for the selected location: flag, country, city and (only when the directory
 * supplied them) latency and load. With no location it prompts the user to choose one; when this
 * build has no location source it says so instead of showing anything made up.
 *
 * Layout is width-aware so nothing wraps letter-by-letter on ~360dp phones.
 */
@Composable
fun LocationCard(
    location: ServerInfo?,
    directory: ServerDirectory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unavailable = directory == ServerDirectory.NotConfigured
    val failed = directory is ServerDirectory.Failed
    val title = when {
        location != null -> location.name
        unavailable || failed -> stringResource(R.string.home_locations_unavailable_title)
        else -> stringResource(R.string.home_location_choose)
    }
    val subtitle = when {
        location != null && !location.isAvailable -> stringResource(R.string.server_unavailable)
        location?.isDevelopment == true -> listOfNotNull(location.city, stringResource(R.string.location_development_subtitle)).joinToString(" · ")
        location != null -> listOfNotNull(location.city, location.country).distinct().filter { it != location.name }.joinToString(", ").ifEmpty { null }
        failed -> stringResource(R.string.home_locations_unreachable_body)
        unavailable -> stringResource(R.string.home_locations_unavailable_body)
        else -> stringResource(R.string.home_location_choose_summary)
    }
    val description = stringResource(R.string.server_card_cd, title)
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = ZuzuColors.NavyRaised),
        border = BorderStroke(1.dp, ZuzuColors.Outline.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocationBadge(countryCode = location?.countryCode, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            BoxWithConstraints(Modifier.weight(1f)) {
                val textWidth = maxWidth
                Column {
                    Text(
                        text = stringResource(R.string.server_card_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = ZuzuColors.TextMuted,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (location?.isPremium == true) {
                            Spacer(Modifier.width(6.dp))
                            PremiumTag()
                        }
                        if (location?.isDevelopment == true) {
                            Spacer(Modifier.width(6.dp))
                            DevelopmentTag()
                        }
                    }
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (location != null && (location.latencyMs != null || location.loadPercent != null)) {
                        Spacer(Modifier.height(6.dp))
                        ServerMetricsRow(
                            latencyMs = location.latencyMs,
                            loadPercent = location.loadPercent,
                            availableWidth = textWidth,
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            if (!unavailable) {
                Text(
                    text = stringResource(if (location == null) R.string.home_location_choose_action else R.string.home_location_change),
                    style = MaterialTheme.typography.labelLarge,
                    color = ZuzuColors.ElectricBlueBright,
                    maxLines = 1,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = ZuzuColors.TextSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Flag for a known country code; a neutral location pin when the country is unknown. */
@Composable
fun LocationBadge(countryCode: String?, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    if (countryCode != null) {
        FlagBadge(countryCode = countryCode, modifier = modifier, size = size)
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(ZuzuColors.NavyHigh)
                .border(1.dp, ZuzuColors.Outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_nav_locations),
                contentDescription = null,
                tint = ZuzuColors.TextSecondary,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/**
 * Latency + load on one line. Width tiers (measured on the text column):
 * >= 220dp full labels; >= 140dp compact latency + load; below that latency only.
 */
@Composable
fun ServerMetricsRow(
    latencyMs: Int?,
    loadPercent: Int?,
    availableWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val compact = availableWidth < 220.dp
    val showLoad = availableWidth >= 140.dp
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LatencyIndicator(latencyMs = latencyMs, compact = compact)
        if (showLoad) LoadIndicator(loadPercent = loadPercent)
    }
}

/** Round flag chip rendered from the ISO country code (emoji regional indicators). */
@Composable
fun FlagBadge(
    countryCode: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(ZuzuColors.NavyHigh)
            .border(1.dp, ZuzuColors.Outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = flagEmoji(countryCode), fontSize = (size.value * 0.52f).sp)
    }
}

/** Regional-indicator flag emoji for an ISO 3166-1 alpha-2 code (derived, never stored). */
fun flagEmoji(countryCode: String): String = CountryCodes.flagEmoji(countryCode)

/**
 * Four signal bars coloured by latency. When [latencyMs] is null the bars are grey and the label
 * reads "Not measured" ([compact] = false) or "— ms" ([compact] = true). Never shows a guess.
 */
@Composable
fun LatencyIndicator(
    latencyMs: Int?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val (activeBars, color) = when {
        latencyMs == null -> 0 to ZuzuColors.TextMuted
        latencyMs < 60 -> 4 to ZuzuColors.Success
        latencyMs < 120 -> 3 to ZuzuColors.Success
        latencyMs < 200 -> 2 to ZuzuColors.Warning
        else -> 1 to ZuzuColors.Danger
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        SignalBars(activeBars = activeBars, color = color)
        Spacer(Modifier.width(4.dp))
        Text(
            text = when {
                latencyMs != null -> stringResource(R.string.server_latency_ms, latencyMs)
                compact -> stringResource(R.string.server_latency_unknown_short)
                else -> stringResource(R.string.server_latency_unknown)
            },
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun SignalBars(activeBars: Int, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 0 until 4) {
            Box(
                Modifier
                    .width(3.dp)
                    .height((4 + i * 3).dp)
                    .background(
                        if (i < activeBars) color else ZuzuColors.Outline,
                        RoundedCornerShape(1.dp),
                    ),
            )
        }
    }
}

/** Small horizontal load meter (0..100 %); empty with "Load —" when unknown. */
@Composable
fun LoadIndicator(loadPercent: Int?, modifier: Modifier = Modifier) {
    val fraction = (loadPercent ?: 0).coerceIn(0, 100) / 100f
    val color = when {
        loadPercent == null -> ZuzuColors.TextMuted
        loadPercent < 60 -> ZuzuColors.Cyan
        loadPercent < 85 -> ZuzuColors.Warning
        else -> ZuzuColors.Danger
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(24.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(ZuzuColors.Outline),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(5.dp)
                    .background(color),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (loadPercent == null) stringResource(R.string.server_load_unknown)
            else stringResource(R.string.server_load_percent, loadPercent),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Gold "Premium" chip for servers that need a premium entitlement (entitlements are not implemented yet). */
@Composable
fun PremiumTag(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.server_premium_tag),
        style = MaterialTheme.typography.labelSmall,
        color = ZuzuColors.Gold,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .background(ZuzuColors.Gold.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Amber "Development" chip marking debug-build test servers (never production locations). */
@Composable
fun DevelopmentTag(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.location_development_tag),
        style = MaterialTheme.typography.labelSmall,
        color = ZuzuColors.Warning,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .background(ZuzuColors.Warning.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
