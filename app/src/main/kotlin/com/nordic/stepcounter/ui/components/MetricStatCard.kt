package com.nordic.stepcounter.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.ui.theme.LocalStepPalette

@Composable
fun MetricStatCard(
    value: String,
    unitLabel: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(12.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.border)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            AutoScaleSingleLineText(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = palette.primaryText,
                minFontSizeSp = 11f,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = unitLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = palette.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
