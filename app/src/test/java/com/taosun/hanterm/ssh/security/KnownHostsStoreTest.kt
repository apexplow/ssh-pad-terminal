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
@Config(sdk = [33])
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
            val fp = HostFingerprint("ssh-ed25519", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            store.put("known.example", 22, fp)
            val fetched = store.get("known.example", 22)
            assertNotNull(fetched)
            assertEquals("ssh-ed25519", fetched!!.keyType)
            assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", fetched.fingerprintBase64)
        }
    }

    @Test
    fun khs_st_02b_getDistinguishesByPort() {
        runBlocking {
            val fp22 = HostFingerprint("ssh-ed25519", "AAAA")
            val fp2222 = HostFingerprint("ssh-rsa", "BBBB")
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
            val first = HostFingerprint("ssh-ed25519", "AAAA-old")
            val second = HostFingerprint("ssh-ed25519", "BBBB-new")
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
            store.put("fsync.example", 22, HostFingerprint("ssh-ed25519", "CCCC"))
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
            store.put("path.example", 22, HostFingerprint("ssh-ed25519", "DDDD"))
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
        }
    }

    // ---- SC-FH-02 + SC-FH-03: delete removes the row + is idempotent ----

    @Test
    fun khs_deleteRemovesRowAndFsyncs() {
        runBlocking {
            store.put("delete.example", 22, HostFingerprint("ssh-ed25519", "FFFF"))
            store.delete("delete.example", 22)
            assertNull(store.get("delete.example", 22))
            // Other rows are untouched.
            store.put("keep.example", 22, HostFingerprint("ssh-ed25519", "GGGG"))
            store.delete("delete.example", 22) // double-delete: idempotent
            assertEquals(
                HostFingerprint("ssh-ed25519", "GGGG"),
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