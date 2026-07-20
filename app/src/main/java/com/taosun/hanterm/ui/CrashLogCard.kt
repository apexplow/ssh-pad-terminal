package com.taosun.hanterm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Displays the previous-launch crash trace with Copy / Dismiss actions.
 *
 * Buttons sit on the same row as the title so they stay reachable even when
 * the trace text is long enough to push them off-screen otherwise.
 */
@Composable
internal fun CrashLogCard(
    trace: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "LAST CRASH (previous launch):",
                color = Color.Red,
                style = TextStyle(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCopy) {
                    Text("Copy crash log", style = TextStyle(fontSize = 11.sp))
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss crash log", style = TextStyle(fontSize = 11.sp))
                }
            }
        }
        Text(
            trace,
            color = Color.White,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            ),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Puts [trace] on the system clipboard as plain text. */
internal fun copyCrashLogToClipboard(context: Context, trace: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("crash log", trace))
}
