package com.example.zuzuvpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zuzuvpn.R
import com.example.zuzuvpn.ui.common.PlaceholderScreen
import com.example.zuzuvpn.ui.home.HomeRoute
import com.example.zuzuvpn.ui.locations.LocationsRoute
import com.example.zuzuvpn.ui.navigation.ZuzuBottomBar
import com.example.zuzuvpn.ui.navigation.ZuzuDestination
import com.example.zuzuvpn.ui.navigation.ZuzuNavigationRail
import com.example.zuzuvpn.ui.settings.LicensesScreen
import com.example.zuzuvpn.ui.settings.SettingsScreen
import com.example.zuzuvpn.ui.theme.ZuzuColors

/**
 * App shell. Bottom navigation on phones, a navigation rail at >= 600dp.
 * Navigation is plain state for Phase 1; swap in navigation-compose when screens gain arguments.
 */
@Composable
fun ZuzuApp() {
    var destination by rememberSaveable { mutableStateOf(ZuzuDestination.Home) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showLicenses) { showLicenses = false }
    BackHandler(enabled = !showLicenses && destination != ZuzuDestination.Home) {
        destination = ZuzuDestination.Home
    }

    val background = Brush.verticalGradient(listOf(ZuzuColors.Navy, ZuzuColors.Midnight))
    val select: (ZuzuDestination) -> Unit = {
        showLicenses = false
        destination = it
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(background),
    ) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                ZuzuNavigationRail(selected = destination, onSelect = select)
                Box(Modifier.weight(1f)) {
                    DestinationContent(destination, showLicenses, { showLicenses = it }, select)
                }
            }
        } else {
            Scaffold(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                // System bars + display cutout (notch / punch-hole), so content never sits under them.
                contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
                bottomBar = { ZuzuBottomBar(selected = destination, onSelect = select) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    DestinationContent(destination, showLicenses, { showLicenses = it }, select)
                }
            }
        }
    }
}

@Composable
private fun DestinationContent(
    destination: ZuzuDestination,
    showLicenses: Boolean,
    setShowLicenses: (Boolean) -> Unit,
    navigate: (ZuzuDestination) -> Unit,
) {
    when (destination) {
        ZuzuDestination.Home -> HomeRoute(
            onOpenLocations = { navigate(ZuzuDestination.Locations) },
            onOpenPremium = { navigate(ZuzuDestination.Premium) },
        )
        ZuzuDestination.Locations -> LocationsRoute(
            onLocationChosen = { navigate(ZuzuDestination.Home) },
        )
        ZuzuDestination.Premium -> PlaceholderScreen(
            title = stringResource(R.string.placeholder_premium_title),
            body = stringResource(R.string.placeholder_premium_body),
            icon = R.drawable.ic_nav_premium,
            accent = ZuzuColors.Gold,
        )
        ZuzuDestination.Settings -> if (showLicenses) {
            LicensesScreen(onBack = { setShowLicenses(false) })
        } else {
            SettingsScreen(onOpenLicenses = { setShowLicenses(true) })
        }
    }
}
