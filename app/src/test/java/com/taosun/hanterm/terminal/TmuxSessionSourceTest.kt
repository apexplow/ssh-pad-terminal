package com.taosun.hanterm.terminal

import com.taosun.hanterm.ssh.RemoteCommandExecutor
import com.taosun.hanterm.ssh.RemoteCommandResult
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxSessionSourceTest {

    @Test
    fun refresh_usesSideBandCommandAndNeverWritesInteractiveEndpoint() = runBlocking {
        val endpoint = RecordingEndpoint()
        val commands = QueueExecutor(
            success(stdout = "${'$'}0|2|attached||main\n"),
        )
        val source = source(endpoint, commands)

        val sessions = source.refresh().getOrThrow()

        assertEquals(listOf("main"), sessions.map { it.name })
        assertEquals("${'$'}0", sessions.single().id)
        assertTrue(endpoint.writes.isEmpty())
        assertEquals(listOf(TmuxSessionSource.TMUX_LIST_COMMAND), commands.commands)
    }

    @Test
    fun consecutiveRefreshes_parseFreshCommandOutput() = runBlocking {
        val commands = QueueExecutor(
            success(stdout = "${'$'}0|1|detached||old\n"),
            success(stdout = "${'$'}1|1|attached||new\n"),
        )
        val source = source(RecordingEndpoint(), commands)

        assertEquals("old", source.refresh().getOrThrow().single().name)
        assertEquals("new", source.refresh().getOrThrow().single().name)
    }

    @Test
    fun refresh_noServer_isEmpty() = runBlocking {
        val commands = QueueExecutor(
            success(stderr = "no server running on /tmp/tmux-1000/default", status = 1),
        )

        assertTrue(source(RecordingEndpoint(), commands).refresh().getOrThrow().isEmpty())
    }

    @Test
    fun refresh_commandNotFound_isFailureNotEmpty() = runBlocking {
        val commands = QueueExecutor(success(stderr = "tmux: not found", status = 127))

        val result = source(RecordingEndpoint(), commands).refresh()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("PATH"))
    }

    @Test
    fun refreshes_areSerialized() = runBlocking {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val executor = object : RemoteCommandExecutor {
            override suspend fun execute(command: String): Result<RemoteCommandResult> {
                val now = active.incrementAndGet()
                maxActive.updateAndGet { maxOf(it, now) }
                delay(10)
                active.decrementAndGet()
                return Result.success(success())
            }
        }
        val source = source(RecordingEndpoint(), executor)

        val first = async { source.refresh() }
        val second = async { source.refresh() }
        first.await()
        second.await()

        assertEquals(1, maxActive.get())
    }

    @Test
    fun switchTo_writesStableTargetThenSeparateEnter() = runBlocking {
        val endpoint = RecordingEndpoint()
        val gaps = mutableListOf<Long>()
        val source = TmuxSessionSource(
            endpoint = endpoint,
            remoteCommandExecutor = QueueExecutor(success()),
            pollDelay = { gaps += it },
        )

        source.switchTo("${'$'}7")

        assertEquals(" tmux switch-client -t '${'$'}7' 2>/dev/null || tmux attach -t '${'$'}7'", endpoint.text(0))
        assertArrayEquals(byteArrayOf('\r'.code.toByte()), endpoint.writes[1])
        assertEquals(listOf(TmuxSessionSource.SWITCH_ENTER_GAP_MS), gaps)
    }

    @Test
    fun detach_usesReportedControlPrefix() = runBlocking {
        val endpoint = RecordingEndpoint()
        val source = source(endpoint, QueueExecutor(success()))

        val result = source.detach("C-a")

        assertTrue(result.isSuccess)
        assertArrayEquals(byteArrayOf(0x01), endpoint.writes[0])
        assertArrayEquals(byteArrayOf('d'.code.toByte()), endpoint.writes[1])
    }

    @Test
    fun detach_rejectsUnsupportedPrefixWithoutWriting() = runBlocking {
        val endpoint = RecordingEndpoint()
        val result = source(endpoint, QueueExecutor(success())).detach("F12")

        assertTrue(result.isFailure)
        assertTrue(endpoint.writes.isEmpty())
    }

    private fun source(endpoint: RecordingEndpoint, executor: RemoteCommandExecutor) =
        TmuxSessionSource(endpoint = endpoint, remoteCommandExecutor = executor, pollDelay = {})

    private class RecordingEndpoint : TerminalEndpoint {
        val writes = mutableListOf<ByteArray>()
        override fun write(bytes: ByteArray) {
            writes += bytes.copyOf()
        }

        fun text(index: Int): String = writes[index].toString(Charsets.UTF_8)
    }

    private class QueueExecutor(vararg outcomes: RemoteCommandResult) :
        RemoteCommandExecutor {
        private val outcomes = ConcurrentLinkedQueue(outcomes.toList())
        val commands = mutableListOf<String>()

        override suspend fun execute(command: String): Result<RemoteCommandResult> {
            commands += command
            return Result.success(outcomes.poll() ?: error("no queued command result"))
        }
    }

    companion object {
        private fun success(
            stdout: String = "",
            stderr: String = "",
            status: Int? = 0,
        ): RemoteCommandResult = RemoteCommandResult(
            stdout = stdout.toByteArray(),
            stderr = stderr.toByteArray(),
            exitStatus = status,
        )
    }
}
