package com.apexplow.hanterm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexplow.hanterm.ssh.ConnectionState

/**
 * Small status pill rendered inside [HanTermConnectionBar]. Switches text,
 * dot color, and pill background on the four [ConnectionState] cases.
 *
 * Pinned by `HanTermAppUiTest` (3 @Test cases): the exact text strings
 * ("Disconnected", "Connected to ${summary}", "Error: ${message}") are
 * asserted by `onNodeWithText(...)` — do not edit the format without
 * updating the test.
 *
 * Issue #65: moved verbatim from `ui/HanTermApp.kt` (Sprint 1) to break up
 * the 785-LOC god-file. Visibility downgraded from `private` to `internal`.
 */
@Composable
internal fun ConnectionStatusLabel(state: ConnectionState) {
    val (text, color, bg) = when (state) {
        ConnectionState.Disconnected -> Triple("Disconnected", Color(0xFF9AA0A6), Color(0xFF20252B))
        ConnectionState.Connecting -> Triple("Connecting…", Color(0xFFFFC107), Color(0xFF332B1A))
        is ConnectionState.Connected -> Triple("Connected to ${state.summary}", Color(0xFF66BB6A), Color(0xFF1E3324))
        is ConnectionState.Error -> Triple("Error: ${state.message}", Color(0xFFEF5350), Color(0xFF331E1E))
    }
    Box(
        modifier = Modifier
            .background(bg, shape = RoundedCornerShape(20.dp))
            .border(1.dp, color.copy(alpha = 0.3f), shape = RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape),
            )
            Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
