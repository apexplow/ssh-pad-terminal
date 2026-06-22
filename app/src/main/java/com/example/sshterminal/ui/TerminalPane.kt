package com.example.sshterminal.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.sshterminal.terminal.TerminalEndpoint
import com.example.sshterminal.terminal.TerminalView

@Composable
fun TerminalPane(
    endpoint: TerminalEndpoint,
    onComposingHint: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TerminalView(context).apply {
                bindEndpoint(endpoint)
                setComposingHintListener(onComposingHint)
            }
        },
        update = { terminalView ->
            terminalView.bindEndpoint(endpoint)
            terminalView.setComposingHintListener(onComposingHint)
        },
    )
}
