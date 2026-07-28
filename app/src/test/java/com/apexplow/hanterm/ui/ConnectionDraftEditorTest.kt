package com.apexplow.hanterm.ui

import com.apexplow.hanterm.data.profile.ConnectionDraft
import com.apexplow.hanterm.data.profile.ConnectionProfile
import com.apexplow.hanterm.data.profile.DefaultConnectionProfile
import com.apexplow.hanterm.data.profile.HostEnrollmentPort
import com.apexplow.hanterm.data.profile.ProfileStorePort
import com.apexplow.hanterm.data.profile.SecretCipherPort
import com.apexplow.hanterm.data.profile.PrivateKeyVaultPort
import com.apexplow.hanterm.data.profile.StoredProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Primary seam test for [ConnectionDraftEditor] (Issue #18).
 *
 * Pure JUnit + [runTest]. No Robolectric, no Compose runtime. The editor's
 * only Android-touching call sites (logging + debug file write) go through
 * the [DebugLogSink] seam; tests inject [RecordingDebugLogSink].
 *
 * Fakes for [ConnectionProfile] are copied from `ConnectionProfileTest`'s
 * private helpers — same shape, same behavior. They live here too because
 * they're test-private (`private class`) and we don't yet have a shared
 * `testFixtures/` source set. If a third test file needs them, lift them
 * then.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionDraftEditorTest {

    private lateinit var store: InMemoryProfileStore
    private lateinit var cipher: RoundTripFakeCipher
    private lateinit var keys: InMemoryPrivateKeyVault
    private lateinit var hosts: RecordingHostEnrollment
    private lateinit var profile: ConnectionProfile
    private lateinit var debugLog: RecordingDebugLogSink

    @Before
    fun setUp() {
        store = InMemoryProfileStore()
        cipher = RoundTripFakeCipher()
        keys = InMemoryPrivateKeyVault()
        hosts = RecordingHostEnrollment()
        profile = DefaultConnectionProfile(store, cipher, keys, hosts)
        debugLog = RecordingDebugLogSink()
    }

    private fun newEditor(scope: TestScope): ConnectionDraftEditor =
        ConnectionDraftEditor(
            profile = profile,
            scope = scope,
            debugLog = debugLog,
            autoClearDelay = 50.milliseconds,
        )

    // ── init / load ──────────────────────────────────────────────────────

    @Test
    fun init_loadsFromProfile() = runTest {
        store.seed(StoredProfile(host = "h", username = "u", passwordBlob = byteArrayOf(1, 2, 3)))

        val editor = newEditor(this)

        assertEquals("h", editor.draft.value.host)
        assertEquals("u", editor.draft.value.username)
        assertEquals("", editor.draft.value.password) // load never fills plaintext
        assertTrue(editor.hasStoredPassword.value)
        assertEquals(DraftStatus.Idle, editor.status.value)
        assertNull(editor.lastSavedFingerprint.value)
    }

    @Test
    fun init_emptyStore_yieldsBlankDraft() = runTest {
        val editor = newEditor(this)

        assertEquals("", editor.draft.value.host)
        assertEquals("", editor.draft.value.username)
        assertEquals("", editor.draft.value.password)
        assertFalse(editor.hasStoredPassword.value)
        assertEquals(DraftStatus.Idle, editor.status.value)
    }

    // ── field-update intents ─────────────────────────────────────────────

    @Test
    fun updateHost_changesDraftAndLeavesStatusUntouched() = runTest {
        val editor = newEditor(this)

        editor.onIntent(DraftIntent.UpdateHost("h.example.com"))

        assertEquals("h.example.com", editor.draft.value.host)
        assertEquals(DraftStatus.Idle, editor.status.value)
    }

    @Test
    fun updatePort_changesDraft() = runTest {
        val editor = newEditor(this)
        editor.onIntent(DraftIntent.UpdatePort("2222"))
        assertEquals("2222", editor.draft.value.port)
    }

    @Test
    fun updateUsername_changesDraft() = runTest {
        val editor = newEditor(this)
        editor.onIntent(DraftIntent.UpdateUsername("ops"))
        assertEquals("ops", editor.draft.value.username)
    }

    @Test
    fun updatePassword_changesDraft() = runTest {
        val editor = newEditor(this)
        editor.onIntent(DraftIntent.UpdatePassword("typed-pw"))
        assertEquals("typed-pw", editor.draft.value.password)
    }

    @Test
    fun updatePrivateKeyName_changesDraft() = runTest {
        val editor = newEditor(this)
        editor.onIntent(DraftIntent.UpdatePrivateKeyName("id_ed25519.pem"))
        assertEquals("id_ed25519.pem", editor.draft.value.privateKeyName)
    }

    // ── Save ─────────────────────────────────────────────────────────────

    @Test
    fun save_replacesBlobAndEmitsSuccessAndFingerprint() = runTest {
        store.seed(StoredProfile(host = "old", username = "old", passwordBlob = byteArrayOf(1)))
        val editor = newEditor(this)
        editor.onIntent(DraftIntent.UpdateHost("new.example"))
        editor.onIntent(DraftIntent.UpdateUsername("ops"))
        editor.onIntent(DraftIntent.UpdatePassword("typed-pw"))

        editor.onIntent(DraftIntent.Save)

        // Draft is replaced with SaveOutcome.draftForUi — password cleared.
        assertEquals("new.example", editor.draft.value.host)
        assertEquals("ops", editor.draft.value.username)
        assertEquals("", editor.draft.value.password)
        // hasStoredPassword updated from SaveOutcome.
        assertTrue(editor.hasStoredPassword.value)
        // Fingerprint reflects the typed password (debug build, via RecordingDebugLogSink).
        assertEquals("TEST_FP:typed-pw", editor.lastSavedFingerprint.value)
        // Success status emitted.
        val status = editor.status.value
        assertTrue("status must be Success, got $status", status is DraftStatus.Success)
        assertEquals("Saved", (status as DraftStatus.Success).message)
        // Debug log called once with the post-save draft.
        assertEquals(1, debugLog.appended.size)
        val (msg, pkName) = debugLog.appended[0]
        assertTrue("message must include 'save host=': got '$msg'", msg.startsWith("save host=new.example"))
        assertEquals("", pkName)
    }

    @Test
    fun save_emptyPassword_keepsBlob() = runTest {
        val originalBlob = byteArrayOf(9, 8, 7)
        store.seed(StoredProfile(host = "h", username = "u", passwordBlob = originalBlob))
        val editor = newEditor(this)
        editor.onIntent(DraftIntent.UpdateHost("h2"))

        editor.onIntent(DraftIntent.Save)

        // Original blob untouched.
        assertEquals(originalBlob.toList(), store.read().passwordBlob?.toList())
        // Debug log fired without privateKeyName.
        assertEquals(1, debugLog.appended.size)
        assertEquals("", debugLog.appended[0].second)
        // Fingerprint is empty because typed password was empty.
        assertEquals("(empty)", editor.lastSavedFingerprint.value)
    }

    @Test
    fun save_clearsPlaintextFromDraft_evenWhenTypingLongPassword() = runTest {
        store.seed(StoredProfile(passwordBlob = byteArrayOf(0)))
        val editor = newEditor(this)
        editor.onIntent(DraftIntent.UpdatePassword("sup3r-secret-pw"))

        editor.onIntent(DraftIntent.Save)

        assertEquals("", editor.draft.value.password)
        // The fingerprint must still have come from the typed password (pre-save).
        assertEquals("TEST_FP:sup3r-secret-pw", editor.lastSavedFingerprint.value)
    }

    // ── Clear ────────────────────────────────────────────────────────────

    @Test
    fun clear_resetsFieldsAndClearsHasStoredPassword() = runTest {
        store.seed(StoredProfile(host = "h", username = "u", passwordBlob = byteArrayOf(1)))
        val editor = newEditor(this)
        // Verify pre-condition: editor sees seeded host + hasStoredPassword.
        assertEquals("h", editor.draft.value.host)
        assertTrue(editor.hasStoredPassword.value)

        editor.onIntent(DraftIntent.Clear)

        assertEquals("", editor.draft.value.host)
        assertEquals("", editor.draft.value.username)
        assertFalse(editor.hasStoredPassword.value)
        assertEquals(DraftStatus.Success("Cleared"), editor.status.value)
        // Clear does NOT log to debug log (matches current behavior).
        assertEquals(0, debugLog.appended.size)
    }

    // ── RemoveSavedPassword ──────────────────────────────────────────────

    @Test
    fun removeSavedPassword_wipesBlobAndClearsFlag() = runTest {
        store.seed(
            StoredProfile(host = "h", username = "u", privateKeyName = "k.pem", passwordBlob = byteArrayOf(1)),
        )
        val editor = newEditor(this)
        // The host/username/privateKeyName fields must survive RemoveSavedPassword.
        editor.onIntent(DraftIntent.RemoveSavedPassword)

        assertNull(store.read().passwordBlob)
        assertEquals("h", store.read().host)
        assertEquals("u", store.read().username)
        assertEquals("k.pem", store.read().privateKeyName)
        assertFalse(editor.hasStoredPassword.value)
        assertEquals(DraftStatus.Success("Saved password removed"), editor.status.value)
        // RemoveSavedPassword does NOT call appendDebugLog (matches current behavior).
        assertEquals(0, debugLog.appended.size)
    }

    // ── ForgetHost ───────────────────────────────────────────────────────

    @Test
    fun forgetHost_callsProfileAndEmitsSuccess() = runTest {
        val editor = newEditor(this)
        runCurrent() // let the launch settle

        editor.onIntent(DraftIntent.ForgetHost(host = "example.com", port = 2222))
        runCurrent() // drain the launched coroutine

        assertEquals(listOf("example.com" to 2222), hosts.deletions)
        assertEquals(DraftStatus.Success("Host enrollment forgotten for example.com"), editor.status.value)
        // ForgetHost logs the event with ConnectionMetadata classification.
        assertEquals(1, debugLog.appended.size)
        val (msg, _) = debugLog.appended[0]
        assertTrue("expected 'forget host=...' message, got '$msg'", msg.startsWith("forget host=example.com"))
    }

    // ── ImportKey ────────────────────────────────────────────────────────

    @Test
    fun importKey_success_updatesPrivateKeyNameAndEmitsSuccess() = runTest {
        val editor = newEditor(this)

        editor.onIntent(
            DraftIntent.ImportKey(displayName = "my_key", bytes = "PEM".toByteArray()),
        )

        assertEquals("my_key.pem", editor.draft.value.privateKeyName)
        val status = editor.status.value
        assertTrue(status is DraftStatus.Success)
        assertEquals("Imported my_key.pem", (status as DraftStatus.Success).message)
        assertTrue("store must persist the normalized key name", "my_key.pem" == store.read().privateKeyName)
    }

    @Test
    fun importKey_failure_emitsStickyError_andLeavesDraftUntouched() = runTest {
        // Wire a vault that fails on import.
        val failingKeys = object : PrivateKeyVaultPort {
            override fun import(safeName: String, bytes: ByteArray): Result<Unit> =
                Result.failure(IllegalStateException("vault locked"))
            override fun resolveAbsolutePath(safeName: String): String? = null
            override fun normalizeSafeName(raw: String): String =
                raw.trim().let { if (it.endsWith(".pem")) it else "$it.pem" }
        }
        val failingProfile = DefaultConnectionProfile(store, cipher, failingKeys, hosts)
        val editor = ConnectionDraftEditor(
            profile = failingProfile,
            scope = this,
            debugLog = debugLog,
            autoClearDelay = 50.milliseconds,
        )
        val preName = editor.draft.value.privateKeyName

        editor.onIntent(
            DraftIntent.ImportKey(displayName = "anything", bytes = "x".toByteArray()),
        )

        assertEquals(preName, editor.draft.value.privateKeyName)
        val status = editor.status.value
        assertTrue("status must be Error, got $status", status is DraftStatus.Error)
        assertTrue(
            "Error must mention 'Import failed': ${(status as DraftStatus.Error).message}",
            status.message.startsWith("Import failed"),
        )
        // Advance time well past autoClearDelay — Error must still be sticky.
        advanceTimeBy(500)
        runCurrent()
        assertEquals(status, editor.status.value)
    }

    @Test
    fun importKey_rejectsOversizedPayload() = runTest {
        val editor = newEditor(this)
        val huge = ByteArray(2 * 1024 * 1024 + 1) // 2 MB + 1 byte (default maxKeyBytes)

        editor.onIntent(DraftIntent.ImportKey(displayName = "huge", bytes = huge))

        val status = editor.status.value
        assertTrue("status must be Error, got $status", status is DraftStatus.Error)
        assertTrue(
            "Error must mention 'too large': ${(status as DraftStatus.Error).message}",
            status.message.contains("too large"),
        )
        // Draft untouched.
        assertEquals("", editor.draft.value.privateKeyName)
    }

    // ── status overwrite semantics ───────────────────────────────────────

    @Test
    fun errorOverwrittenBySuccessOnNextIntent() = runTest {
        val failingKeys = object : PrivateKeyVaultPort {
            override fun import(safeName: String, bytes: ByteArray): Result<Unit> =
                Result.failure(IllegalStateException("nope"))
            override fun resolveAbsolutePath(safeName: String): String? = null
            override fun normalizeSafeName(raw: String): String =
                raw.trim().let { if (it.endsWith(".pem")) it else "$it.pem" }
        }
        val failingProfile = DefaultConnectionProfile(store, cipher, failingKeys, hosts)
        val editor = ConnectionDraftEditor(
            profile = failingProfile,
            scope = this,
            debugLog = debugLog,
            autoClearDelay = 50.milliseconds,
        )

        // Failing import → Error.
        editor.onIntent(DraftIntent.ImportKey("a", byteArrayOf(1)))
        assertTrue("precondition: status must be Error after failing import", editor.status.value is DraftStatus.Error)

        // Save overwrites Error with Success — same editor, same scope.
        editor.onIntent(DraftIntent.UpdateHost("h.example"))
        editor.onIntent(DraftIntent.UpdatePassword("p"))
        editor.onIntent(DraftIntent.Save)
        val status = editor.status.value
        assertTrue("expected Success after Save, got $status", status is DraftStatus.Success)
        assertEquals("Saved", (status as DraftStatus.Success).message)
    }

    @Test
    fun successOverwrittenByErrorOnNextIntent() = runTest {
        store.seed(StoredProfile(passwordBlob = byteArrayOf(0)))
        val failingKeys = object : PrivateKeyVaultPort {
            override fun import(safeName: String, bytes: ByteArray): Result<Unit> =
                Result.failure(IllegalStateException("nope"))
            override fun resolveAbsolutePath(safeName: String): String? = null
            override fun normalizeSafeName(raw: String): String =
                raw.trim().let { if (it.endsWith(".pem")) it else "$it.pem" }
        }
        val profile = DefaultConnectionProfile(store, cipher, failingKeys, hosts)
        val editor = ConnectionDraftEditor(
            profile = profile,
            scope = this,
            debugLog = debugLog,
            autoClearDelay = 50.milliseconds,
        )

        // First: Save succeeds → Success.
        editor.onIntent(DraftIntent.UpdatePassword("p"))
        editor.onIntent(DraftIntent.Save)
        assertTrue("precondition: Save must emit Success", editor.status.value is DraftStatus.Success)

        // Then: a failing import overwrites Success with Error.
        editor.onIntent(DraftIntent.ImportKey("a", byteArrayOf(1)))
        val status = editor.status.value
        assertTrue("expected Error overwriting Success, got $status", status is DraftStatus.Error)
    }

    // ── auto-clear ───────────────────────────────────────────────────────

    @Test
    fun successAutoClearsToIdleAfterDelay() = runTest {
        val editor = newEditor(this)
        editor.onIntent(DraftIntent.Save)

        // Immediately after Save: status is Success, autoClearJob scheduled.
        assertTrue(editor.status.value is DraftStatus.Success)

        // Advance just before the auto-clear delay — status still Success.
        advanceTimeBy(40)
        runCurrent()
        assertTrue(editor.status.value is DraftStatus.Success)

        // Advance past the auto-clear delay — autoClearJob fires, status is Idle.
        advanceTimeBy(60)
        runCurrent()
        assertEquals(DraftStatus.Idle, editor.status.value)
    }

    @Test
    fun backToBackSuccess_olderAutoClearCancelledByNewer() = runTest {
        val editor = newEditor(this)
        store.seed(StoredProfile(passwordBlob = byteArrayOf(0)))
        editor.onIntent(DraftIntent.UpdatePassword("p"))
        editor.onIntent(DraftIntent.Save) // Success("Saved") + autoClearJob A (50ms)
        // Immediately Clear — overwrites with Success("Cleared") + autoClearJob B.
        editor.onIntent(DraftIntent.Clear)

        val status = editor.status.value
        assertTrue("status must be Success(Cleared), got $status", status is DraftStatus.Success)
        assertEquals("Cleared", (status as DraftStatus.Success).message)

        // Advance past the auto-clear delay. Job A was cancelled when B started,
        // so the editor's status is Idle (B fired) — NOT a stale "Saved" + Idle reset.
        advanceTimeBy(60)
        runCurrent()
        assertEquals(DraftStatus.Idle, editor.status.value)
    }

    // ── LogFingerprint ────────────────────────────────────────────────────

    @Test
    fun logFingerprint_writesCredentialMetadataAndEmitsSuccess() = runTest {
        val editor = newEditor(this)

        editor.onIntent(DraftIntent.LogFingerprint(fingerprint = "abc123"))

        assertEquals(1, debugLog.credentialMessages.size)
        assertTrue(
            "credential line must include the fingerprint token: ${debugLog.credentialMessages[0]}",
            debugLog.credentialMessages[0].contains("abc123"),
        )
        assertEquals(DraftStatus.Success("Fingerprint appended to log"), editor.status.value)
    }

    // ── DismissStatus ────────────────────────────────────────────────────

    @Test
    fun dismissStatus_resetsToIdle() = runTest {
        // Seed an Error first.
        val failingKeys = object : PrivateKeyVaultPort {
            override fun import(safeName: String, bytes: ByteArray): Result<Unit> =
                Result.failure(IllegalStateException("nope"))
            override fun resolveAbsolutePath(safeName: String): String? = null
            override fun normalizeSafeName(raw: String): String =
                raw.trim().let { if (it.endsWith(".pem")) it else "$it.pem" }
        }
        val failingProfile = DefaultConnectionProfile(store, cipher, failingKeys, hosts)
        val editor = ConnectionDraftEditor(
            profile = failingProfile,
            scope = this,
            debugLog = debugLog,
            autoClearDelay = 50.milliseconds,
        )
        editor.onIntent(DraftIntent.ImportKey("a", byteArrayOf(1)))
        assertTrue(editor.status.value is DraftStatus.Error)

        editor.onIntent(DraftIntent.DismissStatus)

        assertEquals(DraftStatus.Idle, editor.status.value)
    }

    // ── pure JUnit contract ──────────────────────────────────────────────

    @Test
    fun pureJunit_noAndroidFramework() = runTest {
        // Sanity check: constructing the editor from a TestScope and exercising
        // a sync intent must succeed with zero android.* references. The
        // @RunWith annotation is intentionally absent — if any code path here
        // accidentally pulls in android.util.Log or BuildConfig, the test
        // file would fail to compile or fail at runtime.
        val editor = newEditor(this)
        assertNotNull(editor)
        editor.onIntent(DraftIntent.UpdateHost("x"))
        assertEquals("x", editor.draft.value.host)
    }

    // ── fakes ────────────────────────────────────────────────────────────
    //
    // Copied verbatim from ConnectionProfileTest. Same shapes, same
    // behavior. Promote to a shared testFixtures/ source set if a third
    // test class needs them.

    private class InMemoryProfileStore : ProfileStorePort {
        private var current = StoredProfile()
        fun seed(profile: StoredProfile) { current = profile }
        override fun read(): StoredProfile = current
        override fun write(profile: StoredProfile) { current = profile }
        override fun clearConnectionFields() { current = StoredProfile() }
    }

    private class RoundTripFakeCipher : SecretCipherPort {
        var decryptCount: Int = 0
            private set
        override fun encrypt(plaintext: ByteArray): ByteArray =
            plaintext.map { (it + 1).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray): ByteArray {
            decryptCount++
            return ciphertext.map { (it - 1).toByte() }.toByteArray()
        }
    }

    private class InMemoryPrivateKeyVault : PrivateKeyVaultPort {
        val files = mutableMapOf<String, String>()
        override fun import(safeName: String, bytes: ByteArray): Result<Unit> = runCatching {
            files[safeName] = "/mem/$safeName"
            bytes.fill(0)
        }
        override fun resolveAbsolutePath(safeName: String): String? = files[safeName]
        override fun normalizeSafeName(raw: String): String =
            raw.trim().let { if (it.endsWith(".pem")) it else "$it.pem" }
    }

    private class RecordingHostEnrollment : HostEnrollmentPort {
        val deletions = mutableListOf<Pair<String, Int>>()
        override suspend fun delete(host: String, port: Int) {
            deletions += host to port
        }
    }

    /**
     * Test [DebugLogSink]. Mirrors debug-build behavior for [fingerprint]
     * (non-empty token shaped like "TEST_FP:..." for non-empty passwords)
     * so the editor's `lastSavedFingerprint` propagation can be asserted.
     * Records every [append] and [logCredential] call for inspection.
     */
    private class RecordingDebugLogSink : DebugLogSink {
        val appended = mutableListOf<Pair<String, String>>()
        val credentialMessages = mutableListOf<String>()

        override fun append(message: String, privateKeyName: String) {
            appended += message to privateKeyName
        }

        override fun logCredential(message: String) {
            credentialMessages += message
        }

        override fun fingerprint(password: String): String {
            // Mirror the debug-build convention from passwordFingerprint:
            // empty password → "(empty)", non-empty → a recognisable token.
            return if (password.isEmpty()) "(empty)" else "TEST_FP:$password"
        }
    }
}