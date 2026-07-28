package com.apexplow.hanterm.ssh.security

/**
 * Immutable record of a host's public-key fingerprint (Module 11 / HF-VT-01..02).
 *
 * Wire format matches `ssh-keygen -lf -E sha256`'s output line so the user can
 * cross-check the in-app enrolled value against what `ssh-keyscan host` shows.
 * The fingerprint is the Base64-encoded SHA-256 of the host's public-key
 * **canonical SSH wire bytes** (derived via [HostKeyFingerprint.compute]); the
 * key type is the SSH algorithm name (`"ssh-ed25519"`, `"ssh-rsa"`,
 * `"ecdsa-sha2-nistp256"`, ...).
 *
 * Equality is **case-sensitive** on every field per RFC 4648 §3.4 and per the
 * ssh-keygen output convention; this matches what sshj's `KeyType.toString()`
 * returns and what `ssh-keygen -lf` prints, so callers don't have to normalize
 * on either side.
 *
 * The class is intentionally tiny — it's a pure value type. Persistence, lookup,
 * and the sshj integration live in [KnownHostsStore] and [KnownHostsVerifier].
 *
 * ## Format versions (issue #16)
 *
 * The pre-#16 code did **not** carry a version stamp, but [KnownHostsStore]
 * now persists [algorithmVersion] so the on-disk row is forward-migratable.
 * See [HostKeyFingerprint] for the v0 / v1 / future-version semantics.
 *
 *  - `0` — legacy, pre-#16, never written by current code. Only seen when
 *    [KnownHostsStore] parses a 4-column row written by an older build.
 *  - `1` — SHA-256 of canonical wire bytes. The only version
 *    [HostKeyFingerprint.compute] emits.
 */
data class HostFingerprint(
    val keyType: String,
    val fingerprintBase64: String,
    /**
     * Format version of the stored fingerprint. `1` for current
     * (SHA-256 / wire-bytes); `0` for the legacy pre-#16 `toString()`-hashed
     * format recognized only on read.
     */
    val algorithmVersion: Int,
)