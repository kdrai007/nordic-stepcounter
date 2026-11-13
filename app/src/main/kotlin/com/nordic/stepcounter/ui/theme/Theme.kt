package com.nordic.stepcounter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.nordic.stepcounter.data.preferences.AppTheme

@Composable
fun NordicStepTheme(
    appTheme: AppTheme = AppTheme.NORDIC_DARK,
    content: @Composable () -> Unit
) {
    val palette = appTheme.toStepColorPalette()

    val colorScheme = if (appTheme.isDark) {
        darkColorScheme(
            background = palette.background,
            surface = palette.surface,
            primary = palette.accent,
            onBackground = palette.primaryText,
            onSurface = palette.primaryText,
            outline = palette.border
        )
    } else {
        lightColorScheme(
            background = palette.background,
            surface = palette.surface,
            primary = palette.accent,
            onBackground = palette.primaryText,
            onSurface = palette.primaryText,
            outline = palette.border
        )
    }

    CompositionLocalProvider(LocalStepPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SwissTypography,
            content = content
        )
    }
}

@Composable
fun NordicStepCounterTheme(
    appTheme: AppTheme = AppTheme.NORDIC_DARK,
    content: @Composable () -> Unit
) = NordicStepTheme(appTheme = appTheme, content = content)
