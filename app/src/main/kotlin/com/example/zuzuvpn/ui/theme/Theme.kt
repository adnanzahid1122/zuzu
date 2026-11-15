package com.example.zuzuvpn.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val ZuzuColorScheme = darkColorScheme(
    primary = ZuzuColors.ElectricBlue,
    onPrimary = ZuzuColors.TextPrimary,
    primaryContainer = ZuzuColors.NavyHigh,
    onPrimaryContainer = ZuzuColors.TextPrimary,
    secondary = ZuzuColors.Cyan,
    onSecondary = ZuzuColors.Midnight,
    tertiary = ZuzuColors.Violet,
    onTertiary = ZuzuColors.TextPrimary,
    background = ZuzuColors.Midnight,
    onBackground = ZuzuColors.TextPrimary,
    surface = ZuzuColors.Navy,
    onSurface = ZuzuColors.TextPrimary,
    surfaceVariant = ZuzuColors.NavyRaised,
    onSurfaceVariant = ZuzuColors.TextSecondary,
    surfaceContainer = ZuzuColors.NavyRaised,
    surfaceContainerHigh = ZuzuColors.NavyHigh,
    outline = ZuzuColors.Outline,
    outlineVariant = ZuzuColors.NavyHigh,
    error = ZuzuColors.Danger,
)

private val ZuzuShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Zuzu is dark-only by design: midnight navy in every system theme. */
@Composable
fun ZuzuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZuzuColorScheme,
        typography = ZuzuTypography,
        shapes = ZuzuShapes,
        content = content,
    )
}
