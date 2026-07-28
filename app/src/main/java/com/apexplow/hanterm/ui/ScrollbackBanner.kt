package com.apexplow.hanterm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apexplow.hanterm.terminal.ScrollbackController

/**
 * Top-of-pane banner that surfaces the two-finger scrollback state.
 *
 * Visible in scrollback mode, or briefly when a [ScrollbackController.ScrollbackState.gestureHint]
 * is set (e.g. gesture failed — no adb required). Shows an optional
 * "▼ N 行新输出" badge when new output arrived while scrolled back.
 * Tapping the main row calls [onBackToBottom] when in scrollback mode.
 */
@Composable
fun ScrollbackBanner(
    state: ScrollbackController.ScrollbackState,
    onBackToBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isInScrollback && state.gestureHint == null) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(20.dp),
            )
            .then(
                if (state.isInScrollback) {
                    Modifier.clickable(onClick = onBackToBottom)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (state.isInScrollback) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "↑ 滚回历史 · 点此回到底部",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (state.pendingOutputCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "▼ ${state.pendingOutputCount.coerceAtMost(9999)} 行新输出",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        state.gestureHint?.let { hint ->
            Text(
                text = hint,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(
                    top = if (state.isInScrollback) 4.dp else 0.dp,
                ),
            )
        }
    }
}
