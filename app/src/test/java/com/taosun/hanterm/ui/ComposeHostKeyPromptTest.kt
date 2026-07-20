package com.taosun.hanterm.ui

import com.taosun.hanterm.ssh.security.HostKeyPromptRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ComposeHostKeyPrompt]'s suspend/threading contract — the part
 * [KnownHostsVerifier][com.taosun.hanterm.ssh.security.KnownHostsVerifier]
 * actually depends on. The [ComposeHostKeyPrompt.Dialog] composable itself
 * (the AlertDialog rendering) isn't exercised here — there's no existing
 * Compose UI test harness in this project to drive button taps, and the
 * rendering is declarative Material3 with no branching logic beyond what's
 * already pinned by [HostKeyPromptRequest.previousFingerprint] in
 * `KnownHostsVerifierTest`. Manual verification on-device is the fallback
 * for the dialog's actual appearance, matching the project's existing
 * device-only coverage note for `KeyStoreManager`.
 *
 * [ComposeHostKeyPrompt.confirm] is called via `runBlocking` from sshj's
 * background reader thread in production; these tests drive it from a
 * [Dispatchers.Default] coroutine (a real background thread, not the test's
 * main thread) and answer it from the test's own coroutine — mirroring the
 * cross-thread completion the real UI does from a button's `onClick`.
 */
class ComposeHostKeyPromptTest {

    private val request = HostKeyPromptRequest(
        host = "example.com",
        port = 22,
        keyType = "ssh-ed25519",
        fingerprintBase64 = "AAAA",
        previousFingerprint = null,
    )

    @Test
    fun confirm_suspendsUntilAnsweredThenReturnsTheAnswer() = runBlocking {
        val prompt = ComposeHostKeyPrompt()
        val result = async(Dispatchers.Default) { prompt.confirm(request) }

        val pending = awaitPending(prompt)
        assertNotNull("confirm() must publish a pending request before answering", pending)
        assertEquals(request, pending!!.request)
        assertFalse("must still be suspended before the deferred completes", result.isCompleted)

        pending.answer.complete(true)
        assertEquals(true, withTimeout(1000) { result.await() })
    }

    @Test
    fun confirm_declinedReturnsFalseAndClearsPendingState() = runBlocking {
        val prompt = ComposeHostKeyPrompt()
        val result = async(Dispatchers.Default) { prompt.confirm(request) }

        val pending = awaitPending(prompt)!!
        pending.answer.complete(false)
        assertEquals(false, withTimeout(1000) { result.await() })

        // The `finally` block clears the slot once answered; poll briefly
        // since that happens on the background coroutine, not this thread.
        var cleared = false
        repeat(50) {
            if (prompt.pending.value == null) {
                cleared = true
                return@repeat
            }
            delay(10)
        }
        assertTrue("pending slot must be cleared once answered", cleared)
        assertNull(prompt.pending.value)
    }

    private suspend fun awaitPending(prompt: ComposeHostKeyPrompt): ComposeHostKeyPrompt.Pending? {
        repeat(100) {
            prompt.pending.value?.let { return it }
            delay(10)
        }
        return null
    }
}
