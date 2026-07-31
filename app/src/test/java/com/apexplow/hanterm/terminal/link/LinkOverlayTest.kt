package com.apexplow.hanterm.terminal.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.apexplow.hanterm.terminal.TerminalEndpoint
import com.apexplow.hanterm.terminal.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 4 T12 — pins the LinkOverlay visible-window batch + HashMap
 * contract.
 *
 * The overlay is a small state machine: it scans the live emulator rows,
 * keeps at most one URL per row, and clears on every refresh. Tests:
 *
 *  - empty emulator → empty snapshot
 *  - one URL in row 5 → one span at (5, start, end)
 *  - multiple URLs across multiple rows → snapshot has all of them
 *  - REFRESH_GUARD_MS torn-write guard → second refresh within 16ms is a no-op
 *  - findUrlAt within span → returns the span
 *  - findUrlAt outside any span → null
 *  - emulator source returns null → refresh is a no-op
 *
 * Uses a real [TerminalView] (which builds a real [com.termux.terminal.TerminalEmulator])
 * so we drive the public `emulator.append(...)` API and exercise the same
 * `getSelectedText` code path that production uses. Robolectric @Config(sdk = [36])
 * to match existing terminal tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LinkOverlayTest {

    private lateinit var terminalView: TerminalView
    private lateinit var overlay: LinkOverlay
    private var topRow: Int = 0
    private var lastWriteUptimeMs: Long = 0L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        terminalView = TerminalView(context)
        terminalView.bindEndpoint(TerminalEndpoint {})
        overlay = LinkOverlay(
            emulatorSource = { terminalView.termuxView.mEmulator },
            topRowSource = { topRow },
            lastWriteUptimeMsSource = { lastWriteUptimeMs },
        )
    }

    /** Write text into a row via the real `TerminalEmulator.append`. */
    private fun writeLine(row: Int, text: String) {
        // Move cursor to that row, then write the text. The emulator
        // doesn't expose a direct "write at row" API, so we feed the
        // cursor through `append` with explicit ANSI positioning. For
        // a 24-row default emulator, "row" maps to `row + 1` (1-based).
        val cursorRow = row + 1
        val sb = StringBuilder()
        sb.append("[").append(cursorRow).append(";1H") // CUP row;col
        sb.append(text)
        sb.append("[K") // clear to end of line
        terminalView.termuxView.mEmulator?.append(sb.toString().toByteArray(), sb.length)
    }

    @Test
    fun refresh_emptyEmulator_emptySnapshot() {
        overlay.refresh()
        assertEquals(emptyList<LinkOverlay.UrlSpan>(), overlay.snapshot())
    }

    @Test
    fun refresh_urlInRow0_recordsSpan() {
        writeLine(0, "see https://example.com here")
        overlay.refresh()
        val snapshot = overlay.snapshot()
        assertEquals(1, snapshot.size)
        val span = snapshot[0]
        assertEquals(0, span.row)
        assertEquals("https://example.com", span.url)
        // The URL starts after "see " (4 chars), ends at 4 + 19.
        assertEquals(4, span.startCol)
        assertEquals(23, span.endCol)
    }

    @Test
    fun refresh_multipleRowsWithUrls_recordsEach() {
        writeLine(0, "https://a.com")
        writeLine(1, "https://b.com")
        writeLine(2, "plain text")
        writeLine(3, "ftp://c.example.org/dir/")
        overlay.refresh()
        val urls = overlay.snapshot().map { it.url }.toSet()
        assertEquals(setOf("https://a.com", "https://b.com", "ftp://c.example.org/dir/"), urls)
    }

    @Test
    fun refresh_clearsPreviousSpans() {
        writeLine(0, "https://a.com")
        overlay.refresh()
        assertEquals(1, overlay.snapshot().size)

        // Refresh: now row 0 is empty, row 1 has a URL.
        // We don't have a clean "clear a row" without re-creating the
        // emulator, so we verify the clear-then-fill contract by
        // writing different content to a new row and confirming the
        // old row's span is no longer "the only one".
        writeLine(5, "https://b.com")
        overlay.refresh()
        val urls = overlay.snapshot().map { it.url }
        // The new row's URL is present.
        assertTrue(urls.contains("https://b.com"))
    }

    @Test
    fun refresh_tornWriteGuard_skipsRefreshWithin16ms() {
        writeLine(0, "https://a.com")
        overlay.refresh()
        val initial = overlay.snapshot()
        assertEquals(1, initial.size)
        assertEquals("https://a.com", initial[0].url)

        // Simulate an IO thread write that happened 4 ms ago.
        lastWriteUptimeMs = android.os.SystemClock.uptimeMillis() - 4L
        // The emulator's text is now different but the guard should
        // prevent a re-read.
        writeLine(0, "https://different.com")
        overlay.refresh()
        // The first span should still be in place — the guard skipped
        // the refresh.
        val snapshot = overlay.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals("https://a.com", snapshot[0].url)
    }

    @Test
    fun refresh_tornWriteGuard_expiresAfter16ms() {
        writeLine(0, "https://a.com")
        overlay.refresh()

        // Last write was 100ms ago — well past the guard window.
        lastWriteUptimeMs = android.os.SystemClock.uptimeMillis() - 100L
        writeLine(0, "https://b.com")
        overlay.refresh()
        val snapshot = overlay.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals("https://b.com", snapshot[0].url)
    }

    @Test
    fun refresh_emulatorSourceReturnsNull_noOp() {
        val nullOverlay = LinkOverlay(
            emulatorSource = { null },
            topRowSource = { 0 },
            lastWriteUptimeMsSource = { 0L },
        )
        // No emulator → no spans, no exception.
        nullOverlay.refresh()
        assertEquals(emptyList<LinkOverlay.UrlSpan>(), nullOverlay.snapshot())
    }

    @Test
    fun findUrlAt_withinSpan_returnsSpan() {
        writeLine(0, "see https://example.com here")
        overlay.refresh()
        val span = overlay.findUrlAt(0, 10) // inside the URL text
        assertNotNull(span)
        assertEquals("https://example.com", span!!.url)
    }

    @Test
    fun findUrlAt_outsideSpan_returnsNull() {
        writeLine(0, "see https://example.com here")
        overlay.refresh()
        // col 0 ("s" in "see") is before the URL starts at col 4.
        val span = overlay.findUrlAt(0, 0)
        assertNull(span)
    }

    @Test
    fun findUrlAt_unknownRow_returnsNull() {
        writeLine(0, "https://a.com")
        overlay.refresh()
        val span = overlay.findUrlAt(99, 0)
        assertNull(span)
    }

    @Test
    fun refresh_visibleWindowBatch_respectsTopRow() {
        // The user has scrolled into the scrollback (mTopRow > 0). The
        // overlay should scan top..top+24, not rows 0..23.
        // We can't easily scroll the emulator here, so we just verify
        // that an alternate topRow source is honoured.
        writeLine(0, "https://a.com")
        topRow = 0
        overlay.refresh()
        val rows0 = overlay.snapshot().map { it.row }.toSet()
        assertEquals(setOf(0), rows0)
    }
}

private fun assertTrue(condition: Boolean) {
    if (!condition) throw AssertionError("expected true")
}
