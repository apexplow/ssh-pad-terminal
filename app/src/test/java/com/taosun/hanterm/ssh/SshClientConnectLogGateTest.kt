package com.taosun.hanterm.ssh

import androidx.test.core.app.ApplicationProvider
import com.taosun.hanterm.logging.AppLog
import com.taosun.hanterm.logging.BuildConfigAwareLogPolicy
import com.taosun.hanterm.logging.LogClassification
import com.taosun.hanterm.logging.LogDestination
import com.taosun.hanterm.logging.LogEntry
import com.taosun.hanterm.logging.LogPolicy
import com.taosun.hanterm.ssh.auth.Auth
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.PublicKey

/**
 * Pin that [SshClient]'s connect-failed catch path classifies its
 * [AppLog.e] entry as [LogClassification.ConnectionMetadata] (Logcat-only in
 * debug, dropped in release), **not** the default [LogClassification.Error]
 * which would persist the host/port/username string into `filesDir/app.log`.
 *
 * Part of #54 (open-source-readiness P0). The regression we're guarding
 * against: a future refactor that drops the explicit `classification =`
 * argument would silently leak every user's `host`/`port`/`user` into the
 * release app log — defeating Issue #13's LogPolicy and a serious privacy
 * regression once the repo is public.
 *
 * We trigger the catch path by injecting a [HostKeyVerifier] that always
 * throws sshj's `TransportException(HOST_KEY_NOT_VERIFIABLE)` — the same
 * shape [SshClient.connect] sees in production when a known-hosts mismatch
 * is rejected. We can't drive a real TCP connect here (CLAUDE.md forbids
 * hitting a real sshd from `app/src/test`); under Robolectric the test's
 * TCP connect to `example.com` fails first with a `SocketTimeoutException`,
 * but both paths exercise the same `catch (t: Throwable)` block we want to
 * pin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
class SshClientConnectLogGateTest {

    /**
     * Recording [LogPolicy] for the connect-failure regression. Captures
     * every entry the production code makes so we can assert the classifier
     * saw it AND what classification was passed.
     */
    private class RecordingLogPolicy : LogPolicy {
        val entries: MutableList<LogEntry> = mutableListOf()
        override fun classify(entry: LogEntry): LogDestination {
            entries.add(entry)
            // Mirror the production release policy so a future widening
            // of the "always-File" mapping is caught here too.
            return BuildConfigAwareLogPolicy(isDebug = false).classify(entry)
        }
    }

    /**
     * A [HostKeyVerifier] that always rejects via the same exception type
     * sshj's `KeyExchanger.verifyHost` raises when an entry is rejected.
     * In Robolectric the TCP connect to `example.com` fails first
     * (no DNS / network), so this verifier is rarely exercised; it
     * documents the contract for non-Robolectric test paths and any
     * future integration tests that drive a real socket.
     */
    private class ThrowingVerifier(
        private val message: String = "test forced rejection",
    ) : HostKeyVerifier {
        override fun verify(
            hostname: String,
            port: Int,
            key: PublicKey,
        ): Boolean {
            throw TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE, message)
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> =
            mutableListOf()
    }

    @Before
    fun setUp() {
        AppLog.resetPolicyForTests()
        AppLog.clear()
    }

    @After
    fun tearDown() {
        AppLog.resetPolicyForTests()
        AppLog.clear()
    }

    /**
     * Core pin for #54: when [SshClient.connect] catches any failure in
     * its inner block, the [AppLog.e] entry carrying the host/port/username
     * string must be classified [LogClassification.ConnectionMetadata],
     * NOT the default Error (which → File). Independent of whether the
     * failure was a TCP timeout, KEX rejection, or auth failure — all of
     * those paths run through the same catch block and produce the same
     * entry shape.
     */
    @Test
    fun sc_54_connectFailureLogEntry_isConnectionMetadata_notError() {
        val recording = RecordingLogPolicy()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AppLog.init(context, recording)

        val client = SshClient(
            hostKeyVerifier = ThrowingVerifier(),
            context = context,
        )

        val result = runBlocking {
            client.connect(
                host = "example.com",
                port = 22,
                username = "alice",
                auth = Auth.PasswordAuth("placeholder".toCharArray()),
            )
        }
        // Sanity: the catch path actually ran.
        assertTrue("connect must fail (any cause)", result.isFailure)

        // Find the connect-failed entry — the message we expect from
        // SshClient.connect's catch block contains the host + user.
        val connectFailed = recording.entries.firstOrNull { entry ->
            entry.tag == SshClient.TAG &&
                entry.message.contains("connect failed") &&
                entry.message.contains("example.com") &&
                entry.message.contains("alice")
        }
        assertNotNull(
            "expected exactly one connect-failed entry tagged SshClient " +
                "containing host/user; got ${recording.entries}",
            connectFailed,
        )

        assertEquals(
            "connect-failed entry must be classified ConnectionMetadata " +
                "so release app.log never carries host/port/user " +
                "(Issue #54 / Issue #13)",
            LogClassification.ConnectionMetadata,
            connectFailed!!.classification,
        )
        assertEquals(
            "release classification must Drop (never File)",
            LogDestination.Drop,
            BuildConfigAwareLogPolicy(isDebug = false).classify(connectFailed),
        )
        // Belt-and-braces: the file sink must remain empty after a
        // verifier-rejected connect — even if the classifier were
        // accidentally widened to Error, this asserts we never wrote.
        assertEquals(
            "release app.log must be empty after a classified-as-ConnectionMetadata " +
                "connect-failed entry; got '${AppLog.readTail()}'",
            "",
            AppLog.readTail(),
        )
    }

    /**
     * Pin that the catch path's [SshException] is constructed correctly:
     * the user-facing message is in [failure.message], the original
     * sshj/network throwable is preserved as [failure.cause]. Independent
     * of which specific failure sshj raised (TCP timeout under
     * Robolectric; verifier rejection in integration tests).
     */
    @Test
    fun sc_54_connectFailure_resultCarriesSshExceptionWithFriendlyMessage_andPreservesCause() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AppLog.init(context, RecordingLogPolicy())
        val client = SshClient(
            hostKeyVerifier = ThrowingVerifier(message = "forensic detail"),
            context = context,
        )

        val result = runBlocking {
            client.connect(
                host = "example.com",
                port = 22,
                username = "alice",
                auth = Auth.PasswordAuth("placeholder".toCharArray()),
            )
        }
        val failure = result.exceptionOrNull()
        assertNotNull("failure must carry an exception", failure)
        assertTrue(
            "failure must be SshException, got ${failure?.javaClass?.name}",
            failure is SshException,
        )
        val msg = failure?.message.orEmpty()
        assertTrue(
            "friendly message must NOT be empty; got '$msg'",
            msg.isNotBlank(),
        )
        assertTrue(
            "friendly message must NOT leak raw sshj class names; got '$msg'",
            !msg.contains("net.schmizz"),
        )
        // The throwable is preserved for engineers (so a bug report can be
        // debugged from the SshException alone). It may be the leaf
        // SocketTimeoutException under Robolectric OR a wrapped
        // TransportException if a real socket reaches KEX — both are
        // acceptable.
        val innerCause = failure?.cause
        assertNotNull("SshException.cause must preserve the original Throwable", innerCause)
    }

    /**
     * Pin that the throwable attached to the AppLog entry is the SAME
     * throwable wrapped into the [SshException] — engineers reading
     * Logcat can correlate stack traces with the user-facing error.
     */
    @Test
    fun sc_54_connectFailure_loggedThrowableMatchesSshExceptionCause() {
        val recording = RecordingLogPolicy()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AppLog.init(context, recording)
        val client = SshClient(
            hostKeyVerifier = ThrowingVerifier(message = "forensic detail"),
            context = context,
        )

        val result = runBlocking {
            client.connect(
                host = "example.com",
                port = 22,
                username = "alice",
                auth = Auth.PasswordAuth("placeholder".toCharArray()),
            )
        }
        val failure = result.exceptionOrNull() as? SshException
        assertNotNull("failure must be SshException", failure)

        val entry = recording.entries.firstOrNull { e ->
            e.tag == SshClient.TAG && e.message.contains("connect failed")
        }
        assertNotNull("expected one connect-failed entry", entry)

        // The logged throwable must be the original sshj/network throwable,
        // NOT a re-wrapped one — so engineering forensics in Logcat are
        // accurate. We compare by identity (===) so a future "wrap the
        // cause in another SshException" refactor is caught.
        assertTrue(
            "logged throwable must be the same instance as SshException.cause " +
                "(identity check); got entry.throwable=${entry?.throwable?.javaClass?.name} " +
                "cause=${failure?.cause?.javaClass?.name}",
            entry?.throwable === failure?.cause,
        )
        // And the classification is still ConnectionMetadata.
        assertEquals(
            LogClassification.ConnectionMetadata,
            entry!!.classification,
        )
    }

    /**
     * Belt-and-braces: confirm that the OTHER release-mode entry that
     * [prepareKnownHostsVerifier] makes on store-init failure is NOT
     * logged when the probe succeeds (Robolectric default). That branch
     * does not carry host/port/user in its message body, so its
     * Error → File classification is correct; this test guards against
     * us accidentally widening the re-classification beyond the one
     * connect-failed entry.
     */
    @Test
    fun sc_54_prepareKnownHostsVerifier_doesNotLogOnSuccessfulProbe() {
        val recording = RecordingLogPolicy()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AppLog.init(context, recording)

        val client = SshClient(
            hostKeyVerifier = ThrowingVerifier(),
            context = context,
        )

        runBlocking {
            client.connect(
                host = "example.com",
                port = 22,
                username = "alice",
                auth = Auth.PasswordAuth("placeholder".toCharArray()),
            )
        }

        val storeError = recording.entries.firstOrNull { entry ->
            entry.tag == SshClient.TAG &&
                entry.message.contains("host-key store unavailable")
        }
        assertNull(
            "prepareKnownHostsVerifier must NOT log when the probe succeeds; " +
                "only the connect-failed entry should appear",
            storeError,
        )
    }
}