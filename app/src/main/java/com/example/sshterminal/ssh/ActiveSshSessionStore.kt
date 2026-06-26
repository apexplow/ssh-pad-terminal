package com.example.sshterminal.ssh

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-scoped holder for the currently live [SshSession].
 *
 * ## Why a process-scoped holder
 *
 * The SSH session lives on a long-lived socket inside sshj, kept alive
 * across Activity recreation by [SshKeepAliveService]. When the process
 * survives but the Activity does not (config change we don't handle in
 * the manifest, OR — the more common case on a tablet — Android
 * recreating MainActivity because of multi-window mode transitions we
 * didn't enumerate), Compose's `remember` resets to its initial value
 * and `SshTermApp` re-renders the login page even though the SSH
 * session is still alive on the wire.
 *
 * The Bundle (`rememberSaveable`) is the wrong place for the live
 * [SshSession]: sshj channels aren't `Parcelable`, can't be serialized
 * safely (the transport holds an open socket and a background reader
 * thread), and there is no benefit to round-tripping them through
 * `onSaveInstanceState` when the process and the socket are both still
 * alive.
 *
 * A ViewModel is the wrong level of scope too: it survives Activity
 * recreation, but if Android kills the process (low memory, user
 * swipe-away from recents) the ViewModel dies with it. The SSH session,
 * however, may still be alive on the socket — a ViewModel-only design
 * would tell the user "session lost" when really we just lost our
 * in-process reference to it.
 *
 * What we want: stash the live reference in something that outlives
 * the Activity but shares the process's lifetime. The keepalive
 * service promotes the process into the "perceptible" priority
 * bucket, so the process rarely dies while the user is actively using
 * the app — when it does (rare process kill), the store is empty and
 * we fall back to the login page, which is the correct UX.
 *
 * The companion-object singleton ([AtomicReference] for thread-safe
 * publication) gives us process-wide uniqueness for free.
 */
object ActiveSshSessionStore {

    private val current = AtomicReference<SshSession?>(null)

    /**
     * Set the active session, replacing any previous one. Callers should
     * pass the [SshSession] returned by a successful [SshClient.connect]
     * — the store deliberately accepts any [SshSession] (the constructor
     * is internal, so production callers go through [SshClient] only).
     */
    fun set(session: SshSession) {
        current.set(session)
    }

    /**
     * Read the active session, or `null` if no session is live. Called
     * once on first composition of [com.example.sshterminal.ui.SshTermApp]
     * to re-attach to a session that survived Activity recreation.
     */
    fun get(): SshSession? = current.get()

    /**
     * Clear the active session. Idempotent: safe to call from every
     * disconnect path (BackHandler double-tap, Disconnect button,
     * `onSessionClosed` error handler, etc.) without coordinating which
     * one wins the race. Subsequent [get] returns `null`.
     */
    fun clear() {
        current.set(null)
    }
}
