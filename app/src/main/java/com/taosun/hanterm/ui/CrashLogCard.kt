package com.taosun.hanterm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
 * Rendered in a modern high-contrast dark card.
 */
@Composable
internal fun CrashLogCard(
    trace: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B1616)),
        border = BorderStroke(1.dp, Color(0xFF6B2626)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "LAST CRASH (previous launch):",
                    color = Color(0xFFFF6B6B),
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onCopy) {
                        Text("Copy crash log", style = TextStyle(fontSize = 11.sp, color = Color(0xFFFFB8B8)))
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Dismiss crash log", style = TextStyle(fontSize = 11.sp, color = Color(0xFFFF8888)))
                    }
                }
            }
            Text(
                trace,
                color = Color(0xFFF0E0E0),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E0C0C), shape = RoundedCornerShape(8.dp))
                    .padding(8.dp),
            )
        }
    }
}

/** Puts [trace] on the system clipboard as plain text. */
internal fun copyCrashLogToClipboard(context: Context, trace: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("crash log", trace))
}
