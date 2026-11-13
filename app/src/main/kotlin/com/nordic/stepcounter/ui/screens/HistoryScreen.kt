package com.nordic.stepcounter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.ui.StepUiState
import com.nordic.stepcounter.ui.components.MetricStatCard
import com.nordic.stepcounter.ui.components.SwissBarChart
import com.nordic.stepcounter.ui.theme.LocalStepPalette

@Composable
fun HistoryScreen(
    state: StepUiState,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Day (24h), 1 = Week (7d), 2 = Month (30d)

    val chartValues = when (selectedTab) {
        0 -> (0..23).map { hour -> state.hourlyToday.firstOrNull { it.hour == hour }?.steps ?: 0 }
        1 -> state.recentRecords.take(7).reversed().map { it.steps }
        else -> state.recentRecords.take(30).reversed().map { it.steps }
    }

    val bestDay = state.recentRecords.maxOfOrNull { it.steps } ?: state.todaySteps
    val avgSteps = if (state.recentRecords.isNotEmpty()) {
        state.recentRecords.map { it.steps }.average().toInt()
    } else {
        state.todaySteps
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "ANALYTICS & HISTORY",
            style = MaterialTheme.typography.labelSmall,
            color = palette.mutedText
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("24 HOURS", "7 DAYS", "30 DAYS").forEachIndexed { index, title ->
                FilterChip(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    label = { Text(title, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        SwissBarChart(
            values = chartValues,
            maxValue = (chartValues.maxOrNull() ?: state.dailyGoal).coerceAtLeast(1000)
        )

        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricStatCard(
                value = "%,d".format(avgSteps),
                unitLabel = "DAILY AVG",
                modifier = Modifier.weight(1f)
            )
            MetricStatCard(
                value = "%,d".format(bestDay),
                unitLabel = "PERSONAL BEST",
                modifier = Modifier.weight(1f)
            )
        }
    }
}
