package com.taosun.hanterm.data.profile

/**
 * Live form edit state for a [ConnectionProfile].
 *
 * [password] holds only newly typed plaintext during the editing session.
 * After [ConnectionProfile.load] or a successful [ConnectionProfile.save] it is
 * always empty — never a decrypted copy of the stored blob.
 */
data class ConnectionDraft(
    val host: String,
    val port: String,
    val username: String,
    val password: String,
    val privateKeyName: String,
)

/** Result of [ConnectionProfile.load]: editable draft plus stored-password flag. */
data class ProfileSnapshot(
    val draft: ConnectionDraft,
    val hasStoredPassword: Boolean,
)

/** Result of [ConnectionProfile.save] for the UI to adopt. */
data class SaveOutcome(
    val draftForUi: ConnectionDraft,
    val hasStoredPassword: Boolean,
)

/**
 * Output of [ConnectionProfile.prepareConnect] — ready for
 * [com.taosun.hanterm.ssh.ConnectionRuntime.connect].
 */
data class ConnectPrepared(
    val host: String,
    val port: Int,
    val username: String,
    val auth: com.taosun.hanterm.ssh.auth.Auth,
)
