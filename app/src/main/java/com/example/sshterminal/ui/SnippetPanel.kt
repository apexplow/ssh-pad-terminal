package com.example.sshterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sshterminal.data.prefs.CommandSnippet
import com.example.sshterminal.data.prefs.SnippetStore
import com.example.sshterminal.terminal.TerminalEndpoint
import kotlinx.coroutines.launch

/**
 * Sprint 3 / Module 16 / SNP-UI-01..04 — bottom sheet for saved command snippets.
 *
 * The panel reads [SnippetStore.getAll] every time it opens (SNP-UI-02),
 * so external mutations are picked up on the next open without an explicit
 * refresh hook. Internal mutations (add via the inline form, delete via
 * the per-row trash icon) take effect immediately in the visible list
 * (SNP-UI-03 / SNP-UI-04) because the Composable state is bound to the
 * store's current value via [snippets] / [refresh].
 *
 * Wire bytes are produced by [buildSnippetPayload] (SNP-SEND-01/02): a tap
 * on a snippet with `appendNewline = true` writes `command + "\r"`, while
 * `appendNewline = false` writes `command` only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetPanel(
    store: SnippetStore,
    endpoint: TerminalEndpoint,
    onDismiss: () -> Unit,
) {
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    // Re-read every time the panel is composed (i.e. every time it opens).
    // Using `mutableStateOf` with the list as the value so Compose tracks
    // structural changes (add / delete) — `var snippets = ...` would not
    // trigger recomposition.
    var snippets by remember { mutableStateOf(store.getAll()) }
    var showAddForm by remember { mutableStateOf(false) }

    fun refresh() {
        snippets = store.getAll()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(min = 200.dp, max = 640.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "命令 Snippet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row {
                    IconButton(
                        onClick = { showAddForm = !showAddForm },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "添加 Snippet",
                        )
                    }
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                sheetState.hide()
                                onDismiss()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Add form (collapsible)
            if (showAddForm) {
                AddSnippetForm(
                    onSubmit = { label, command, appendNewline ->
                        store.add(CommandSnippet(label = label, command = command, appendNewline = appendNewline))
                        refresh()
                        showAddForm = false
                    },
                    onCancel = { showAddForm = false },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // List
            if (snippets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "还没有 Snippet。点击 + 添加一条。",
                        color = Color.Gray,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = snippets, key = { it.id }) { snippet ->
                        SnippetRow(
                            snippet = snippet,
                            onTap = {
                                endpoint.write(buildSnippetPayload(snippet.command, snippet.appendNewline))
                                coroutineScope.launch {
                                    sheetState.hide()
                                    onDismiss()
                                }
                            },
                            onDelete = {
                                store.delete(snippet.id)
                                refresh()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Inline form for adding a new snippet. Kept small and focused — the
 * spec intentionally defers editing to a future iteration (the
 * delete-via-trash + re-add cycle is the v1 escape hatch).
 */
@Composable
private fun AddSnippetForm(
    onSubmit: (label: String, command: String, appendNewline: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var appendNewline by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF6F8FA))
            .padding(12.dp),
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text("命令") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Switch(
                checked = appendNewline,
                onCheckedChange = { appendNewline = it },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("自动回车")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(onClick = onCancel) { Text("取消") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (label.isNotBlank() && command.isNotBlank()) {
                        onSubmit(label.trim(), command, appendNewline)
                    }
                },
            ) { Text("保存") }
        }
    }
}

@Composable
private fun SnippetRow(
    snippet: CommandSnippet,
    onTap: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF6F8FA))
            .clickable(onClick = onTap)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = snippet.label,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = snippet.command,
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!snippet.appendNewline) {
                Text(
                    text = "(不自动回车)",
                    color = Color(0xFFFFC107),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除 ${snippet.label}",
                tint = Color(0xFFEF5350),
            )
        }
    }
}