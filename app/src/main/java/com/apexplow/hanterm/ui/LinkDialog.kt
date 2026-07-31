package com.apexplow.hanterm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexplow.hanterm.logging.AppLog
import com.apexplow.hanterm.logging.LogClassification
import com.apexplow.hanterm.terminal.link.LaunchResult
import com.apexplow.hanterm.terminal.link.LinkDetector
import com.apexplow.hanterm.terminal.link.LinkIntentLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Compose Material3 ModalBottomSheet for the Sprint 4 long-press-URL flow.
 *
 * Three actions: Open (browser), Copy (clipboard), Share (system share
 * sheet). Mirrors the [ComposeHostKeyPrompt] pattern — the URL payload
 * lives in a [MutableStateFlow] so the host (TerminalView /
 * `LinkGesture`) can push a URL without the dialog knowing about
 * Compose, and the dialog renders only when there's a pending URL.
 *
 * **T18 — URL re-validation on recomposition.** Each time this
 * composable recomposes, we re-run [LinkDetector.firstUrlIn] on the
 * stored URL. If the regex rejects it (the stored URL doesn't match
 * the current tightened regex, e.g. the URL was malformed from the
 * start), we silently dismiss — the user never sees a dialog they
 * can't act on. Same regex is re-run inside [LinkIntentLauncher.launch]
 * before the actual `ACTION_VIEW` dispatch, so a stale URL between
 * dialog render and tap is also caught.
 *
 * Mount this once at the top of the composable tree (alongside
 * `ComposeHostKeyPrompt.Dialog()`); it returns `null`/renders nothing
 * when there's no pending URL.
 */
class LinkDialogState {

    private val _pendingUrl = MutableStateFlow<String?>(null)

    /** Read-only view for the Compose side. */
    val pendingUrl: StateFlow<String?> get() = _pendingUrl

    /** Push a URL onto the dialog. Called from [LinkGesture.onLongPress]. */
    fun show(url: String) {
        _pendingUrl.value = url
    }

    /** Clear the dialog (any user action that closes it). */
    fun dismiss() {
        _pendingUrl.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkDialog(
    state: LinkDialogState,
    onLaunch: (Context, String) -> LaunchResult = LinkIntentLauncher::launch,
) {
    val current by state.pendingUrl.collectAsState()
    val url = current ?: return

    // T18 — re-validate the URL on every recomposition. If the regex
    // rejects it (e.g. the URL was malformed, or our tightening
    // OV #7 would now reject it), dismiss the dialog silently. The
    // user never sees a dialog they can't act on.
    val validated = remember(url) { LinkDetector.firstUrlIn(url) }
    if (validated == null) {
        AppLog.d(
            "LinkDialog",
            "URL failed T18 re-validation: $url",
            classification = LogClassification.ConnectionMetadata,
        )
        state.dismiss()
        return
    }

    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = { state.dismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text("Open link", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                validated,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = {
                        // Re-validate one more time at click time — T18.
                        // The dialog might have rendered several frames
                        // ago and the overlay snapshot might have moved
                        // on; LinkIntentLauncher re-runs the regex too,
                        // but doing it here gives us a clean dismiss
                        // path with no log spam.
                        val result = onLaunch(context, validated)
                        when (result) {
                            LaunchResult.Ok,
                            LaunchResult.StaleUrl,
                            -> state.dismiss()
                            LaunchResult.NoBrowser -> {
                                // Leave dialog open — the user can still
                                // Copy or Share. A snackbar would be
                                // nicer but we don't have a SnackbarHost
                                // wired here in v0.1.
                                AppLog.w(
                                    "LinkDialog",
                                    "Open: no ACTION_VIEW handler; leaving dialog open",
                                    classification = LogClassification.ConnectionMetadata,
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Open") }
                TextButton(
                    onClick = {
                        copyToClipboard(context, validated)
                        state.dismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Copy") }
                TextButton(
                    onClick = {
                        shareUrl(context, validated)
                        state.dismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Share") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("URL", text))
}

private fun shareUrl(context: Context, url: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    val chooser = Intent.createChooser(sendIntent, "Share URL").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }.onFailure {
        AppLog.w(
            "LinkDialog",
            "Share chooser failed: ${it.message}",
            classification = LogClassification.ConnectionMetadata,
        )
    }
}