package com.taosun.hanterm.ssh

/**
 * Result of one command executed on a short-lived SSH session channel.
 *
 * A non-zero [exitStatus] is a successfully completed remote command, not a
 * transport failure. Feature layers (tmux in particular) need stderr and the
 * exact status to distinguish "no server" from "command not found".
 */
data class RemoteCommandResult(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val exitStatus: Int?,
    val exitSignal: String? = null,
)

/**
 * Narrow capability for side-band remote commands.
 *
 * This deliberately does not belong to [SshTransport]: the latter represents
 * the one long-lived interactive shell channel, while every execution here
 * opens and closes an independent SSH channel on the same authenticated
 * connection.
 */
interface RemoteCommandExecutor {
    suspend fun execute(command: String): Result<RemoteCommandResult>

    /** Cancels active commands and releases local worker threads. */
    fun close() = Unit
}

internal object UnavailableRemoteCommandExecutor : RemoteCommandExecutor {
    override suspend fun execute(command: String): Result<RemoteCommandResult> =
        Result.failure(IllegalStateException("remote command channel unavailable"))
}
