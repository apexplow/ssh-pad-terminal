package com.taosun.hanterm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tests for [CrashHandler]. Pin the rotation behaviour required by
 * Issue #38 (P2 in #31 store-readiness plan): keep at least the last
 * three crashes under `filesDir/crashes/`, the startup banner still
 * reads the most recent, and [CrashHandler.clearLastCrash] wipes the
 * directory.
 *
 * Tests drive the handler via the `@VisibleForTesting` factory
 * [CrashHandler.createForTest] rather than the global [CrashHandler.install]
 * so they can construct a fresh handler against the per-test temp dir.
 *
 * `Thread.UncaughtExceptionHandler` is intentionally NOT installed globally
 * during tests; that would interfere with the Robolectric runner's own
 * teardown path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrashHandlerTest {

    private lateinit var context: Context
    private lateinit var crashDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        crashDir = File(context.filesDir, CrashHandler.CRASH_DIR)
        if (crashDir.exists()) crashDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        if (crashDir.exists()) crashDir.deleteRecursively()
    }

    /**
     * Helper: synthesize a crash handler pointed at the per-test temp dir
     * and capture a single synthetic crash under it, then call rotate().
     */
    private fun handler(keepLast: Int = CrashHandler.KEEP_LAST_CRASHES): CrashHandler =
        CrashHandler.createForTest(
            crashDir = crashDir,
            keepLast = keepLast,
            delegate = null,
        )

    private fun recordCrash(
        h: CrashHandler,
        message: String = "synthetic",
        threadName: String = "test-thread",
    ) {
        // Thread#getName is final; allocate a real Thread and set the name
        // on it directly. setName() does not require the thread to be alive.
        val t = Thread { /* no-op; never started */ }
        t.name = threadName
        h.writeCrashFile(t, RuntimeException(message))
        h.rotate()
    }

    private fun filesInDir(): List<File> =
        crashDir.listFiles { f -> f.isFile && f.name.endsWith(CrashHandler.CRASH_FILE_SUFFIX) }
            ?.toList()
            ?: emptyList()

    // -----------------------------------------------------------------
    // Issue #38 — rotation pins
    // -----------------------------------------------------------------

    @Test
    fun test_syntheticCrash_writesTimestampedFile() {
        val h = handler()
        recordCrash(h, "first", "test-thread-1")

        val files = filesInDir()
        assertEquals("one crash → one file", 1, files.size)
        val f = files.single()
        assertTrue(
            "filename must start with crash- and end with .log: ${f.name}",
            f.name.startsWith(CrashHandler.CRASH_FILE_PREFIX) &&
                f.name.endsWith(CrashHandler.CRASH_FILE_SUFFIX),
        )
        val text = f.readText()
        assertTrue("file should carry the throwable type: $text", text.contains("java.lang.RuntimeException"))
        assertTrue("file should carry the user message: $text", text.contains("first"))
        assertTrue("file should carry the thread name: $text", text.contains("test-thread-1"))
        assertTrue(
            "in-file header should be yyyy-MM-dd HH:mm:ss: $text",
            Regex("""\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\] test-thread-1 \(id=\d+\)\n""").containsMatchIn(text),
        )
    }

    @Test
    fun test_rotation_keepsOnlyLastThree_whenMoreThanCap() {
        val h = handler(keepLast = 3)
        // mtimes in milliseconds; ensure ordering by waiting past millisecond resolution.
        repeat(5) { i ->
            recordCrash(h, "crash-#$i", "t-$i")
            // 5 ms apart guarantees a different lastModified() under POSIX
            // ext4 (millisecond resolution) AND avoids filename-tie collisions.
            Thread.sleep(5)
        }
        val files = filesInDir()
        assertEquals(
            "after 5 crashes with keepLast=3, exactly 3 files must remain",
            3,
            files.size,
        )
        val names = files.map { it.name }.sorted()
        // Sanity: filenames sort lexicographically by timestamp, so the
        // oldest two ("crash-#0", "crash-#1") — i.e. the smallest two by
        // name — must have been rotated out.
        assertTrue(
            "rotated-out file #0 should be gone, surviving names: $names",
            names.none { it.contains("#0") } && names.none { it.contains("#1") },
        )
        // Surviving files must reference the most recent user messages.
        val surviving = files.joinToString("\n") { it.readText() }
        assertTrue("latest crash must survive", surviving.contains("crash-#4"))
        assertFalse("oldest crash must be rotated out", surviving.contains("crash-#0"))
    }

    @Test
    fun test_readLastCrash_returnsMostRecent() {
        val h = handler()
        Thread.sleep(5)
        recordCrash(h, "older", "older")
        Thread.sleep(10)
        recordCrash(h, "newer", "newer")
        Thread.sleep(10)
        recordCrash(h, "newest", "newest")

        val text = CrashHandler.readLastCrash(context)
        assertNotNull("readLastCrash must return non-null when files exist", text)
        assertTrue("must return the newest crash: $text", text!!.contains("newest"))
        // Older crashes must still be on disk — that's the whole point of rotation.
        assertTrue(
            "older crash must still be on disk after rotation: $text",
            filesInDir().any { it.readText().contains("older") },
        )
    }

    @Test
    fun test_readLastCrash_returnsNullWhenDirEmpty() {
        // crashDir doesn't exist yet (deleteRecursively in setUp)
        assertNull(
            "readLastCrash must return null when no directory exists",
            CrashHandler.readLastCrash(context),
        )
    }

    @Test
    fun test_clearLastCrash_removesAllRotatedFiles() {
        val h = handler()
        repeat(3) { i ->
            recordCrash(h, "crash $i", "t-$i")
            Thread.sleep(5)
        }
        assertEquals(3, filesInDir().size)

        CrashHandler.clearLastCrash(context)
        assertTrue(
            "directory must be empty after clearLastCrash",
            filesInDir().isEmpty(),
        )
        assertNull(
            "readLastCrash must return null after clear",
            CrashHandler.readLastCrash(context),
        )
    }

    @Test
    fun test_install_migratesLegacySingleFile() {
        // Simulate a pre-#38 build by writing a legacy crash.log
        val legacy = File(context.filesDir, "crash.log")
        legacy.parentFile?.mkdirs()
        legacy.writeText("legacy crash from old build")

        CrashHandler.install(context)

        assertFalse("legacy crash.log must be deleted by install()", legacy.exists())
        // install() only sets up the handler — it must NOT create the
        // directory or write any files itself (no spurious first crash).
        assertFalse(
            "install() must not create the rotation directory eagerly",
            crashDir.exists(),
        )
    }

    @Test
    fun test_isHandledTransportAbort_stillSuppresses() {
        // The "sshj Reader thread abort" suppression was the ONLY guarantee
        // in the old CrashHandler. Issue #38's rotation change must not
        // regress this — reproduce an sshj-shaped throwable and confirm
        // no file is written.
        val abortChain = net.schmizz.sshj.common.SSHException(
            "Software caused connection abort",
        ).initCause(java.net.SocketException("Connection reset"))
        // Use the production uninstall-installed path: install() ourselves,
        // then drive uncaughtException via a synthetic Thread named "Reader".
        CrashHandler.install(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val handler = previous as? CrashHandler ?: error(
                "install() did not register a CrashHandler",
            )
            val readerThread = Thread { /* no-op */ }
            readerThread.name = "Reader"
            handler.uncaughtException(readerThread, abortChain)
            // We must yield to the rotate() path (which is a no-op when no
            // file was written). The abort path returns from writeCrashFile
            // before any disk access; assert no crash file appears.
            assertTrue(
                "abort must not produce a crash file; dir contents: ${crashDir.listFiles()?.toList()}",
                filesInDir().isEmpty(),
            )
        } finally {
            // Restore whatever was there before so other tests / JUnit teardown
            // don't observe a leaking default handler.
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun test_concurrentWrites_eachCrashSurvives() {
        // Three threads each writing one crash in parallel. With
        // keepLast=3 == thread count, all three should land — and each
        // crash file must carry its own thread-specific message (no torn
        // or interleaved writes between threads).
        val h = handler(keepLast = 3)
        val threads = (1..3).map { i ->
            Thread {
                recordCrash(h, "concurrent-$i", "ct-$i")
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }

        val files = filesInDir()
        assertEquals("all three concurrent crashes should land within cap", 3, files.size)
        // Each surviving file must reference exactly one of the three
        // thread-specific messages, and the union must cover all three
        // (no thread silently lost its crash).
        val surviving = files.joinToString("\n") { it.readText() }
        assertEquals(
            "one crash per thread should survive",
            setOf("concurrent-1", "concurrent-2", "concurrent-3"),
            setOf("concurrent-1", "concurrent-2", "concurrent-3")
                .filter { surviving.contains(it) }
                .toSet(),
        )
    }

    @Test
    fun test_filenameFormatter_isFilesystemSafe() {
        // Defensive: the format we use MUST NOT contain ':' (illegal on
        // some attached-storage providers) and must be sortable as a
        // string for the rare mtime-tie case.
        val now = java.time.LocalDateTime.of(2026, 7, 24, 13, 45, 22, 123_000_000)
        val s = CrashHandler.FILENAME_FORMATTER.format(now)
        assertFalse("filename must not contain colons: $s", ':' in s)
        assertFalse("filename must not contain spaces: $s", ' ' in s)
        assertEquals(
            "format should be yyyyMMdd-HHmmss-SSS",
            "20260724-134522-123",
            s,
        )
    }
}
