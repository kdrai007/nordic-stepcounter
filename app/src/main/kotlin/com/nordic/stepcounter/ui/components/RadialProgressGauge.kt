package com.nordic.stepcounter.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.ui.theme.LocalStepPalette

@Composable
fun RadialProgressGauge(
    steps: Int,
    goal: Int,
    isPaused: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    val targetProgress = if (goal > 0) (steps.toFloat() / goal.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 600),
        label = "stepProgress"
    )

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(260.dp)) {
        Canvas(modifier = Modifier.size(240.dp)) {
            val strokeWidth = 14.dp.toPx()
            drawArc(
                color = palette.trackBackground,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = if (isPaused) palette.mutedText else palette.accent,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "%,d".format(steps),
                style = MaterialTheme.typography.displayLarge,
                color = palette.primaryText
            )
            Text(
                text = if (isPaused) "PAUSED" else "STEPS / %,d".format(goal),
                style = MaterialTheme.typography.labelSmall,
                color = palette.mutedText
            )
        }
    }
}
