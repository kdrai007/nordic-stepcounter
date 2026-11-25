package com.nordic.stepcounter.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.R
import com.nordic.stepcounter.ui.StepUiState
import com.nordic.stepcounter.ui.components.MetricStatCard
import com.nordic.stepcounter.ui.components.PauseResumeButton
import com.nordic.stepcounter.ui.components.RadialProgressGauge
import com.nordic.stepcounter.ui.theme.LocalStepPalette
import java.util.Locale

@Composable
fun TodayScreen(
    state: StepUiState,
    onTogglePause: () -> Unit,
    hasPermission: Boolean = true,
    hasNotificationPermission: Boolean = true,
    hasStepSensor: Boolean = true,
    onRequestPermission: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onDismissDowntimeNotice: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.app_header_title),
                style = MaterialTheme.typography.labelSmall,
                color = palette.mutedText
            )

            Spacer(modifier = Modifier.height(16.dp))

            RadialProgressGauge(
                steps = state.todaySteps,
                goal = state.dailyGoal,
                isPaused = state.isPaused
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricStatCard(
                        value = String.format(Locale.getDefault(), "%.2f", state.distanceKm),
                        unitLabel = stringResource(R.string.metric_unit_km),
                        modifier = Modifier.weight(1f)
                    )
                    MetricStatCard(
                        value = String.format(Locale.getDefault(), "%.0f", state.caloriesKcal),
                        unitLabel = stringResource(R.string.metric_unit_kcal),
                        modifier = Modifier.weight(1f)
                    )
                    MetricStatCard(
                        value = "${state.activeMinutes}",
                        unitLabel = stringResource(R.string.metric_unit_mins),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                if (!hasStepSensor) {
                    InfoBanner(
                        message = stringResource(R.string.banner_no_sensor)
                    )
                } else if (!hasPermission) {
                    ActionBanner(
                        message = stringResource(R.string.banner_activity_permission),
                        actionLabel = stringResource(R.string.action_grant),
                        onAction = onRequestPermission
                    )
                } else if (!hasNotificationPermission) {
                    ActionBanner(
                        message = stringResource(R.string.banner_notification_permission),
                        actionLabel = stringResource(R.string.action_grant),
                        onAction = onRequestNotificationPermission
                    )
                }

                if (state.downtimeGapFromDate.isNotEmpty() && state.downtimeGapToDate.isNotEmpty()) {
                    ActionBanner(
                        message = stringResource(
                            R.string.banner_downtime_gap,
                            state.downtimeGapFromDate,
                            state.downtimeGapToDate
                        ),
                        actionLabel = stringResource(R.string.action_dismiss),
                        onAction = onDismissDowntimeNotice
                    )
                }

                PauseResumeButton(
                    isPaused = state.isPaused,
                    onToggle = onTogglePause,
                    enabled = hasPermission && hasStepSensor
                )
            }
        }
    }
}

@Composable
private fun InfoBanner(
    message: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(8.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.border)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = palette.mutedText,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun ActionBanner(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(8.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = palette.mutedText,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAction) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accent
                )
            }
        }
    }
}
