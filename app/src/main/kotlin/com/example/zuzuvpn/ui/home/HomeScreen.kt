package com.example.zuzuvpn.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.zuzuvpn.R
import com.example.zuzuvpn.ui.components.ConnectButton
import com.example.zuzuvpn.ui.components.DemoBadge
import com.example.zuzuvpn.ui.components.MascotMood
import com.example.zuzuvpn.ui.components.RabbitMascot
import com.example.zuzuvpn.ui.components.ServerLocationCard
import com.example.zuzuvpn.ui.components.StatusIndicator
import com.example.zuzuvpn.ui.components.ZuzuTopBar
import com.example.zuzuvpn.ui.theme.ZuzuColors
import com.example.zuzuvpn.ui.theme.ZuzuTheme

/** Stateful entry point: owns the [HomeViewModel]. */
@Composable
fun HomeRoute(
    onOpenLocations: () -> Unit,
    onOpenPremium: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onPrimaryAction = viewModel::onPrimaryAction,
        onOpenLocations = onOpenLocations,
        onOpenPremium = onOpenPremium,
        modifier = modifier,
    )
}

/** Stateless home screen. Phone: single column. Width >= 600dp: hero left, controls right. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onPrimaryAction: () -> Unit,
    onOpenLocations: () -> Unit,
    onOpenPremium: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxSize()) {
            ZuzuTopBar(onPremiumClick = onOpenPremium)
            if (wide) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    MascotHero(
                        phase = state.phase,
                        size = minOf(this@BoxWithConstraints.maxHeight * 0.6f, 340.dp),
                        modifier = Modifier.weight(1f),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        HomeControls(state, onPrimaryAction, onOpenLocations)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MascotHero(
    phase = state.phase,
    size = minOf(this@BoxWithConstraints.maxWidth * 0.62f, 260.dp)
)
                    HomeControls(state, onPrimaryAction, onOpenLocations)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeControls(
    state: HomeUiState,
    onPrimaryAction: () -> Unit,
    onOpenLocations: () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = 480.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.isDemo) {
            DemoBadge()
            Spacer(Modifier.height(12.dp))
        }
        StatusIndicator(
            phase = state.phase,
            isDemo = state.isDemo,
            timerText = if (state.phase == ConnectionPhase.Connected) {
                stringResource(R.string.demo_session_timer, formatElapsed(state.demoElapsedSeconds))
            } else {
                null
            },
        )
        Spacer(Modifier.height(8.dp))
        ConnectButton(phase = state.phase, onClick = onPrimaryAction)
        Spacer(Modifier.height(16.dp))
        ServerLocationCard(server = state.server, onClick = onOpenLocations)
        if (state.isDemo) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.demo_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = ZuzuColors.TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Mascot on a soft glow whose colour follows the phase. */
@Composable
private fun MascotHero(
    phase: ConnectionPhase,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val glow by animateColorAsState(
        targetValue = when (phase) {
            ConnectionPhase.Disconnected -> ZuzuColors.ElectricBlue.copy(alpha = 0.22f)
            ConnectionPhase.Connecting -> ZuzuColors.Cyan.copy(alpha = 0.3f)
            ConnectionPhase.Connected -> ZuzuColors.Success.copy(alpha = 0.28f)
        },
        animationSpec = tween(600),
        label = "heroGlow",
    )
    val mood = when (phase) {
        ConnectionPhase.Disconnected -> MascotMood.Sleepy
        ConnectionPhase.Connecting -> MascotMood.Alert
        ConnectionPhase.Connected -> MascotMood.Happy
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glow, Color.Transparent),
                        center = center,
                        radius = this.size.minDimension / 2f,
                    ),
                )
            }
            RabbitMascot(mood = mood, modifier = Modifier.size(size * 0.86f))
        }
    }
}

private fun formatElapsed(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

@Preview(name = "Home · disconnected", widthDp = 360, heightDp = 780, showBackground = true, backgroundColor = 0xFF060B1F)
@Composable
private fun HomeDisconnectedPreview() {
    ZuzuTheme { HomeScreen(HomeUiState(), {}, {}, {}) }
}

@Preview(name = "Home · connecting", widthDp = 360, heightDp = 780, showBackground = true, backgroundColor = 0xFF060B1F)
@Composable
private fun HomeConnectingPreview() {
    ZuzuTheme { HomeScreen(HomeUiState(phase = ConnectionPhase.Connecting), {}, {}, {}) }
}

@Preview(name = "Home · connected", widthDp = 360, heightDp = 780, showBackground = true, backgroundColor = 0xFF060B1F)
@Composable
private fun HomeConnectedPreview() {
    ZuzuTheme { HomeScreen(HomeUiState(phase = ConnectionPhase.Connected, demoElapsedSeconds = 754), {}, {}, {}) }
}

@Preview(name = "Home · tablet", widthDp = 900, heightDp = 600, showBackground = true, backgroundColor = 0xFF060B1F)
@Composable
private fun HomeTabletPreview() {
    ZuzuTheme { HomeScreen(HomeUiState(), {}, {}, {}) }
}
