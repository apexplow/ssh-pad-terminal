package com.taosun.hanterm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taosun.hanterm.logging.AppLog

/**
 * Displays the saved-password fingerprint and a helper that copies it to the
 * in-app log for side-by-side comparison with `sha256sum` on a real host.
 */
@Composable
internal fun FingerprintSection(
    fingerprint: String,
    onStatusMessageChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            "Password fingerprint:\n  $fingerprint",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            ),
            modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(onClick = {
            AppLog.i("ConfigScreen", "share-request fingerprint=$fingerprint")
            onStatusMessageChange("Fingerprint appended to log")
        }) {
            Text("Copy fingerprint to log", style = TextStyle(fontSize = 11.sp))
        }
    }
}
