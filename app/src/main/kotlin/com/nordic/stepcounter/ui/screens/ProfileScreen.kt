package com.nordic.stepcounter.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.R
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.domain.StepCalculationEngine
import com.nordic.stepcounter.domain.backup.BackupSnapshot
import com.nordic.stepcounter.ui.BackupErrorReason
import com.nordic.stepcounter.ui.BackupSuccessType
import com.nordic.stepcounter.ui.BackupUiState
import com.nordic.stepcounter.ui.StepUiState
import com.nordic.stepcounter.ui.theme.LocalStepPalette
import com.nordic.stepcounter.ui.theme.displayName
import com.nordic.stepcounter.ui.theme.isDark
import com.nordic.stepcounter.ui.theme.toStepColorPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

private val GOAL_PRESETS = listOf(6000, 8000, 10000, 12000)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    state: StepUiState,
    onSelectGoal: (Int) -> Unit,
    onSelectTheme: (AppTheme) -> Unit,
    onUpdatePhysicalProfile: (heightCm: Float, weightKg: Float, isMale: Boolean, customStrideMeters: Float) -> Unit = { _, _, _, _ -> },
    backupState: BackupUiState = BackupUiState.Idle,
    onRequestExportBackup: (passphrase: String?) -> Unit = {},
    onRequestRestoreBackup: () -> Unit = {},
    onUnlockEncryptedBackup: (pendingBytes: ByteArray, passphrase: String) -> Unit = { _, _ -> },
    onConfirmRestoreBackup: (snapshot: BackupSnapshot, restoreProfile: Boolean) -> Unit = { _, _ -> },
    onDismissBackupState: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    val locale = Locale.getDefault()
    var showCalibrationDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomGoalDialog by rememberSaveable { mutableStateOf(false) }
    var showExportPassphraseDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.profile_header),
            style = MaterialTheme.typography.labelSmall,
            color = palette.mutedText
        )

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.profile_daily_goal),
            style = MaterialTheme.typography.labelSmall,
            color = palette.primaryText
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            GOAL_PRESETS.forEach { preset ->
                FilterChip(
                    selected = state.dailyGoal == preset,
                    onClick = { onSelectGoal(preset) },
                    label = {
                        Text(
                            String.format(locale, "%,d", preset),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
            val isCustomGoal = state.dailyGoal !in GOAL_PRESETS
            val customLabel = if (isCustomGoal) {
                stringResource(
                    R.string.profile_custom_goal_active,
                    String.format(locale, "%,d", state.dailyGoal)
                )
            } else {
                stringResource(R.string.profile_custom_goal)
            }
            FilterChip(
                selected = isCustomGoal,
                onClick = { showCustomGoalDialog = true },
                label = { Text(customLabel, style = MaterialTheme.typography.labelSmall) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.profile_biometrics_stride),
                style = MaterialTheme.typography.labelSmall,
                color = palette.primaryText
            )
            Text(
                text = stringResource(R.string.profile_calibrate),
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
                    text = stringResource(
                        R.string.profile_biometrics_summary,
                        state.heightCm.toInt(),
                        state.weightKg.toInt()
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.primaryText
                )
                Spacer(modifier = Modifier.height(4.dp))
                val sexLabel = stringResource(if (state.isMale) R.string.sex_male else R.string.sex_female)
                val strideType = stringResource(
                    if (state.customStrideMeters > 0f) R.string.profile_stride_custom else R.string.profile_stride_estimated
                )
                Text(
                    text = stringResource(
                        R.string.profile_stride_summary,
                        sexLabel,
                        state.effectiveStrideLengthMeters,
                        strideType
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.mutedText
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.profile_cloud_backup),
            style = MaterialTheme.typography.labelSmall,
            color = palette.primaryText
        )
        Spacer(modifier = Modifier.height(8.dp))
        val isBackupWorking = backupState is BackupUiState.InProgress
        val lastBackupFormatted = remember(state.lastBackupTimestampMs, locale) {
            if (state.lastBackupTimestampMs > 0L) {
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                    .withLocale(locale)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(state.lastBackupTimestampMs))
            } else {
                null
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = palette.surface,
            border = BorderStroke(1.dp, palette.border)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.backup_auto_status),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.primaryText
                )
                Text(
                    text = if (lastBackupFormatted != null) {
                        stringResource(R.string.backup_last_manual_date, lastBackupFormatted)
                    } else {
                        stringResource(R.string.backup_last_manual_never)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.mutedText
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        enabled = !isBackupWorking,
                        onClick = { showExportPassphraseDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.backup_export_button),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.background,
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        enabled = !isBackupWorking,
                        onClick = onRequestRestoreBackup,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.backup_restore_button),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.primaryText,
                            maxLines = 1
                        )
                    }
                }
                when (backupState) {
                    is BackupUiState.InProgress -> {
                        Text(
                            text = stringResource(
                                if (backupState.isExport) {
                                    R.string.backup_working_export
                                } else {
                                    R.string.backup_working_restore
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.accent
                        )
                    }
                    is BackupUiState.Success -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (backupState.type) {
                                    BackupSuccessType.EXPORT -> stringResource(
                                        R.string.backup_success_export,
                                        backupState.dayCount
                                    )
                                    BackupSuccessType.RESTORE -> stringResource(
                                        R.string.backup_success_restore,
                                        backupState.dayCount,
                                        backupState.updatedDays
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.accent,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onDismissBackupState) {
                                Text(stringResource(R.string.action_dismiss), color = palette.mutedText)
                            }
                        }
                    }
                    is BackupUiState.Error -> {
                        val errorText = when (backupState.reason) {
                            BackupErrorReason.READ_FAILED -> stringResource(R.string.backup_error_read_failed)
                            BackupErrorReason.WRITE_FAILED -> stringResource(R.string.backup_error_write_failed)
                            BackupErrorReason.FILE_TOO_LARGE -> stringResource(R.string.backup_error_too_large)
                            BackupErrorReason.INVALID_FORMAT -> stringResource(R.string.backup_error_invalid_format)
                            BackupErrorReason.UNSUPPORTED_VERSION -> stringResource(R.string.backup_error_unsupported_version)
                            BackupErrorReason.UNKNOWN -> stringResource(R.string.backup_error_unknown)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = errorText,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.accent,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onDismissBackupState) {
                                Text(stringResource(R.string.action_dismiss), color = palette.mutedText)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.profile_color_theme),
            style = MaterialTheme.typography.labelSmall,
            color = palette.primaryText
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppTheme.entries.chunked(2).forEach { themePair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    themePair.forEach { themeOption ->
                        val optionPalette = themeOption.toStepColorPalette()
                        val isSelected = state.appTheme == themeOption
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { onSelectTheme(themeOption) },
                            shape = RoundedCornerShape(10.dp),
                            color = optionPalette.surface,
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) optionPalette.accent else optionPalette.border
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = themeOption.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = optionPalette.primaryText
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = stringResource(
                                        if (themeOption.isDark) R.string.profile_theme_dark else R.string.profile_theme_light
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = optionPalette.accent
                                )
                            }
                        }
                    }
                    if (themePair.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    if (showCustomGoalDialog) {
        var goalText by rememberSaveable(state.dailyGoal) {
            mutableStateOf(state.dailyGoal.toString())
        }
        val parsedGoal = goalText.trim().toIntOrNull()
        val isGoalValid = parsedGoal != null && parsedGoal in 1_000..100_000

        AlertDialog(
            onDismissRequest = { showCustomGoalDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    text = stringResource(R.string.profile_custom_goal_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.primaryText
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = goalText,
                        onValueChange = { input ->
                            goalText = input.filter { it.isDigit() }.take(6)
                        },
                        singleLine = true,
                        isError = goalText.isNotEmpty() && !isGoalValid,
                        label = {
                            Text(
                                text = stringResource(R.string.profile_custom_goal_hint),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (goalText.isNotEmpty() && !isGoalValid) {
                        Text(
                            text = stringResource(R.string.profile_custom_goal_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.accent
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = isGoalValid,
                    onClick = {
                        if (parsedGoal != null && parsedGoal in 1_000..100_000) {
                            onSelectGoal(parsedGoal)
                            showCustomGoalDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                ) {
                    Text(stringResource(R.string.action_save), color = palette.background)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomGoalDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = palette.mutedText)
                }
            }
        )
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
                    text = stringResource(R.string.dialog_calibrate_title),
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
                        Text(
                            stringResource(R.string.dialog_sex_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.mutedText
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = editIsMale,
                                onClick = { editIsMale = true },
                                label = { Text(stringResource(R.string.sex_male), style = MaterialTheme.typography.labelSmall) }
                            )
                            FilterChip(
                                selected = !editIsMale,
                                onClick = { editIsMale = false },
                                label = { Text(stringResource(R.string.sex_female), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    // Height adjustment
                    Column {
                        Text(
                            stringResource(R.string.dialog_height_label, editHeight.toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.mutedText
                        )
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
                        Text(
                            stringResource(R.string.dialog_weight_label, editWeight.toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.mutedText
                        )
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

                    // Custom Stride adjustment (rounded cleanly to 2 decimal places)
                    Column {
                        val computedStride = StepCalculationEngine.calculateDefaultStrideLengthMeters(editHeight, editIsMale)
                        Text(
                            text = if (editCustomStride > 0f) {
                                stringResource(R.string.dialog_custom_stride_label, editCustomStride)
                            } else {
                                stringResource(R.string.dialog_estimated_stride_label, computedStride)
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
                                label = {
                                    Text(
                                        stringResource(R.string.stride_auto),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = {
                                    val base = roundStride(if (editCustomStride > 0f) editCustomStride else computedStride)
                                    editCustomStride = roundStride(base - 0.02f).coerceIn(0.30f, 1.50f)
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                Text("-0.02", color = palette.primaryText, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = {
                                    val base = roundStride(if (editCustomStride > 0f) editCustomStride else computedStride)
                                    editCustomStride = roundStride(base + 0.02f).coerceIn(0.30f, 1.50f)
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
                        val finalCustomStride = if (editCustomStride > 0f) roundStride(editCustomStride) else 0f
                        onUpdatePhysicalProfile(editHeight, editWeight, editIsMale, finalCustomStride)
                        showCalibrationDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                ) {
                    Text(stringResource(R.string.action_save), color = palette.background)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCalibrationDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = palette.mutedText)
                }
            }
        )
    }

    if (showExportPassphraseDialog) {
        var passphrase by remember { mutableStateOf("") }
        var confirmPassphrase by remember { mutableStateOf("") }
        var showPassword by remember { mutableStateOf(false) }
        val needsConfirm = passphrase.isNotEmpty()
        val isMismatch = needsConfirm && confirmPassphrase != passphrase
        val canProceed = !needsConfirm || confirmPassphrase == passphrase

        AlertDialog(
            onDismissRequest = { showExportPassphraseDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    text = stringResource(R.string.backup_export_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.primaryText
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.backup_export_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.mutedText
                    )
                    PassphraseTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = stringResource(R.string.backup_passphrase_label),
                        showPassword = showPassword,
                        onToggleShowPassword = { showPassword = !showPassword }
                    )
                    if (needsConfirm) {
                        OutlinedTextField(
                            value = confirmPassphrase,
                            onValueChange = { confirmPassphrase = it },
                            singleLine = true,
                            isError = isMismatch,
                            label = { Text(stringResource(R.string.backup_passphrase_confirm_label)) },
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (isMismatch) {
                            Text(
                                text = stringResource(R.string.backup_passphrase_mismatch),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.accent
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = canProceed,
                    onClick = {
                        val chosen = passphrase.ifEmpty { null }
                        showExportPassphraseDialog = false
                        onRequestExportBackup(chosen)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                ) {
                    Text(stringResource(R.string.backup_export_continue), color = palette.background)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportPassphraseDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = palette.mutedText)
                }
            }
        )
    }

    if (backupState is BackupUiState.PassphraseRequired) {
        var unlockPassphrase by remember(backupState.pendingBytes) { mutableStateOf("") }
        var showPassword by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = onDismissBackupState,
            containerColor = palette.surface,
            title = {
                Text(
                    text = stringResource(R.string.backup_unlock_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.primaryText
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.backup_unlock_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.mutedText
                    )
                    PassphraseTextField(
                        value = unlockPassphrase,
                        onValueChange = { unlockPassphrase = it },
                        label = stringResource(R.string.backup_unlock_label),
                        showPassword = showPassword,
                        onToggleShowPassword = { showPassword = !showPassword },
                        isError = backupState.isRetry
                    )
                    if (backupState.isRetry) {
                        Text(
                            text = stringResource(R.string.backup_unlock_wrong),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.accent
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = unlockPassphrase.isNotEmpty(),
                    onClick = { onUnlockEncryptedBackup(backupState.pendingBytes, unlockPassphrase) },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                ) {
                    Text(stringResource(R.string.backup_unlock_action), color = palette.background)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissBackupState) {
                    Text(stringResource(R.string.action_cancel), color = palette.mutedText)
                }
            }
        )
    }

    if (backupState is BackupUiState.RestorePreview) {
        var restoreProfile by remember(backupState.snapshot) { mutableStateOf(true) }
        val summary = backupState.summary

        AlertDialog(
            onDismissRequest = onDismissBackupState,
            containerColor = palette.surface,
            title = {
                Text(
                    text = stringResource(R.string.backup_preview_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.primaryText
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (summary.dayCount > 0) {
                        Text(
                            text = stringResource(
                                R.string.backup_preview_summary,
                                summary.dayCount,
                                String.format(locale, "%,d", summary.totalSteps)
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.primaryText
                        )
                        if (summary.earliestDate != null && summary.latestDate != null) {
                            Text(
                                text = stringResource(
                                    R.string.backup_preview_range,
                                    summary.earliestDate,
                                    summary.latestDate
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.mutedText
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.backup_preview_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.mutedText
                        )
                    }
                    Text(
                        text = stringResource(R.string.backup_preview_merge_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.mutedText
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { restoreProfile = !restoreProfile },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = restoreProfile,
                            onCheckedChange = { restoreProfile = it }
                        )
                        Text(
                            text = stringResource(R.string.backup_preview_restore_profile),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.primaryText
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onConfirmRestoreBackup(backupState.snapshot, restoreProfile) },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent)
                ) {
                    Text(stringResource(R.string.backup_preview_confirm), color = palette.background)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissBackupState) {
                    Text(stringResource(R.string.action_cancel), color = palette.mutedText)
                }
            }
        )
    }
}

@Composable
private fun PassphraseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    showPassword: Boolean,
    onToggleShowPassword: () -> Unit,
    isError: Boolean = false
) {
    val palette = LocalStepPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        isError = isError,
        label = { Text(label) },
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = onToggleShowPassword) {
                Text(
                    text = stringResource(
                        if (showPassword) R.string.backup_passphrase_hide else R.string.backup_passphrase_show
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accent
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

private fun roundStride(value: Float): Float = (value * 100f).roundToInt() / 100f
