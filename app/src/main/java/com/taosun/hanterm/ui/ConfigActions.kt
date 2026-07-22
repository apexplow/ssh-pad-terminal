package com.taosun.hanterm.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.sp
import com.taosun.hanterm.BuildConfig
import com.taosun.hanterm.logging.AppLog
import com.taosun.hanterm.theme.WarpAccent
import com.taosun.hanterm.theme.WarpMuted
import com.taosun.hanterm.theme.WarpText
import java.io.File
import java.security.MessageDigest

/**
 * Sprint 2.5 / S3 (CS-PF-01 + CS-PF-02): gated by [BuildConfig.DEBUG].
 */
internal fun passwordFingerprint(
    password: String,
    isDebug: Boolean = BuildConfig.DEBUG,
): String {
    if (!isDebug) return ""
    if (password.isEmpty()) return "(empty, length=0)"
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(password.toByteArray(Charsets.UTF_8))
    val hex = bytes.joinToString("") { "%02x".format(it) }
    val first = password.first()
    val firstByteHex = "0x%02x".format(first.code)
    val firstRepr = if (first.isLetterOrDigit() || first in "!@#\$%^&*()-_=+[]{};:,.<>?/ ") {
        "'$first'"
    } else {
        "(non-printable $firstByteHex)"
    }
    return "len=${password.length} sha256[0..16]=${hex.take(16)} firstByte=$firstByteHex $firstRepr"
}

/** Sprint 2.5 / S3 (CS-DL-01..04): file sink gated by [BuildConfig.DEBUG]. */
internal fun appendDebugLog(
    context: Context,
    message: String,
    isDebug: Boolean = BuildConfig.DEBUG,
) {
    Log.d("ConfigScreen", message)
    AppLog.i("ConfigScreen", message)
    if (!isDebug) return
    val debugFile = File(context.filesDir, "debug.log")
    runCatching {
        debugFile.appendText(message + "\n", Charsets.UTF_8)
    }
}

/**
 * Modern Save / Clear / forget-enrolled-host / remove-saved-password action row.
 */
@Composable
internal fun ConfigActions(
    onSave: () -> Unit,
    onClear: () -> Unit,
    canForgetHost: Boolean,
    onForgetHost: () -> Unit,
    canRemoveSavedPassword: Boolean = false,
    onRemoveSavedPassword: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val buttonShape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onSave,
                shape = buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WarpAccent,
                    contentColor = Color(0xFF0F1419),
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .semantics(mergeDescendants = true) {},
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = AppIcons.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Save", style = TextStyle(fontWeight = FontWeight.Bold))
                }
            }

            OutlinedButton(
                onClick = onClear,
                shape = buttonShape,
                border = BorderStroke(1.dp, Color(0xFF2E353D)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = WarpText,
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .semantics(mergeDescendants = true) {},
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = null,
                        tint = WarpMuted,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Clear")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            if (canRemoveSavedPassword) {
                OutlinedButton(
                    onClick = onRemoveSavedPassword,
                    shape = buttonShape,
                    border = BorderStroke(1.dp, Color(0xFF3D2626)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFF7B7B),
                    ),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text("Remove saved password", style = TextStyle(fontSize = 11.sp))
                }
            }
            if (canForgetHost) {
                OutlinedButton(
                    onClick = onForgetHost,
                    shape = buttonShape,
                    border = BorderStroke(1.dp, Color(0xFF3D2626)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFF7B7B),
                    ),
                    modifier = Modifier
                        .height(36.dp)
                        .semantics(mergeDescendants = true) {},
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = Color(0xFFFF7B7B),
                            modifier = Modifier.size(14.dp),
                        )
                        Text("Forget enrolled host", style = TextStyle(fontSize = 11.sp))
                    }
                }
            }
        }
    }
}
