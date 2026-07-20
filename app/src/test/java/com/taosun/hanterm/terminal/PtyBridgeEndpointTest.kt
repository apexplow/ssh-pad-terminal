package com.taosun.hanterm.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract tests for [PtyBridgeEndpoint] — the [TerminalEndpoint]
 * adapter that sits between `TerminalView`'s IME chain and
 * [PtyBridge]'s view side.
 *
 * Plain JUnit (the adapter is pure-Kotlin; no Android framework).
 * The cases pin:
 *   - byte forwarding from `endpoint.write` to `bridge.view.read`
 *     on the OTHER end of the bridge (not loopback)
 *   - empty bytes are silently dropped (matches
 *     [com.taosun.hanterm.ssh.SshSession.write]'s contract)
 *   - post-close writes are silent no-ops (the bridge, not the
 *     adapter, enforces this — the adapter is a one-line shim)
 */
class PtyBridgeEndpointTest {

    @Test
    fun write_forwardsToBridgeView_andTransportCanRead() {
        val bridge = BufferedPtyBridge()
        val endpoint = PtyBridgeEndpoint(bridge)
        endpoint.write(byteArrayOf(1, 2, 3))
        // The bytes that `endpoint.write` produced must appear at
        // the *transport* end of the bridge (i.e., the bytes
        // travel through the bridge as if they came from the
        // user). If they appeared at view.read instead, the
        // adapter would be looping back and the wiring would
        // be broken.
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            bridge.transport.read(),
        )
        bridge.close()
    }

    @Test
    fun emptyWrite_isSilentNoOp() {
        val bridge = BufferedPtyBridge()
        val endpoint = PtyBridgeEndpoint(bridge)
        endpoint.write(ByteArray(0))
        // Empty write must not enqueue anything. Closing and
        // reading both sides must yield EOF without any payload
        // having crossed the bridge.
        bridge.close()
        assertNull(bridge.transport.read())
        assertNull(bridge.view.read())
    }

    @Test
    fun writeAfterBridgeClose_isNoOp() {
        val bridge = BufferedPtyBridge()
        val endpoint = PtyBridgeEndpoint(bridge)
        bridge.close()
        // Post-close write must not throw, must not queue.
        endpoint.write(byteArrayOf(1, 2, 3))
        endpoint.write(byteArrayOf(4, 5, 6))
        assertNull(bridge.transport.read())
    }
}
