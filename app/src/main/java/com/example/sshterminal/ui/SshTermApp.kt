package com.example.sshterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.sshterminal.terminal.MockEchoSession
import com.example.sshterminal.theme.SshTermTheme
import com.example.sshterminal.theme.WarpBackground

@Composable
fun SshTermApp() {
    SshTermTheme {
        val echoSession = remember { MockEchoSession() }
        val composingHint = remember { mutableStateOf<String?>(null) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WarpBackground)
        ) {
            ConfigScreen(modifier = Modifier.padding(12.dp))
            TerminalPane(
                endpoint = echoSession,
                onComposingHint = { composingHint.value = it },
                modifier = Modifier.weight(1f),
            )
            composingHint.value?.let {
                Text(text = it, color = Color.White, modifier = Modifier.padding(12.dp))
            }
        }
    }
}
