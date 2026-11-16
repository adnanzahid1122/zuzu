package com.example.zuzuvpn.ui.home

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.zuzuvpn.R
import com.example.zuzuvpn.data.servers.ServerDirectory
import com.example.zuzuvpn.data.servers.ServerInfo
import com.example.zuzuvpn.ui.components.LocationCard
import com.example.zuzuvpn.ui.components.ConnectButton
import com.example.zuzuvpn.ui.components.MascotMood
import com.example.zuzuvpn.ui.components.RabbitMascot
import com.example.zuzuvpn.ui.components.StatusIndicator
import com.example.zuzuvpn.ui.components.ZuzuTopBar
import com.example.zuzuvpn.ui.theme.ZuzuColors
import com.example.zuzuvpn.ui.theme.ZuzuTheme
import com.example.zuzuvpn.vpn.ConnectStep
import com.example.zuzuvpn.vpn.VpnError
import com.example.zuzuvpn.vpn.VpnNotifications
import com.example.zuzuvpn.vpn.VpnPermission
import com.example.zuzuvpn.vpn.VpnProtocol
import com.example.zuzuvpn.vpn.VpnState
import kotlinx.coroutines.delay

/**
 * Stateful entry point: owns the [HomeViewModel] and the Activity-result launchers for the
 * notification permission (Android 13+) and the system VPN consent dialog.
 */
@Composable
fun HomeRoute(
    onOpenLocations: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onPermissionResult(result.resultCode == Activity.RESULT_OK)
    }
    val requestVpnConsent = {
        val intent = VpnPermission.prepare(context)
        if (intent == null) viewModel.onPermissionResult(true) else vpnConsent.launch(intent)
    }
    // The answer does not block connecting: without it the VPN still runs, only the ongoing
    // notification is hidden from the shade.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestVpnConsent()
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                HomeEvent.OpenLocations -> onOpenLocations()
                HomeEvent.RequestVpnPermission -> {
                    val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    if (needsNotificationPermission) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        requestVpnConsent()
                    }
                }
            }
        }
    }

    HomeScreen(
        state = state,
        onPrimaryAction = viewModel::onPrimaryAction,
        onChangeLocation = viewModel::onChangeLocation,
        modifier = modifier,
    )
}

/** Stateless home screen. Phone: single column. Width >= 600dp: hero left, controls right. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onPrimaryAction: () -> Unit,
    onChangeLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxSize()) {
            ZuzuTopBar()
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
                        HomeControls(state, onPrimaryAction, onChangeLocation)
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
                        size = minOf(this@BoxWithConstraints.maxWidth * 0.62f, 260.dp),
                    )
                    HomeControls(state, onPrimaryAction, onChangeLocation)
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
    onChangeLocation: () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = 480.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val status = statusText(state)
        StatusIndicator(
            phase = state.phase,
            title = status.title,
            subtitle = status.subtitle,
            isError = status.isError,
            timerText = (state.vpn as? VpnState.Connected)?.let {
                stringResource(R.string.session_timer, formatElapsed(rememberElapsedSeconds(it.sinceElapsedRealtime)))
            },
        )
        Spacer(Modifier.height(8.dp))
        ConnectButton(phase = state.phase, onClick = onPrimaryAction)
        Spacer(Modifier.height(16.dp))
        if (state.directory != ServerDirectory.Loading) {
            LocationCard(location = state.location, directory = state.directory, onClick = onChangeLocation)
        }
    }
}

private data class StatusText(val title: String, val subtitle: String?, val isError: Boolean = false)

/** Maps the real VPN state to status copy. "Connected" only exists for [VpnState.Connected]. */
@Composable
private fun statusText(state: HomeUiState): StatusText = when (val vpn = state.vpn) {
    VpnState.Disconnected -> when (state.notice) {
        null -> StatusText(stringResource(R.string.status_disconnected_title), stringResource(R.string.status_disconnected_subtitle))
        HomeNotice.PermissionDenied ->
            StatusText(stringResource(R.string.status_failed_title), stringResource(R.string.vpn_error_permission), isError = true)
        else -> StatusText(stringResource(R.string.status_disconnected_title), stringResource(noticeText(state.notice)), isError = true)
    }
    is VpnState.Connecting -> StatusText(
        stringResource(R.string.status_connecting_title),
        stringResource(VpnNotifications.stepText(vpn.step)),
    )
    VpnState.Disconnecting -> StatusText(stringResource(R.string.status_disconnecting_title), null)
    is VpnState.Connected -> StatusText(
        stringResource(R.string.status_connected_title),
        stringResource(R.string.status_connected_subtitle, vpn.serverLabel),
    )
    is VpnState.Failed -> {
        val reason = stringResource(errorText(vpn.error))
        val subtitle = if (vpn.clockHint) reason + "\n" + stringResource(R.string.vpn_error_clock_hint) else reason
        StatusText(stringResource(R.string.status_failed_title), subtitle, isError = true)
    }
}

