package com.nordic.stepcounter.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.nordic.stepcounter.data.preferences.AppTheme

@Composable
fun NordicStepTheme(
    appTheme: AppTheme = AppTheme.NORDIC_DARK,
    content: @Composable () -> Unit
) {
    val palette = appTheme.toStepColorPalette()

    val colorScheme = if (appTheme.isDark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.background,
            secondary = palette.accent,
            onSecondary = palette.background,
            secondaryContainer = palette.accent.copy(alpha = 0.18f),
            onSecondaryContainer = palette.accent,
            background = palette.background,
            onBackground = palette.primaryText,
            surface = palette.surface,
            onSurface = palette.primaryText,
            surfaceVariant = palette.surface,
            onSurfaceVariant = palette.mutedText,
            surfaceContainer = palette.surface,
            outline = palette.border,
            outlineVariant = palette.border
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.background,
            secondary = palette.accent,
            onSecondary = palette.background,
            secondaryContainer = palette.accent.copy(alpha = 0.18f),
            onSecondaryContainer = palette.accent,
            background = palette.background,
            onBackground = palette.primaryText,
            surface = palette.surface,
            onSurface = palette.primaryText,
            surfaceVariant = palette.surface,
            onSurfaceVariant = palette.mutedText,
            surfaceContainer = palette.surface,
            outline = palette.border,
            outlineVariant = palette.border
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !appTheme.isDark
            insetsController.isAppearanceLightNavigationBars = !appTheme.isDark
        }
    }

    CompositionLocalProvider(LocalStepPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SwissTypography,
            content = content
        )
    }
}
