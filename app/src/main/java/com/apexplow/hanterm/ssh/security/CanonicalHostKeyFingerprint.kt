package com.apexplow.hanterm.ssh.security

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * Production [HostKeyFingerprint] — derives the fingerprint from the canonical
 * SSH wire bytes of the [PublicKey] using sshj's
 * [KeyType.putPubKeyIntoBuffer] encoding.
 *
 * ## Why wire bytes, not `toString()`
 *
 * The pre-#16 algorithm hashed `key.toString().toByteArray(UTF-8)`. sshj's
 * [PublicKey.toString] returns the `authorized_keys`-line format (e.g.
 * `"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI… [comment]"`). That's:
 *
 *  1. **Not the wire bytes** — it's a debug-string rendering of them. The
 *     actual SSH wire bytes are the ones `ssh-keygen -lf` reports.
 *  2. **Version-fragile** — any BouncyCastle or sshj bump that tweaks
 *     `toString()` formatting silently invalidates every enrolled host.
 *     Users see false MITM warnings on every library upgrade.
 *  3. **Includes the key comment** when one is present, so two generated
 *     keys with identical material but different comments would yield
 *     different fingerprints — a quiet source of false mismatches.
 *
 * ## Why SHA-256 (and not sshj's `SecurityUtils.getFingerprint`)
 *
 * sshj ships [net.schmizz.sshj.common.SecurityUtils.getFingerprint], but it
 * uses **MD5** (not SHA-256) and returns **hex** (not Base64) — the old
 * `ssh-keygen -lf` default format, not the modern `-E sha256` one. We
 * deliberately don't use it: the user-facing fingerprint in the
 * "Trust this host?" dialog should match what `ssh-keygen -lf -E sha256`
 * prints, so the user can verify with a standard tool. We compute SHA-256
 * + Base64 ourselves; the algorithm is one line.
 *
 * ## Threading
 *
 * [compute] is pure / synchronous. The [Buffer.PlainBuffer] is created
 * per call — never share. ([Buffer.getCompactData] mutates the read
 * position; a shared instance across calls would corrupt the result.)
 *
 * ## Cert types
 *
 * For `KeyType.*_CERT` variants, [KeyType.putPubKeyIntoBuffer] writes the
 * certificate blob (not the parent key). The fingerprint is still
 * deterministic for the lifetime of the cert, so TOFU still works — we
 * deliberately do NOT call [KeyType.getParent] to flatten certs, because
 * that would lose the wire-format identity the server actually presented.
 */
class CanonicalHostKeyFingerprint : HostKeyFingerprint {

    override val currentVersion: Int = 1

    override fun compute(publicKey: PublicKey): FingerprintResult {
        val keyType = KeyType.fromKey(publicKey)
        require(keyType != KeyType.UNKNOWN) {
            "Unsupported host key type: ${publicKey.algorithm}"
        }
        val buf = Buffer.PlainBuffer()
        keyType.putPubKeyIntoBuffer(publicKey, buf)
        val wireBytes = buf.getCompactData()
        val digest = MessageDigest.getInstance("SHA-256").digest(wireBytes)
        return FingerprintResult(
            keyType = keyType.toString(),
            fingerprintBase64 = Base64.getEncoder().encodeToString(digest),
            algorithmVersion = currentVersion,
        )
    }
}
