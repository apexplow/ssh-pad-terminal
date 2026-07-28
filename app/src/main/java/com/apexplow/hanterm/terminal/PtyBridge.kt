package com.apexplow.hanterm.terminal

/**
 * A bidirectional "PTY-shaped" channel between two ends of a terminal
 * session: the *view* (the Termux emulator + IME chain the user sees
 * and types into) and the *transport* (whatever sits on the other
 * side — an SSH channel today, a mosh client later, a local shell
 * eventually).
 *
 * The shape is deliberately Unix-PTY-flavoured:
 *   - a full-duplex byte stream in each direction, exposed as two
 *     symmetric [PtyEndpoint]s: [view] and [transport];
 *   - a [resize] signal that conveys cols/rows changes;
 *   - an idempotent [close] that unblocks every pending [PtyEndpoint.read]
 *     with `null` (EOF) on both ends.
 *
 * The contract intentionally does **not** require implementations to
 * be backed by an actual kernel PTY. The v1 in-process
 * [BufferedPtyBridge] uses two [java.util.concurrent.LinkedBlockingQueue]s;
 * a future NDK-backed `pty(7)` implementation can sit behind the same
 * interface without changing callers.
 *
 * ## The two ends
 *
 * Both [view] and [transport] are full-duplex [PtyEndpoint]s. Each
 * has [PtyEndpoint.read], [PtyEndpoint.write], and [PtyEndpoint.close].
 *
 * The **view** side reads bytes the transport sent (remote output)
 * and writes bytes the user produced (keystrokes, IME commits,
 * paste). Pseudocode:
 *
 * ```
 * view.read()       // returns remote output bytes
 * view.write(bytes) // sends keystrokes / IME commits to transport
 * ```
 *
 * The **transport** side writes bytes the remote sent (so the view
 * can read them) and reads bytes the user produced (so it can
 * forward them across the wire). Pseudocode:
 *
 * ```
 * transport.write(bytes) // pushes remote output to be view.read()'d
 * transport.read()       // drains user keystrokes to send upstream
 * ```
 *
 * The two [PtyEndpoint]s are inverse views of the same two byte
 * streams:
 *
 * ```
 * transport.write(bytes)  ──►  view.read()
 *   view.write(bytes)  ──►  transport.read()
 * ```
 *
 * A byte sent into one end appears at the read of the other end —
 * never at its own end. They are *not* loopback.
 *
 * ## Resize
 *
 * [resize] is a single cross-cutting signal. The view calls it
 * (because the view is what knows the font metrics and therefore
 * the rows/cols). Whoever wants to react — typically the adapter
 * — registers a listener via [setResizeListener] and forwards it
 * to the transport (e.g. `SshSession.resizePty`).
 *
 * ## Threading
 *
 * Every method is safe to call from any thread. The reader on
 * each end is expected to be a single thread; the writer side
 * supports concurrent callers.
 *
 * ## Why this exists
 *
 * Today the Termux emulator is wired directly to
 * [com.apexplow.hanterm.ssh.SshSession] at
 * `ui/TerminalPane.kt:81, 120-123` — there is no seam where a
 * local "shell emitting TTY-shaped bytes" could slot in. That
 * seam is what mosh, a local `bash`, or any other child-process
 * transport needs. [PtyBridge] is the missing middle.
 *
 * ## Future wiring (step 2b)
 *
 * This commit ships the building blocks. Two seam points, both
 * already in the codebase, are where the wiring lands:
 *
 * 1. `ui/TerminalPane.kt:120-123` — a future `BridgeReadLoop` will
 *    drain [PtyEndpoint.read] on `bridge.view` into
 *    `emulator.append(bytes, bytes.size)`, replacing the current
 *    `session.readInto { ... }` call.
 * 2. `terminal/TerminalView.kt:706` (`setPtyResizeListener`) — a
 *    future wiring will register `bridge.resize(cols, rows)` so
 *    the adapter can pick the cols/rows up and forward to
 *    `SshSession.resizePty`.
 *
 * Neither seam is touched in this commit.
 */
interface PtyBridge {

    /** The view (presentation) end. See class kdoc. */
    val view: PtyEndpoint

    /** The transport end. See class kdoc. */
    val transport: PtyEndpoint

    /**
     * Forwards a cols/rows change to whoever is listening. Typical
     * caller is the view (when the font size / layout changes).
     * Typical listener is the adapter, which translates the signal
     * into whatever the transport needs (sshj
     * `changeWindowDimensions`, mosh resize frame, kernel `TIOCSWINSZ`,
     * etc.).
     *
     * Coalescing: callers MAY invoke this on every layout pass; v1
     * stores only the last value.
     */
    fun resize(cols: Int, rows: Int)

    /**
     * Registers a single resize listener (or `null` to detach).
     * The shape mirrors [com.apexplow.hanterm.terminal.TerminalView.setPtyResizeListener]
     * (`terminal/TerminalView.kt:706`): one slot, last-writer wins,
     * and on registration the listener is fired once with the most
     * recent known size so a freshly-bound transport never has to
     * wait for the next layout pass.
     */
    fun setResizeListener(listener: ((Int, Int) -> Unit)?)

    /**
     * Closes both ends. Idempotent — safe to call from either
     * thread, safe to call multiple times, and equivalent to
     * `view.close()` or `transport.close()`. After close:
     *   - every pending [PtyEndpoint.read] on either end returns
     *     `null` and stays `null`;
     *   - every subsequent [PtyEndpoint.write] on either end is a
     *     silent no-op;
     *   - a subsequent [resize] is a silent no-op.
     */
    fun close()
}

/**
 * One end of a [PtyBridge]. Both [PtyBridge.view] and
 * [PtyBridge.transport] are [PtyEndpoint]s, so the view's read /
 * transport's write pair share a queue, and the transport's read
 * / view's write pair share another.
 *
 * The contract deliberately mirrors
 * [com.apexplow.hanterm.ssh.SshTransport] (the four-method
 * interface that backs [com.apexplow.hanterm.ssh.SshSession]):
 * [read] uses the same null-on-EOF shape as
 * `SshTransport.readBytes`, and [write] applies the same
 * empty-write-is-no-op rule as `SshSession.write`
 * (`ssh/SshSession.kt:100`). That symmetry is what lets the
 * future `SshBridgeAdapter` substitute `bridge.transport.read()`
 * for `transport.readBytes()` line for line.
 */
interface PtyEndpoint {
    /**
     * Blocks until at least one byte is available, then returns
     * up to ~8 KiB. Returns `null` on EOF — once `null` is observed,
     * every subsequent call also returns `null` (the bridge does
     * not reopen, so a second `read()` would otherwise block
     * forever on an empty queue).
     */
    fun read(): ByteArray?

    /**
     * Writes bytes that the *other* end will read. Empty writes
     * are silent no-ops (mirrors [com.apexplow.hanterm.ssh.SshSession.write]),
     * and post-close writes are also no-ops.
     */
    fun write(bytes: ByteArray)

    /**
     * Closes the bridge. Idempotent. Equivalent to calling
     * [PtyBridge.close]. See [PtyBridge.close] for the
     * post-close contract.
     */
    fun close()
}
