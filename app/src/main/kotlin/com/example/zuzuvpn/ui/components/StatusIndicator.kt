package com.example.zuzuvpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.zuzuvpn.ui.home.ConnectionPhase
import com.example.zuzuvpn.ui.theme.ZuzuColors

/**
 * Status dot + title + subtitle. The caller supplies the text from the real VPN state; this
 * component never claims a connection on its own.
 *
 * @param isError shows the subtitle in the warning colour (failed attempt, revoked tunnel).
 */
@Composable
fun StatusIndicator(
    phase: ConnectionPhase,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    timerText: String? = null,
) {
    val dotColor by animateColorAsState(
        targetValue = when {
            isError -> ZuzuColors.Warning
            phase == ConnectionPhase.Disconnected -> ZuzuColors.TextMuted
            phase == ConnectionPhase.Connecting -> ZuzuColors.Cyan
            else -> ZuzuColors.Success
        },
        label = "dot",
    )
    val blink by rememberInfiniteTransition(label = "status").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "blink",
    )

    Column(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .alpha(if (phase == ConnectionPhase.Connecting) blink else 1f)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) ZuzuColors.Warning else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (timerText != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = timerText,
                style = MaterialTheme.typography.labelMedium,
                color = ZuzuColors.TextMuted,
            )
        }
    }
}
