package com.taosun.hanterm.ssh.security

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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

/**
 * Robolectric tests for [KnownHostsStore] — pins the file-format contract,
 * atomic-write guarantee, and the malformed-row defense (KHS-ST-01..06).
 *
 * Each test starts from a clean `filesDir/known_hosts` so rows from one
 * case don't bleed into the next. Robolectric gives us a real
 * `applicationContext.filesDir`, which is what the production code uses —
 * no shadow indirection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KnownHostsStoreTest {

    private lateinit var store: KnownHostsStore
    private val knownHostsFile: File
        get() = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, KnownHostsStore.FILE_NAME)

    @Before
    fun setUp() {
        // Belt-and-braces: a previous test run that crashed mid-write could
        // leave a stale file on disk. Wipe before each test.
        knownHostsFile.delete()
        store = KnownHostsStore(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        knownHostsFile.delete()
    }

    /** Convenience for "current-format" v1 test rows. */
    private fun v1(keyType: String, fp: String) = HostFingerprint(keyType, fp, algorithmVersion = 1)

    // ---- KHS-ST-01: get returns null when store is empty ----

    @Test
    fun khs_st_01_getReturnsNullWhenStoreNeverWritten() {
        runBlocking {
            assertNull(store.get("first-time-host.example", 22))
        }
    }

    // ---- KHS-ST-02: get returns the stored fingerprint ----

    @Test
    fun khs_st_02_getReturnsStoredFingerprint() {
        runBlocking {
            val fp = v1("ssh-ed25519", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            store.put("known.example", 22, fp)
            val fetched = store.get("known.example", 22)
            assertNotNull(fetched)
            assertEquals("ssh-ed25519", fetched!!.keyType)
            assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", fetched.fingerprintBase64)
            assertEquals(
                "current-format rows round-trip with algorithmVersion = 1",
                1,
                fetched.algorithmVersion,
            )
        }
    }

    @Test
    fun khs_st_02b_getDistinguishesByPort() {
        runBlocking {
            val fp22 = v1("ssh-ed25519", "AAAA")
            val fp2222 = v1("ssh-rsa", "BBBB")
            store.put("multi.example", 22, fp22)
            store.put("multi.example", 2222, fp2222)
            assertEquals(fp22, store.get("multi.example", 22))
            assertEquals(fp2222, store.get("multi.example", 2222))
        }
    }

    // ---- KHS-ST-03: put atomically overwrites an existing row ----

    @Test
    fun khs_st_03_putOverwritesExistingRow() {
        runBlocking {
            val first = v1("ssh-ed25519", "AAAA-old")
            val second = v1("ssh-ed25519", "BBBB-new")
            store.put("rewrite.example", 22, first)
            store.put("rewrite.example", 22, second)
            assertEquals(second, store.get("rewrite.example", 22))
            // Exactly one row on disk for this (host, port).
            val rows = knownHostsFile.readLines().filter { it.contains("rewrite.example") }
            assertEquals("exactly one row per (host, port)", 1, rows.size)
        }
    }

    // ---- KHS-ST-04: writes fsync before returning ----

    @Test
    fun khs_st_04_writePersistsBeforePutReturns() {
        runBlocking {
            // After put returns, the bytes must be on disk AND the file handle
            // closed (so a process kill doesn't see a half-written temp file
            // rename). AtomicFile.finishWrite does both.
            store.put("fsync.example", 22, v1("ssh-ed25519", "CCCC"))
            assertTrue(
                "filesDir/known_hosts must exist after put",
                knownHostsFile.exists(),
            )
            val contents = knownHostsFile.readText()
            assertTrue(
                "fsync.example row must be on disk before put returns: $contents",
                contents.contains("fsync.example"),
            )
        }
    }

    // ---- KHS-ST-05: store path is filesDir/known_hosts, not in allowBackup ----

    @Test
    fun khs_st_05_storeLivesInFilesDirAsKnownHosts() {
        runBlocking {
            store.put("path.example", 22, v1("ssh-ed25519", "DDDD"))
            val expectedPath = File(
                ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
                "known_hosts",
            )
            assertTrue("store must write to filesDir/known_hosts", expectedPath.exists())
            assertEquals("known_hosts", KnownHostsStore.FILE_NAME)
        }
    }

    // ---- KHS-ST-06: malformed row → get returns null, no throw ----

    @Test
    fun khs_st_06_getReturnsNullForMalformedRow() {
        // Hand-craft a known_hosts file with three malformed rows. None
        // should be parseable, so a `get` for any of them returns null
        // without throwing.
        knownHostsFile.writeText(
            """
            # header line, must be ignored
            truncated
            only-three-fields	22	ssh-ed25519
            four	22	ssh-ed25519	!!!not-base64!!!
            empty-host	 	22	ssh-ed25519	AAAA
            """.trimIndent(),
        )

        runBlocking {
            assertNull("truncated row should not parse", store.get("truncated", 22))
            assertNull("only-three-fields row should not parse", store.get("only-three-fields", 22))
            assertNull("bad-base64 row should not parse", store.get("four", 22))
            assertNull("empty-host row should not parse", store.get("", 22))
            // A well-formed row inserted between malformed ones must still be readable.
            assertNull("get for an unrelated host returns null", store.get("never-written.example", 22))
        }
    }

    @Test
    fun khs_st_06b_malformedRowDoesNotBlockWellFormedRow() {
        // Sanity: the malformed-row tolerance applies per-row, not per-file.
        knownHostsFile.writeText(
            """
            # malformed row in the middle
            garbage line here
            good.example	22	ssh-ed25519	EEEEEEEE
            """.trimIndent(),
        )
        runBlocking {
            val fetched = store.get("good.example", 22)
            assertNotNull("well-formed row after a malformed row must still parse", fetched)
            assertEquals("EEEEEEEE", fetched!!.fingerprintBase64)
            // Pre-#16 4-column rows are recognized as legacy v0; see khs_st_07.
            assertEquals(
                "4-column row stamps algorithmVersion = 0 on read",
                0,
                fetched.algorithmVersion,
            )
        }
    }

    // ---- KHS-ST-07..09: algorithmVersion / v0 legacy / v1 round-trip (#16) ----

    @Test
    fun khs_st_07_legacy4ColumnRowParsesAsVersionZero() {
        // Pre-#16 file: 4 columns, no algorithmVersion. The store must
        // accept it (backwards compat) and stamp algorithmVersion = 0 on
        // read. The verifier is then responsible for v0→v1 migration.
        knownHostsFile.writeText(
            "legacy.example\t22\tssh-ed25519\tOLDLEGACYFP\n",
        )
        runBlocking {
            val fetched = store.get("legacy.example", 22)
            assertNotNull("4-column legacy row must still parse", fetched)
            assertEquals("ssh-ed25519", fetched!!.keyType)
            assertEquals("OLDLEGACYFP", fetched.fingerprintBase64)
            assertEquals(
                "legacy 4-column rows stamp algorithmVersion = 0 on read",
                0,
                fetched.algorithmVersion,
            )
        }
    }

    @Test
    fun khs_st_08_v1RowRoundTripsWith5Columns() {
        // Current format: 5 columns, trailing `1`. put → on-disk → get must
        // round-trip including the algorithmVersion stamp.
        runBlocking {
            val fp = HostFingerprint(
                keyType = "ssh-ed25519",
                fingerprintBase64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                algorithmVersion = 1,
            )
            store.put("v1.example", 22, fp)

            // Raw on-disk check: exactly the 5 fields, tab-separated, ending in `1`.
            val onDisk = knownHostsFile.readLines()
                .single { it.contains("v1.example") }
            val parts = onDisk.split('\t')
            assertEquals("v1 rows write exactly 5 columns", 5, parts.size)
            assertEquals("v1.example", parts[0])
            assertEquals("22", parts[1])
            assertEquals("ssh-ed25519", parts[2])
            assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", parts[3])
            assertEquals("1", parts[4])

            // Round-trip through get.
            val fetched = store.get("v1.example", 22)
            assertEquals(fp, fetched)
        }
    }

    @Test
    fun khs_st_09_mixedLegacyAndV1RowsBothParse() {
        // A single file containing BOTH a 4-column legacy row and a 5-column
        // v1 row. Both must parse, with the right version stamps each.
        knownHostsFile.writeText(
            """
            # mixed legacy + v1 file
            legacy.example	22	ssh-ed25519	OLDLEGACYFP
            v1.example	22	ssh-rsa	NEWV1FP	1
            """.trimIndent(),
        )
        runBlocking {
            val legacy = store.get("legacy.example", 22)
            assertNotNull(legacy)
            assertEquals(0, legacy!!.algorithmVersion)
            assertEquals("OLDLEGACYFP", legacy.fingerprintBase64)

            val v1 = store.get("v1.example", 22)
            assertNotNull(v1)
            assertEquals(1, v1!!.algorithmVersion)
            assertEquals("NEWV1FP", v1.fingerprintBase64)
            assertEquals("ssh-rsa", v1.keyType)
        }
    }

    // ---- SC-FH-02 + SC-FH-03: delete removes the row + is idempotent ----

    @Test
    fun khs_deleteRemovesRowAndFsyncs() {
        runBlocking {
            store.put("delete.example", 22, v1("ssh-ed25519", "FFFF"))
            store.delete("delete.example", 22)
            assertNull(store.get("delete.example", 22))
            // Other rows are untouched.
            store.put("keep.example", 22, v1("ssh-ed25519", "GGGG"))
            store.delete("delete.example", 22) // double-delete: idempotent
            assertEquals(
                v1("ssh-ed25519", "GGGG"),
                store.get("keep.example", 22),
            )
            assertFalse(
                "deleted row must not appear on disk",
                knownHostsFile.readText().contains("delete.example"),
            )
        }
    }

    // ---- probe() (SC-KHV-02 support): no-throw on a healthy store ----

    @Test
    fun probe_returnsNullOnHealthyStore() {
        runBlocking {
            assertNull(store.probe())
        }
    }

    @Test
    fun probe_returnsNullOnEmptyStore() {
        // The empty store is the production first-launch state — must not
        // be treated as a probe failure.
        runBlocking {
            assertNull(store.probe())
        }
    }
}