package com.taosun.hanterm.ui.config

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.taosun.hanterm.ssh.ConnectionState
import com.taosun.hanterm.ui.ConnectionLogPanel
import com.taosun.hanterm.ui.HanTermAppViewModel
import com.taosun.hanterm.ui.TerminalPane

/**
 * The 3-item block (error log + terminal preview + composing-hint overlay)
 * shared by both branches of [ConfigScreenLayout] — split-landscape shows
 * it as the trailing pane; single-column-portrait shows it as the bottom
 * of the scrollable form.
 *
 * Declared as a [ColumnScope] extension so the inner [TerminalPane] can
 * keep the original `Modifier.weight(1f)` (the original `ConfigScreenLayout`
 * used `weight(1f)` for the preview in BOTH branches; lifting that to
 * `fillMaxSize()` would break the split-branch layout where the parent
 * Column also has children stacked above and below the preview).
 *
 * The two outer branches of [ConfigScreenLayout] wrap this composable in
 * an outer `Column` with branch-specific modifier (the split branch uses
 * `weight(1f).fillMaxSize()`; the single-column branch uses
 * `fillMaxSize().background(WarpBackground).verticalScroll(rememberScrollState())`).
 * This helper intentionally contains only the inner 3 items so the outer
 * column's modifier difference stays in [ConfigScreenLayout] where it belongs.
 *
 * Issue #65: extracted from `ui/HanTermApp.kt::ConfigScreenLayout` where
 * the 3 items were duplicated between the split branch (lines 691-718)
 * and the single-column branch (lines 720-755).
 */
@Composable
internal fun ColumnScope.ConfigPreviewContent(
    viewModel: HanTermAppViewModel,
    fontSize: Int,
) {
    val context = LocalContext.current
    ConnectionLogPanel(
        context = context,
        logRefreshTick = viewModel.logRefreshTick.value,
        errorMessage = (viewModel.connectionState.value as? ConnectionState.Error)
            ?.message,
        showLogs = viewModel.showLogs.value,
        onToggleShowLogs = { viewModel.toggleLogs() },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
    TerminalPane(
        view = viewModel.connectionView.value,
        onComposingHint = { viewModel.onComposingHint(it) },
        onSessionClosed = { reason, closeReason ->
            viewModel.onSessionClosed(reason, closeReason)
        },
        fontSize = fontSize,
        modifier = Modifier.weight(1f),
    )
    viewModel.composingHint.value?.let {
        Text(text = it, color = Color.White, modifier = Modifier.padding(12.dp))
    }
}
