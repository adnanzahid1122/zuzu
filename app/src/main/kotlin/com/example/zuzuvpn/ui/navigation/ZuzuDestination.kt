package com.example.zuzuvpn.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.zuzuvpn.R

/** Top-level destinations shown in the bottom bar (phones) or navigation rail (wide screens). */
enum class ZuzuDestination(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
) {
    Home(R.string.nav_home, R.drawable.ic_nav_home),
    Locations(R.string.nav_locations, R.drawable.ic_nav_locations),
    Settings(R.string.nav_settings, R.drawable.ic_nav_settings),
}
