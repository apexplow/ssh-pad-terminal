package com.taosun.hanterm.ssh

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshSessionRemoteCommandTest {

    @Test
    fun close_closesCommandExecutorWithSession() {
        val commands = RecordingCommandExecutor()
        val session = SshSession(
            transport = NoopTransport(),
            remoteCommandExecutor = commands,
            onClose = {},
        )

        session.close()
        session.awaitWriteQueueDrained()

        assertTrue(commands.closed.get())
    }

    @Test
    fun executeAfterSessionClose_failsWithoutCallingExecutor() = runBlocking {
        val commands = RecordingCommandExecutor()
        val session = SshSession(
            transport = NoopTransport(),
            remoteCommandExecutor = commands,
            onClose = {},
        )
        session.close()

        val result = session.executeRemoteCommand("tmux")

        assertTrue(result.isFailure)
        assertFalse(commands.executed.get())
    }

    private class RecordingCommandExecutor : RemoteCommandExecutor {
        val closed = AtomicBoolean(false)
        val executed = AtomicBoolean(false)

        override suspend fun execute(command: String): Result<RemoteCommandResult> {
            executed.set(true)
            return Result.success(RemoteCommandResult(byteArrayOf(), byteArrayOf(), 0))
        }

        override fun close() {
            closed.set(true)
        }
    }

    private class NoopTransport : SshTransport {
        override fun write(bytes: ByteArray) = Unit
        override fun readBytes(): ByteArray? = null
        override fun resizePty(cols: Int, rows: Int, widthPx: Int, heightPx: Int) = Unit
        override fun close() = Unit
    }
}
