package com.nordic.stepcounter.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.ui.theme.LocalStepPalette

@Composable
fun PauseResumeButton(
    isPaused: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    OutlinedButton(
        onClick = onToggle,
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (isPaused) palette.accent else palette.border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = palette.surface,
            contentColor = if (isPaused) palette.accent else palette.primaryText
        )
    ) {
        Text(
            text = if (isPaused) "RESUME TRACKING" else "PAUSE TRACKING",
            style = MaterialTheme.typography.labelSmall
        )
    }
}
