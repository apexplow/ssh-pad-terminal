package com.example.sshterminal.ssh.auth

import com.example.sshterminal.logging.AppLog
import net.schmizz.sshj.SSHClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Sprint 2.5 / Module 14 (S4) — pins the auth-diagnostic gating on
 * [PasswordAuthProvider.authenticate].
 *
 * The 3-arg `authenticate(client, username, auth)` keeps the
 * [SshAuthProvider] interface contract intact; the 4-arg overload
 * exposes the `isDebug` flag for tests. Robolectric can't easily
 * shadow SSHJ's `authPassword` call (it's a final method on
 * [SSHClient]), so the test exercises a *partial* auth path by
 * feeding an [Auth.PasswordAuth] and observing what the diagnostic
 * branch did BEFORE the call would have run. We do that by wrapping
 * the call in a try/catch — the authPassword invocation will throw
 * (no real transport), but the Log.* and the file-sink side effects
 * happen on the line *before* the throw, so we can still assert on
 * them.
 *
 * Spec coverage:
 *  - [pap_lg_01] PAP-LG-01 — debug build emits the existing
 *    `Log.i(TAG, "password auth: ...")` line.
 *  - [pap_lg_02] PAP-LG-02 — release build does NOT emit any `Log.*`
 *    call AND does not call `sha256Hex`. We can't poke at the
 *    private `sha256Hex` directly, but we can verify the *absence*
 *    of any log line (the Log.i is the only consumer of sha256Hex,
 *    so no Log.i ⇒ sha256Hex was never called).
 *  - [pap_lg_03] PAP-LG-03 — `authenticate` does NOT write to
 *    `AppLog` (the file sink) for any auth event, regardless of
 *    build type.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PasswordAuthProviderLogGateTest {

    @Before
    fun setUp() {
        // Clear both sinks so a stale entry from a previous test
        // doesn't make us believe a no-op write happened.
        ShadowLog.clear()
        AppLog.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        AppLog.clear()
    }

    @After
    fun tearDown() {
        AppLog.clear()
    }

    @Test
    fun pap_lg_01_logIEmittedInDebug() {
        val client = SSHClient()
        val auth = Auth.PasswordAuth("hunter2-supersecret")

        // The 4-arg overload with isDebug=true is the test surface.
        // We catch any throw from client.authPassword (no transport
        // is open) — what we care about is the Log.i on the line above.
        runCatching {
            PasswordAuthProvider.authenticate(client, "ops", auth, isDebug = true)
        }

        val logs = ShadowLog.getLogs()
        val sshAuth = logs.filter { it.tag == "SshAuth" }
        assertTrue(
            "debug build must emit Log.i on tag=SshAuth; got logs=$logs",
            sshAuth.isNotEmpty(),
        )
        val msg = sshAuth.first().msg
        assertTrue(
            "Log.i must include the username: $msg",
            msg.contains("user=ops"),
        )
        assertTrue(
            "Log.i must include the password length: $msg",
            msg.contains("length=${auth.password.length}"),
        )
        assertTrue(
            "Log.i must include the truncated sha256: $msg",
            msg.contains("sha256="),
        )
        assertTrue(
            "Log.i must include the first byte: $msg",
            msg.contains("firstByte="),
        )
        // Confirm the Log type (Log.i, not Log.d) — the test name uses
        // 'logI' for a reason; the spec is about the I-level line.
        assertEquals(
            "the log entry must be at I level (Log.i)",
            android.util.Log.INFO,
            sshAuth.first().type,
        )
    }

    @Test
    fun pap_lg_02_noLogInReleaseAndNoSha256Hex() {
        val client = SSHClient()
        val auth = Auth.PasswordAuth("hunter2-supersecret")

        runCatching {
            PasswordAuthProvider.authenticate(client, "ops", auth, isDebug = false)
        }

        // CS-PAP-LG-02: no Log.* AND no sha256Hex invocation.
        // We can't observe sha256Hex directly (private), but the
        // single call site is inside the `if (isDebug)` branch, so
        // asserting "no Log.i" is structurally equivalent to
        // asserting "no sha256Hex call".
        val sshAuth = ShadowLog.getLogs().filter { it.tag == "SshAuth" }
        assertTrue(
            "release build must NOT emit any Log.* on tag=SshAuth; got $sshAuth",
            sshAuth.isEmpty(),
        )
    }

    @Test
    fun pap_lg_03_noAppLogWriteInEitherBuildType() {
        // Belt-and-braces: AppLog must never see the auth event, in
        // either build type. The assertion uses the AppLog file sink
        // (which is the user-facing in-app log panel) — if anything
        // called AppLog.i/d/e from this code path, the password-
        // derived content would have a second leak route.
        for (isDebug in listOf(true, false)) {
            ShadowLog.clear()
            AppLog.clear()
            val client = SSHClient()
            val auth = Auth.PasswordAuth("hunter2-supersecret")
            runCatching {
                PasswordAuthProvider.authenticate(client, "ops", auth, isDebug = isDebug)
            }
            val tail = AppLog.readTail()
            assertFalse(
                "AppLog must NOT contain 'password auth' for isDebug=$isDebug; got '$tail'",
                tail.contains("password auth"),
            )
            assertFalse(
                "AppLog must NOT contain the password length for isDebug=$isDebug; got '$tail'",
                tail.contains("length="),
            )
            assertFalse(
                "AppLog must NOT contain 'sha256=' for isDebug=$isDebug; got '$tail'",
                tail.contains("sha256="),
            )
        }
    }
}
