package com.apexplow.hanterm

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-scoped marker that [com.apexplow.hanterm.ssh.SshSession] raises
 * when its `readInto` loop catches a known teardown exception
 * (SocketException / SSHException / SocketTimeoutException / etc.).
 *
 * [com.apexplow.hanterm.CrashHandler] consults [isRecent] when sshj's
 * internal Reader thread's re-thrown escape reaches the JVM's
 * uncaughtExceptionHandler. If the marker was raised within the last
 * [WINDOW_MS] milliseconds, the exception is treated as the EXPECTED
 * teardown (the user's clean Disconnect / a TCP RST after the socket
 * went silent) and the crash-overlay file is NOT written.
 *
 * Issue #62: replaces CrashHandler's brittle `t.name.startsWith("Reader")`
 * prefix match. sshj 0.40 happens to use that name today, but the thread
 * is internal to sshj and could rename in any release — the marker is
 * owned by **our** code path, so it is stable across sshj versions.
 *
 * The window is generous (500 ms) because the Reader thread's re-throw
 * can arrive shortly AFTER [com.apexplow.hanterm.ssh.SshSession.readInto]
 * returns its [Result.failure] — sshj's internal Reader thread is on a
 * separate OS thread and runs concurrently with our coroutine.
 */
object TransportAbortSignal {
    /**
     * Time window during which a [mark] call still suppresses the
     * crash-overlay write. 500 ms comfortably covers:
     *  - coroutine dispatch latency on Dispatchers.IO
     *  - sshj's internal Reader thread cleanup race
     *  - GC pauses on a busy device
     * Resetting the marker after a fixed window prevents a stuck "true"
     * from suppressing a genuinely unrelated crash later.
     */
    private const val WINDOW_MS: Long = 500L

    private val expectedAtMs: AtomicLong = AtomicLong(0L)

    /** Record that a known teardown just happened. Idempotent. */
    fun mark() {
        expectedAtMs.set(System.currentTimeMillis())
    }

    /** True when a [mark] call landed within [WINDOW_MS] of now. */
    fun isRecent(): Boolean {
        val marked = expectedAtMs.get()
        if (marked == 0L) return false
        return System.currentTimeMillis() - marked < WINDOW_MS
    }

    /**
     * Test seam: clear the marker so the next assertion is independent
     * of any prior test's signal. Tests that don't care about the
     * marker should still call this in @After to keep order
     * independence.
     */
    fun resetForTests() {
        expectedAtMs.set(0L)
    }
}