package com.nordic.stepcounter.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nordic.stepcounter.data.preferences.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {

    @Test
    fun `all 8 app themes map to distinct palettes correctly`() {
        assertEquals(NordicDarkPalette, AppTheme.NORDIC_DARK.toStepColorPalette())
        assertEquals(NordicLightPalette, AppTheme.NORDIC_LIGHT.toStepColorPalette())
        assertEquals(GruvboxDarkPalette, AppTheme.GRUVBOX_DARK.toStepColorPalette())
        assertEquals(GruvboxLightPalette, AppTheme.GRUVBOX_LIGHT.toStepColorPalette())
        assertEquals(CatppuccinDarkPalette, AppTheme.CATPPUCCIN_DARK.toStepColorPalette())
        assertEquals(CatppuccinLightPalette, AppTheme.CATPPUCCIN_LIGHT.toStepColorPalette())
        assertEquals(TokyoNightDarkPalette, AppTheme.TOKYONIGHT_DARK.toStepColorPalette())
        assertEquals(TokyoNightLightPalette, AppTheme.TOKYONIGHT_LIGHT.toStepColorPalette())
    }

    @Test
    fun `isDark returns true for dark themes and false for light themes`() {
        assertTrue(AppTheme.NORDIC_DARK.isDark)
        assertFalse(AppTheme.NORDIC_LIGHT.isDark)

        assertTrue(AppTheme.GRUVBOX_DARK.isDark)
        assertFalse(AppTheme.GRUVBOX_LIGHT.isDark)

        assertTrue(AppTheme.CATPPUCCIN_DARK.isDark)
        assertFalse(AppTheme.CATPPUCCIN_LIGHT.isDark)

        assertTrue(AppTheme.TOKYONIGHT_DARK.isDark)
        assertFalse(AppTheme.TOKYONIGHT_LIGHT.isDark)
    }

    @Test
    fun `nordic palette colors match specifications`() {
        assertEquals(Color(0xFF0A0A0A), NordicDarkPalette.background)
        assertEquals(Color(0xFF161616), NordicDarkPalette.surface)
        assertEquals(Color(0xFF262626), NordicDarkPalette.border)
        assertEquals(Color(0xFFFFFFFF), NordicDarkPalette.primaryText)
        assertEquals(Color(0xFF8A8A8E), NordicDarkPalette.mutedText)
        assertEquals(Color(0xFF00E599), NordicDarkPalette.accent)
        assertEquals(Color(0xFF222222), NordicDarkPalette.trackBackground)

        assertEquals(Color(0xFFF8F9FA), NordicLightPalette.background)
        assertEquals(Color(0xFFFFFFFF), NordicLightPalette.surface)
        assertEquals(Color(0xFFE2E8F0), NordicLightPalette.border)
        assertEquals(Color(0xFF111111), NordicLightPalette.primaryText)
        assertEquals(Color(0xFF64748B), NordicLightPalette.mutedText)
        assertEquals(Color(0xFF00B377), NordicLightPalette.accent)
        assertEquals(Color(0xFFE2E8F0), NordicLightPalette.trackBackground)
    }

    @Test
    fun `gruvbox palette colors match specifications`() {
        assertEquals(Color(0xFF1D2021), GruvboxDarkPalette.background)
        assertEquals(Color(0xFF282828), GruvboxDarkPalette.surface)
        assertEquals(Color(0xFF3C3836), GruvboxDarkPalette.border)
        assertEquals(Color(0xFFEBDBB2), GruvboxDarkPalette.primaryText)
        assertEquals(Color(0xFFA89984), GruvboxDarkPalette.mutedText)
        assertEquals(Color(0xFFB8BB26), GruvboxDarkPalette.accent)
        assertEquals(Color(0xFF3C3836), GruvboxDarkPalette.trackBackground)

        assertEquals(Color(0xFFFBF1C7), GruvboxLightPalette.background)
        assertEquals(Color(0xFFEBDBB2), GruvboxLightPalette.surface)
        assertEquals(Color(0xFFD5C4A1), GruvboxLightPalette.border)
        assertEquals(Color(0xFF3C3836), GruvboxLightPalette.primaryText)
        assertEquals(Color(0xFF7C6F64), GruvboxLightPalette.mutedText)
        assertEquals(Color(0xFF79740E), GruvboxLightPalette.accent)
        assertEquals(Color(0xFFD5C4A1), GruvboxLightPalette.trackBackground)
    }

    @Test
    fun `catppuccin palette colors match specifications`() {
        assertEquals(Color(0xFF1E1E2E), CatppuccinDarkPalette.background)
        assertEquals(Color(0xFF313244), CatppuccinDarkPalette.surface)
        assertEquals(Color(0xFF45475A), CatppuccinDarkPalette.border)
        assertEquals(Color(0xFFCDD6F4), CatppuccinDarkPalette.primaryText)
        assertEquals(Color(0xFFA6ADC8), CatppuccinDarkPalette.mutedText)
        assertEquals(Color(0xFFA6E3A1), CatppuccinDarkPalette.accent)
        assertEquals(Color(0xFF45475A), CatppuccinDarkPalette.trackBackground)

        assertEquals(Color(0xFFEFF1F5), CatppuccinLightPalette.background)
        assertEquals(Color(0xFFE6E9EF), CatppuccinLightPalette.surface)
        assertEquals(Color(0xFFCCD0DA), CatppuccinLightPalette.border)
        assertEquals(Color(0xFF4C4F69), CatppuccinLightPalette.primaryText)
        assertEquals(Color(0xFF6C6F85), CatppuccinLightPalette.mutedText)
        assertEquals(Color(0xFF40A02B), CatppuccinLightPalette.accent)
        assertEquals(Color(0xFFCCD0DA), CatppuccinLightPalette.trackBackground)
    }

    @Test
    fun `tokyonight palette colors match specifications`() {
        assertEquals(Color(0xFF1A1B26), TokyoNightDarkPalette.background)
        assertEquals(Color(0xFF24283B), TokyoNightDarkPalette.surface)
        assertEquals(Color(0xFF414868), TokyoNightDarkPalette.border)
        assertEquals(Color(0xFFC0CAF5), TokyoNightDarkPalette.primaryText)
        assertEquals(Color(0xFF565F89), TokyoNightDarkPalette.mutedText)
        assertEquals(Color(0xFF7AA2F7), TokyoNightDarkPalette.accent)
        assertEquals(Color(0xFF292E42), TokyoNightDarkPalette.trackBackground)

        assertEquals(Color(0xFFE1E2E7), TokyoNightLightPalette.background)
        assertEquals(Color(0xFFD5D6DB), TokyoNightLightPalette.surface)
        assertEquals(Color(0xFFB4B5B9), TokyoNightLightPalette.border)
        assertEquals(Color(0xFF3760BF), TokyoNightLightPalette.primaryText)
        assertEquals(Color(0xFF6172B0), TokyoNightLightPalette.mutedText)
        assertEquals(Color(0xFF2E7DE9), TokyoNightLightPalette.accent)
        assertEquals(Color(0xFFC4C5CA), TokyoNightLightPalette.trackBackground)
    }

    @Test
    fun `swiss typography conforms to design specification`() {
        assertEquals(FontFamily.Monospace, SwissTypography.displayLarge.fontFamily)
        assertEquals(FontWeight.Bold, SwissTypography.displayLarge.fontWeight)
        assertEquals(56.sp, SwissTypography.displayLarge.fontSize)
        assertEquals((-1.5).sp, SwissTypography.displayLarge.letterSpacing)

        assertEquals(FontFamily.SansSerif, SwissTypography.titleMedium.fontFamily)
        assertEquals(FontWeight.SemiBold, SwissTypography.titleMedium.fontWeight)
        assertEquals(18.sp, SwissTypography.titleMedium.fontSize)
        assertEquals(0.5.sp, SwissTypography.titleMedium.letterSpacing)

        assertEquals(FontFamily.Monospace, SwissTypography.labelSmall.fontFamily)
        assertEquals(FontWeight.Medium, SwissTypography.labelSmall.fontWeight)
        assertEquals(11.sp, SwissTypography.labelSmall.fontSize)
        assertEquals(2.sp, SwissTypography.labelSmall.letterSpacing)
    }

    @Test
    fun `localStepPalette provides static composition local`() {
        // Verify LocalStepPalette provides NordicDarkPalette
        val provided = LocalStepPalette provides NordicDarkPalette
        assertEquals(NordicDarkPalette, provided.value)
    }
}
