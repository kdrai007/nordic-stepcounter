package com.nordic.stepcounter.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.R
import com.nordic.stepcounter.ui.theme.LocalStepPalette
import java.util.Locale

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

    val formattedSteps = String.format(Locale.getDefault(), "%,d", steps)
    val formattedGoal = String.format(Locale.getDefault(), "%,d", goal)
    val percent = if (goal > 0) ((steps.toLong() * 100L) / goal.toLong()).coerceIn(0L, 100L).toInt() else 0
    val accessibilityDescription = if (isPaused) {
        stringResource(R.string.gauge_cd_paused, formattedSteps, formattedGoal, percent)
    } else {
        stringResource(R.string.gauge_cd_active, formattedSteps, formattedGoal, percent)
    }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(260.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityDescription
            }
    ) {
        val gaugeSize = minOf(maxWidth, maxHeight, 260.dp)
        val ringSize = (gaugeSize - 20.dp).coerceAtLeast(160.dp)
        val strokeWidth = 14.dp
        val innerContentWidth = (ringSize - strokeWidth * 2 - 20.dp).coerceAtLeast(100.dp)

        Canvas(modifier = Modifier.size(ringSize)) {
            val strokePx = strokeWidth.toPx()
            drawArc(
                color = palette.trackBackground,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            drawArc(
                color = if (isPaused) palette.mutedText else palette.accent,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = innerContentWidth)
                .padding(horizontal = 4.dp)
                .clearAndSetSemantics {}
        ) {
            AutoScaleSingleLineText(
                text = formattedSteps,
                style = MaterialTheme.typography.displayLarge,
                color = palette.primaryText,
                minFontSizeSp = 18f,
                contentAlignment = Alignment.Center
            )
            Text(
                text = if (isPaused) {
                    stringResource(R.string.gauge_paused)
                } else {
                    stringResource(R.string.gauge_steps_goal, formattedGoal)
                },
                style = MaterialTheme.typography.labelSmall,
                color = palette.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
