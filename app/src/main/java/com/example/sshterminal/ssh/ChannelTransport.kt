package com.example.sshterminal.ssh

import com.hierynomus.sshj.channel.Channel
import com.hierynomus.sshj.channel.ChannelShell
import net.schmizz.sshj.common.IOUtils

/**
 * Production [SshTransport] backed by an SSHJ [Channel] (always a [ChannelShell]
 * in practice — SshClient only opens shell channels).
 *
 * Wraps the channel's byte streams and translates the [SshTransport] contract
 * into SSHJ's API. The IO loop and write path are both blocking, so callers
 * must dispatch them onto [kotlinx.coroutines.Dispatchers.IO].
 *
 * [SSH_ANDROID_PITFALL]: SSHJ's `Channel.outputStream` is buffered. If we
 * don't `flush()` after every `write`, keystrokes pile up in the local
 * buffer until the next 1 KiB boundary or channel close — the user types
 * "ls" and the remote shell sees "l" three seconds later. Always flush.
 */
internal class ChannelTransport(
    private val channel: Channel,
) : SshTransport {

    private val input = channel.inputStream
    private val output = channel.outputStream

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun readBytes(): ByteArray? {
        val buf = ByteArray(READ_BUFFER_BYTES)
        val n = input.read(buf)
        if (n <= 0) return null
        return if (n == buf.size) buf else buf.copyOf(n)
    }

    override fun resizePty(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        val shell = channel as? ChannelShell ?: return
        shell.setTerminalCols(cols)
        shell.setTerminalRows(rows)
        if (widthPx > 0) shell.setTerminalWidth(widthPx)
        if (heightPx > 0) shell.setTerminalHeight(heightPx)
    }

    override fun close() {
        IOUtils.closeQuietly(channel)
    }

    private companion object {
        // 8 KiB matches SSHJ's own default ChannelInputStream buffer.
        const val READ_BUFFER_BYTES = 8 * 1024
    }
}
