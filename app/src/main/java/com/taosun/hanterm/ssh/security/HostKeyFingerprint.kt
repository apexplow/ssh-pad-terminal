package com.taosun.hanterm.ssh.security

import java.security.PublicKey

/**
 * Module 11 / #16 — canonical host-key fingerprint computation.
 *
 * The previous design ([KnownHostsVerifier.fingerprintBase64], removed in #16)
 * hashed `key.toString().toByteArray(UTF-8)` as the fingerprint. sshj's
 * [java.security.PublicKey.toString] returns the `authorized_keys`-line format
 * ("algorithm base64-wire [comment]"), which is **not** the canonical SSH wire
 * bytes — and it can change between BouncyCastle / sshj versions, which would
 * silently invalidate every enrolled host on upgrade and produce false MITM
 * warnings.
 *
 * This module replaces that with a dedicated interface that derives the
 * fingerprint from the canonical SSH wire bytes — the same bytes that
 * `ssh-keygen -lf` reports. Stored records carry an [FingerprintResult.algorithmVersion]
 * stamp so future format migrations (e.g. swapping SHA-256 for a stronger hash)
 * can be detected and migrated deterministically.
 *
 * ## Versioning
 *
 *  - **v0** (legacy, pre-#16): fingerprint = `Base64(SHA-256(key.toString()))`,
 *    `keyType` = JCA name (`"Ed25519"`, `"RSA"`). No version stamp. Recognized
 *    by [KnownHostsStore] parsing a 4-column row and stamping
 *    `algorithmVersion = 0` on read.
 *  - **v1** (this module, the only version we currently emit):
 *    fingerprint = `Base64(SHA-256(canonical-wire-bytes))`, `keyType` = SSH
 *    algorithm name (`"ssh-ed25519"`, `"ssh-rsa"`). Recognized by
 *    [KnownHostsStore] parsing a 5-column row whose last column is `1`.
 *
 * Future versions (v2+) MUST keep the previous version readable. The version
 * stamp exists exactly so [KnownHostsVerifier] can tell "old format" rows from
 * "fresh" rows and run a deterministic migration (re-enroll with the new format)
 * rather than refusing the connection or silently corrupting the store.
 */
data class FingerprintResult(
    /**
     * SSH algorithm name (e.g. `"ssh-ed25519"`, `"ssh-rsa"`), as reported by
     * sshj's [net.schmizz.sshj.common.KeyType.toString]. NOT the JCA name
     * (`"Ed25519"`, `"RSA"`) — that was the pre-#16 behavior and would
     * mismatch the format `ssh-keygen -lf` shows.
     */
    val keyType: String,
    /**
     * Base64-encoded SHA-256 of the canonical SSH wire bytes of the
     * [PublicKey], exactly as `ssh-keygen -lf -E sha256` reports. Stable
     * across BouncyCastle and sshj versions, deterministic for the lifetime
     * of a host's key.
     */
    val fingerprintBase64: String,
    /**
     * Format version of this fingerprint. `1` for SHA-256 over canonical
     * wire bytes. `0` denotes the pre-#16 `toString()`-hashed legacy
     * format (recognized only on read by [KnownHostsStore]; never emitted
     * by [compute]).
     */
    val algorithmVersion: Int,
)

/**
 * Computes a [FingerprintResult] for a host's [PublicKey].
 *
 * [KnownHostsVerifier] delegates fingerprinting to this interface so the
 * canonical-wire-bytes algorithm lives in one module and can be exercised
 * independently of the verifier's TOFU logic. Tests inject a fake
 * implementation to keep the verifier's TOFU-state-machine tests key-agnostic.
 */
interface HostKeyFingerprint {

    /**
     * The format version this implementation emits. Bumped only when the
     * algorithm changes (e.g. switching to SHA-512). Bumping requires a
     * corresponding migration story in [KnownHostsStore] and
     * [KnownHostsVerifier] — see #16 for the v0 → v1 precedent.
     */
    val currentVersion: Int

    /**
     * Compute the fingerprint of [publicKey].
     *
     * @throws IllegalArgumentException if sshj's
     *   [net.schmizz.sshj.common.KeyType.fromKey] returns
     *   [net.schmizz.sshj.common.KeyType.UNKNOWN] — an sshj-unsupported key
     *   type is a refuse, not a crash on sshj's transport reader thread.
     */
    fun compute(publicKey: PublicKey): FingerprintResult
}
