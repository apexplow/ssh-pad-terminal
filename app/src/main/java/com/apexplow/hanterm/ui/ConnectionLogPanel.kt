package com.apexplow.hanterm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexplow.hanterm.logging.AppLog

/**
 * In-app log viewer for connect failures. Renders the tail of [AppLog] in a
 * monospace block with a one-tap Copy button — no adb required.
 */
@Composable
fun ConnectionLogPanel(
    context: Context,
    logRefreshTick: Int,
    errorMessage: String?,
    showLogs: Boolean,
    onToggleShowLogs: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeightDp: Int = 240,
) {
    val logText = remember(logRefreshTick, showLogs) {
        if (showLogs) AppLog.readTail() else ""
    }

    Column(modifier = modifier.fillMaxWidth()) {
        errorMessage?.let { msg ->
            Text(
                text = msg,
                color = Color(0xFFEF5350),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onToggleShowLogs,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (showLogs) "Hide logs" else "Show logs",
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val body = buildString {
                        errorMessage?.let { append("Error: ").append(it).append("\n\n") }
                        append(AppLog.readTail())
                    }
                    clipboard.setPrimaryClip(ClipData.newPlainText("ssh-term log", body))
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Copy logs", color = Color.White, fontSize = 12.sp)
            }
        }

        if (showLogs) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeightDp.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0D1117))
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                Text(
                    text = if (logText.isBlank()) "(log is empty — no entries yet)" else logText,
                    color = Color(0xFFC9D1D9),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
