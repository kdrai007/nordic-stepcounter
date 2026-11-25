package com.nordic.stepcounter.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.R
import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.ui.StepUiState
import com.nordic.stepcounter.ui.components.MetricStatCard
import com.nordic.stepcounter.ui.components.SwissBarChart
import com.nordic.stepcounter.ui.theme.LocalStepPalette
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    state: StepUiState,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0 = Day (24h), 1 = Week (7d), 2 = Month (30d)
    val locale = Locale.getDefault()

    val recordsByDate = remember(state.recentRecords) {
        state.recentRecords.associateBy { it.date }
    }
    val today = remember(state.currentDate) {
        runCatching { LocalDate.parse(state.currentDate) }.getOrElse { LocalDate.now() }
    }

    val tabTitles = listOf(
        stringResource(R.string.history_tab_24h),
        stringResource(R.string.history_tab_7d),
        stringResource(R.string.history_tab_30d)
    )

    val dayDateFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEE MMM d", locale) }
    val monthDateFormatter = remember(locale) { DateTimeFormatter.ofPattern("MMM d", locale) }
    val compactTickFormatter = remember(locale) { DateTimeFormatter.ofPattern("M/d", locale) }

    val chartValues: List<Int>
    val barLabels: List<String>
    val xAxisTicks: List<Pair<Int, String>>

    when (selectedTab) {
        0 -> {
            chartValues = (0..23).map { hour ->
                state.hourlyToday.firstOrNull { it.hour == hour }?.steps ?: 0
            }
            barLabels = (0..23).map { hour -> String.format(locale, "%02d:00", hour) }
            xAxisTicks = listOf(0 to "0h", 6 to "6h", 12 to "12h", 18 to "18h", 23 to "23h")
        }
        1 -> {
            val dates = (6 downTo 0).map { daysAgo -> today.minusDays(daysAgo.toLong()) }
            chartValues = dates.map { date -> recordsByDate[date.toString()]?.steps ?: 0 }
            barLabels = dates.map { date -> date.format(dayDateFormatter) }
            xAxisTicks = dates.mapIndexed { idx, date ->
                idx to date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale)
            }
        }
        else -> {
            val dates = (29 downTo 0).map { daysAgo -> today.minusDays(daysAgo.toLong()) }
            chartValues = dates.map { date -> recordsByDate[date.toString()]?.steps ?: 0 }
            barLabels = dates.map { date -> date.format(monthDateFormatter) }
            xAxisTicks = listOf(0, 7, 14, 21, 29).map { idx ->
                idx to dates[idx].format(compactTickFormatter)
            }
        }
    }

    val primaryAvgLabel: String
    val primaryAvgValue: Int
    when (selectedTab) {
        0 -> {
            primaryAvgLabel = stringResource(R.string.history_hourly_avg)
            val elapsedHours = (LocalTime.now().hour + 1).coerceIn(1, 24)
            primaryAvgValue = state.todaySteps / elapsedHours
        }
        1 -> {
            primaryAvgLabel = stringResource(R.string.history_daily_avg)
            primaryAvgValue = calculateWindowDailyAverage(
                today = today,
                windowDays = 7,
                recordsByDate = recordsByDate,
                earliestRecordDateStr = state.earliestRecordDate,
                todaySteps = state.todaySteps
            )
        }
        else -> {
            primaryAvgLabel = stringResource(R.string.history_daily_avg)
            primaryAvgValue = calculateWindowDailyAverage(
                today = today,
                windowDays = 30,
                recordsByDate = recordsByDate,
                earliestRecordDateStr = state.earliestRecordDate,
                todaySteps = state.todaySteps
            )
        }
    }

    val bestDay = maxOf(state.personalBestSteps, state.todaySteps)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.history_header),
            style = MaterialTheme.typography.labelSmall,
            color = palette.mutedText
        )
        Spacer(modifier = Modifier.height(16.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tabTitles.forEachIndexed { index, title ->
                FilterChip(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    label = { Text(title, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        val chartMax = when (selectedTab) {
            0 -> (chartValues.maxOrNull() ?: 0).coerceAtLeast(1000)
            else -> maxOf(chartValues.maxOrNull() ?: 0, state.dailyGoal, 1000)
        }
        SwissBarChart(
            values = chartValues,
            maxValue = chartMax,
            barLabels = barLabels,
            xAxisTicks = xAxisTicks,
            periodLabel = tabTitles[selectedTab],
            goalValue = if (selectedTab == 0) null else state.dailyGoal
        )

        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricStatCard(
                value = String.format(locale, "%,d", primaryAvgValue),
                unitLabel = primaryAvgLabel,
                modifier = Modifier.weight(1f)
            )
            MetricStatCard(
                value = String.format(locale, "%,d", bestDay),
                unitLabel = stringResource(R.string.history_personal_best),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Computes the true daily average across completed days within the [windowDays] window,
 * counting skipped days (missing rows) as 0 steps while excluding today's still-incomplete row
 * whenever at least one completed prior day exists.
 */
internal fun calculateWindowDailyAverage(
    today: LocalDate,
    windowDays: Int,
    recordsByDate: Map<String, DailyStepEntity>,
    earliestRecordDateStr: String,
    todaySteps: Int
): Int {
    val windowStart = today.minusDays((windowDays - 1).toLong())
    val yesterday = today.minusDays(1)
    val earliest = earliestRecordDateStr
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: today
    val effectiveStart = if (earliest.isAfter(windowStart)) earliest else windowStart

    if (effectiveStart.isAfter(yesterday)) {
        return todaySteps
    }

    val completedDayCount = (ChronoUnit.DAYS.between(effectiveStart, yesterday) + 1L)
        .coerceAtLeast(1L)
    var totalSteps = 0L
    var cursor = effectiveStart
    while (!cursor.isAfter(yesterday)) {
        totalSteps += (recordsByDate[cursor.toString()]?.steps ?: 0).toLong()
        cursor = cursor.plusDays(1)
    }
    return (totalSteps / completedDayCount).toInt()
}
