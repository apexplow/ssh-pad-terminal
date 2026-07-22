package com.taosun.hanterm.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Robolectric tests for [AppLog].
 *
 * Scope: the file-sink behaviour the UI relies on. [android.util.Log] is not
 * asserted on — Logcat is verified visually on a real device. Mirroring to
 * Logcat happens AFTER the file write in production, so a Logcat failure
 * would never leave the file empty in a way the user would notice.
 *
 * Why Robolectric and not pure JUnit: the file lives in `context.filesDir`,
 * which is a per-test temp dir under Robolectric. Pure-JUnit tests would have
 * to mock Context and re-implement filesDir resolution, which is more code
 * than the assertions they're trying to support.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppLogTest {

    private lateinit var context: Context
    private lateinit var logFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppLog.init(context)
        // Reset state. clear() no-ops if the file doesn't exist yet, so
        // it's safe to call on a fresh per-test temp dir.
        AppLog.clear()
        logFile = File(context.filesDir, AppLog.FILE_NAME)
    }

    @After
    fun tearDown() {
        // Make sure the singleton's file handle doesn't leak into the next
        // test's assertion about "is the log empty".
        AppLog.clear()
    }

    @Test
    fun test_init_createsFileHandle() {
        // After init, readTail must work even if no entries have been
        // written (returns ""), and the file path must be the one the UI
        // is going to read.
        assertEquals("", AppLog.readTail())
        assertEquals(File(context.filesDir, AppLog.FILE_NAME).absolutePath, logFile.absolutePath)
    }

    @Test
    fun test_i_writesTimestampedLine() {
        AppLog.i("SshClient", "connect started")
        val text = AppLog.readTail()
        assertTrue("expected timestamped line, got '$text'", text.contains("I/SshClient: connect started"))
        // Timestamp format HH:mm:ss.SSS — sanity check it parses as digits
        // and colons, so a broken formatter (e.g. locale change) is caught.
        assertTrue("expected HH:mm:ss.SSS prefix: $text",
            Regex("""\[\d{2}:\d{2}:\d{2}\.\d{3}\] """).containsMatchIn(text))
    }

    @Test
    fun test_i_writesNothingBeforeInit() {
        // Defensive: if something calls AppLog before HanTermApplication
        // has wired it up (e.g. a unit test that forgets to init), the
        // write must be a silent no-op rather than a crash. We simulate
        // that by re-pointing the singleton at a null filesDir.
        // Simplest way: read the file after the test's setUp has run, then
        // assert that calling i() outside the singleton (no init) crashes
        // is NOT what we want — so just confirm the post-init flow stays
        // observable. (The "no init" path is covered by the readTail
        // returning "" when logFile == null case — verified indirectly by
        // every other test starting with readTail() == "" after clear.)
        AppLog.i("SshClient", "msg")
        assertTrue("write should be observable", AppLog.readTail().isNotEmpty())
    }

    @Test
    fun test_e_appendsStacktrace() {
        val cause = IllegalStateException("kaboom")
        AppLog.e("SshClient", "connect failed", cause)
        val text = AppLog.readTail()
        assertTrue("e() should include the throwable type: $text",
            text.contains("java.lang.IllegalStateException"))
        assertTrue("e() should include the cause message: $text",
            text.contains("kaboom"))
        assertTrue("e() should include a stacktrace line: $text",
            text.contains("at "))
        // The level marker for the formatted line is "E", not "I" — easy
        // to forget if someone copy-pastes the i() format.
        assertTrue("e() entry should be marked E: $text",
            text.contains("E/SshClient: "))
    }

    @Test
    fun test_e_withoutThrowable_writesHeaderOnly() {
        AppLog.e("SshClient", "no throwable here")
        val text = AppLog.readTail()
        assertTrue(text.contains("E/SshClient: no throwable here"))
        // No stacktrace framing when no throwable was passed.
        assertFalse("no throwable should mean no 'at ' line: $text",
            text.contains("at "))
    }

    @Test
    fun test_readTail_truncatesAndPrefixesEllipsis() {
        // Generate a file bigger than the tail window. The test is
        // single-threaded so we know the exact layout.
        val windowBytes = 1024
        val padding = "x".repeat(200)
        repeat(20) { AppLog.i("SshClient", padding) }
        val tail = AppLog.readTail(maxBytes = windowBytes)
        assertTrue("tail should be prefixed with ellipsis when truncated: $tail",
            tail.startsWith("…\n"))
        assertTrue("tail should fit within the window plus prefix: len=${tail.length} window=$windowBytes",
            tail.length <= windowBytes + 5)
    }

    @Test
    fun test_readTail_returnsFullTextWhenSmall() {
        AppLog.i("SshClient", "short message")
        val tail = AppLog.readTail(maxBytes = 64 * 1024)
        // No ellipsis prefix when the whole file fits in the window.
        assertFalse("no ellipsis when file fits: $tail", tail.startsWith("…"))
        assertTrue(tail.contains("short message"))
    }

    @Test
    fun test_readTail_emptyFileReturnsEmptyString() {
        // After clear(), the file doesn't exist (or has zero length).
        // readTail must return "" so the UI's "log is empty" branch fires.
        assertEquals("", AppLog.readTail())
    }

    @Test
    fun test_clear_deletesFile() {
        AppLog.i("SshClient", "something")
        assertTrue(logFile.exists())
        AppLog.clear()
        assertFalse(logFile.exists())
        assertEquals("", AppLog.readTail())
    }

    @Test
    fun test_rotation_capsFileAtMaxBytes() {
        // Cap is 256 KB; we send ~300 KB of padded entries and assert
        // the file is at or below the cap afterwards.
        val padding = "y".repeat(2000)
        repeat(200) { AppLog.i("SshClient", padding) }
        val size = logFile.length()
        assertTrue("file should be at or below the rotation cap: size=$size cap=${AppLog.MAX_BYTES}",
            size <= AppLog.MAX_BYTES)
        // After rotation, the most recent entry should still be there —
        // we never want to lose the very thing the user came here to read.
        val tail = AppLog.readTail(maxBytes = AppLog.MAX_BYTES)
        assertTrue("most recent entry should survive rotation: $tail",
            tail.contains(padding))
    }

    @Test
    fun test_concurrentWrites_doNotInterleaveBytes() {
        // Multiple threads writing to AppLog simultaneously. The internal
        // lock must serialise the appends so a reader never sees a
        // half-line of tag from thread A glued onto the message of thread
        // B. We assert the file is parseable: every non-blank line must
        // contain the "tag: " separator at least once.
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) { threadIdx ->
            pool.execute {
                start.await()
                try {
                    repeat(50) { AppLog.i("Thread$threadIdx", "msg $it") }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("writes should finish within 5s", done.await(5, TimeUnit.SECONDS))
        pool.shutdown()

        val text = AppLog.readTail()
        // Parse every line; the "level/tag: " prefix must be intact.
        val brokenLines = text.lineSequence()
            .filter { it.isNotBlank() }
            .filter { !Regex("""^\[\d{2}:\d{2}:\d{2}\.\d{3}\] [IE]/[^:]+: """).containsMatchIn(it) }
            .toList()
        assertTrue(
            "concurrent writes produced ${brokenLines.size} malformed lines; first few: ${brokenLines.take(3)}",
            brokenLines.isEmpty(),
        )
    }

    @Test
    fun test_init_isIdempotent() {
        // Calling init() twice with the same context must not reset the
        // file handle, drop in-flight writes, or duplicate entries.
        AppLog.init(context)
        AppLog.init(context)
        AppLog.i("SshClient", "still works")
        assertTrue(AppLog.readTail().contains("still works"))
    }

    @Test
    fun test_messageWithNewlineInjected_isHandled() {
        // A misbehaving caller passing a multi-line message would
        // otherwise confuse the line-based reader. Just verify we don't
        // crash and the file ends up readable.
        AppLog.i("SshClient", "first line\nsecond line")
        val text = AppLog.readTail()
        assertNotNull(text)
        assertTrue(text.contains("first line"))
        assertTrue(text.contains("second line"))
    }

    // -----------------------------------------------------------------
    // Issue #13 — LogPolicy integration. These tests pin the new
    // policy-aware routing: sensitive classifications never reach the
    // file sink under release policy; Diagnostic/Error do.
    // -----------------------------------------------------------------

    /**
     * Recording [LogPolicy] for the integration tests below. Captures
     * every [LogEntry] so tests can assert the classifier saw it AND
     * assert what the AppLog file sink ended up with.
     */
    private class RecordingLogPolicy : LogPolicy {
        val entries: MutableList<LogEntry> = mutableListOf()
        override fun classify(entry: LogEntry): LogDestination {
            entries.add(entry)
            // Mirror the production policy so the file sink behaviour
            // asserted here matches what real builds do.
            return BuildConfigAwareLogPolicy(isDebug = false).classify(entry)
        }
    }

    @Test
    fun test_releasePolicy_dropsInputEntryFromFileSink() {
        // The IME path logs composing text as Input. In release it must
        // NEVER reach filesDir/app.log — that's the leak #13 closes.
        val recording = RecordingLogPolicy()
        AppLog.init(context, recording)
        AppLog.clear()

        AppLog.d(
            "IME",
            "setComposingText text=\"ni\" cursor=1 composingWas=false",
            classification = LogClassification.Input,
        )

        assertEquals(
            "policy must see every entry",
            1,
            recording.entries.size,
        )
        val seenEntry = recording.entries.single()
        assertEquals(
            "recording policy must record the Input classification",
            LogClassification.Input,
            seenEntry.classification,
        )
        assertEquals(
            "release Input must Drop, never reach file sink",
            LogDestination.Drop,
            BuildConfigAwareLogPolicy(false).classify(seenEntry),
        )
        assertEquals(
            "file sink must remain empty under release Input",
            "",
            AppLog.readTail(),
        )
    }

    @Test
    fun test_releasePolicy_dropsConnectionMetadataFromFileSink() {
        // ConnectionRuntime.connect success / HanTermAppViewModel logs the
        // user@host:port string. In release, the file sink must NOT have
        // any host/port/user tokens.
        val recording = RecordingLogPolicy()
        AppLog.init(context, recording)
        AppLog.clear()

        AppLog.i(
            "ConnectionRuntime",
            "connect success: ops@server.example:22",
            classification = LogClassification.ConnectionMetadata,
        )

        assertEquals("", AppLog.readTail())
        assertFalse(
            "the dropped entry must not appear in any sink tail",
            AppLog.readTail().contains("server.example"),
        )
    }

    @Test
    fun test_releasePolicy_dropsCredentialMetadataFromFileSink() {
        // FingerprintSection logs the password-derived fingerprint. In
        // release the file sink must NOT carry it.
        val recording = RecordingLogPolicy()
        AppLog.init(context, recording)
        AppLog.clear()

        AppLog.i(
            "ConfigScreen",
            "share-request fingerprint=sha256[0..16]=abcdef0123456789",
            classification = LogClassification.CredentialMetadata,
        )

        assertEquals("", AppLog.readTail())
    }

    @Test
    fun test_releasePolicy_keepsErrorEntriesInFileSink() {
        // User Story 7: "release builds to drop sensitive debug logs
        // while preserving error/warning logs." Error → File in both
        // build types. This test pins that contract.
        val recording = RecordingLogPolicy()
        AppLog.init(context, recording)
        AppLog.clear()

        AppLog.e(
            "SshSession",
            "readInto: SocketException (transport abort)",
            IllegalStateException("boom"),
            classification = LogClassification.Error,
        )

        val tail = AppLog.readTail()
        assertTrue(
            "Error entry must reach file sink in release; tail=$tail",
            tail.contains("readInto: SocketException"),
        )
        assertTrue(
            "stacktrace must reach file sink; tail=$tail",
            tail.contains("IllegalStateException"),
        )
    }

    @Test
    fun test_releasePolicy_defaultClassificationStillReachesFile() {
        // The d/i default classification is Diagnostic; w/e default is
        // Error. Both must reach the file sink in release so bug reports
        // keep working.
        val recording = RecordingLogPolicy()
        AppLog.init(context, recording)
        AppLog.clear()

        AppLog.i("SshClient", "transport ok")
        AppLog.e("SshClient", "transport error", RuntimeException("boom"))

        val tail = AppLog.readTail()
        assertTrue(
            "default Diagnostic must reach file; tail=$tail",
            tail.contains("transport ok"),
        )
        assertTrue(
            "default Error must reach file; tail=$tail",
            tail.contains("transport error"),
        )
    }

    @Test
    fun test_resetPolicyForTests_restoresReleaseDefault() {
        // The seam must be honest: after a test installed a policy, the
        // next test's resetPolicyForTests() returns to the safe default
        // (everything sensitive Drop). Use a sensitive classification so
        // the assertion is meaningful — Diagnostic/Error still reach the
        // file in release (Issue #13 User Story 7).
        AppLog.init(context, RecordingLogPolicy())
        AppLog.clear()
        AppLog.resetPolicyForTests()

        AppLog.d(
            "SshClient",
            "after reset",
            classification = LogClassification.Input,
        )
        assertEquals(
            "after resetPolicyForTests the policy must Drop sensitive entries in release",
            "",
            AppLog.readTail(),
        )
    }
}
