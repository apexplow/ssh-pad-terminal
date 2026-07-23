package com.taosun.hanterm.ssh.security

import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * TOFU host-key verifier wired into sshj (Module 11 / KHV-VF-01..06, #16).
 *
 * ## Algorithm
 *
 * sshj invokes [verify] exactly once per host during the SSH handshake, after
 * the TCP+SSH-banner dance and before key-exchange. We get the host name,
 * port, and the raw [PublicKey] the server presented; we need to answer `true`
 * (accept) or `false` (refuse + leave the connection broken).
 *
 *  1. Compute the presented fingerprint via the injected [fingerprint]
 *     module — production = [CanonicalHostKeyFingerprint], which derives
 *     `Base64(SHA-256(canonical-wire-bytes))` from sshj's
 *     [net.schmizz.sshj.common.KeyType.putPubKeyIntoBuffer] encoding. The
 *     resulting [FingerprintResult] is the format `ssh-keygen -lf -E sha256`
 *     prints, stable across BouncyCastle / sshj versions.
 *  2. Look up `(host, port)` in [KnownHostsStore].
 *  3. **No record** → first-use path. If a [prompt] is wired, ask the user
 *     first (KHV-UX-02); a decline fails closed (`false`), nothing is
 *     written. Otherwise (no prompt — every existing test, and the
 *     original TOFU-only behavior) write the fingerprint and return `true`
 *     (KHV-VF-02) without asking. Either way, if the disk write fails, we
 *     fail closed (return `false`).
 *  4. **Stored row matches the current format** → return `true`
 *     (KHV-VF-03). Never prompts — this is the common case on every
 *     connect after the first.
 *  5. **Legacy v0 row** (algorithmVersion = 0, written by a pre-#16 build) →
 *     confirm the same key is being presented by recomputing the *old*
 *     `Base64(SHA-256(key.toString()))` fingerprint + JCA keyType. If
 *     both match, silently rewrite as v1 in place. Otherwise fall through
 *     to the mismatch branch. This is the only "auto-migrate" path — the
 *     pre-#16 fingerprint cannot byte-equal a v1 wire-byte hash, so a
 *     direct equality check is meaningless.
 *  6. **Any other mismatch** (key type, fingerprint, or both) → refuse by
 *     default (KHV-VF-04). A key-type change is treated as a mismatch
 *     too (KHV-VF-05). If a [prompt] is wired, the user can explicitly
 *     re-trust the new key (KHV-UX-02); approving overwrites the store
 *     with the new fingerprint. Without a prompt, the store is left
 *     untouched and the user must manually "Forget this host" in Settings
 *     to re-enroll.
 *
 * ## Threading
 *
 * sshj calls [verify] from its transport reader thread. The store
 * operations are suspend, so we hop into [runBlocking] here — the reader
 * thread is short-lived per-connect and the store lock is fast. The
 * alternative (refactoring sshj's verifier API to be suspending) is a
 * much bigger surgery for no real benefit.
 */
class KnownHostsVerifier(
    private val store: KnownHostsStore,
    private val host: String,
    private val port: Int,
    /**
     * Optional interactive gate (KHV-UX-02). `null` preserves the original
     * silent TOFU behavior — auto-accept first use, auto-reject mismatch —
     * which is what every pre-existing test relies on.
     */
    private val prompt: HostKeyPrompt? = null,
    /**
     * Fingerprint module. Defaulted to [CanonicalHostKeyFingerprint] for
     * production; tests inject a fake to keep TOFU-state-machine assertions
     * key-agnostic (see [KnownHostsVerifierTest]).
     */
    private val fingerprint: HostKeyFingerprint = CanonicalHostKeyFingerprint(),
) : HostKeyVerifier {

    override fun verify(
        hostname: String,
        port: Int,
        key: PublicKey,
    ): Boolean {
        val presented = runCatching { fingerprint.compute(key) }.getOrNull() ?: return false
        val existing = runBlocking { store.get(host, port) }
        return when {
            existing == null -> {
                if (prompt != null && !askToTrust(presented, previous = null)) {
                    return false
                }
                // First-use: enroll, accept. If the disk write fails, fail
                // closed so we don't silently accept a host we couldn't record.
                runBlocking {
                    runCatching {
                        store.put(host, port, presented.toHostFingerprint())
                    }.isSuccess
                }
            }
            // v1 exact match — common case, never prompts.
            existing.algorithmVersion == fingerprint.currentVersion &&
                existing == presented.toHostFingerprint() -> true

            // Legacy v0 row (pre-#16, `toString()`-hashed): the stored
            // fingerprintBase64 was computed the OLD way and therefore can
            // never byte-equal a v1 wire-byte hash. Confirm it's the same
            // key by recomputing the OLD fingerprint + JCA keyType; if both
            // match, silently rewrite as v1 in place. This is the only
            // honest "auto-migrate" path.
            existing.algorithmVersion == 0 &&
                existing.fingerprintBase64 == legacyFingerprint(key) &&
                existing.keyType == legacyKeyType(key) -> {
                runBlocking {
                    runCatching {
                        store.put(host, port, presented.toHostFingerprint())
                    }.isSuccess
                }
            }

            // Everything else (keyType change, fingerprint change, both):
            // KHV-VF-04 / KHV-VF-05. Prompt (re-enroll) if wired, else refuse.
            else -> {
                if (prompt != null && askToTrust(presented, previous = existing)) {
                    runBlocking {
                        runCatching {
                            store.put(host, port, presented.toHostFingerprint())
                        }.isSuccess
                    }
                } else {
                    false
                }
            }
        }
    }

    /** Blocks (via [runBlocking], on whatever thread sshj calls [verify] from) until [prompt] answers. */
    private fun askToTrust(presented: FingerprintResult, previous: HostFingerprint?): Boolean {
        val p = prompt ?: return false
        return runBlocking {
            p.confirm(
                HostKeyPromptRequest(
                    host = host,
                    port = port,
                    keyType = presented.keyType,
                    fingerprintBase64 = presented.fingerprintBase64,
                    previousFingerprint = previous,
                ),
            )
        }
    }

    /**
     * sshj 0.38's [HostKeyVerifier] has a second abstract method that asks
     * which algorithms the verifier already trusts for `(host, port)`. We
     * surface whatever's in our store — on first use this returns the
     * algorithm of the just-enrolled host, otherwise the algorithm of the
     * stored row.
     */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        val existing = runBlocking { store.get(host, port) } ?: return emptyList()
        return listOf(existing.keyType)
    }

    /**
     * The pre-#16 fingerprint algorithm — used **only** to confirm a
     * legacy v0 row in the store refers to the same key the server is
     * currently presenting. The result is structurally identical to the
     * old `Base64(SHA-256(key.toString()))` value; we keep it private so
     * the legacy path never bleeds into a new enrollment.
     */
    private fun legacyFingerprint(key: PublicKey): String {
        val md = MessageDigest.getInstance("SHA-256")
        val wireBytes = key.toString().toByteArray(Charsets.UTF_8)
        val digest = md.digest(wireBytes)
        return Base64.getEncoder().encodeToString(digest)
    }

    /** The pre-#16 keyType naming — the JCA name, not the SSH name. */
    private fun legacyKeyType(key: PublicKey): String? = key.algorithm

    private fun FingerprintResult.toHostFingerprint(): HostFingerprint =
        HostFingerprint(
            keyType = keyType,
            fingerprintBase64 = fingerprintBase64,
            algorithmVersion = algorithmVersion,
        )
}
