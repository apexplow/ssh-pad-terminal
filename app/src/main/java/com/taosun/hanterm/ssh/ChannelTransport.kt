package com.taosun.hanterm.ssh

import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.direct.Session

/**
 * Production [SshTransport] backed by an SSHJ [Channel]. In practice the
 * channel is always a [Session.Shell] (SshClient only opens shell channels
 * in v1.0) but we type as [Channel] here to keep the abstraction narrow —
 * `SshSession` doesn't need to know whether the underlying channel is a
 * shell, exec, subsystem, or future port-forward.
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
        // Only Session.Shell supports window-change requests. Anything else
        // (Command, Subsystem) just ignores the resize — that's the right
        // behavior for the v1.0 "always a shell" surface but degrades
        // gracefully if a future caller reuses SshTransport with another
        // channel type.
        val shell = channel as? Session.Shell ?: return
        runCatching {
            shell.changeWindowDimensions(cols, rows, widthPx, heightPx)
        }
    }

    override fun close() {
        // closeQuietly has two overloads; Channel is a Closeable so the
        // vararg one is the right match. We explicitly cast to disambiguate
        // the call.
        IOUtils.closeQuietly(channel as java.io.Closeable)
    }

    private companion object {
        // 8 KiB matches SSHJ's own default ChannelInputStream buffer.
        const val READ_BUFFER_BYTES = 8 * 1024
    }
}
