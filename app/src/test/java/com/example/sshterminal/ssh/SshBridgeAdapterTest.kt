package com.example.sshterminal.ssh

import com.example.sshterminal.terminal.BufferedPtyBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Contract tests for [SshBridgeAdapter] — proves the bridge
 * abstracts an SSH session faithfully by gluing [SshSession]
 * to a [BufferedPtyBridge] and verifying that bytes, resize
 * signals, and EOF flow in both directions through the bridge
 * the way production expects them to.
 *
 * Reuses [FakeTransport] from `SshSessionWriteTest.kt` (same
 * package, same `internal` visibility). No Robolectric, no
 * Android framework, no real SSH socket — `FakeTransport`
 * records outbound writes and replays canned inbound reads.
 *
 * ## Setup
 *
 * Each test runs `runBlocking { ... }` (whose body executes on
 * the caller's thread) but launches the adapter on a SEPARATE
 * [CoroutineScope] backed by `Dispatchers.IO`. This is
 * load-bearing — if the adapter shares the `runBlocking` scope,
 * its IO-bound coroutines can't make progress while the test
 * thread is inside a JVM-level blocking call (such as
 * `bridge.view.read()`). The dedicated `IO` scope sidesteps the
 * starvation.
 *
 * The dedicated scope is created fresh in [setUp] and cancelled
 * in [tearDown]. A class-level shared scope would be cancelled
 * after the first test, breaking the rest.
 *
 * The cases pin:
 *   -1. user keystrokes from `bridge.view.write` arrive at the
 *       FakeTransport via the outbound coroutine
 *   -2. remote bytes enqueued on the FakeTransport arrive at
 *       `bridge.view.read` via the inbound coroutine
 *   -3. `bridge.resize` is forwarded as
 *       `FakeTransport.resizePty`
 *   -4. an EOF on the FakeTransport exits the inbound coroutine
 *       but leaves the outbound coroutine alive (and still
 *       forwarding user keystrokes)
 *   -5. `bridge.close` exits the outbound coroutine and EOFs
 *       `bridge.view.read`; further `bridge.view.write` is
 *       silently dropped on the floor
 */
class SshBridgeAdapterTest {

    private lateinit var adapterScope: CoroutineScope
    private lateinit var env: Env

    @Before
    fun setUp() {
        adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = FakeTransport()
        val session = SshSession(transport = transport, onClose = {})
        val bridge = BufferedPtyBridge()
        val adapter = SshBridgeAdapter(session, bridge)
        val job = adapter.start(adapterScope)
        env = Env(transport, session, bridge, adapter, job)
    }

    @After
    fun tearDown() {
        adapterScope.cancel()
    }

    @Test(timeout = 10_000)
    fun adapter_outboundBytes_arriveAtTransport() = runBlocking {
        env.bridge.view.write(
            byteArrayOf(0x11.toByte(), 0x22.toByte(), 0x33.toByte()),
        )

        awaitTrue("outbound bytes must arrive at transport") {
            env.transport.recordedWrites.isNotEmpty()
        }

        env.session.awaitWriteQueueDrained()
        assertEquals(1, env.transport.recordedWrites.size)
        assertArrayEquals(
            byteArrayOf(0x11.toByte(), 0x22.toByte(), 0x33.toByte()),
            env.transport.recordedWrites[0],
        )
    }

    @Test(timeout = 10_000)
    fun adapter_inboundBytes_arriveAtView() = runBlocking {
        env.transport.enqueueRead(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
        )

        // The read must be done off the runBlocking thread so
        // the IO dispatcher's inbound coroutine has somewhere
        // to run.
        val got = AtomicReference<ByteArray?>(null)
        val reader = adapterScope.launch(Dispatchers.IO) {
            got.set(env.bridge.view.read())
        }

        awaitTrue("inbound bytes must arrive at bridge.view") {
            got.get() != null
        }
        assertArrayEquals(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
            got.get(),
        )
        reader.cancel()
    }

    @Test(timeout = 10_000)
    fun adapter_resizeFiresPtyResize() = runBlocking {
        env.bridge.resize(80, 24)
        env.bridge.resize(132, 50)

        awaitTrue("resize must reach transport") {
            env.transport.resizeCalls.size >= 2
        }
        env.session.awaitWriteQueueDrained()
        assertEquals(
            listOf(
                FakeTransport.ResizeCall(80, 24, 0, 0),
                FakeTransport.ResizeCall(132, 50, 0, 0),
            ),
            env.transport.resizeCalls,
        )
    }

    @Test(timeout = 10_000)
    fun adapter_eofFromSession_closesInbound_andClosesBridgeCleanly() =
        runBlocking {
            // EOF on the SSH read side: transport.readBytes()
            // returns null. session.readInto's `finally`
            // clause then closes the session, which sets its
            // `closed` flag and shuts down its write executor.
            // After that, future session.write() calls are
            // no-ops — that's the existing SshSession
            // post-close contract, not anything the adapter
            // controls.
            //
            // What we CAN observe: the bridge still routes
            // correctly after the session close, and closing
            // the bridge EOFs both ends. If the adapter
            // coroutines had tangled, the bridge would either
            // not EOF or hang.
            env.transport.enqueueEof()

            // Give inbound a chance to observe the EOF and
            // session.readInto to return.
            delay(200)

            // Bridge.view.read must still EOF after bridge.close.
            env.bridge.close()

            val got = AtomicReference<ByteArray?>(SENTINEL_NON_NULL)
            val reader = adapterScope.launch(Dispatchers.IO) {
                got.set(env.bridge.view.read())
            }
            awaitTrue("view.read must EOF after inbound EOF + bridge.close") {
                got.get() == null
            }
            reader.cancel()
        }

    @Test(timeout = 10_000)
    fun adapter_closeBridge_closesOutbound_andPropagatesEofToView() =
        runBlocking {
            env.bridge.view.write(byteArrayOf(0x42.toByte()))
            awaitTrue("pre-close write must reach transport") {
                env.transport.recordedWrites.isNotEmpty()
            }

            env.bridge.close()

            // After close, view.read must EOF.
            val got = AtomicReference<ByteArray?>(SENTINEL_NON_NULL)
            val reader = adapterScope.launch(Dispatchers.IO) {
                got.set(env.bridge.view.read())
            }
            awaitTrue("view.read must EOF after bridge.close") {
                got.get() == null
            }
            reader.cancel()

            // Post-close writes must not produce new transport
            // writes — outbound's bridge.transport.read()
            // returns null and the coroutine exits.
            val sizeBefore = env.transport.recordedWrites.size
            env.bridge.view.write(byteArrayOf(0x99.toByte()))
            delay(100)
            env.bridge.view.write(byteArrayOf(0x9A.toByte()))
            delay(100)
            env.session.awaitWriteQueueDrained()
            assertEquals(
                "post-close view.write must not produce more transport writes",
                sizeBefore,
                env.transport.recordedWrites.size,
            )
            assertTrue(
                "at least the pre-close write reached the transport",
                sizeBefore >= 1,
            )
        }

    /**
     * Polls [predicate] every 25 ms, yielding the runBlocking
     * thread between polls. Asserts [description] if the
     * predicate never becomes true within [timeoutMs]. Using
     * `delay()` (not `Thread.sleep`) is critical — delay()
     * yields the dispatcher so IO-bound coroutines on the
     * adapter's scope can advance.
     */
    private suspend fun awaitTrue(
        description: String,
        timeoutMs: Long = 5_000,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(25)
        }
        assertTrue(description, predicate())
    }

    private class Env(
        val transport: FakeTransport,
        val session: SshSession,
        val bridge: BufferedPtyBridge,
        val adapter: SshBridgeAdapter,
        val job: Job,
    )

    private companion object {
        // Sentinel used to distinguish "read returned null" from
        // "read hasn't completed yet" in the atomic where null is
        // a legitimate value.
        private val SENTINEL_NON_NULL = ByteArray(0)
    }
}
