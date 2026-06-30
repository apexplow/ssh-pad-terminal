package com.example.sshterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.example.sshterminal.terminal.ScrollbackController

/**
 * Top-of-pane banner that surfaces the two-finger scrollback state.
 *
 * Hidden by default; visible whenever the controller is in scrollback
 * mode. Shows an optional "▼ N 行新输出" badge when new output arrived
 * while the user was scrolled back. Tapping anywhere on the banner
 * calls [onBackToBottom], which the caller is expected to wire to
 * [com.example.sshterminal.terminal.TerminalView.scrollToBottom].
 */
@Composable
fun ScrollbackBanner(
    state: ScrollbackController.ScrollbackState,
    onBackToBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isInScrollback) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onBackToBottom)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "↑ 滚回历史",
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
