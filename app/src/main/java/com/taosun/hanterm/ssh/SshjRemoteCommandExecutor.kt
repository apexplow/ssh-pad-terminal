package com.taosun.hanterm.ssh

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session

/**
 * Executes bounded commands on independent SSH session channels.
 *
 * One command is admitted at a time. Its session is published before `exec`
 * so cancellation can close an in-flight channel-open/exec request without
 * ever touching another invocation's resource.
 */
internal class SshjRemoteCommandExecutor(
    private val sessionFactory: RemoteCommandSessionFactory,
    private val timeoutMs: Long = SshConfig.REMOTE_COMMAND_TIMEOUT_MS,
    private val outputLimitBytes: Int = SshConfig.REMOTE_COMMAND_OUTPUT_LIMIT_BYTES,
) : RemoteCommandExecutor {

    constructor(
        client: SSHClient,
        timeoutMs: Long = SshConfig.REMOTE_COMMAND_TIMEOUT_MS,
        outputLimitBytes: Int = SshConfig.REMOTE_COMMAND_OUTPUT_LIMIT_BYTES,
    ) : this(
        sessionFactory = SshjRemoteCommandSessionFactory(client),
        timeoutMs = timeoutMs,
        outputLimitBytes = outputLimitBytes,
    )

    private val closed = AtomicBoolean(false)
    private val admission = Mutex()
    private val executionPermit = Semaphore(1, true)
    private val activeResource = AtomicReference<Closeable?>(null)
    private val commandExecutor = daemonCachedPool("SshRemoteCommand")
    private val streamExecutor = daemonCachedPool("SshRemoteStream")

    override suspend fun execute(command: String): Result<RemoteCommandResult> =
        admission.withLock {
            if (closed.get()) {
                return@withLock Result.failure(
                    IllegalStateException("remote command executor is closed"),
                )
            }
            if (command.isBlank()) {
                return@withLock Result.failure(
                    IllegalArgumentException("remote command must not be blank"),
                )
            }
            try {
                withTimeout(timeoutMs) {
                    executeCancellable(command)
                }
            } catch (e: TimeoutCancellationException) {
                Result.failure(RemoteCommandTimeoutException(timeoutMs, e))
            }
        }

    private suspend fun executeCancellable(command: String): Result<RemoteCommandResult> =
        suspendCancellableCoroutine { continuation ->
            val cancellationRequested = AtomicBoolean(false)
            val localResource = AtomicReference<Closeable?>(null)
            val task = runCatching {
                commandExecutor.submit {
                    var permitAcquired = false
                    var stdoutFuture: Future<ByteArray>? = null
                    var stderrFuture: Future<ByteArray>? = null
                    try {
                        executionPermit.acquire()
                        permitAcquired = true
                        ensureStillActive(cancellationRequested, continuation.isActive)
                        val session = sessionFactory.open()
                        publish(localResource, session)
                        ensureStillActive(cancellationRequested, continuation.isActive)

                        val channel = session.exec(command)
                        publish(localResource, channel)
                        ensureStillActive(cancellationRequested, continuation.isActive)

                        val readerFailure = AtomicReference<Throwable?>(null)
                        stdoutFuture = drainAsync(channel.stdout, channel, readerFailure)
                        stderrFuture = drainAsync(channel.stderr, channel, readerFailure)

                        try {
                            channel.awaitCompletion()
                        } catch (t: Throwable) {
                            throw readerFailure.get() ?: t
                        }

                        val stdout = awaitDrain(stdoutFuture, readerFailure)
                        val stderr = awaitDrain(stderrFuture, readerFailure)
                        channel.close()
                        val result = RemoteCommandResult(
                            stdout = stdout,
                            stderr = stderr,
                            exitStatus = channel.exitStatus,
                            exitSignal = channel.exitSignal,
                        )
                        if (continuation.isActive) {
                            continuation.resume(Result.success(result))
                        }
                    } catch (t: Throwable) {
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(t))
                        }
                    } finally {
                        stdoutFuture?.cancel(true)
                        stderrFuture?.cancel(true)
                        val resource = localResource.getAndSet(null)
                        resource?.closeQuietly()
                        activeResource.compareAndSet(resource, null)
                        if (permitAcquired) executionPermit.release()
                    }
                }
            }.getOrElse { submitFailure ->
                continuation.resume(Result.failure(submitFailure))
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                cancellationRequested.set(true)
                localResource.get()?.let(::closeAsync)
                task.cancel(true)
            }
        }

    /**
     * Replaces the invocation-local cancel target. If executor close won the
     * race, close the newly published resource immediately.
     */
    private fun publish(target: AtomicReference<Closeable?>, resource: Closeable) {
        target.set(resource)
        activeResource.set(resource)
        if (closed.get()) {
            resource.closeQuietly()
            throw IllegalStateException("remote command executor is closed")
        }
    }

    private fun ensureStillActive(cancelled: AtomicBoolean, continuationActive: Boolean) {
        if (cancelled.get() || !continuationActive || closed.get()) {
            throw java.util.concurrent.CancellationException("remote command cancelled")
        }
    }

    private fun drainAsync(
        input: InputStream,
        channel: RemoteCommandChannel,
        readerFailure: AtomicReference<Throwable?>,
    ): Future<ByteArray> = streamExecutor.submit<ByteArray> {
        try {
            readBounded(input, outputLimitBytes)
        } catch (t: Throwable) {
            readerFailure.compareAndSet(null, t)
            channel.closeQuietly()
            throw t
        }
    }

    private fun awaitDrain(
        future: Future<ByteArray>,
        readerFailure: AtomicReference<Throwable?>,
    ): ByteArray = try {
        future.get()
    } catch (t: Throwable) {
        throw readerFailure.get() ?: t
    }

    /**
     * Non-blocking for callers such as the main-thread Disconnect handler.
     * The active channel is closed on a daemon worker; parent SSH teardown is
     * still owned by SshClient's existing write-executor path.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeResource.getAndSet(null)?.let(::closeAsync)
        commandExecutor.shutdown()
        streamExecutor.shutdownNow()
    }

    private fun closeAsync(resource: Closeable) {
        runCatching {
            commandExecutor.execute { resource.closeQuietly() }
        }.onFailure {
            Thread(
                { resource.closeQuietly() },
                "SshRemoteClose",
            ).apply { isDaemon = true }.start()
        }
    }

    private fun Closeable.closeQuietly() {
        runCatching { close() }
    }

    private companion object {
        fun daemonCachedPool(name: String): ExecutorService =
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, name).apply { isDaemon = true }
            }

        fun readBounded(input: InputStream, limitBytes: Int): ByteArray {
            val output = ByteArrayOutputStream(minOf(limitBytes, 8 * 1024))
            val buffer = ByteArray(4 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size() + count > limitBytes) {
                    throw RemoteCommandOutputLimitException(limitBytes)
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}

internal class RemoteCommandTimeoutException(
    timeoutMs: Long,
    cause: Throwable,
) : Exception("remote command timed out after ${timeoutMs}ms", cause)

internal class RemoteCommandOutputLimitException(
    limitBytes: Int,
) : Exception("remote command output exceeded $limitBytes bytes")

internal fun interface RemoteCommandSessionFactory {
    fun open(): RemoteCommandSession
}

internal interface RemoteCommandSession : Closeable {
    fun exec(command: String): RemoteCommandChannel
}

internal interface RemoteCommandChannel : Closeable {
    val stdout: InputStream
    val stderr: InputStream
    val exitStatus: Int?
    val exitSignal: String?
    fun awaitCompletion()
}

private class SshjRemoteCommandSessionFactory(
    private val client: SSHClient,
) : RemoteCommandSessionFactory {
    override fun open(): RemoteCommandSession =
        SshjRemoteCommandSession(client.startSession())
}

private class SshjRemoteCommandSession(
    private val session: Session,
) : RemoteCommandSession {
    private val closed = AtomicBoolean(false)

    override fun exec(command: String): RemoteCommandChannel = try {
        SshjRemoteCommandChannel(session, session.exec(command))
    } catch (t: Throwable) {
        close()
        throw t
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { session.close() }
    }
}

private class SshjRemoteCommandChannel(
    private val session: Session,
    private val command: Session.Command,
) : RemoteCommandChannel {
    private val closed = AtomicBoolean(false)
    private var capturedExitStatus: Int? = null
    private var capturedExitSignal: String? = null

    override val stdout: InputStream = command.inputStream
    override val stderr: InputStream = command.errorStream
    override val exitStatus: Int? get() = capturedExitStatus
    override val exitSignal: String? get() = capturedExitSignal

    override fun awaitCompletion() {
        command.join()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { command.close() }
        capturedExitStatus = command.exitStatus
        capturedExitSignal = command.exitSignal?.toString()
        runCatching { session.close() }
    }
}
