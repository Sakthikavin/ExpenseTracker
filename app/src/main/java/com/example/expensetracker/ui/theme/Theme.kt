package com.example.expensetracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Light-only for v1 (matches the rest of the app, per UX_REDESIGN_PLAN.md) — built from the fixed
// palette in Color.kt rather than dynamic/wallpaper-derived color, so the app looks the same
// regardless of device or system theme.
//
// lightColorScheme() does NOT derive unspecified roles (primaryContainer, secondaryContainer,
// tertiary, ...) from the ones given — each has its own independent M3 baseline default, and that
// baseline is purple. Every role Material3 actually paints with by default (FAB, segmented
// buttons, nav bar selection) must be listed explicitly or the purple leaks straight back in.
private val AppColorScheme = lightColorScheme(
    primary = Series1,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6FA),
    onPrimaryContainer = Color(0xFF0B3A66),
    secondary = TextSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDECE6),
    onSecondaryContainer = TextPrimary,
    tertiary = TextSecondary,
    onTertiary = Color.White,
    tertiaryContainer = Gridline,
    onTertiaryContainer = TextPrimary,
    error = StatusCritical,
    onError = Color.White,
    errorContainer = Color(0xFFFBE3E2),
    onErrorContainer = Color(0xFF9C2323),
    background = PagePlane,
    onBackground = TextPrimary,
    surface = SurfacePlane,
    onSurface = TextPrimary,
    surfaceVariant = Gridline,
    onSurfaceVariant = TextSecondary,
    outline = Gridline,
    outlineVariant = Gridline,
    surfaceTint = Series1,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F9F7),
    surfaceContainer = Color(0xFFF3F2ED),
    surfaceContainerHigh = Color(0xFFEDECE6),
    surfaceContainerHighest = Color(0xFFE7E6DF),
    surfaceBright = SurfacePlane,
    surfaceDim = Color(0xFFDAD9D2),
    inverseSurface = TextPrimary,
    inverseOnSurface = SurfacePlane,
    inversePrimary = Color(0xFF9FC5EE),
    scrim = Color(0xFF000000),
)

@Composable
fun ExpenseTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = Typography,
        content = content,
    )
}
