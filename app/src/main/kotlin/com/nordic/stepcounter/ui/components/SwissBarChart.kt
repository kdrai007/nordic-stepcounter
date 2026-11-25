package com.nordic.stepcounter.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.ui.theme.LocalStepPalette

@Composable
fun SwissBarChart(
    values: List<Int>,
    maxValue: Int,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    val safeMax = maxValue.coerceAtLeast(1)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        if (values.isEmpty()) return@Canvas
        val barCount = values.size
        val spacing = if (barCount > 15) 3.dp.toPx() else 8.dp.toPx()
        val totalSpacing = spacing * (barCount - 1)
        val maxBarWidth = 36.dp.toPx()
        val calculatedBarWidth = ((size.width - totalSpacing) / barCount).coerceAtLeast(3f)
        val barWidth = calculatedBarWidth.coerceAtMost(maxBarWidth)
        val totalContentWidth = barCount * barWidth + totalSpacing
        val startOffset = ((size.width - totalContentWidth) / 2f).coerceAtLeast(0f)

        values.forEachIndexed { index, value ->
            val ratio = (value.toFloat() / safeMax.toFloat()).coerceIn(0.04f, 1f)
            val barHeight = size.height * ratio
            val x = startOffset + index * (barWidth + spacing)
            val y = size.height - barHeight

            drawRoundRect(
                color = if (value > 0) palette.accent else palette.trackBackground,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
        }
    }
}
