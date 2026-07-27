package com.taosun.hanterm.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.taosun.hanterm.data.profile.ConnectionProfile
import com.taosun.hanterm.theme.WarpBackground
import com.taosun.hanterm.ui.AndroidDebugLogSink
import com.taosun.hanterm.ui.ConfigScreen
import com.taosun.hanterm.ui.ConnectionDraftEditor
import com.taosun.hanterm.ui.HanTermAppViewModel
import com.taosun.hanterm.ui.components.HanTermConnectionBar
import com.taosun.hanterm.ui.components.HanTermTopHeader
import com.taosun.hanterm.ui.shouldUseSplitLayout

/**
 * Layout root for the config screen (the screen mounted by [HanTermApp] when
 * `viewModel.showTerminal.value == false`). Owns the [ConnectionDraftEditor]
 * instance (Issue #18) and picks between the split-landscape layout and the
 * single-column-portrait layout via [LayoutDecision.shouldUseSplitLayout].
 *
 * The 3-item preview block (`ConnectionLogPanel` + `TerminalPane` +
 * composing-hint `Text`) is shared between the two branches via
 * [ConfigPreviewContent] — Issue #65 removed the ~20-line duplication that
 * used to live in `ui/HanTermApp.kt::ConfigScreenLayout`.
 *
 * Issue #65: moved verbatim from `ui/HanTermApp.kt` (Sprint 1 / #18) to
 * break up the 785-LOC god-file. Visibility downgraded from `private` to
 * `internal` so the shell in `ui/HanTermApp.kt` can call it.
 */
@Composable
internal fun ConfigScreenLayout(
    viewModel: HanTermAppViewModel,
    profile: ConnectionProfile,
    fontSize: Int,
    autoShowTerminalOnConnect: Boolean,
) {
    val context = LocalContext.current
    // Issue #18: the editor owns the credential-editing state machine.
    // Its lifetime is bound to this composition via rememberCoroutineScope
    // — when the user flips to the terminal pane (showTerminal = true),
    // ConfigScreenLayout leaves composition and the editor's scope cancels.
    val editorScope = rememberCoroutineScope()
    val editor = remember {
        ConnectionDraftEditor(
            profile = profile,
            scope = editorScope,
            debugLog = AndroidDebugLogSink(context),
        )
    }
    val orientation = LocalConfiguration.current.orientation
    val onConnect = {
        // Synchronous StateFlow read — captures the editor's draft at click
        // time. startConnect's signature still accepts a nullable draft
        // (null means "fall back to profile.load().draft"); the editor's
        // draft is never null because init() seeds it from profile.load().
        viewModel.startConnect(editor.draft.value) {
            if (autoShowTerminalOnConnect) {
                viewModel.setShowTerminal(true)
            }
        }
    }
    if (shouldUseSplitLayout(orientation, viewModel.showTerminal.value)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(WarpBackground),
        ) {
            // Leading pane: connect/disconnect + config form.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                HanTermTopHeader()
                HanTermConnectionBar(viewModel = viewModel, onConnect = onConnect)
                Spacer(modifier = Modifier.height(8.dp))
                ConfigScreen(
                    editor = editor,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            // Trailing pane: error log (when in Error) + terminal preview.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            ) {
                ConfigPreviewContent(
                    viewModel = viewModel,
                    fontSize = fontSize,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WarpBackground)
                .verticalScroll(rememberScrollState()),
        ) {
            HanTermTopHeader()
            HanTermConnectionBar(viewModel = viewModel, onConnect = onConnect)
            Spacer(modifier = Modifier.height(8.dp))
            ConfigScreen(
                editor = editor,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            ConfigPreviewContent(
                viewModel = viewModel,
                fontSize = fontSize,
            )
        }
    }
}
