package com.apexplow.hanterm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexplow.hanterm.theme.WarpAccent
import com.apexplow.hanterm.theme.WarpMuted
import com.apexplow.hanterm.theme.WarpSurface
import com.apexplow.hanterm.theme.WarpText
import com.apexplow.hanterm.ui.AppIcons

/**
 * Static header row at the top of the config screen: icon tile + "HanTerm /
 * Decoupled IME SSH Terminal" title block on the left; "当前主机 (Single
 * Host)" pill on the right. Pure layout — no state.
 *
 * Issue #65: moved verbatim from `ui/HanTermApp.kt` (Sprint 1) to break up
 * the 785-LOC god-file. Visibility downgraded from `private` to `internal`
 * so the shell in `ui/HanTermApp.kt` can call it.
 */
@Composable
internal fun HanTermTopHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(WarpAccent.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                    .border(1.dp, WarpAccent.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Terminal,
                    contentDescription = null,
                    tint = WarpAccent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column {
                Text(
                    text = "HanTerm",
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarpText,
                    ),
                )
                Text(
                    text = "Decoupled IME SSH Terminal",
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = WarpMuted,
                    ),
                )
            }
        }

        Box(
            modifier = Modifier
                .background(WarpSurface, RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFF2E353D), RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(WarpAccent, CircleShape),
                )
                Text(
                    text = "当前主机 (Single Host)",
                    style = TextStyle(fontSize = 11.sp, color = WarpMuted, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}
