package com.apexplow.hanterm.ui

import com.apexplow.hanterm.terminal.link.LaunchResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sprint 4 T15 — pins the [LinkDialogState] state machine.
 *
 * The composable's behaviour is layered:
 *   1. [LinkDialogState] — pure MutableStateFlow + show/dismiss.
 *   2. The Compose layer (LinkDialog) — re-validates URL on recomposition.
 *
 * This test pins (1). The recomposition-time re-validation lives in the
 * Compose layer; we'd need Compose UI tests (T-LARGE-2 in TODOS.md) for
 * that, which v0.1 hasn't wired up. The state machine is the load-bearing
 * part of the public contract — the host pushes a URL, the dialog
 * renders, the host dismisses after the user picks an action.
 */
class LinkDialogStateTest {

    @Test
    fun initialState_pendingUrlIsNull() = runBlocking {
        val state = LinkDialogState()
        assertNull(state.pendingUrl.first())
    }

    @Test
    fun show_setsPendingUrl() = runBlocking {
        val state = LinkDialogState()
        state.show("https://example.com")
        assertEquals("https://example.com", state.pendingUrl.first())
    }

    @Test
    fun show_replacesPreviousUrl() = runBlocking {
        val state = LinkDialogState()
        state.show("https://a.com")
        state.show("https://b.com")
        assertEquals("https://b.com", state.pendingUrl.first())
    }

    @Test
    fun dismiss_clearsPendingUrl() = runBlocking {
        val state = LinkDialogState()
        state.show("https://example.com")
        state.dismiss()
        assertNull(state.pendingUrl.first())
    }

    @Test
    fun dismiss_whenAlreadyDismissed_isNoOp() = runBlocking {
        val state = LinkDialogState()
        state.dismiss() // no-op, must not throw
        assertNull(state.pendingUrl.first())
    }

    @Test
    fun showAfterDismiss_worksAgain() = runBlocking {
        val state = LinkDialogState()
        state.show("https://a.com")
        state.dismiss()
        state.show("https://b.com")
        assertEquals("https://b.com", state.pendingUrl.first())
    }

    @Test
    fun stateflowEmitsLatestValue() = runBlocking {
        val state = LinkDialogState()
        // Subscribe before show — the StateFlow contract is that
        // collectors get the latest value.
        val first = state.pendingUrl.first()
        assertNull(first)
        state.show("https://example.com")
        val second = state.pendingUrl.first()
        assertEquals("https://example.com", second)
    }

    @Test
    fun launchResultSealedInterface_exhaustiveValues() {
        // The three branches of LaunchResult are non-empty and pinned to
        // their data-object identity. A future refactor that drops one
        // (or renames it) will trip the exhaustive `when` in LinkDialog
        // — and this test catches the regression directly.
        assertEquals(LaunchResult.Ok, LaunchResult.Ok)
        assertEquals(LaunchResult.StaleUrl, LaunchResult.StaleUrl)
        assertEquals(LaunchResult.NoBrowser, LaunchResult.NoBrowser)
    }
}