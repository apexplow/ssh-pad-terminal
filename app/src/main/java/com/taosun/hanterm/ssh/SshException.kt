package com.taosun.hanterm.ssh

/**
 * Internal exception type that carries a [SshErrorMessages]-translated
 * message while preserving the original [cause] for `adb logcat` diagnosis.
 *
 * The UI layer only ever sees the friendly `message`; the original stack
 * trace stays in the cause chain so `Log.e(TAG, ..., cause)` can still print
 * it for engineers reading logs.
 *
 * Used by **both** failure paths so the UI sees consistent wording:
 *
 *  - [SshClient.connect] wraps connect-time failures (DNS, TCP RST,
 *    SocketTimeoutException during `client.connect`, kex rejection).
 *  - [SshSession.readInto] wraps post-connect read-loop failures
 *    (SocketException on TCP RST, SocketTimeoutException when SO_TIMEOUT
 *    fires during a quiet shell, sshj [SSHException] on transport errors).
 *
 * The previous name was `SshConnectException`; it grew misleading once
 * [SshSession.readInto] started wrapping the same way, so this is now the
 * shared umbrella for any SSH failure that has been translated through
 * [SshErrorMessages.friendly].
 */
internal class SshException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)