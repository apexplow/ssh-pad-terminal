package com.apexplow.hanterm.ui

import com.apexplow.hanterm.ssh.ConnectionView
import com.apexplow.hanterm.terminal.InboundTransferRouter
import com.apexplow.hanterm.terminal.zmodem.TransferEvent
import com.termux.terminal.TerminalEmulator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Production inbound pump used by [TerminalPane]: read → filter → append on
 * [ioDispatcher], per-chunk [onDisplayUpdated] on [mainDispatcher] (TV-IME-04
 * rising-edge must not ride a CONFLATED paint signal), and CONFLATED
 * [refreshSignal] for VSync invalidate only.
 *
 * Extracted so unit tests can inject dedicated single-thread dispatchers and
 * pin the IO/Main split without driving Compose `LaunchedEffect`.
 */
internal object TerminalInboundLoop {

    /**
     * Drains [read] until EOF (`null`) or cancellation. [applyChunk] returns
     * whether display bytes were appended (so the caller can signal paint).
     */
    suspend fun run(
        read: () -> ByteArray?,
        applyChunk: (ByteArray) -> Boolean,
        onDisplayUpdated: () -> Unit,
        refreshSignal: Channel<Unit>,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    ) {
        withContext(ioDispatcher) {
            while (currentCoroutineContext().isActive) {
                val bytes = read() ?: break
                val displayed = applyChunk(bytes)
                if (displayed) {
                    refreshSignal.trySend(Unit)
                }
                // Per-chunk Main hop — cheap (alt-buffer flag check) but must
                // not be CONFLATED with paint, or a rising-edge CSI ?1049h
                // between two Main paint consumers could be missed (TV-IME-04).
                withContext(mainDispatcher) {
                    onDisplayUpdated()
                }
            }
        }
    }
}

/**
 * Route one inbound PTY chunk through [InboundTransferRouter]: replies go
 * to SSH, display bytes go to the emulator, transfer events become Snackbars.
 *
 * **Thread**: safe to call off Main. [TerminalEmulator.append] matches Termux's
 * session-reader-thread contract. Returns `true` when display bytes were
 * appended (caller should signal a Main-thread invalidate).
 */
internal fun applyInboundChunk(
    bytes: ByteArray,
    transfers: InboundTransferRouter,
    endpoint: ConnectionView,
    emulator: TerminalEmulator,
): Boolean {
    val result = transfers.onInbound(bytes)
    result.reply?.let { endpoint.write(it) }
    val displayed = result.display.isNotEmpty()
    if (displayed) {
        emulator.append(result.display, result.display.size)
    }
    when (val event = result.event) {
        is TransferEvent.Done ->
            UiMessageBridge.showMessage("Saved to Downloads: ${event.fileName}")
        is TransferEvent.Failed ->
            UiMessageBridge.showMessage("Transfer failed: ${event.reason}")
        null -> Unit
    }
    return displayed
}
