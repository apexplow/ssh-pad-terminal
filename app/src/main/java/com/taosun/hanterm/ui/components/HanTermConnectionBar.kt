package com.taosun.hanterm.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taosun.hanterm.ssh.ConnectionState
import com.taosun.hanterm.theme.WarpAccent
import com.taosun.hanterm.theme.WarpSurface
import com.taosun.hanterm.ui.AppIcons
import com.taosun.hanterm.ui.HanTermAppViewModel

/**
 * Connect / Disconnect card with the [ConnectionStatusLabel] pill on the
 * right. The Connect button is disabled while a connection is in flight;
 * the Disconnect button is only enabled when the underlying
 * [com.taosun.hanterm.ssh.ConnectionView] is live.
 *
 * Pinned by `HanTermAppUiTest` (3 @Test cases): the exact button labels
 * "Connect" / "Disconnect" are asserted by `onNodeWithText(...).performClick()`.
 *
 * Issue #65: moved verbatim from `ui/HanTermApp.kt` (Sprint 1) to break up
 * the 785-LOC god-file. Visibility downgraded from `private` to `internal`.
 */
@Composable
internal fun HanTermConnectionBar(
    viewModel: HanTermAppViewModel,
    onConnect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = WarpSurface),
        border = BorderStroke(1.dp, Color(0xFF2E353D)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isBusy = viewModel.connectionState.value is ConnectionState.Connecting
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onConnect,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WarpAccent,
                        contentColor = Color(0xFF0F1419),
                        disabledContainerColor = WarpAccent.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier
                        .height(40.dp)
                        .semantics(mergeDescendants = true) {},
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("Connect", style = TextStyle(fontWeight = FontWeight.Bold))
                    }
                }

                OutlinedButton(
                    onClick = {
                        viewModel.disconnect()
                        // TODO(oss-followup): route through HanTermAppViewModel.markDisconnected()
                        // instead of writing the VM's connectionState directly. Tracked as a
                        // separate VM-convention cleanup; out of scope for the #65 UI split.
                        viewModel.connectionState.value = ConnectionState.Disconnected
                    },
                    enabled = viewModel.connectionView.value.isLive,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF3D2626)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFF7B7B),
                    ),
                    modifier = Modifier
                        .height(40.dp)
                        .semantics(mergeDescendants = true) {},
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Power,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("Disconnect")
                    }
                }
            }

            ConnectionStatusLabel(viewModel.connectionState.value)
        }
    }
}
