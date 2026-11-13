package com.nordic.stepcounter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.ui.StepUiState
import com.nordic.stepcounter.ui.components.MetricStatCard
import com.nordic.stepcounter.ui.components.PauseResumeButton
import com.nordic.stepcounter.ui.components.RadialProgressGauge
import com.nordic.stepcounter.ui.theme.LocalStepPalette

@Composable
fun TodayScreen(
    state: StepUiState,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "NORDIC STEP COUNTER",
            style = MaterialTheme.typography.labelSmall,
            color = palette.mutedText
        )

        RadialProgressGauge(
            steps = state.todaySteps,
            goal = state.dailyGoal,
            isPaused = state.isPaused
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricStatCard(
                    value = "%.2f".format(state.distanceKm),
                    unitLabel = "KM",
                    modifier = Modifier.weight(1f)
                )
                MetricStatCard(
                    value = "%.0f".format(state.caloriesKcal),
                    unitLabel = "KCAL",
                    modifier = Modifier.weight(1f)
                )
                MetricStatCard(
                    value = "${state.activeMinutes}",
                    unitLabel = "MINS",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            PauseResumeButton(
                isPaused = state.isPaused,
                onToggle = onTogglePause
            )
        }
    }
}
