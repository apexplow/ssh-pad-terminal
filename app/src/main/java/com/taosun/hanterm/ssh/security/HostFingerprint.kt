package com.taosun.hanterm.ssh.security

/**
 * Immutable record of a host's public-key fingerprint (Module 11 / HF-VT-01..02).
 *
 * Wire format matches `ssh-keygen -lf`'s output line so the user can cross-check
 * the in-app enrolled value against what `ssh-keyscan host` shows. The fingerprint
 * is the Base64-encoded SHA-256 of the host's public-key wire bytes; the key type
 * is the SSH algorithm name as sshj reports it (`"ssh-ed25519"`, `"ssh-rsa"`,
 * `"ecdsa-sha2-nistp256"`, ...).
 *
 * Equality is **case-sensitive** on both fields per RFC 4648 §3.4 and per the
 * ssh-keygen output convention; this matches what sshj's `PublicKey.getAlgorithm()`
 * returns and what ssh-keygen prints, so callers don't have to normalize on either
 * side.
 *
 * The class is intentionally tiny — it's a pure value type. Persistence, lookup,
 * and the sshj integration live in [KnownHostsStore] and [KnownHostsVerifier].
 */
data class HostFingerprint(
    val keyType: String,
    val fingerprintBase64: String,
)