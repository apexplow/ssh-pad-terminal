package com.taosun.hanterm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.taosun.hanterm.terminal.TerminalEndpoint
import com.taosun.hanterm.terminal.TmuxSession
import com.taosun.hanterm.terminal.TmuxSessionSource
import kotlinx.coroutines.launch

/**
 * Right-edge side drawer that lists tmux sessions and switches (or attaches)
 * to one on tap.
 *
 * Replaces the Sprint-3 SnippetPanel, which was a `ModalBottomSheet` and
 * felt cramped on a pad's wide canvas. The right edge co-locates with
 * the existing `TopEnd` IconButton trigger in [HanTermApp.TerminalScreen],
 * so the user's thumb doesn't have to cross the screen to open and tap.
 *
 * ## Why custom (not Material3 `ModalNavigationDrawer`)
 *
 * Material3's [androidx.compose.material3.ModalNavigationDrawer] only
 * supports the start edge. A right-edge drawer (which is what we want
 * here — see co-location note above) needs a manual layout. We compose
 * a scrim + [AnimatedVisibility] over a [Box] that aligns the sheet at
 * [Alignment.CenterEnd]. This is ~40 lines and avoids fighting the
 * Material3 directional defaults.
 *
 * ## Lifecycle
 *
 * Auto-refreshes on every open (see `LaunchedEffect(open)`) so the user
 * sees fresh sessions even if they created one outside the app while
 * the drawer was closed. A manual refresh button on the header covers
 * the "opened drawer right after another tmux action" case.
 *
 * @param endpoint active session endpoint; used both to inject the
 *   probe and to emit the switch command.
 * @param source captures the `tmux list-sessions` output. Constructed
 *   outside this composable so the parent can share one instance across
 *   opens (cheaper than reallocating per open — the source is stateless
 *   but its poll loop is real IO).
 */
@Composable
fun TmuxDrawer(
    endpoint: TerminalEndpoint,
    source: TmuxSessionSource,
    open: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!open) return

    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<TmuxDrawerState>(TmuxDrawerState.Loading) }

    LaunchedEffect(open) {
        if (open) {
            state = TmuxDrawerState.Loading
            val result = source.refresh()
            state = result.fold(
                onSuccess = { sessions ->
                    if (sessions.isEmpty()) TmuxDrawerState.Empty else TmuxDrawerState.Loaded(sessions)
                },
                onFailure = { TmuxDrawerState.Error(it.message ?: "未知错误") },
            )
        }
    }

    fun selectSession(session: TmuxSession) {
        endpoint.write(source.switchCommand(session.name))
        onDismiss()
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim — taps anywhere outside the drawer dismiss it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(
                animationSpec = tween(durationMillis = 220),
                initialOffsetX = { it },
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(durationMillis = 180),
                targetOffsetX = { it },
            ),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxSize()
                    .width(320.dp),
                drawerShape = RoundedCornerShape(0.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    DrawerHeader(
                        onRefresh = {
                            scope.launch {
                                state = TmuxDrawerState.Loading
                                val result = source.refresh()
                                state = result.fold(
                                    onSuccess = { sessions ->
                                        if (sessions.isEmpty()) TmuxDrawerState.Empty
                                        else TmuxDrawerState.Loaded(sessions)
                                    },
                                    onFailure = {
                                        TmuxDrawerState.Error(it.message ?: "未知错误")
                                    },
                                )
                            }
                        },
                        onDismiss = onDismiss,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    when (val s = state) {
                        is TmuxDrawerState.Loading -> LoadingBody()
                        is TmuxDrawerState.Empty -> EmptyBody()
                        is TmuxDrawerState.Loaded -> SessionList(sessions = s.sessions, onSelect = ::selectSession)
                        is TmuxDrawerState.Error -> ErrorBody(s.message)
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerHeader(
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "tmux Sessions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Row {
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "刷新",
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                )
            }
        }
    }
}

@Composable
private fun LoadingBody() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text("正在查询 tmux…", color = Color.Gray)
        }
    }
}

@Composable
private fun EmptyBody() {
    Box(
        modifier = Modifier.fillMaxSize().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "未检测到 tmux session。\n请在远程主机启动 tmux 后下拉刷新。",
            color = Color.Gray,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ErrorBody(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "获取失败：$message",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SessionList(
    sessions: List<TmuxSession>,
    onSelect: (TmuxSession) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = sessions, key = { it.name }) { session ->
            SessionRow(session = session, onTap = { onSelect(session) })
        }
    }
}

@Composable
private fun SessionRow(
    session: TmuxSession,
    onTap: () -> Unit,
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
        // Attached indicator: green dot when the session is currently attached.
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (session.attached) Color(0xFF4CAF50) else Color(0xFFBDBDBD)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.name,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${session.windows} 窗口 · ${session.lastActivity}",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Drawer body state. Sealed so the [TmuxDrawer] composable can `when`-match
 * and the compiler can prove every state is handled.
 */
private sealed interface TmuxDrawerState {
    data object Loading : TmuxDrawerState
    data object Empty : TmuxDrawerState
    data class Loaded(val sessions: List<TmuxSession>) : TmuxDrawerState
    data class Error(val message: String) : TmuxDrawerState
}

/**
 * Reminder for future touch-points: the screen-buffer read in
 * [TmuxSessionSource] blocks the IO dispatcher for up to 3s, so keep
 * this composable off the UI dispatcher.
 */
@Suppress("unused")
private val ioDispatcherHint: Unit = Unit.also {
    // No-op marker — kdoc anchor for future readers.
}
