package com.example.zuzuvpn.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zuzuvpn.ui.components.ZuzuWordmark
import com.example.zuzuvpn.ui.theme.ZuzuColors

@Composable
fun ZuzuBottomBar(
    selected: ZuzuDestination,
    onSelect: (ZuzuDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = ZuzuColors.Navy,
        tonalElevation = 0.dp,
    ) {
        ZuzuDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ZuzuColors.ElectricBlueBright,
                    selectedTextColor = ZuzuColors.ElectricBlueBright,
                    indicatorColor = ZuzuColors.ElectricBlue.copy(alpha = 0.18f),
                    unselectedIconColor = ZuzuColors.TextMuted,
                    unselectedTextColor = ZuzuColors.TextMuted,
                ),
            )
        }
    }
}

@Composable
fun ZuzuNavigationRail(
    selected: ZuzuDestination,
    onSelect: (ZuzuDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier,
        containerColor = ZuzuColors.Navy,
        header = { ZuzuWordmark() },
    ) {
        ZuzuDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = ZuzuColors.ElectricBlueBright,
                    selectedTextColor = ZuzuColors.ElectricBlueBright,
                    indicatorColor = ZuzuColors.ElectricBlue.copy(alpha = 0.18f),
                    unselectedIconColor = ZuzuColors.TextMuted,
                    unselectedTextColor = ZuzuColors.TextMuted,
                ),
            )
        }
    }
}
