package com.nordic.stepcounter.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.ui.StepUiState
import com.nordic.stepcounter.ui.theme.LocalStepPalette
import com.nordic.stepcounter.ui.theme.displayName
import com.nordic.stepcounter.ui.theme.isDark
import com.nordic.stepcounter.ui.theme.toStepColorPalette

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    state: StepUiState,
    onSelectGoal: (Int) -> Unit,
    onSelectTheme: (AppTheme) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = "PROFILE & CALIBRATION",
            style = MaterialTheme.typography.labelSmall,
            color = palette.mutedText
        )

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "DAILY STEP GOAL",
            style = MaterialTheme.typography.labelSmall,
            color = palette.primaryText
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(6000, 8000, 10000, 12000).forEach { preset ->
                FilterChip(
                    selected = state.dailyGoal == preset,
                    onClick = { onSelectGoal(preset) },
                    label = { Text("%,d".format(preset), style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "BIOMETRICS & STRIDE",
            style = MaterialTheme.typography.labelSmall,
            color = palette.primaryText
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Height: ${state.heightCm.toInt()} cm • Weight: ${state.weightKg.toInt()} kg • Stride: ${"%.2f".format(state.effectiveStrideMeters)} m",
            style = MaterialTheme.typography.titleMedium,
            color = palette.mutedText
        )

        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "COLOR THEME (8 PALETTES)",
            style = MaterialTheme.typography.labelSmall,
            color = palette.primaryText
        )
        Spacer(modifier = Modifier.height(12.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 2
        ) {
            AppTheme.entries.forEach { themeOption ->
                val optionPalette = themeOption.toStepColorPalette()
                val isSelected = state.appTheme == themeOption
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelectTheme(themeOption) },
                    shape = RoundedCornerShape(10.dp),
                    color = optionPalette.surface,
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) optionPalette.accent else optionPalette.border
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = themeOption.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = optionPalette.primaryText
                        )
                        Text(
                            text = if (themeOption.isDark) "DARK" else "LIGHT",
                            style = MaterialTheme.typography.labelSmall,
                            color = optionPalette.accent
                        )
                    }
                }
            }
        }
    }
}
