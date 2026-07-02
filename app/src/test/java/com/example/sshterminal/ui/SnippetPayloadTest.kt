package com.example.sshterminal.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JUnit coverage for [buildSnippetPayload] — Sprint 3 / Module 16 /
 * SNP-TS-02.
 *
 * Mirrors the byte-level shape of [com.example.sshterminal.terminal.KeyEventRoutingTest]'s
 * `test_ctrlA_writesSohByte` so the project's existing "assert a specific
 * byte went out" pattern is preserved. No Robolectric, no `TerminalEndpoint`
 * fake — the byte-array contract is fully self-contained.
 */
class SnippetPayloadTest {

    @Test
    fun snp_ts_02_appendNewline_false_returnsUtf8BytesOnly() {
        // SNP-SEND-01: appendNewline=false must NOT append any extra bytes.
        val payload = buildSnippetPayload("ll", appendNewline = false)
        assertArrayEquals(
            "appendNewline=false must yield exactly the UTF-8 bytes of the command",
            byteArrayOf(0x6C, 0x6C), // 'l', 'l'
            payload,
        )
    }

    @Test
    fun snp_ts_02_appendNewline_true_appendsCarriageReturn() {
        // SNP-SEND-02: appendNewline=true must append a SINGLE \r (0x0D),
        // not \n (0x0A). The spec is explicit: '\r' matches the existing
        // KEYCODE_ENTER mapping (KM-KC-02). Pinning 0x0D here means a
        // future refactor that "fixes" it to \n will fail this test.
        val payload = buildSnippetPayload("ll", appendNewline = true)
        assertArrayEquals(
            "appendNewline=true must append CR (0x0D), not LF (0x0A)",
            byteArrayOf(0x6C, 0x6C, 0x0D), // 'l', 'l', CR
            payload,
        )
    }

    @Test
    fun snp_ts_02_multibyteUtf8_preservesBytesAndAppendsCr() {
        // A Chinese command exercises the UTF-8 path: each CJK char is 3
        // bytes in UTF-8, and the payload must NOT corrupt them on the way
        // through Charsets.UTF_8 encoding.
        val payload = buildSnippetPayload("你好", appendNewline = true)
        val expected = "你好".toByteArray(Charsets.UTF_8) + "\r".toByteArray(Charsets.UTF_8)
        assertEquals(
            "CJK command should be 6 UTF-8 bytes + 1 CR = 7 bytes total",
            7,
            payload.size,
        )
        assertArrayEquals(expected, payload)
    }

    @Test
    fun snp_ts_02_emptyCommand_appendNewline_true_returnsJustCr() {
        // Edge case: empty command + appendNewline=true → a lone CR. The
        // spec doesn't forbid this; the user might intentionally bind the
        // Snippet button to "send Enter" by leaving the command blank.
        val payload = buildSnippetPayload("", appendNewline = true)
        assertArrayEquals(
            "empty command + appendNewline=true must produce a single CR byte",
            byteArrayOf(0x0D),
            payload,
        )
    }
}