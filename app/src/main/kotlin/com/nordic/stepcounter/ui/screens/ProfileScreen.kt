package com.nordic.stepcounter.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.domain.StepCalculationEngine
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
    onUpdatePhysicalProfile: (heightCm: Float, weightKg: Float, isMale: Boolean, customStrideMeters: Float) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    var showCalibrationDialog by rememberSaveable { mutableStateOf(false) }

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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(6000, 8000, 10000, 12000).forEach { preset ->
                FilterChip(
                    selected = state.dailyGoal == preset,
                    onClick = { onSelectGoal(preset) },
                    label = { Text("%,d".format(preset), style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "BIOMETRICS & STRIDE",
                style = MaterialTheme.typography.labelSmall,
                color = palette.primaryText
            )
            Text(
                text = "CALIBRATE",
                style = MaterialTheme.typography.labelSmall,
                color = palette.accent,
                modifier = Modifier
                    .clickable { showCalibrationDialog = true }
                    .padding(4.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showCalibrationDialog = true },
            shape = RoundedCornerShape(10.dp),
            color = palette.surface,
            border = BorderStroke(1.dp, palette.border)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Height: ${state.heightCm.toInt()} cm • Weight: ${state.weightKg.toInt()} kg",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.primaryText
                )
                Spacer(modifier = Modifier.height(4.dp))
                val sexLabel = if (state.isMale) "Male" else "Female"
                val strideType = if (state.customStrideMeters > 0f) "Custom" else "Estimated"
                Text(
                    text = "Sex: $sexLabel • Stride: ${"%.2f".format(state.effectiveStrideMeters)} m ($strideType)",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.mutedText
                )
            }
        }

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

    if (showCalibrationDialog) {
        var editHeight by rememberSaveable { mutableFloatStateOf(state.heightCm) }
        var editWeight by rememberSaveable { mutableFloatStateOf(state.weightKg) }
        var editIsMale by rememberSaveable { mutableStateOf(state.isMale) }
        var editCustomStride by rememberSaveable { mutableFloatStateOf(state.customStrideMeters) }

        AlertDialog(
            onDismissRequest = { showCalibrationDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    text = "CALIBRATE BIOMETRICS",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.primaryText
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Sex selection
                    Column {
                        Text("SEX", style = MaterialTheme.typography.labelSmall, color = palette.mutedText)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = editIsMale,
                                onClick = { editIsMale = true },
                                label = { Text("Male", style = MaterialTheme.typography.labelSmall) }
                            )
                            FilterChip(
                                selected = !editIsMale,
                                onClick = { editIsMale = false },
                                label = { Text("Female", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    // Height adjustment
                    Column {
                        Text("HEIGHT: ${editHeight.toInt()} CM", style = MaterialTheme.typography.labelSmall, color = palette.mutedText)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { editHeight = (editHeight - 5f).coerceAtLeast(100f) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text("-5", color = palette.primaryText, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { editHeight = (editHeight - 1f).coerceAtLeast(100f) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text("-1", color = palette.primaryText, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { editHeight = (editHeight + 1f).coerceAtMost(250f) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text("+1", color = palette.primaryText, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { editHeight = (editHeight + 5f).coerceAtMost(250f) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text("+5", color = palette.primaryText, maxLines = 1)
                            }
                        }
                    }

                    // Weight adjustment
                    Column {
                        Text("WEIGHT: ${editWeight.toInt()} KG", style = MaterialTheme.typography.labelSmall, color = palette.mutedText)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { editWeight = (editWeight - 5f).coerceAtLeast(30f) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text("-5", color = palette.primaryText, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { editWeight = (editWeight - 1f).coerceAtLeast(30f) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text("-1", color = palette.primaryText, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { editWeight = (editWeight + 1f).coerceAtMost(250f) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text("+1", color = palette.primaryText, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { editWeight = (editWeight + 5f).coerceAtMost(250f) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text("+5", color = palette.primaryText, maxLines = 1)
                            }
                        }
                    }

                    // Custom Stride adjustment
                    Column {
                        val computedStride = StepCalculationEngine.calculateDefaultStrideLengthMeters(editHeight, editIsMale)
                        Text(
                            text = if (editCustomStride > 0f) {
                                "CUSTOM STRIDE: ${"%.2f".format(editCustomStride)} M"
                            } else {
                                "ESTIMATED STRIDE: ${"%.2f".format(computedStride)} M"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.mutedText
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = editCustomStride == 0f,
                                onClick = { editCustomStride = 0f },
                                label = { Text("Auto", style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = {
                                    val current = if (editCustomStride > 0f) editCustomStride else computedStride
                                    editCustomStride = (current - 0.02f).coerceAtLeast(0.3f)
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text("-0.02", color = palette.primaryText, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = {
                                    val current = if (editCustomStride > 0f) editCustomStride else computedStride
                                    editCustomStride = (current + 0.02f).coerceAtMost(1.5f)
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text("+0.02", color = palette.primaryText, maxLines = 1)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdatePhysicalProfile(editHeight, editWeight, editIsMale, editCustomStride)
                        showCalibrationDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                ) {
                    Text("SAVE", color = palette.background)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCalibrationDialog = false }) {
                    Text("CANCEL", color = palette.mutedText)
                }
            }
        )
    }
}
