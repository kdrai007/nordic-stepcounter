package com.nordic.stepcounter.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.R
import com.nordic.stepcounter.ui.theme.LocalStepPalette

@Composable
fun PauseResumeButton(
    isPaused: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    val activeBorderColor = if (isPaused) palette.accent else palette.border
    val activeContentColor = if (isPaused) palette.accent else palette.primaryText

    OutlinedButton(
        onClick = onToggle,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) activeBorderColor else palette.border.copy(alpha = 0.5f)
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = palette.surface,
            contentColor = activeContentColor,
            disabledContainerColor = palette.surface.copy(alpha = 0.6f),
            disabledContentColor = palette.mutedText.copy(alpha = 0.6f)
        )
    ) {
        Text(
            text = stringResource(
                if (isPaused) R.string.action_resume_tracking else R.string.action_pause_tracking
            ),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
