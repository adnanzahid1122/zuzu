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
import com.example.zuzuvpn.ui.home.ServerLocation
import com.example.zuzuvpn.ui.theme.ZuzuColors

/**
 * Tappable Home card for the selected server: flag, country, city, latency and load.
 *
 * Layout is width-aware so nothing wraps letter-by-letter on ~360dp phones:
 * country and city each stay on one line (ellipsized); latency and load sit on their own row,
 * switching to compact labels and dropping load when the text column gets narrow.
 * The chevron always keeps its space.
 */
@Composable
fun ServerLocationCard(
    server: ServerLocation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.server_card_cd, server.countryName, server.cityName)
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
            FlagBadge(countryCode = server.countryCode, size = 42.dp)
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
                            text = server.countryName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (server.isSample) {
                            Spacer(Modifier.width(6.dp))
                            SampleTag()
                        }
                    }
                    Text(
                        text = server.cityName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    ServerMetricsRow(
                        latencyMs = server.latencyMs,
                        loadPercent = server.loadPercent,
                        availableWidth = textWidth,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = ZuzuColors.TextSecondary,
                modifier = Modifier.size(24.dp),
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

/** Converts "US" to the regional-indicator flag emoji. Returns the code itself if invalid. */
fun flagEmoji(countryCode: String): String {
    val code = countryCode.uppercase()
    if (code.length != 2 || code.any { it !in 'A'..'Z' }) return code
    val first = Character.codePointAt(code, 0) - 'A'.code + 0x1F1E6
    val second = Character.codePointAt(code, 1) - 'A'.code + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}

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

/** Grey "Sample" chip marking demo data. */
@Composable
fun SampleTag(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.server_sample_tag),
        style = MaterialTheme.typography.labelSmall,
        color = ZuzuColors.TextSecondary,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
