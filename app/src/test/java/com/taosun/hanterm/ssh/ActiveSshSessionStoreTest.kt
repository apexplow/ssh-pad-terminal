package com.taosun.hanterm.ssh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Pure JUnit contract test for [ActiveSshSessionStore] — the process-scoped
 * holder that lets a recreated [com.taosun.hanterm.ui.HanTermApp]
 * re-attach to an [SshSession] that survived Activity recreation.
 *
 * No Android framework calls and no Robolectric: the store is a plain
 * [java.util.concurrent.atomic.AtomicReference] wrapper, and the
 * interesting behaviour (last-writer-wins on [set], idempotent [clear])
 * is pure JVM. Keeping it framework-free means the test runs in <10ms
 * and doesn't drag in Robolectric's resource resolution just to read
 * a `Bundle`.
 */
class ActiveSshSessionStoreTest {

    @Before
    fun setUp() {
        // The store is a process-scoped singleton, so previous tests
        // (e.g. SshSessionWriteTest) can leave state behind. Reset on
        // entry to make each test independent.
        ActiveSshSessionStore.clear()
    }

    @After
    fun tearDown() {
        // Be a good citizen: don't leak the live reference into the
        // next test class.
        ActiveSshSessionStore.clear()
    }

    @Test
    fun test_get_returnsNullWhenEmpty() {
        // Sanity: a fresh store reports no active session. setUp() cleared
        // the previous test's state, so this should hold.
        assertNull(
            "fresh store must report null so the UI falls back to the login page",
            ActiveSshSessionStore.get(),
        )
    }

    @Test
    fun test_set_thenGet_returnsSameReference() = run {
        // Build a session through the same path the production code uses
        // (SshSession(transport) is internal, so we go through the
        // constructor from the same package — the test sits in
        // com.taosun.hanterm.ssh exactly for that reason).
        val transport = FakeTransportForStore()
        val session = SshSession(transport = transport, onClose = {})

        ActiveSshSessionStore.set(session)

        val got = ActiveSshSessionStore.get()
        assertNotNull("store must return the session we just set", got)
        assertSame(
            "store must hand back the *same* reference — recreating " +
                "the session would break the Activity-recreation path " +
                "(the live sshj socket belongs to this exact object)",
            session,
            got,
        )
    }

    @Test
    fun test_set_replacesPreviousSession() {
        val firstTransport = FakeTransportForStore()
        val first = SshSession(transport = firstTransport, onClose = {})
        val secondTransport = FakeTransportForStore()
        val second = SshSession(transport = secondTransport, onClose = {})

        ActiveSshSessionStore.set(first)
        ActiveSshSessionStore.set(second)

        assertSame(
            "set() must overwrite — the second connect is the live one, " +
                "the first is dead",
            second,
            ActiveSshSessionStore.get(),
        )
    }

    @Test
    fun test_clear_isIdempotent() {
        // Clear on an empty store is a no-op.
        ActiveSshSessionStore.clear()
        assertNull(
            "clearing an empty store must still leave it empty",
            ActiveSshSessionStore.get(),
        )

        // Clear on a populated store empties it.
        val transport = FakeTransportForStore()
        ActiveSshSessionStore.set(SshSession(transport = transport, onClose = {}))
        ActiveSshSessionStore.clear()
        assertNull(
            "clear() must drop the live reference so the recreated " +
                "Activity does not re-attach to a torn-down session",
            ActiveSshSessionStore.get(),
        )

        // Clearing again is still safe.
        ActiveSshSessionStore.clear()
        assertNull("double-clear must be safe", ActiveSshSessionStore.get())
    }

    @Test
    fun recreatedUi_canExecuteThroughStoredSession() = runBlocking {
        val commands = object : RemoteCommandExecutor {
            override suspend fun execute(command: String): Result<RemoteCommandResult> =
                Result.success(
                    RemoteCommandResult(
                        stdout = command.toByteArray(),
                        stderr = byteArrayOf(),
                        exitStatus = 0,
                    ),
                )
        }
        val session = SshSession(
            transport = FakeTransportForStore(),
            remoteCommandExecutor = commands,
            onClose = {},
        )
        ActiveSshSessionStore.set(session)

        val recreatedReference = ActiveSshSessionStore.get()!!
        val result = recreatedReference.commandExecutor.execute("tmux-list").getOrThrow()

        assertEquals("tmux-list", result.stdout.toString(Charsets.UTF_8))
    }
}

/**
 * Test-only [SshTransport] for the store tests. We don't drive the
 * read loop here — we only need a non-null transport to satisfy
 * [SshSession]'s constructor. The store tests don't care what the
 * transport does.
 */
private class FakeTransportForStore : SshTransport {
    override fun write(bytes: ByteArray) = Unit
    override fun readBytes(): ByteArray? = null  // immediate EOF — never read in these tests
    override fun resizePty(cols: Int, rows: Int, widthPx: Int, heightPx: Int) = Unit
    override fun close() = Unit
}
