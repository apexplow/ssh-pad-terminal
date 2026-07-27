package com.taosun.hanterm.terminal.zmodem

/**
 * Hard caps for inbound file transfers (`sz` over ZMODEM, `tsz` over trzsz).
 *
 * Issue #60 (open-source-readiness P2): before these caps, a single peer
 * could
 *  - fill `MediaStore Downloads` with arbitrary bytes via `sz /dev/zero`
 *    (no per-file size cap)
 *  - OOM the process by streaming a crafted header that never completes
 *    (no per-filter state-machine `pending` size cap)
 *
 * Both knobs are read by [ZmodemFilter] and [TrzszFilter]; we keep them in
 * one file so the cap is documented + auditable from a single grep target.
 *
 * Sizing rationale:
 *  - [MAX_DOWNLOAD_BYTES] = 100 MB / file. Largest APK in a typical
 *    Android tablet's normal workflow. Larger files split naturally via
 *    `split` on the remote side. The cap applies per-receive — a session
 *    that does receive 100 MB × N files in sequence is fine; each file
 *    resets `bytesReceived` when a new [TransferSink.begin] runs.
 *  - [MAX_PENDING_BYTES] = 64 KB state-machine buffer. Normal ZMODEM
 *    subpackets are ≤ 8 KB (`lrzsz` default) and trzsz binary frames are
 *    ≤ 10 KB (`trzsz` default); 64 KB is ~6× worst-case headroom for a
 *    mid-frame byte arrival. Anything larger is a header that never
 *    completes — hostile or buggy peer — and we abort rather than OOM.
 */
object TransferLimits {
    /** Per-file receive size cap. Applies to a single [TransferSink.begin]. */
    const val MAX_DOWNLOAD_BYTES: Long = 100L * 1024L * 1024L

    /** Filter state-machine `pending` buffer cap (input bytes accumulated
     *  while waiting for the next frame boundary). */
    const val MAX_PENDING_BYTES: Int = 64 * 1024
}