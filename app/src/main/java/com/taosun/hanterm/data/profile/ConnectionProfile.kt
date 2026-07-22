package com.taosun.hanterm.data.profile

/**
 * Durable single-host connection picture and credential lifecycle.
 *
 * Lifetime = process (one instance from [ConnectionProfiles.create], shared by
 * the ViewModel and ConfigScreen). Live draft editing stays in Compose; this
 * module owns load / save / connect-prep / import / forget / clear.
 *
 * ## Password semantics
 *
 * | Intent | empty draft.password | non-empty draft.password |
 * |---|---|---|
 * | [save] | KEEP blob | encrypt → replace blob |
 * | [prepareConnect] | KEEP blob; Auth from blob or key | encrypt → replace; Auth from draft plaintext |
 * | [clearStoredPassword] | wipe blob | — |
 * | [clearAll] | wipe blob + clear fields | — |
 */
interface ConnectionProfile {
    fun load(): ProfileSnapshot

    /** Explicit Save. Empty password keeps the stored blob. */
    fun save(draft: ConnectionDraft): SaveOutcome

    /**
     * Connect path: persist non-empty draft fields (same side effect as today's
     * Connect), then materialize [ConnectPrepared]. Empty password keeps the
     * blob. When [draft.password] is non-empty, Auth is built from that
     * plaintext (no encrypt→decrypt round-trip).
     */
    suspend fun prepareConnect(draft: ConnectionDraft): Result<ConnectPrepared>

    fun clearStoredPassword()

    /**
     * Clears connection fields and the password blob. Does **not** wipe
     * fontSize / migration flags. Returns a blank draft for the UI.
     */
    fun clearAll(): ConnectionDraft

    /** Encrypts and stores the PEM; returns the normalized safe name. */
    fun importKey(displayName: String, bytes: ByteArray): Result<String>

    /** Removes TOFU enrollment for [host]:[port]. */
    suspend fun forgetHost(host: String, port: Int)
}
