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
import kotlin.math.pow

class ThemeTest {

    private fun Color.relativeLuminance(): Double {
        fun linearize(channel: Float): Double {
            val c = channel.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linearize(red) + 0.7152 * linearize(green) + 0.0722 * linearize(blue)
    }

    private fun contrastRatio(fg: Color, bg: Color): Double {
        val l1 = fg.relativeLuminance()
        val l2 = bg.relativeLuminance()
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

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
    fun `all 8 palettes meet WCAG AA 4_5 to 1 contrast for primaryText, mutedText, and accent`() {
        for (theme in AppTheme.entries) {
            val palette = theme.toStepColorPalette()
            assertTrue(
                "${theme.name} primaryText on background failed WCAG AA",
                contrastRatio(palette.primaryText, palette.background) >= 4.5
            )
            assertTrue(
                "${theme.name} primaryText on surface failed WCAG AA",
                contrastRatio(palette.primaryText, palette.surface) >= 4.5
            )
            assertTrue(
                "${theme.name} mutedText on background failed WCAG AA",
                contrastRatio(palette.mutedText, palette.background) >= 4.5
            )
            assertTrue(
                "${theme.name} mutedText on surface failed WCAG AA",
                contrastRatio(palette.mutedText, palette.surface) >= 4.5
            )
            assertTrue(
                "${theme.name} accent on background failed WCAG AA",
                contrastRatio(palette.accent, palette.background) >= 4.5
            )
            assertTrue(
                "${theme.name} accent on surface failed WCAG AA",
                contrastRatio(palette.accent, palette.surface) >= 4.5
            )
        }
    }

    @Test
    fun `palette colors match WCAG updated specifications`() {
        assertEquals(Color(0xFF00E599), NordicDarkPalette.accent)
        assertEquals(Color(0xFF007A52), NordicLightPalette.accent)
        assertEquals(Color(0xFF5D5248), GruvboxLightPalette.mutedText)
        assertEquals(Color(0xFF5E5A08), GruvboxLightPalette.accent)
        assertEquals(Color(0xFF236F18), CatppuccinLightPalette.accent)
        assertEquals(Color(0xFF8C96C0), TokyoNightDarkPalette.mutedText)
        assertEquals(Color(0xFF27499A), TokyoNightLightPalette.primaryText)
        assertEquals(Color(0xFF465486), TokyoNightLightPalette.mutedText)
        assertEquals(Color(0xFF1D56A6), TokyoNightLightPalette.accent)
    }

    @Test
    fun `swiss typography conforms to design specification including bodySmall`() {
        assertEquals(FontFamily.Monospace, SwissTypography.displayLarge.fontFamily)
        assertEquals(FontWeight.Bold, SwissTypography.displayLarge.fontWeight)
        assertEquals(56.sp, SwissTypography.displayLarge.fontSize)
        assertEquals((-1.5).sp, SwissTypography.displayLarge.letterSpacing)

        assertEquals(FontFamily.SansSerif, SwissTypography.titleMedium.fontFamily)
        assertEquals(FontWeight.SemiBold, SwissTypography.titleMedium.fontWeight)
        assertEquals(18.sp, SwissTypography.titleMedium.fontSize)
        assertEquals(0.5.sp, SwissTypography.titleMedium.letterSpacing)

        assertEquals(FontFamily.SansSerif, SwissTypography.bodySmall.fontFamily)
        assertEquals(FontWeight.Normal, SwissTypography.bodySmall.fontWeight)
        assertEquals(12.sp, SwissTypography.bodySmall.fontSize)

        assertEquals(FontFamily.Monospace, SwissTypography.labelSmall.fontFamily)
        assertEquals(FontWeight.Medium, SwissTypography.labelSmall.fontWeight)
        assertEquals(11.sp, SwissTypography.labelSmall.fontSize)
        assertEquals(2.sp, SwissTypography.labelSmall.letterSpacing)
    }

    @Test
    fun `appTheme storageKey and legacy enum name deserialization round-trip`() {
        for (theme in AppTheme.entries) {
            assertEquals(theme, AppTheme.fromStorageValue(theme.storageKey))
            assertEquals(theme, AppTheme.fromStorageValue(theme.name))
        }
        assertEquals(AppTheme.NORDIC_DARK, AppTheme.fromStorageValue("unknown_theme"))
        assertEquals(AppTheme.NORDIC_DARK, AppTheme.fromStorageValue(null))
    }

    @Test
    fun `localStepPalette provides static composition local`() {
        val provided = LocalStepPalette provides NordicDarkPalette
        assertEquals(NordicDarkPalette, provided.value)
    }
}
