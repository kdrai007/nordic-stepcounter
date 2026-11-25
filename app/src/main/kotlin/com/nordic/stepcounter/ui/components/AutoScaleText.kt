package com.nordic.stepcounter.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * Single-line text that scales down its font size proportionally when needed so values
 * never clip or wrap on compact viewports or high system font-scale settings.
 */
@Composable
fun AutoScaleSingleLineText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minFontSizeSp: Float = 11f,
    contentAlignment: Alignment = Alignment.CenterStart
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier, contentAlignment = contentAlignment) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val baseFontSizeSp = if (style.fontSize.isSp) style.fontSize.value else 16f

        val fittedFontSize = remember(text, style, maxWidthPx, density.fontScale, minFontSizeSp) {
            if (maxWidthPx <= 0f) {
                baseFontSizeSp.sp
            } else {
                val baseLayout = textMeasurer.measure(
                    text = text,
                    style = style,
                    maxLines = 1,
                    softWrap = false
                )
                val measuredWidth = baseLayout.size.width.toFloat()
                if (measuredWidth <= maxWidthPx) {
                    baseFontSizeSp.sp
                } else {
                    val ratio = (maxWidthPx / measuredWidth) * 0.94f
                    val scaledSp = (baseFontSizeSp * ratio).coerceIn(minFontSizeSp, baseFontSizeSp)
                    val secondPass = textMeasurer.measure(
                        text = text,
                        style = style.copy(fontSize = scaledSp.sp),
                        maxLines = 1,
                        softWrap = false
                    )
                    if (secondPass.size.width > maxWidthPx && secondPass.size.width > 0) {
                        val refinedSp = (scaledSp * (maxWidthPx / secondPass.size.width) * 0.96f)
                            .coerceIn(minFontSizeSp, scaledSp)
                        refinedSp.sp
                    } else {
                        scaledSp.sp
                    }
                }
            }
        }

        Text(
            text = text,
            style = style.copy(fontSize = fittedFontSize),
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
