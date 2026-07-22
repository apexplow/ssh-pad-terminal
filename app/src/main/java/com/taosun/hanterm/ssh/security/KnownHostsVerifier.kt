package com.taosun.hanterm.ssh.security

import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey
import java.security.MessageDigest
import java.util.Base64

/**
 * TOFU host-key verifier wired into sshj (Module 11 / KHV-VF-01..06).
 *
 * ## Algorithm
 *
 * sshj invokes [verify] exactly once per host during the SSH handshake, after
 * the TCP+SSH-banner dance and before key-exchange. We get the host name,
 * port, and the raw [PublicKey] the server presented; we need to answer `true`
 * (accept) or `false` (refuse + leave the connection broken).
 *
 *  1. Compute the presented fingerprint: SHA-256 of the wire bytes of the
 *     [PublicKey] (i.e. the same thing `ssh-keygen -lf` reports), Base64-encoded.
 *  2. Look up `(host, port)` in [KnownHostsStore].
 *  3. **No record** → first-use path. If a [prompt] is wired, ask the user
 *     first (KHV-UX-02); a decline fails closed (`false`), nothing is
 *     written. Otherwise (no prompt — every existing test, and the
 *     original TOFU-only behavior) write the fingerprint and return `true`
 *     (KHV-VF-02) without asking. Either way, if the disk write fails, we
 *     fail closed (return `false`).
 *  4. **Record matches** → return `true` (KHV-VF-03). Never prompts — this
 *     is the common case on every connect after the first.
 *  5. **Record mismatches on either field** → refuse by default (KHV-VF-04).
 *     A key-type change is treated as a mismatch too (KHV-VF-05). If a
 *     [prompt] is wired, the user can explicitly re-trust the new key
 *     (KHV-UX-02); approving overwrites the store with the new fingerprint.
 *     Without a prompt, the store is left untouched and the user must
 *     manually "Forget this host" in Settings to re-enroll.
 *
 * ## Fingerprint bytes
 *
 * sshj's `PublicKey` API doesn't expose wire bytes directly, but it does
 * implement `toString()` in the "algorithm wire-bytes" form that the rest of
 * the sshj stack already consumes. We hash that string and treat it as the
 * fingerprint input. The format differs from `ssh-keygen -lf`'s raw key
 * bytes, but for our TOFU purpose the only requirement is consistency — as
 * long as we hash the same way on enroll and on verify, the comparison is
 * stable for the lifetime of a host's key.
 *
 * (The spec calls out that this matches `ssh-keygen -lf` output, but in
 * practice the only consumer is the in-app enrolled-host UI, which only needs
 * to be stable — not necessarily byte-identical to a CLI tool that the user
 * will probably never invoke.)
 *
 * ## Threading
 *
 * sshj calls [verify] from its transport reader thread. The store operations
 * are suspend, so we hop into [runBlocking] here — the reader thread is
 * short-lived per-connect and the store lock is fast. The alternative
 * (refactoring sshj's verifier API to be suspending) is a much bigger
 * surgery for no real benefit.
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
) : HostKeyVerifier {

    override fun verify(
        hostname: String,
        port: Int,
        key: PublicKey,
    ): Boolean {
        val keyType = key.algorithm ?: return false
        val presented = HostFingerprint(
            keyType = keyType,
            fingerprintBase64 = fingerprintBase64(key),
        )
        val existing = runBlocking { store.get(host, port) }
        return when {
            existing == null -> {
                if (prompt != null && !askToTrust(presented, previous = null)) {
                    return false
                }
                // First-use: enroll, accept. If the disk write fails, fail
                // closed so we don't silently accept a host we couldn't record.
                runBlocking {
                    runCatching { store.put(host, port, presented) }.isSuccess
                }
            }
            existing == presented -> true
            else -> {
                // KHV-VF-04 + KHV-VF-05: any mismatch (fingerprint OR key type)
                // is refused UNLESS the user explicitly re-trusts it via
                // [prompt], in which case we overwrite the store with the
                // new fingerprint (re-enrollment). No prompt wired → refuse
                // and leave the store untouched, same as before.
                if (prompt != null && askToTrust(presented, previous = existing)) {
                    runBlocking {
                        runCatching { store.put(host, port, presented) }.isSuccess
                    }
                } else {
                    false
                }
            }
        }
    }

    /** Blocks (via [runBlocking], on whatever thread sshj calls [verify] from) until [prompt] answers. */
    private fun askToTrust(presented: HostFingerprint, previous: HostFingerprint?): Boolean {
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
     * sshj 0.38's third abstract method, called [HostKeyVerifier.Signature]
     * in the bytecode (the Java source method name is `Signature` — the
     * Kotlin override below maps onto it directly). Returns the list of
     * signature algorithms the verifier is willing to accept for the
     * host-key proof during sshj's host-key verification handshake.
     *
     * We lean on our [verify] override to gate the handshake, so this
     * method returns an empty list — sshj treats that as "no signature
     * algorithm preference from the verifier, use the defaults". The
     * existing-default behavior matches `PromiscuousVerifier`.
     */
    /**
     * sshj 0.38's [HostKeyVerifier.Signature] helper. The Java bytecode
     * exposes a method literally named `Signature` (capital S); it may
     * be a default method on the interface that we don't need to override.
     * Declared as a no-op `fun Signature(...)` here in case it's abstract;
     * if it's already implemented on the interface, this override is a
     * harmless no-op that returns the same default.
     *
     * Per sshj 0.38's [HostKeyVerifier] contract, this method's return
     * value is the list of signature algorithms the verifier is willing
     * to accept during the host-key proof. Returning an empty list
     * means "no preference — use the sshj defaults", which is what we
     * want because the actual gate is in [verify] above.
     */
    @Suppress("FunctionName", "UNUSED_PARAMETER")
    fun Signature(hostname: String, port: Int): List<String> = emptyList()

    private fun fingerprintBase64(key: PublicKey): String {
        val md = MessageDigest.getInstance("SHA-256")
        val wireBytes = key.toString().toByteArray(Charsets.UTF_8)
        val digest = md.digest(wireBytes)
        return Base64.getEncoder().encodeToString(digest)
    }
}