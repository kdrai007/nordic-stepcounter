package com.nordic.stepcounter.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nordic.stepcounter.R
import com.nordic.stepcounter.ui.theme.LocalStepPalette
import java.util.Locale

@Composable
fun SwissBarChart(
    values: List<Int>,
    maxValue: Int,
    barLabels: List<String> = emptyList(),
    xAxisTicks: List<Pair<Int, String>> = emptyList(),
    periodLabel: String = "",
    goalValue: Int? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    val safeMax = maxValue.coerceAtLeast(1)
    var selectedIndex by remember(values.size, periodLabel) { mutableStateOf<Int?>(null) }
    val textMeasurer = rememberTextMeasurer()

    val peakSteps = remember(values) { values.maxOrNull() ?: 0 }
    val formattedPeak = String.format(Locale.getDefault(), "%,d", peakSteps)
    val formattedMax = String.format(Locale.getDefault(), "%,d", safeMax)
    val selectedIdx = selectedIndex?.takeIf { it in values.indices }
    val chartSemanticsDesc = if (selectedIdx != null) {
        val label = barLabels.getOrNull(selectedIdx) ?: "#${selectedIdx + 1}"
        val valStr = String.format(Locale.getDefault(), "%,d", values[selectedIdx])
        stringResource(R.string.chart_cd_selected, periodLabel, label, valStr)
    } else {
        stringResource(R.string.chart_cd_summary, periodLabel, formattedPeak)
    }

    val tickLabelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        letterSpacing = 0.5.sp,
        color = palette.mutedText
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = chartSemanticsDesc
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val readoutText = if (selectedIdx != null) {
                val label = barLabels.getOrNull(selectedIdx) ?: "#${selectedIdx + 1}"
                val count = String.format(Locale.getDefault(), "%,d", values[selectedIdx])
                stringResource(R.string.chart_selected_readout, label, count)
            } else {
                stringResource(R.string.chart_hint_tap)
            }
            Text(
                text = readoutText,
                style = MaterialTheme.typography.labelSmall,
                color = if (selectedIdx != null) palette.accent else palette.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formattedMax,
                style = MaterialTheme.typography.labelSmall,
                color = palette.mutedText,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(206.dp)
                .pointerInput(values.size) {
                    detectTapGestures { offset ->
                        if (values.isEmpty()) return@detectTapGestures
                        val barCount = values.size
                        val spacing = if (barCount > 15) 3.dp.toPx() else 8.dp.toPx()
                        val totalSpacing = spacing * (barCount - 1)
                        val maxBarWidth = 36.dp.toPx()
                        val calculatedBarWidth = ((size.width - totalSpacing) / barCount).coerceAtLeast(3f)
                        val barWidth = calculatedBarWidth.coerceAtMost(maxBarWidth)
                        val totalContentWidth = barCount * barWidth + totalSpacing
                        val startOffset = ((size.width - totalContentWidth) / 2f).coerceAtLeast(0f)

                        val stride = barWidth + spacing
                        val relX = offset.x - startOffset
                        val tappedIdx = if (stride > 0f) {
                            (relX / stride).toInt().coerceIn(0, barCount - 1)
                        } else {
                            0
                        }
                        selectedIndex = if (selectedIndex == tappedIdx) null else tappedIdx
                    }
                }
        ) {
            if (values.isEmpty()) return@Canvas

            val axisAreaHeight = 22.dp.toPx()
            val chartHeight = (size.height - axisAreaHeight).coerceAtLeast(40f)
            val barCount = values.size
            val spacing = if (barCount > 15) 3.dp.toPx() else 8.dp.toPx()
            val totalSpacing = spacing * (barCount - 1)
            val maxBarWidth = 36.dp.toPx()
            val calculatedBarWidth = ((size.width - totalSpacing) / barCount).coerceAtLeast(3f)
            val barWidth = calculatedBarWidth.coerceAtMost(maxBarWidth)
            val totalContentWidth = barCount * barWidth + totalSpacing
            val startOffset = ((size.width - totalContentWidth) / 2f).coerceAtLeast(0f)

            // Baseline rule
            drawLine(
                color = palette.border,
                start = Offset(0f, chartHeight),
                end = Offset(size.width, chartHeight),
                strokeWidth = 1.dp.toPx()
            )

            // Optional daily goal dashed reference line
            if (goalValue != null && goalValue > 0) {
                val goalRatio = (goalValue.toFloat() / safeMax.toFloat()).coerceIn(0f, 1f)
                val goalY = chartHeight * (1f - goalRatio)
                drawLine(
                    color = palette.mutedText.copy(alpha = 0.65f),
                    start = Offset(0f, goalY),
                    end = Offset(size.width, goalY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                )
            }

            // Bars
            values.forEachIndexed { index, value ->
                val ratio = (value.toFloat() / safeMax.toFloat()).coerceIn(0.04f, 1f)
                val barHeight = chartHeight * ratio
                val x = startOffset + index * (barWidth + spacing)
                val y = chartHeight - barHeight

                val isSelected = selectedIdx == index
                val hasSelection = selectedIdx != null
                val baseColor = if (value > 0) palette.accent else palette.trackBackground
                val barColor = when {
                    isSelected -> if (value > 0) palette.accent else palette.primaryText.copy(alpha = 0.45f)
                    hasSelection -> baseColor.copy(alpha = 0.42f)
                    else -> baseColor
                }

                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }

            // X-axis tick labels aligned to exact bar centers
            val labelTopY = chartHeight + 5.dp.toPx()
            xAxisTicks.forEach { (index, label) ->
                if (index in 0 until barCount) {
                    val measured = textMeasurer.measure(text = label, style = tickLabelStyle)
                    val barCenterX = startOffset + index * (barWidth + spacing) + barWidth / 2f
                    val maxLeft = (size.width - measured.size.width).coerceAtLeast(0f)
                    val textX = (barCenterX - measured.size.width / 2f).coerceIn(0f, maxLeft)
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(textX, labelTopY)
                    )
                }
            }
        }

        if (goalValue != null && goalValue > 0) {
            val formattedGoal = String.format(Locale.getDefault(), "%,d", goalValue)
            Text(
                text = stringResource(R.string.chart_goal_label, formattedGoal),
                style = MaterialTheme.typography.labelSmall,
                color = palette.mutedText,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}
