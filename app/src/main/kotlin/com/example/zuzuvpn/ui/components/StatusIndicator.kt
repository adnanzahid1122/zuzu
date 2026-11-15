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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.zuzuvpn.R
import com.example.zuzuvpn.ui.home.ConnectionPhase
import com.example.zuzuvpn.ui.theme.ZuzuColors

/** Status dot + title + subtitle. Copy is explicitly "demo" while [isDemo] is true. */
@Composable
fun StatusIndicator(
    phase: ConnectionPhase,
    isDemo: Boolean,
    modifier: Modifier = Modifier,
    timerText: String? = null,
) {
    val dotColor by animateColorAsState(
        targetValue = when (phase) {
            ConnectionPhase.Disconnected -> ZuzuColors.TextMuted
            ConnectionPhase.Connecting -> ZuzuColors.Cyan
            ConnectionPhase.Connected -> ZuzuColors.Success
        },
        label = "dot",
    )
    val blink by rememberInfiniteTransition(label = "status").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "blink",
    )

    // Phase 1 only ships demo copy. Real-status copy is added when the tunnel is wired up and
    // a handshake has been verified; until then nothing claims the traffic is protected.
    check(isDemo) { "Real tunnel status is not implemented in Phase 1" }
    val title = stringResource(
        when (phase) {
            ConnectionPhase.Disconnected -> R.string.status_disconnected_title
            ConnectionPhase.Connecting -> R.string.status_connecting_title
            ConnectionPhase.Connected -> R.string.status_connected_title
        },
    )
    val subtitle = stringResource(
        when (phase) {
            ConnectionPhase.Disconnected -> R.string.status_disconnected_subtitle
            ConnectionPhase.Connecting -> R.string.status_connecting_subtitle
            ConnectionPhase.Connected -> R.string.status_connected_subtitle
        },
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
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = if (phase == ConnectionPhase.Connected) ZuzuColors.Warning else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
