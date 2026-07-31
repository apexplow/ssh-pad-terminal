package com.apexplow.hanterm.terminal.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 30 JUnit cases pinning [LinkDetector.firstUrlIn].
 *
 * Plan §Step 2 named 12 base cases. Sprint 4 eng-review finding OV #7
 * added 4 tightening cases for malformed URLs (`http:///foo` etc.).
 * The remaining 14 cover port/query/fragment/edge-case combinations
 * called out in the plan.
 *
 * Pure JUnit (no Robolectric) — the regex has zero Android imports
 * by design (see package-info.kt).
 */
class LinkDetectorTest {

    // --- Plan §Step 2 named cases ---

    @Test
    fun test_urlAtStartOfLine() {
        assertEquals(
            "https://example.com",
            LinkDetector.firstUrlIn("https://example.com is the home page"),
        )
    }

    @Test
    fun test_urlInMiddleOfLine() {
        assertEquals(
            "https://example.com",
            LinkDetector.firstUrlIn("see https://example.com for context"),
        )
    }

    @Test
    fun test_urlAtEndOfLine() {
        assertEquals(
            "https://example.com",
            LinkDetector.firstUrlIn("home: https://example.com"),
        )
    }

    @Test
    fun test_trailingComma_included() {
        assertEquals(
            "https://github.com",
            LinkDetector.firstUrlIn("repo https://github.com, branch main"),
        )
    }

    @Test
    fun test_trailingSemicolon_included() {
        assertEquals(
            "https://github.com",
            LinkDetector.firstUrlIn("repo https://github.com; main"),
        )
    }

    @Test
    fun test_trailingPeriod_included() {
        assertEquals(
            "https://github.com",
            LinkDetector.firstUrlIn("repo https://github.com."),
        )
    }

    @Test
    fun test_trailingParen_included() {
        assertEquals(
            "https://github.com",
            LinkDetector.firstUrlIn("(see https://github.com)"),
        )
    }

    @Test
    fun test_twoUrlsOnOneLine_returnsFirst() {
        assertEquals(
            "https://first.example.com",
            LinkDetector.firstUrlIn("first https://first.example.com then https://second.example.com"),
        )
    }

    @Test
    fun test_markdownLink_returnsUrlNotBracket() {
        assertEquals(
            "https://example.com",
            LinkDetector.firstUrlIn("[click here](https://example.com)"),
        )
    }

    @Test
    fun test_htmlAnchor_returnsUrl() {
        assertEquals(
            "https://example.com",
            LinkDetector.firstUrlIn("<a href=\"https://example.com\">click</a>"),
        )
    }

    @Test
    fun test_ftpScheme_matches() {
        assertEquals(
            "ftp://files.example.com",
            LinkDetector.firstUrlIn("get ftp://files.example.com"),
        )
    }

    @Test
    fun test_urlWithPort_matches() {
        assertEquals(
            "https://localhost:8080/api",
            LinkDetector.firstUrlIn("server https://localhost:8080/api"),
        )
    }

    @Test
    fun test_urlWithQuery_matches() {
        assertEquals(
            "https://example.com/search?q=hello&lang=en",
            LinkDetector.firstUrlIn("https://example.com/search?q=hello&lang=en"),
        )
    }

    @Test
    fun test_urlWithFragment_matches() {
        assertEquals(
            "https://example.com/page#section",
            LinkDetector.firstUrlIn("jump to https://example.com/page#section"),
        )
    }

    @Test
    fun test_ansiSgrSequence_doesNotCorruptMatch() {
        // Terminal output wraps URLs in ANSI SGR sequences. Regex finds the URL
        // despite the surrounding ESC[0m bytes because the regex operates on
        // raw bytes and the URL chars themselves are clean ASCII.
        assertEquals(
            "https://github.com",
            LinkDetector.firstUrlIn("[0m[31mhttps://github.com[0m"),
        )
    }

    @Test
    fun test_emptyString_returnsNull() {
        assertNull(LinkDetector.firstUrlIn(""))
    }

    @Test
    fun test_whitespaceOnly_returnsNull() {
        assertNull(LinkDetector.firstUrlIn("   \t  "))
    }

    @Test
    fun test_looksLikeUrlButNoScheme_returnsNull() {
        // OV #7-adjacent: scheme-less strings like `foo://bar` have NO scheme
        // matching `(https?|ftp)`, so this returns null. (Note: `foo` is also
        // not in our scheme list, so this falls through.)
        assertNull(LinkDetector.firstUrlIn("see foo://bar for context"))
    }

    // --- OV #7 regex-tightening cases ---

    @Test
    fun test_threeSlashes_returnsNull() {
        // http:///foo has no host. After `://`, next char is `/`, not alphanumeric.
        assertNull(LinkDetector.firstUrlIn("see http:///foo for context"))
    }

    @Test
    fun test_openBracket_returnsNull() {
        // http://[ is malformed — `[` is not alphanumeric, so first-char rule rejects.
        assertNull(LinkDetector.firstUrlIn("see http://[ for context"))
    }

    @Test
    fun test_emptyBrackets_returnsNull() {
        // http://[] has `]` as second char but first char `[` fails alphanumeric.
        assertNull(LinkDetector.firstUrlIn("http://[]"))
    }

    @Test
    fun test_pathWithTripleSlash_returnsNull() {
        assertNull(LinkDetector.firstUrlIn("http:///path"))
    }

    // --- Additional cases for edge coverage ---

    @Test
    fun test_singleCharHost_isAccepted() {
        // RFC 1123 allows single-letter hostnames in theory. We accept
        // rather than enforce minimum length.
        assertEquals("http://a", LinkDetector.firstUrlIn("http://a"))
    }

    @Test
    fun test_ipAddressHost_accepted() {
        assertEquals(
            "http://192.168.1.1/router",
            LinkDetector.firstUrlIn("lan http://192.168.1.1/router"),
        )
    }

    @Test
    fun test_localhost_accepted() {
        assertEquals("http://localhost", LinkDetector.firstUrlIn("http://localhost"))
    }

    @Test
    fun test_httpsPort8443_accepted() {
        assertEquals(
            "https://example.com:8443",
            LinkDetector.firstUrlIn("alt https://example.com:8443"),
        )
    }

    @Test
    fun test_urlWithMultiLevelPath_accepted() {
        assertEquals(
            "https://example.com/foo/bar/baz",
            LinkDetector.firstUrlIn("deep https://example.com/foo/bar/baz"),
        )
    }

    @Test
    fun test_urlAfterNewline_accepted() {
        assertEquals(
            "https://example.com",
            LinkDetector.firstUrlIn("\nhttps://example.com"),
        )
    }

    @Test
    fun test_urlBeforeNewline_accepted() {
        assertEquals(
            "https://example.com",
            LinkDetector.firstUrlIn("https://example.com\n"),
        )
    }

    @Test
    fun test_urlInsideMarkdownAngleBracket_terminatesAtBracket() {
        // `<https://example.com>` — the `>` is excluded, so regex stops at `m`.
        assertEquals(
            "https://example.com",
            LinkDetector.firstUrlIn("<https://example.com>"),
        )
    }
}