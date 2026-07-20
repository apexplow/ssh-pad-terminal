package com.taosun.hanterm.terminal

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process [PtyBridge] baseline.
 *
 * Two independent [PtyEndpoint]s sit on top of two independent
 * [LinkedBlockingQueue]s:
 *
 * ```
 *   transport.write(bytes)  ──►  transportToView  ──►  view.read()
 *        view.write(bytes)  ──►  viewToTransport  ──►  transport.read()
 * ```
 *
 * Bytes sent through one end appear at the read of the other end
 * — never at the writer's own end. The two streams are
 * independent; closing one does not affect the other (only the
 * single global [close] shuts down both).
 *
 * ## Why a v1 baseline at all
 *
 *   1. The contract is hard to discover by writing a native impl
 *      first. A pure-Java reference gives a `git diff`-able
 *      specification the future impl must satisfy.
 *   2. Testability. The [PtyBridgeTest] cases run as plain JUnit —
 *      no Robolectric, no native deps, no emulator — because
 *      this impl doesn't need any of them.
 *
 * ## Mechanics
 *
 * Each queue holds either a [ByteArray] chunk or the singleton
 * [EOF] marker (compared by reference). The reader's
 * [PtyEndpoint.read] does `queue.take()`: a [ByteArray] is
 * returned as-is; the [EOF] marker sets a per-stream
 * [AtomicBoolean] latch (so subsequent reads short-circuit to
 * `null` without blocking on an empty queue — the
 * "null-stays-null" contract) and returns `null`.
 *
 * The `null-on-EOF` read shape deliberately mirrors
 * [com.taosun.hanterm.ssh.SshTransport.readBytes] —
 * `bytes ?: break` in the read loop works the same way against
 * either transport.
 *
 * Empty writes are silent no-ops, matching
 * [com.taosun.hanterm.ssh.SshSession.write] (`ssh/SshSession.kt:100`).
 *
 * [close] is idempotent (`AtomicBoolean.compareAndSet`) and
 * thread-safe. The EOF sentinel is enqueued into BOTH queues
 * exactly once, under a single lock shared by every writer on
 * both streams. The lock guarantees a writer's `queue.put(bytes)`
 * either completes before the relevant queue's `put(EOF)` (the
 * reader sees the chunk, then EOF, then `null`) or never runs
 * at all (the writer saw `closed=true` on entry). No byte is
 * ever stranded behind the EOF sentinel.
 *
 * The queue is unbounded in v1. The contract test sizes are
 * small enough that no writer ever blocks, and unbounded
 * sidesteps the "close()-during-full-queue blocks" hazard that
 * a bounded queue would introduce. A future v2 production
 * impl can add a `capacity` parameter without changing the
 * contract.
 *
 * ## Thread safety
 *
 * Every method is safe to call from any thread. The reader on
 * each end is expected to be a single thread; the writer side
 * supports concurrent callers (mirrors sshj's `Channel.outputStream`,
 * which is `synchronized` internally).
 */
class BufferedPtyBridge : PtyBridge {

    private val transportToViewQueue: LinkedBlockingQueue<Any> = LinkedBlockingQueue()
    private val viewToTransportQueue: LinkedBlockingQueue<Any> = LinkedBlockingQueue()

    // Set by the reader that observes the EOF sentinel on each
    // stream. Subsequent reads short-circuit to null instead of
    // blocking on an empty queue — the "null stays null" contract.
    private val eofReachedOnViewSide: AtomicBoolean = AtomicBoolean(false)
    private val eofReachedOnTransportSide: AtomicBoolean = AtomicBoolean(false)

    // Global close gate. Once flipped, every subsequent write
    // (on either end) is a no-op.
    private val closed: AtomicBoolean = AtomicBoolean(false)

    // Serializes (a) the writer's closed-check + put on either
    // stream and (b) the closer's compareAndSet + put(EOF) on
    // both queues. One shared lock is enough for v1 because the
    // 20-test contract doesn't exercise backpressure; a future
    // production impl can split into per-stream locks if needed.
    private val closeLock: Any = Any()

    @Volatile private var lastCols: Int = 0
    @Volatile private var lastRows: Int = 0
    private val resizeListener: AtomicReference<((Int, Int) -> Unit)?> =
        AtomicReference(null)

    override val view: PtyEndpoint = Endpoint(
        readQueue = transportToViewQueue,
        writeQueue = viewToTransportQueue,
        eofFlag = eofReachedOnViewSide,
    )

    override val transport: PtyEndpoint = Endpoint(
        readQueue = viewToTransportQueue,
        writeQueue = transportToViewQueue,
        eofFlag = eofReachedOnTransportSide,
    )

    /**
     * One of the two symmetric [PtyEndpoint]s. Read and write
     * go to *different* queues because the endpoint sits on the
     * far side of the bridge — view.read and transport.write
     * share `transportToViewQueue`, view.write and transport.read
     * share `viewToTransportQueue`.
     */
    private inner class Endpoint(
        private val readQueue: LinkedBlockingQueue<Any>,
        private val writeQueue: LinkedBlockingQueue<Any>,
        private val eofFlag: AtomicBoolean,
    ) : PtyEndpoint {
        override fun read(): ByteArray? {
            // Short-circuit once EOF has been observed. The
            // queue is empty after EOF, so queue.take() would
            // block forever; the per-stream latch is what makes
            // the "null stays null" contract work across
            // multiple read() calls.
            if (eofFlag.get()) return null
            val item = readQueue.take()
            return when {
                item === EOF -> {
                    eofFlag.set(true)
                    null
                }
                else -> item as ByteArray
            }
        }

        override fun write(bytes: ByteArray) {
            // Empty writes are silent no-ops — the contract on
            // PtyEndpoint doesn't promise they'll reach the wire,
            // and queueing a zero-byte chunk would just consume
            // a slot for no information.
            if (bytes.isEmpty()) return
            synchronized(closeLock) {
                // Re-check under the lock so a close() that
                // races us still wins: writers that lost the
                // race become no-ops, not late enqueues. The
                // lock is what guarantees each queue never
                // sees a chunk after the EOF marker.
                if (closed.get()) return
                writeQueue.put(bytes)
            }
        }

        override fun close() {
            this@BufferedPtyBridge.close()
        }
    }

    override fun resize(cols: Int, rows: Int) {
        if (closed.get()) return
        lastCols = cols
        lastRows = rows
        resizeListener.get()?.invoke(cols, rows)
    }

    override fun setResizeListener(listener: ((Int, Int) -> Unit)?) {
        resizeListener.set(listener)
        // Mirror TerminalView.setPtyResizeListener's "fire once
        // on registration" behavior: a freshly-bound transport
        // gets the current size rather than waiting for the next
        // layout pass.
        if (listener != null && lastCols > 0 && lastRows > 0) {
            listener.invoke(lastCols, lastRows)
        }
    }

    override fun close() {
        synchronized(closeLock) {
            if (closed.compareAndSet(false, true)) {
                // Put EOF into BOTH queues under the lock.
                // Every concurrent writer has either already
                // finished its put() (its chunk will be drained
                // before the reader sees the sentinel) or
                // hasn't yet entered write() (it will see
                // closed=true on entry and return).
                transportToViewQueue.put(EOF)
                viewToTransportQueue.put(EOF)
            }
        }
    }

    private companion object {
        /**
         * Singleton EOF marker. Compared by reference (`===`),
         * so a legitimate zero-byte payload (which the contract
         * already forbids as a no-op) could not collide even if
         * it slipped through. The same object is enqueued into
         * both queues — it's just an identity tag, not data.
         */
        private val EOF: Any = Any()
    }
}
