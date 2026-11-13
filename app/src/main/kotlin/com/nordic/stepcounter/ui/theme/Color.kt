package com.nordic.stepcounter.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.nordic.stepcounter.data.preferences.AppTheme

@Immutable
data class StepColorPalette(
    val background: Color,
    val surface: Color,
    val border: Color,
    val primaryText: Color,
    val mutedText: Color,
    val accent: Color,
    val trackBackground: Color
)

val NordicDarkPalette = StepColorPalette(
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF161616),
    border = Color(0xFF262626),
    primaryText = Color(0xFFFFFFFF),
    mutedText = Color(0xFF8A8A8E),
    accent = Color(0xFF00E599),
    trackBackground = Color(0xFF222222)
)

val NordicLightPalette = StepColorPalette(
    background = Color(0xFFF8F9FA),
    surface = Color(0xFFFFFFFF),
    border = Color(0xFFE2E8F0),
    primaryText = Color(0xFF111111),
    mutedText = Color(0xFF64748B),
    accent = Color(0xFF00B377),
    trackBackground = Color(0xFFE2E8F0)
)

val GruvboxDarkPalette = StepColorPalette(
    background = Color(0xFF1D2021),
    surface = Color(0xFF282828),
    border = Color(0xFF3C3836),
    primaryText = Color(0xFFEBDBB2),
    mutedText = Color(0xFFA89984),
    accent = Color(0xFFB8BB26),
    trackBackground = Color(0xFF3C3836)
)

val GruvboxLightPalette = StepColorPalette(
    background = Color(0xFFFBF1C7),
    surface = Color(0xFFEBDBB2),
    border = Color(0xFFD5C4A1),
    primaryText = Color(0xFF3C3836),
    mutedText = Color(0xFF7C6F64),
    accent = Color(0xFF79740E),
    trackBackground = Color(0xFFD5C4A1)
)

val CatppuccinDarkPalette = StepColorPalette(
    background = Color(0xFF1E1E2E),
    surface = Color(0xFF313244),
    border = Color(0xFF45475A),
    primaryText = Color(0xFFCDD6F4),
    mutedText = Color(0xFFA6ADC8),
    accent = Color(0xFFA6E3A1),
    trackBackground = Color(0xFF45475A)
)

val CatppuccinLightPalette = StepColorPalette(
    background = Color(0xFFEFF1F5),
    surface = Color(0xFFE6E9EF),
    border = Color(0xFFCCD0DA),
    primaryText = Color(0xFF4C4F69),
    mutedText = Color(0xFF6C6F85),
    accent = Color(0xFF40A02B),
    trackBackground = Color(0xFFCCD0DA)
)

val TokyoNightDarkPalette = StepColorPalette(
    background = Color(0xFF1A1B26),
    surface = Color(0xFF24283B),
    border = Color(0xFF414868),
    primaryText = Color(0xFFC0CAF5),
    mutedText = Color(0xFF565F89),
    accent = Color(0xFF7AA2F7),
    trackBackground = Color(0xFF292E42)
)

val TokyoNightLightPalette = StepColorPalette(
    background = Color(0xFFE1E2E7),
    surface = Color(0xFFD5D6DB),
    border = Color(0xFFB4B5B9),
    primaryText = Color(0xFF3760BF),
    mutedText = Color(0xFF6172B0),
    accent = Color(0xFF2E7DE9),
    trackBackground = Color(0xFFC4C5CA)
)

fun AppTheme.toStepColorPalette(): StepColorPalette = when (this) {
    AppTheme.NORDIC_DARK -> NordicDarkPalette
    AppTheme.NORDIC_LIGHT -> NordicLightPalette
    AppTheme.GRUVBOX_DARK -> GruvboxDarkPalette
    AppTheme.GRUVBOX_LIGHT -> GruvboxLightPalette
    AppTheme.CATPPUCCIN_DARK -> CatppuccinDarkPalette
    AppTheme.CATPPUCCIN_LIGHT -> CatppuccinLightPalette
    AppTheme.TOKYONIGHT_DARK -> TokyoNightDarkPalette
    AppTheme.TOKYONIGHT_LIGHT -> TokyoNightLightPalette
}

val AppTheme.isDark: Boolean
    get() = when (this) {
        AppTheme.NORDIC_DARK,
        AppTheme.GRUVBOX_DARK,
        AppTheme.CATPPUCCIN_DARK,
        AppTheme.TOKYONIGHT_DARK -> true
        AppTheme.NORDIC_LIGHT,
        AppTheme.GRUVBOX_LIGHT,
        AppTheme.CATPPUCCIN_LIGHT,
        AppTheme.TOKYONIGHT_LIGHT -> false
    }

val AppTheme.displayName: String
    get() = when (this) {
        AppTheme.NORDIC_DARK -> "Nordic Dark"
        AppTheme.NORDIC_LIGHT -> "Nordic Light"
        AppTheme.GRUVBOX_DARK -> "Gruvbox Dark"
        AppTheme.GRUVBOX_LIGHT -> "Gruvbox Light"
        AppTheme.CATPPUCCIN_DARK -> "Catppuccin Dark"
        AppTheme.CATPPUCCIN_LIGHT -> "Catppuccin Light"
        AppTheme.TOKYONIGHT_DARK -> "Tokyo Night Dark"
        AppTheme.TOKYONIGHT_LIGHT -> "Tokyo Night Light"
    }

val LocalStepPalette = staticCompositionLocalOf { NordicDarkPalette }
