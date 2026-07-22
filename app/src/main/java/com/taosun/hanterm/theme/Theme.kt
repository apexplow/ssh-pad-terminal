package com.taosun.hanterm.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AppColorScheme = darkColorScheme(
    background = WarpBackground,
    surface = WarpSurface,
    primary = WarpAccent,
    onBackground = WarpText,
    onSurface = WarpText,
)

@Composable
fun HanTermTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = AppTypography,
        content = content,
    )
}
