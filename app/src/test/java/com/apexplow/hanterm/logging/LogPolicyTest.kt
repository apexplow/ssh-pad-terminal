package com.apexplow.hanterm.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure-JUnit tests for [BuildConfigAwareLogPolicy]. No Android, no
 * Robolectric — the policy takes `isDebug: Boolean` so it can be exercised
 * directly. The `:app` `BuildConfig.DEBUG` is only consulted in
 * [AppLog.init]'s default-arg expression, never here.
 *
 * Spec coverage (Issue #13):
 *  - [lpp_01] LPP-01 — debug build: every sensitive classification goes
 *    to [LogDestination.LogcatOnly], never the file sink.
 *  - [lpp_02] LPP-02 — release build: every sensitive classification goes
 *    to [LogDestination.Drop], neither file nor Logcat.
 *  - [lpp_03] LPP-03 — both build types: non-sensitive classifications
 *    (Diagnostic / Security / Error) go to [LogDestination.File].
 *  - [lpp_04] LPP-04 — adversarial regression: a table-driven sweep
 *    across (Input, CredentialMetadata, ConnectionMetadata) × (debug,
 *    release) shows no path where any sensitive entry reaches
 *    [LogDestination.File].
 *  - [lpp_05] LPP-05 — the classifier is pure: same input → same output,
 *    no shared state leaks between calls.
 */
class LogPolicyTest {

    private fun entry(
        classification: LogClassification,
        message: String = "test",
    ): LogEntry = LogEntry(
        level = LogLevel.I,
        tag = "Test",
        message = message,
        classification = classification,
    )

    @Test
    fun lpp_01_debugBuild_routesSensitiveEntriesToLogcatOnly() {
        val policy = BuildConfigAwareLogPolicy(isDebug = true)
        for (classification in listOf(
            LogClassification.Input,
            LogClassification.CredentialMetadata,
            LogClassification.ConnectionMetadata,
        )) {
            assertEquals(
                "debug $classification must go to LogcatOnly (never File)",
                LogDestination.LogcatOnly,
                policy.classify(entry(classification)),
            )
        }
    }

    @Test
    fun lpp_02_releaseBuild_dropsSensitiveEntries() {
        val policy = BuildConfigAwareLogPolicy(isDebug = false)
        for (classification in listOf(
            LogClassification.Input,
            LogClassification.CredentialMetadata,
            LogClassification.ConnectionMetadata,
        )) {
            assertEquals(
                "release $classification must Drop (never File, never Logcat)",
                LogDestination.Drop,
                policy.classify(entry(classification)),
            )
        }
    }

    @Test
    fun lpp_03_nonSensitiveEntriesAlwaysReachFile() {
        // Bug reports need Error / Diagnostic / Security to survive — both
        // build types.
        for (isDebug in listOf(true, false)) {
            val policy = BuildConfigAwareLogPolicy(isDebug = isDebug)
            for (classification in listOf(
                LogClassification.Diagnostic,
                LogClassification.Security,
                LogClassification.Error,
            )) {
                assertEquals(
                    "$classification @ isDebug=$isDebug must reach File",
                    LogDestination.File,
                    policy.classify(entry(classification)),
                )
            }
        }
    }

    @Test
    fun lpp_04_adversarial_noSensitiveEntryEverReachesFile() {
        // Sweep every (sensitive classification, build type) cell and assert
        // the destination is NEVER File. This is the regression that would
        // catch any future refactor that accidentally widens the
        // "always-file" mapping to include a sensitive classification.
        for (isDebug in listOf(true, false)) {
            val policy = BuildConfigAwareLogPolicy(isDebug = isDebug)
            for (classification in listOf(
                LogClassification.Input,
                LogClassification.CredentialMetadata,
                LogClassification.ConnectionMetadata,
            )) {
                val dest = policy.classify(entry(classification))
                assertNotEquals(
                    "sensitive classification $classification must NEVER reach File " +
                        "(isDebug=$isDebug); got $dest",
                    LogDestination.File,
                    dest,
                )
            }
        }
    }

    @Test
    fun lpp_05_classifierIsPure_noStateLeaksBetweenCalls() {
        // Two back-to-back calls with the same input must return the same
        // destination. Catches the (unlikely) regression where someone
        // caches state inside the policy and an earlier call biases a
        // later one.
        val policy = BuildConfigAwareLogPolicy(isDebug = false)
        val first = policy.classify(entry(LogClassification.Input, "pinyin text"))
        val second = policy.classify(entry(LogClassification.Input, "different text"))
        assertEquals(
            "classifier must be pure; first=$first second=$second",
            first,
            second,
        )
    }
}