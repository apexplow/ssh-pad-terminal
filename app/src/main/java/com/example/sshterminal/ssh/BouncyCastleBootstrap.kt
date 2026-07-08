package com.example.sshterminal.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Registers a modern BouncyCastle JCE provider with the JVM.
 *
 * ## Why we ship our own
 *
 * Android's bundled "BC" provider on API 29 (Android 10) is BouncyCastle 1.62,
 * which predates Ed25519 (`java.security.spec.EdECPrivateKeySpec` didn't land
 * in BC until 1.68). SSHJ 0.40+ routes Ed25519 through its internal
 * [net.schmizz.sshj.common.Ed25519KeyFactory] which calls
 * `KeyFactory.getInstance("Ed25519")`; the system "BC" on API 29 throws
 * `KeyFactory not found` for Ed25519, so we must register a modern BC
 * before SSHJ's first Ed25519 lookup.
 *
 * We bundle `org.bouncycastle:bcprov-jdk18on:1.78.1` and register it at the
 * top of the provider list. Once registered, calls to
 * `KeyFactory.getInstance("Ed25519")` resolve to our build, SSHJ is happy, and
 * any Android system code that asked for "BC" by name still gets a working
 * provider (ours is API-compatible).
 *
 * ## Idempotency
 *
 * [ensureRegistered] is safe to call repeatedly and from multiple threads.
 * It only does work when the currently-registered "BC" provider is NOT our
 * `BouncyCastleProvider` class.
 *
 * [SSH_ANDROID_PITFALL]: some locked-down JVMs (Robolectric's sandbox is one)
 * throw `SecurityException` from [Security.insertProviderAt]. We swallow that
 * because sshj will fall back to whatever providers ARE available — the unit
 * tests don't exercise real Ed25519 loading, and on-device code will always
 * run with full `SecurityManager` privileges.
 */
internal object BouncyCastleBootstrap {
    private val lock = Any()

    fun ensureRegistered() {
        synchronized(lock) {
            val current = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (current != null && current.javaClass == BouncyCastleProvider::class.java) return
            // removeProvider is a no-op if absent, so we don't bother guarding it.
            runCatching { Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME) }
            runCatching { Security.insertProviderAt(BouncyCastleProvider(), 1) }
        }
    }
}
