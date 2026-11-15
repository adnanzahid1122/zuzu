package com.example.zuzuvpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.zuzuvpn.R
import com.example.zuzuvpn.ui.home.ConnectionPhase
import com.example.zuzuvpn.ui.theme.ZuzuColors

/**
 * Large circular power button with a pulsing glow (Disconnected), a spinning progress arc
 * (Connecting) and a steady green ring (Connected).
 */
@Composable
fun ConnectButton(
    phase: ConnectionPhase,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 136.dp,
) {
    val transition = rememberInfiniteTransition(label = "connect")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "pulse",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "spin",
    )

    val accent by animateColorAsState(
        targetValue = when (phase) {
            ConnectionPhase.Disconnected -> ZuzuColors.ElectricBlue
            ConnectionPhase.Connecting -> ZuzuColors.Cyan
            ConnectionPhase.Connected -> ZuzuColors.Success
        },
        animationSpec = tween(500),
        label = "accent",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "press")

    val label = stringResource(
        when (phase) {
            ConnectionPhase.Disconnected -> R.string.connect_button_connect
            ConnectionPhase.Connecting -> R.string.connect_button_cancel
            ConnectionPhase.Connected -> R.string.connect_button_disconnect
        },
    )
    val description = stringResource(
        when (phase) {
            ConnectionPhase.Disconnected -> R.string.connect_button_cd_connect
            ConnectionPhase.Connecting -> R.string.connect_button_cd_cancel
            ConnectionPhase.Connected -> R.string.connect_button_cd_disconnect
        },
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(diameter * 1.35f),
            contentAlignment = Alignment.Center,
        ) {
            // Glow + progress ring
            Canvas(modifier = Modifier.fillMaxSize()) {
                val glowAlpha = if (phase == ConnectionPhase.Disconnected) 0.18f + 0.22f * pulse else 0.3f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = glowAlpha), Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2f,
                    ),
                )
                val ringInset = size.minDimension * 0.12f
                val ringSize = Size(size.width - ringInset * 2, size.height - ringInset * 2)
                val ringStroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                drawArc(
                    color = ZuzuColors.NavyHigh,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize,
                    style = ringStroke,
                )
                when (phase) {
                    ConnectionPhase.Connecting -> drawArc(
                        color = accent,
                        startAngle = spin - 90f,
                        sweepAngle = 110f,
                        useCenter = false,
                        topLeft = Offset(ringInset, ringInset),
                        size = ringSize,
                        style = ringStroke,
                    )
                    ConnectionPhase.Connected -> drawArc(
                        color = accent,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(ringInset, ringInset),
                        size = ringSize,
                        style = ringStroke,
                    )
                    ConnectionPhase.Disconnected -> Unit
                }
            }

            // The pressable core
            val coreBrush = when (phase) {
                ConnectionPhase.Disconnected -> Brush.linearGradient(listOf(ZuzuColors.ElectricBlueBright, ZuzuColors.ElectricBlue))
                ConnectionPhase.Connecting -> Brush.linearGradient(listOf(ZuzuColors.NavyHigh, ZuzuColors.NavyRaised))
                ConnectionPhase.Connected -> Brush.linearGradient(listOf(ZuzuColors.Success, ZuzuColors.Cyan))
            }
            Box(
                modifier = Modifier
                    .size(diameter * 0.78f)
                    .scale(pressScale)
                    .clip(CircleShape)
                    .background(coreBrush)
                    .border(2.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_power),
                    contentDescription = null,
                    tint = if (phase == ConnectionPhase.Connecting) accent else Color.White,
                    modifier = Modifier.size(diameter * 0.3f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
