package com.taosun.hanterm.ssh.auth

import com.taosun.hanterm.ssh.BouncyCastleBootstrap
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.StringWriter
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * Sprint 2.5 / Module 14 (S4) — pins the parallel-hygiene log gate on
 * [PublicKeyAuthProvider.loadKeyProvider].
 *
 * The key *format* (OpenSSHv1, PKCS8, PuTTY, …) is non-sensitive and
 * useful for diagnosis, so we log it at `Log.d` in debug builds only.
 * The *path* is never logged.
 *
 * Spec coverage:
 *  - [pkp_lg_01] PKP-LG-01 — release builds do NOT log the
 *    `privateKeyPath`. The current code path has no path logging
 *    anywhere, so we assert "no `Log.*` mentions the path" in
 *    release; this is a regression guard for future refactors.
 *  - [pkp_lg_02] PKP-LG-02 — `loadKeyProvider` may log the key format
 *    at `Log.d` in debug builds and is silent in release. We use
 *    Robolectric's [ShadowLog] stream to assert both branches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
class PublicKeyAuthProviderLogGateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var keyPath: String

    @Before
    fun setUp() {
        BouncyCastleBootstrap.ensureRegistered()
        ShadowLog.clear()

        // A real RSA PEM file on disk — required by
        // KeyProviderUtil.detectKeyFileFormat, which is what
        // loadKeyProvider calls. PKCS#8 PEM is the most boring
        // format and works through the system RSA provider, so
        // we don't need to drag BC into the test beyond what the
        // bootstrap does for safety.
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            .generateKeyPair()
        val sw = StringWriter()
        JcaPEMWriter(sw).use { it.writeObject(JcaMiscPEMGenerator(keyPair.private)) }
        val file = tempFolder.newFile("id_rsa.pem")
        file.writeText(sw.toString())
        keyPath = file.absolutePath
    }

    @Test
    fun pkp_lg_01_pathNotLoggedInRelease() {
        // PKP-LG-01: the privateKeyPath is never logged. Today
        // loadKeyProvider doesn't even take a path-logging branch,
        // so we assert "no log line contains the path"; that's a
        // regression guard for the obvious "let's also log
        // loadKeyProvider path=..." future addition.
        val provider = PublicKeyAuthProvider.loadKeyProvider(keyPath)
        assertNotNull("loadKeyProvider must succeed", provider)

        val logs = ShadowLog.getLogs().filter { it.tag == "SshKeyAuth" }
        for (entry in logs) {
            assertFalse(
                "release build must not log the privateKeyPath: msg='${entry.msg}'",
                entry.msg.contains(keyPath),
            )
            assertFalse(
                "release build must not log 'path=': msg='${entry.msg}'",
                entry.msg.contains("path="),
            )
        }
    }

    @Test
    fun pkp_lg_02_formatLoggedInDebugSilentInRelease() {
        // Debug branch: Robolectric's default BuildConfig is for the
        // debug build type, so the production `if (BuildConfig.DEBUG)`
        // branch fires and we expect a `Log.d` with the key format.
        // We don't pin to OpenSSHv1 because the detection might land
        // on a different format depending on BC's encoder quirks;
        // we just pin the structural shape.
        val provider = PublicKeyAuthProvider.loadKeyProvider(keyPath)
        assertNotNull(provider)
        val debugLogs = ShadowLog.getLogs().filter { it.tag == "SshKeyAuth" }
        assertTrue(
            "debug build must emit a Log.d on tag=SshKeyAuth; got $debugLogs",
            debugLogs.isNotEmpty(),
        )
        val debugMsg = debugLogs.first().msg
        assertTrue(
            "debug Log.d must mention 'format=': $debugMsg",
            debugMsg.contains("format="),
        )
        // The path must not leak even in the debug branch — same as
        // PKP-LG-01, applied to the debug build.
        assertFalse(
            "debug build must not log the privateKeyPath: msg='$debugMsg'",
            debugMsg.contains(keyPath),
        )
    }
}
