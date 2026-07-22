package com.taosun.hanterm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taosun.hanterm.ssh.security.HostKeyPrompt
import com.taosun.hanterm.ssh.security.HostKeyPromptRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bridges [KnownHostsVerifier][com.taosun.hanterm.ssh.security.KnownHostsVerifier]'s
 * synchronous [confirm] call (invoked via `runBlocking`, from sshj's
 * transport reader thread — NOT the Compose/main thread) into a Yes/No
 * dialog the user can answer.
 *
 * ## Threading
 *
 * [confirm] parks whatever thread called it inside a [CompletableDeferred]
 * await. [pending] is a [MutableStateFlow] rather than a plain
 * `mutableStateOf` specifically so the write from that background thread is
 * unambiguously safe — `StateFlow.value` is a plain atomic/volatile field
 * write, no Compose snapshot semantics involved. [Dialog] collects it back
 * on the composition's dispatcher via [collectAsState]. Completing the
 * [CompletableDeferred] from the button's `onClick` (main thread) resumes
 * the suspended coroutine on the original background thread — a standard,
 * dispatcher-agnostic `kotlinx.coroutines` pattern.
 *
 * One [ComposeHostKeyPrompt] instance is `remember`ed alongside the
 * [com.taosun.hanterm.ssh.SshClient] instance it's wired into; only one
 * connect attempt is ever in flight at a time, so there's no need to queue
 * more than one pending prompt.
 */
class ComposeHostKeyPrompt : HostKeyPrompt {

    /** `internal` (not `private`) so unit tests can drive the answer without a Compose test rule. */
    internal data class Pending(
        val request: HostKeyPromptRequest,
        val answer: CompletableDeferred<Boolean>,
    )

    internal val pending = MutableStateFlow<Pending?>(null)

    override suspend fun confirm(request: HostKeyPromptRequest): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pending.value = Pending(request, answer)
        return try {
            answer.await()
        } finally {
            pending.value = null
        }
    }

    /**
     * Renders nothing when there's no pending decision; otherwise a modal
     * Yes/No [AlertDialog]. Callers mount this once, unconditionally, at the
     * top of the composable tree (it's a no-op most of the time).
     */
    @Composable
    fun Dialog() {
        val current by pending.collectAsState()
        val slot = current ?: return
        val request = slot.request
        val isChange = request.previousFingerprint != null

        AlertDialog(
            onDismissRequest = { slot.answer.complete(false) },
            title = { Text(if (isChange) "Host key changed" else "Unknown host") },
            text = {
                Column {
                    if (isChange) {
                        Text(
                            "The key presented by ${request.host}:${request.port} does NOT match " +
                                "the one saved from an earlier connection. This can mean the server " +
                                "was reinstalled or its key was rotated — or that the connection is " +
                                "being intercepted.",
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Previously trusted:", fontSize = 12.sp)
                        Text(
                            request.previousFingerprint?.fingerprintBase64.orEmpty(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Now presenting:", fontSize = 12.sp)
                    } else {
                        Text(
                            "The authenticity of ${request.host}:${request.port} can't be " +
                                "established yet — this is the first connection to it.",
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("${request.keyType} key fingerprint:", fontSize = 12.sp)
                    }
                    Text(
                        request.fingerprintBase64,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Trust this key and continue connecting?")
                }
            },
            confirmButton = {
                TextButton(onClick = { slot.answer.complete(true) }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { slot.answer.complete(false) }) { Text("No") }
            },
        )
    }
}
