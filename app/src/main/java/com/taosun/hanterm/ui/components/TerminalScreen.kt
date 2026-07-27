package com.taosun.hanterm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taosun.hanterm.ssh.ConnectionState
import com.taosun.hanterm.terminal.TerminalView
import com.taosun.hanterm.ui.ConnectionLogPanel
import com.taosun.hanterm.ui.HanTermAppViewModel
import com.taosun.hanterm.ui.TerminalPane

/**
 * Wraps [TerminalPane] in a Box and overlays a full-screen "Connection
 * Closed" card with Reconnect / Back-to-Config buttons when the underlying
 * [com.taosun.hanterm.ssh.ConnectionView] is not live and the VM is in
 * [ConnectionState.Error] or [ConnectionState.Disconnected].
 *
 * Pinned by `HanTermAppUiTest.connectFailure_rendersErrorOverlay` (the
 * "Connection Closed" card body, the formatted "Error: ${message}" text,
 * and the two buttons).
 *
 * Issue #65: moved verbatim from `ui/HanTermApp.kt` (Sprint 2) to break up
 * the 785-LOC god-file. Visibility downgraded from `private` to `internal`.
 */
@Composable
internal fun TerminalScreen(
    viewModel: HanTermAppViewModel,
    onTerminalViewChanged: (TerminalView?) -> Unit,
    fontSize: Int,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        TerminalPane(
            view = viewModel.connectionView.value,
            onComposingHint = { viewModel.onComposingHint(it) },
            onSessionClosed = { reason, closeReason ->
                viewModel.onSessionClosed(reason, closeReason)
            },
            onTerminalViewChanged = onTerminalViewChanged,
            fontSize = fontSize,
            modifier = Modifier.fillMaxSize(),
        )
        viewModel.composingHint.value?.let {
            Text(
                text = it,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(4.dp)
            )
        }

        // Disconnected / connection error overlay
        if (!viewModel.connectionView.value.isLive &&
            (viewModel.connectionState.value is ConnectionState.Error ||
                viewModel.connectionState.value is ConnectionState.Disconnected)
        ) {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(viewModel.connectionState.value) {
                focusRequester.requestFocus()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .focusRequester(focusRequester)
                    .focusable()
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter) {
                            viewModel.startConnect()
                            true
                        } else {
                            false
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF21262D).copy(alpha = 0.9f),
                        contentColor = Color.White
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .padding(24.dp)
                        .width(360.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Connection Closed",
                            style = TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF85149)
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val errMsg = when (val state = viewModel.connectionState.value) {
                            is ConnectionState.Error -> state.message
                            else -> "The connection to the remote host was closed."
                        }
                        val parts = errMsg.split(": ", limit = 2)
                        val category = if (parts.size == 2) parts[0] else null
                        val detail = if (parts.size == 2) parts[1] else errMsg

                        category?.let {
                            Text(
                                text = it,
                                color = Color(0xFFFFC107),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text(
                            text = detail,
                            color = Color(0xFFC9D1D9),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        ConnectionLogPanel(
                            context = LocalContext.current,
                            logRefreshTick = viewModel.logRefreshTick.value,
                            errorMessage = null,
                            showLogs = viewModel.showLogs.value,
                            onToggleShowLogs = { viewModel.toggleLogs() },
                            maxHeightDp = 200,
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    // "Back to Config" doesn't disconnect — the
                                    // session is already gone via onSessionClosed
                                    // (which cleared the store and tore down the
                                    // client). We just collapse the overlay.
                                    viewModel.setShowTerminal(false)
                                    // TODO(oss-followup): route through HanTermAppViewModel.markDisconnected()
                                    // instead of writing the VM's connectionState directly. Tracked as a
                                    // separate VM-convention cleanup; out of scope for the #65 UI split.
                                    viewModel.connectionState.value = ConnectionState.Disconnected
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Back to Config", color = Color.White)
                            }
                            Button(
                                onClick = { viewModel.startConnect() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF238636)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Reconnect", color = Color.White)
                            }
                        }
                    }
                }
            }
        }

    }
}
