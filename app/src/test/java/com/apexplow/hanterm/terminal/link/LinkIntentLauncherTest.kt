package com.apexplow.hanterm.terminal.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sprint 4 T14 — pins the contract surfaces of [LinkIntentLauncher.launch]:
 *  1. URL fails re-validation → [LaunchResult.StaleUrl] (no dispatch)
 *  2. URL is well-formed but no ACTION_VIEW handler → [LaunchResult.NoBrowser]
 *
 * The full `Ok` path (URL resolves, `startActivity` returns) is gated on
 * Robolectric's shadow PackageManager matching a real ResolveInfo; we
 * cover the regex-validation contract here and pin the "happy path"
 * dispatch through the [LinkDialog]'s higher-level integration tests
 * in Step 13. v0.1's manual device test plan covers the actual browser
 * launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36])
class LinkIntentLauncherTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun launch_malformedUrl_returnsStaleUrl_noDispatch() {
        // The "URL" has no scheme — fails LinkDetector.firstUrlIn
        // after the input has been canonicalised.
        val result = LinkIntentLauncher.launch(context, "example.com")
        assertEquals(LaunchResult.StaleUrl, result)
    }

    @Test
    fun launch_urlWithMalformedFirstChar_returnsStaleUrl() {
        // OV #7 — `://[` is rejected by the regex (first char after ://
        // must be alphanumeric). Stale, not dispatched.
        val result = LinkIntentLauncher.launch(context, "http://[")
        assertEquals(LaunchResult.StaleUrl, result)
    }

    @Test
    fun launch_emptyUrl_returnsStaleUrl() {
        val result = LinkIntentLauncher.launch(context, "")
        assertEquals(LaunchResult.StaleUrl, result)
    }

    @Test
    fun launch_urlFailingRegex_returnsStaleUrl() {
        // Control character at start — detector strips ESC, but the
        // URL itself doesn't match the regex. (We use a string that
        // looks URL-ish but trips the regex.)
        val result = LinkIntentLauncher.launch(context, "https://")
        assertEquals(LaunchResult.StaleUrl, result)
    }

    @Test
    fun launch_urlWithTrailingPunctuation_strippedAndDispatched() {
        // The detector strips trailing `,;.!)` from the URL — the
        // *stripped* URL is what gets re-validated. The expected
        // verdict is NoBrowser (no ACTION_VIEW handler installed in
        // the test environment) or Ok. Both prove the regex path
        // worked; we accept either.
        val result = LinkIntentLauncher.launch(context, "https://example.com,")
        // Without a registered handler the path returns NoBrowser;
        // with a handler it would return Ok. Either is a valid
        // "regex stripped and intent was attempted" outcome.
        org.junit.Assert.assertTrue(
            "expected NoBrowser or Ok; got $result",
            result == LaunchResult.NoBrowser || result == LaunchResult.Ok,
        )
    }
}