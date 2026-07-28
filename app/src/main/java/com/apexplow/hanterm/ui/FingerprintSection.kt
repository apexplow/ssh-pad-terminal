package com.apexplow.hanterm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexplow.hanterm.theme.WarpAccent
import com.apexplow.hanterm.theme.WarpMuted
import com.apexplow.hanterm.theme.WarpSurface

/**
 * Displays the saved-password fingerprint and a button that asks the parent
 * to copy it to the in-app log under [com.apexplow.hanterm.logging.LogClassification.CredentialMetadata].
 *
 * The actual logging call lives in [ConnectionDraftEditor]'s
 * [DraftIntent.LogFingerprint] handler — Issue #18 centralised it there.
 * The button just fires [onCopyToLog]; the parent wires it to the editor.
 */
@Composable
internal fun FingerprintSection(
    fingerprint: String,
    onCopyToLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = WarpSurface),
        border = BorderStroke(1.dp, Color(0xFF2E353D)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(
                "Password fingerprint:\n  $fingerprint",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = WarpMuted,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF101214), shape = RoundedCornerShape(8.dp))
                    .padding(8.dp),
            )
            TextButton(onClick = onCopyToLog) {
                Text("Copy fingerprint to log", style = TextStyle(fontSize = 11.sp, color = WarpAccent))
            }
        }
    }
}
