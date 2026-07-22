package com.taosun.hanterm.ssh

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshjRemoteCommandExecutorTest {
    private val executors = mutableListOf<SshjRemoteCommandExecutor>()

    @After
    fun tearDown() {
        executors.forEach { it.close() }
    }

    @Test
    fun execute_collectsBothStreamsAndExitMetadata() = runBlocking {
        val channel = FakeCommandChannel(
            stdoutBytes = "out".toByteArray(),
            stderrBytes = "warn".toByteArray(),
            status = 7,
            signal = "TERM",
        )
        val executor = executor(channel)

        val result = executor.execute("example").getOrThrow()

        assertEquals("out", result.stdout.toString(Charsets.UTF_8))
        assertEquals("warn", result.stderr.toString(Charsets.UTF_8))
        assertEquals(7, result.exitStatus)
        assertEquals("TERM", result.exitSignal)
        assertTrue(channel.closed.get())
    }

    @Test
    fun execute_outputOverLimit_closesOnlyCommandChannel() = runBlocking {
        val channel = FakeCommandChannel(stdoutBytes = ByteArray(9) { 1 })
        val executor = executor(channel, outputLimit = 8)

        val result = executor.execute("too-loud")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is RemoteCommandOutputLimitException)
        assertTrue(channel.closed.get())
    }

    @Test
    fun execute_timeoutActivelyClosesBlockingChannel() = runBlocking {
        val channel = FakeCommandChannel(blockUntilClose = true)
        val executor = executor(channel, timeoutMs = 2_000)
        val pending = async(Dispatchers.Default) { executor.execute("hang") }
        assertTrue(channel.awaitStarted())

        val result = pending.await()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is RemoteCommandTimeoutException)
        assertTrue(channel.closed.get())
    }

    @Test
    fun callerCancellation_isRethrownAndClosesChannel() = runBlocking {
        val channel = FakeCommandChannel(blockUntilClose = true)
        val executor = executor(channel)
        val job = launch(Dispatchers.Default) {
            executor.execute("cancel-me")
        }
        assertTrue(channel.awaitStarted())

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(channel.closed.get())
    }

    @Test
    fun cancellingQueuedExecution_doesNotOpenOrCloseActiveExecution() = runBlocking {
        val firstChannel = FakeCommandChannel(blockUntilClose = true)
        val secondChannel = FakeCommandChannel()
        val openedSessions = AtomicInteger(0)
        val executor = SshjRemoteCommandExecutor(
            sessionFactory = RemoteCommandSessionFactory {
                val channel = if (openedSessions.getAndIncrement() == 0) {
                    firstChannel
                } else {
                    secondChannel
                }
                object : RemoteCommandSession {
                    override fun exec(command: String): RemoteCommandChannel = channel
                    override fun close() = channel.close()
                }
            },
        ).also { executors += it }

        val first = async(Dispatchers.Default) { executor.execute("first") }
        assertTrue(firstChannel.awaitStarted())
        val second = launch(Dispatchers.Default) { executor.execute("second") }
        delay(100)

        second.cancelAndJoin()

        assertEquals(1, openedSessions.get())
        assertFalse(firstChannel.closed.get())
        assertFalse(secondChannel.closed.get())
        firstChannel.close()
        assertTrue(first.await().isSuccess)
    }

    @Test
    fun cancellationDuringSessionOpen_neverExecutesAndClosesPublishedSession() = runBlocking {
        val openStarted = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val sessionClosed = CountDownLatch(1)
        val execCalled = AtomicBoolean(false)
        val session = object : RemoteCommandSession {
            override fun exec(command: String): RemoteCommandChannel {
                execCalled.set(true)
                return FakeCommandChannel()
            }

            override fun close() {
                sessionClosed.countDown()
            }
        }
        val executor = SshjRemoteCommandExecutor(
            sessionFactory = RemoteCommandSessionFactory {
                openStarted.countDown()
                while (true) {
                    try {
                        releaseOpen.await()
                        break
                    } catch (_: InterruptedException) {
                        // Model an SSHJ open that does not promptly obey interruption.
                    }
                }
                session
            },
        ).also { executors += it }
        val job = launch(Dispatchers.Default) { executor.execute("must-not-run") }
        assertTrue(openStarted.await(5, TimeUnit.SECONDS))

        job.cancelAndJoin()
        releaseOpen.countDown()

        assertTrue(sessionClosed.await(5, TimeUnit.SECONDS))
        assertFalse(execCalled.get())
    }

    @Test
    fun close_rejectsFutureCommands() = runBlocking {
        val executor = executor(FakeCommandChannel())
        executor.close()

        assertTrue(executor.execute("after-close").isFailure)
    }

    private fun executor(
        channel: FakeCommandChannel,
        timeoutMs: Long = 5_000,
        outputLimit: Int = 64 * 1024,
    ): SshjRemoteCommandExecutor =
        SshjRemoteCommandExecutor(
            sessionFactory = RemoteCommandSessionFactory {
                object : RemoteCommandSession {
                    override fun exec(command: String): RemoteCommandChannel = channel
                    override fun close() {
                        channel.close()
                    }
                }
            },
            timeoutMs = timeoutMs,
            outputLimitBytes = outputLimit,
        ).also { executors += it }

    private class FakeCommandChannel(
        stdoutBytes: ByteArray = byteArrayOf(),
        stderrBytes: ByteArray = byteArrayOf(),
        private val status: Int? = 0,
        private val signal: String? = null,
        private val blockUntilClose: Boolean = false,
    ) : RemoteCommandChannel {
        override val stdout: InputStream = ByteArrayInputStream(stdoutBytes)
        override val stderr: InputStream = ByteArrayInputStream(stderrBytes)
        override val exitStatus: Int? get() = status
        override val exitSignal: String? get() = signal
        val closed = AtomicBoolean(false)
        private val closeLatch = CountDownLatch(1)
        private val startedLatch = CountDownLatch(1)

        override fun awaitCompletion() {
            startedLatch.countDown()
            if (blockUntilClose) {
                check(closeLatch.await(10, TimeUnit.SECONDS)) { "test channel was not closed" }
            }
        }

        fun awaitStarted(): Boolean = startedLatch.await(5, TimeUnit.SECONDS)

        override fun close() {
            closed.set(true)
            closeLatch.countDown()
        }
    }
}
