package com.example.zuzuvpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.zuzuvpn.ZuzuApplication
import com.example.zuzuvpn.dev.DeveloperUi
import com.example.zuzuvpn.ui.home.HomeRoute
import com.example.zuzuvpn.ui.locations.LocationsRoute
import com.example.zuzuvpn.ui.navigation.ZuzuBottomBar
import com.example.zuzuvpn.ui.navigation.ZuzuDestination
import com.example.zuzuvpn.ui.navigation.ZuzuNavigationRail
import com.example.zuzuvpn.ui.settings.LicensesScreen
import com.example.zuzuvpn.ui.settings.SettingsScreen
import com.example.zuzuvpn.ui.theme.ZuzuColors

/** Settings sub-screens. */
private enum class SettingsPage { Main, Licenses, Developer }

/**
 * App shell with the three consumer areas: Home, Locations and Settings. Bottom navigation on
 * phones, a navigation rail at >= 600dp. Developer screens exist only in debug builds.
 */
@Composable
fun ZuzuApp() {
    val developerUi: DeveloperUi? = (LocalContext.current.applicationContext as ZuzuApplication).container.developerTools.ui
    var destination by rememberSaveable { mutableStateOf(ZuzuDestination.Home) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.Main) }

    BackHandler(enabled = settingsPage != SettingsPage.Main) { settingsPage = SettingsPage.Main }
    BackHandler(enabled = settingsPage == SettingsPage.Main && destination != ZuzuDestination.Home) {
        destination = ZuzuDestination.Home
    }

    val background = Brush.verticalGradient(listOf(ZuzuColors.Navy, ZuzuColors.Midnight))
    val select: (ZuzuDestination) -> Unit = {
        settingsPage = SettingsPage.Main
        destination = it
    }
    val content: @Composable () -> Unit = {
        when (destination) {
            ZuzuDestination.Home -> HomeRoute(onOpenLocations = { select(ZuzuDestination.Locations) })
            ZuzuDestination.Locations -> LocationsRoute(onLocationChosen = { select(ZuzuDestination.Home) })
            ZuzuDestination.Settings -> when {
                settingsPage == SettingsPage.Licenses -> LicensesScreen(onBack = { settingsPage = SettingsPage.Main })
                settingsPage == SettingsPage.Developer && developerUi != null -> developerUi.Screen(
                    onClose = { settingsPage = SettingsPage.Main },
                    onServerReady = { select(ZuzuDestination.Home) },
                )
                else -> SettingsScreen(
                    onOpenLicenses = { settingsPage = SettingsPage.Licenses },
                    developerEntry = developerUi?.let { ui -> { ui.SettingsEntry(onOpen = { settingsPage = SettingsPage.Developer }) } },
                )
            }
        }
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
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            Scaffold(
                containerColor = Color.Transparent,
                // System bars + display cutout (notch / punch-hole), so content never sits under them.
                contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
                bottomBar = { ZuzuBottomBar(selected = destination, onSelect = select) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) { content() }
            }
        }
    }
}