@StringRes
private fun noticeText(notice: HomeNotice): Int = when (notice) {
    HomeNotice.LocationsUnavailable -> R.string.vpn_error_locations_unavailable
    HomeNotice.DirectoryUnreachable -> R.string.vpn_error_directory_unreachable
    HomeNotice.NoLocation -> R.string.vpn_error_no_location
    HomeNotice.LocationNotReady -> R.string.vpn_error_location_not_ready
    HomeNotice.PermissionDenied -> R.string.vpn_error_permission
    HomeNotice.SignInRequired -> R.string.vpn_error_sign_in_required
    HomeNotice.SubscriptionRequired -> R.string.vpn_error_subscription_required
    HomeNotice.PremiumRequired -> R.string.vpn_error_premium_required
    HomeNotice.RateLimited -> R.string.vpn_error_rate_limited
    HomeNotice.SecurityError -> R.string.vpn_error_security
}

@StringRes
private fun errorText(error: VpnError): Int = when (error) {
    VpnError.PermissionDenied -> R.string.vpn_error_permission
    VpnError.NoServerConfigured -> R.string.vpn_error_no_server
    VpnError.InvalidServerConfig -> R.string.vpn_error_invalid_server
    VpnError.ServerNameNotResolved -> R.string.vpn_error_dns
    VpnError.EngineStartFailed -> R.string.vpn_error_engine_start
    VpnError.TunnelEstablishFailed -> R.string.vpn_error_tunnel
    VpnError.TrafficCheckFailed -> R.string.vpn_error_traffic
    VpnError.EngineStopped -> R.string.vpn_error_engine_stopped
    VpnError.RevokedBySystem -> R.string.vpn_error_revoked
    VpnError.ProtocolNotAvailable -> R.string.vpn_error_unavailable
    VpnError.Unknown -> R.string.vpn_error_unknown
}

/** Seconds since [sinceElapsedRealtime], ticking once per second while composed. */
@Composable
private fun rememberElapsedSeconds(sinceElapsedRealtime: Long): Long {
    val seconds by produceState(initialValue = elapsedSince(sinceElapsedRealtime), sinceElapsedRealtime) {
        while (true) {
            value = elapsedSince(sinceElapsedRealtime)
            delay(1_000)
        }
    }
    return seconds
}

private fun elapsedSince(sinceElapsedRealtime: Long): Long =
    ((SystemClock.elapsedRealtime() - sinceElapsedRealtime) / 1_000).coerceAtLeast(0)

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

// Preview-only data; never used at runtime.
private val previewLocation = ServerInfo(id = "preview", name = "Germany", country = "Germany", countryCode = "DE", city = "Frankfurt", protocol = VpnProtocol.Shadowsocks, loadPercent = 34)

@Preview(name = "Home · disconnected", widthDp = 360, heightDp = 780, showBackground = true, backgroundColor = 0xFF060B1F)
@Composable
private fun HomeDisconnectedPreview() {
    ZuzuTheme { HomeScreen(HomeUiState(directory = ServerDirectory.Available(listOf(previewLocation)), location = previewLocation), {}, {}) }
}

@Preview(name = "Home · connecting", widthDp = 360, heightDp = 780, showBackground = true, backgroundColor = 0xFF060B1F)
@Composable
private fun HomeConnectingPreview() {
    ZuzuTheme {
        HomeScreen(
            HomeUiState(
                vpn = VpnState.Connecting(VpnProtocol.Shadowsocks, ConnectStep.VerifyingTraffic),
                directory = ServerDirectory.Available(listOf(previewLocation)),
                location = previewLocation,
            ),
            {}, {},
        )
    }
}

@Preview(name = "Home · failed", widthDp = 360, heightDp = 780, showBackground = true, backgroundColor = 0xFF060B1F)
@Composable
private fun HomeFailedPreview() {
    ZuzuTheme {
        HomeScreen(
            HomeUiState(
                vpn = VpnState.Failed(VpnProtocol.Shadowsocks, VpnError.TrafficCheckFailed),
                directory = ServerDirectory.Available(listOf(previewLocation)),
                location = previewLocation,
            ),
            {}, {},
        )
    }
}

@Preview(name = "Home · locations unavailable", widthDp = 360, heightDp = 780, showBackground = true, backgroundColor = 0xFF060B1F)
@Composable
private fun HomeUnavailablePreview() {
    ZuzuTheme { HomeScreen(HomeUiState(directory = ServerDirectory.NotConfigured, notice = HomeNotice.LocationsUnavailable), {}, {}) }
}

@Preview(name = "Home · tablet", widthDp = 900, heightDp = 600, showBackground = true, backgroundColor = 0xFF060B1F)
@Composable
private fun HomeTabletPreview() {
    ZuzuTheme { HomeScreen(HomeUiState(directory = ServerDirectory.Available(listOf(previewLocation)), location = previewLocation), {}, {}) }
}
