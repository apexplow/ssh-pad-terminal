package com.taosun.hanterm.terminal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for Module 19 tmux drawer "terminal emulator unavailable".
 *
 * HanTerm deliberately never attaches a Termux [com.termux.terminal.TerminalSession]
 * (its constructor forks a local shell via JNI) — see [TerminalView]'s emulator
 * init and [TerminalViewClientNullSessionTest]. The live emulator lives only in
 * the inner view's `mEmulator` field.
 *
 * Module 19's first `currentEmulator()` implementation went through
 * `getCurrentSession()?.emulator`, which is always null here, so
 * [TmuxSessionSource.refresh] permanently failed with
 * `IllegalStateException("terminal emulator unavailable")`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TerminalViewCurrentEmulatorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun currentEmulator_returnsMEmulator_withoutTerminalSession() {
        val view = TerminalView(context)

        // Preconditions that pin the design: no Termux session, but mEmulator
        // is wired in the constructor.
        assertNull(view.termuxView.currentSession)
        assertNotNull(view.termuxView.mEmulator)

        val published = view.currentEmulator()
        assertNotNull(
            "currentEmulator() must return the constructor-wired mEmulator; " +
                "getCurrentSession()-based lookup always returns null and breaks " +
                "the tmux drawer",
            published,
        )
        assertSame(view.termuxView.mEmulator, published)
    }

    @Test
    fun refresh_viaLiveTerminalViewRef_readsCurrentEmulator() = kotlinx.coroutines.runBlocking {
        // Production wiring: AtomicReference<TerminalView?> + currentEmulator()
        // at refresh time — not a Compose-cached emulator that an IO-loop
        // finally can null out when the drawer opens.
        val view = TerminalView(context)
        val viewRef = java.util.concurrent.atomic.AtomicReference(view)
        val endpoint = object : TerminalEndpoint {
            override fun write(bytes: ByteArray) = Unit
        }
        val screen = """
            ${TmuxSessionParser.BEGIN_SENTINEL}
            main|1|detached|
            ${TmuxSessionParser.END_SENTINEL}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        view.currentEmulator()!!.append(screen, screen.size)

        val source = TmuxSessionSource(
            endpoint = endpoint,
            emulatorProvider = { viewRef.get()?.currentEmulator() },
            pollDelay = { },
        )
        val result = source.refresh()
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        assertEquals("main", result.getOrThrow().single().name)
    }
}
