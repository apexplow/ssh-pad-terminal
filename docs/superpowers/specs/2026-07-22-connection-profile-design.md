# ConnectionProfile Deep-Module Extraction — Design Spec

**Date**: 2026-07-22
**Status**: Approved (grilling + design-it-twice hybrid)
**Scope**: Pull connection-config and credential lifecycle out of
`ConfigScreen` / `ConfigActions` / `ConnectionFormSection` /
`HanTermAppViewModel` into a single deep module at `data/profile/`.
UI edits a `ConnectionDraft`; Connect gets `ConnectPrepared` and hands it
to `ConnectionRuntime`.

Domain language: see root [`CONTEXT.md`](../../../CONTEXT.md).

Architecture review candidate: `/tmp/architecture-review-20260722-0535.html`
§"connection-profile".

---

## Problem

Credential and connection-field knowledge leaks across four UI callers:

| Caller today | What it knows |
|---|---|
| `ConfigActions.loadInitialConfig` / `saveConfig` | KeyStore encrypt/decrypt + prefs field write |
| `ConnectionFormSection.applyDraftForConnect` | Encrypt-if-nonempty + prefs write (Connect side effect) |
| `PrivateKeyImporter.importPrivateKey` | SAF Uri → bytes → `EncryptedPrivateKeyStore` |
| `ConfigScreen.onForgetHost` | `KnownHostsStore.delete` via `runBlocking`, **hard-coded `DEFAULT_PORT`** |
| `HanTermAppViewModel.resolveAuth` | Decrypt → CharArray wipe → `Auth` |

Consequences:

1. **Empty-password semantics diverge** — `saveConfig` wipes the blob on empty;
   `applyDraftForConnect` keeps it. Callers must remember which helper they are in.
2. **`loadInitialConfig` decrypts into a `String` draft field** — plaintext
   survives in Compose state across the editing session after every cold start.
3. **Connect round-trips Keystore** when the user just typed a password:
   encrypt into prefs, then decrypt in `resolveAuth`.
4. **`forgetHost` ignores the draft port** — always deletes `(host, 22)`.
5. Tests pin helpers scattered across UI packages instead of one module interface.

---

## Non-Goals

- Multi-host list / `ProfileId` / StateFlow observability (Sprint 3+).
- Merging into `ConnectionRuntime` — profile is pre-connect; runtime is live session.
- Changing TOFU verify / enroll — stays in `ssh/security`; profile only
  proxies `forgetHost`.
- Moving `fontSize` / debug-migration flags off `AppPreferences`.
- DI framework — Application constructs one instance, same pattern as
  `ConnectionRuntime`.
- Product change to require Save before Connect — Connect still implicitly
  persists (today's behaviour).
- Wiping imported key files on `clearAll` — match today's `prefs.clear()`
  (key files may orphan; out of scope).

---

## Decisions (locked in grilling)

| # | Decision |
|---|---|
| 1 | Scope: `load` / `save` / `clearAll` / `importKey` / `prepareConnect` / `forgetHost` / `clearStoredPassword`. Live draft editing stays in Compose. |
| 2 | `prepareConnect(draft)` implicitly persists non-empty draft fields (same as today's Connect). |
| 3 | Module name `ConnectionProfile`, package `data/profile/`. `ConnectionDraft` moves there. |
| 4 | `load` never returns plaintext password. |
| 5 | Empty password on `save` **and** `prepareConnect` = **KEEP** stored blob. Wipe only via `clearStoredPassword`. |
| 6 | `importKey(displayName, bytes)` — UI owns SAF / `ContentResolver`. |
| 7 | `clearAll` = whole connection form; separate Remove-saved-password control. |
| 8 | `load()` → `ProfileSnapshot(draft, hasStoredPassword)`. |
| 9 | One instance created in `HanTermApplication`, shared by ViewModel + ConfigScreen. |
| 10 | External interface = hot-path facade (design 3). Internal injectable ports (design 4). No `apply(intent)` cast surface; no rich multi-host API. |
| 11 | `prepareConnect` materializes `Auth` from draft plaintext when non-empty (skip encrypt→decrypt round-trip); still persists encrypted blob as side effect. |
| 12 | `forgetHost(host, port)` uses the draft's real port. |
| 13 | `clearAll` clears connection fields only — must **not** wipe `fontSize` / migration flags via `prefs.edit().clear()`. |

---

## Architecture

### Before

```
ConfigScreen ──▶ loadInitialConfig / saveConfig ──▶ KeyStoreManager + AppPreferences
     │
     ├──▶ PrivateKeyImporter ──▶ EncryptedPrivateKeyStore
     └──▶ KnownHostsStore.delete (DEFAULT_PORT)

ViewModel.startConnect
     ├──▶ applyDraftForConnect ──▶ encrypt + prefs
     └──▶ resolveAuth ──▶ decrypt + Auth ──▶ ConnectionRuntime.connect
```

### After

```
┌─────────────────────────────────────────────────────────┐
│                  ConnectionProfile                      │
│  load / save / prepareConnect / clearAll / …            │
│  ─────────────────────────────────────────────────────  │
│  internal ports: Store · Cipher · Vault · HostEnrollment│
└────────────┬──────────────┬──────────────┬──────────────┘
             ▼              ▼              ▼
      AppPreferences   KeyStoreManager  EncryptedPrivateKeyStore
      (conn fields)                     KnownHostsStore.delete

ConfigScreen ──▶ profile.load/save/importKey/forget/clear*
ViewModel    ──▶ profile.prepareConnect ──▶ ConnectPrepared
                                          ──▶ ConnectionRuntime.connect
```

UI never imports `KeyStoreManager` / `EncryptedPrivateKeyStore` /
`KnownHostsStore` for connection intents.

### Public API

```kotlin
package com.taosun.hanterm.data.profile

import com.taosun.hanterm.ssh.auth.Auth

data class ConnectionDraft(
    val host: String,
    val port: String,
    val username: String,
    /** Newly typed plaintext only. Always "" after load / successful save. */
    val password: String,
    val privateKeyName: String,
)

data class ProfileSnapshot(
    val draft: ConnectionDraft,       // draft.password always ""
    val hasStoredPassword: Boolean,
)

data class SaveOutcome(
    val draftForUi: ConnectionDraft,  // password cleared to ""
    val hasStoredPassword: Boolean,
)

/** Ready for ConnectionRuntime.connect — Auth already materialized. */
data class ConnectPrepared(
    val host: String,
    val port: Int,
    val username: String,
    val auth: Auth,
)

/**
 * Durable single-host connection picture + credential lifecycle.
 * Lifetime = process (constructed once in HanTermApplication).
 */
interface ConnectionProfile {
    fun load(): ProfileSnapshot

    /** Explicit Save. Empty password keeps blob. */
    fun save(draft: ConnectionDraft): SaveOutcome

    /**
     * Connect path: persist like today's applyDraftForConnect, then
     * materialize Auth. Empty password keeps blob.
     * Non-empty draft.password → Auth from that plaintext (no decrypt round-trip)
     * and encrypt blob as side effect.
     */
    suspend fun prepareConnect(draft: ConnectionDraft): Result<ConnectPrepared>

    fun clearStoredPassword()

    /** Connection fields + password blob + privateKeyName ref. Not fontSize. */
    fun clearAll(): ConnectionDraft

    /** Returns normalized safeName stored on disk and selected in profile. */
    fun importKey(displayName: String, bytes: ByteArray): Result<String>

    suspend fun forgetHost(host: String, port: Int)
}
```

### Password semantics (single table)

| Intent | `draft.password` empty | `draft.password` non-empty |
|---|---|---|
| `save` | **KEEP** blob | Encrypt → replace blob |
| `prepareConnect` | **KEEP** blob; Auth from blob or key | Encrypt → replace blob; Auth from draft plaintext |
| `clearStoredPassword` | Wipe blob | (n/a — no draft) |
| `clearAll` | Wipe blob + clear fields | (n/a) |

Auth priority (unchanged): non-blank `privateKeyName` with resolvable file →
`PublicKeyAuth`; else password blob / draft password → `PasswordAuth(CharArray)`.

### Internal ports (not part of UI interface)

```kotlin
internal interface ProfileStorePort {
    fun read(): StoredProfile
    fun write(profile: StoredProfile)
    fun clearConnectionFields()  // not prefs.clear()
}

internal interface SecretCipherPort {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

internal interface PrivateKeyVaultPort {
    fun import(safeName: String, bytes: ByteArray): Result<Unit>
    fun resolveAbsolutePath(safeName: String): String?
    fun normalizeSafeName(raw: String): String
}

internal interface HostEnrollmentPort {
    suspend fun delete(host: String, port: Int)
}
```

Production adapters wrap existing types. Tests use in-memory fakes —
`ConnectionProfileTest` is pure JUnit where possible (fake cipher + store).

### Auth materialization (implementation sketch)

```kotlin
private fun materializeAuth(draft: ConnectionDraft, stored: StoredProfile): Auth {
    val keyName = draft.privateKeyName.trim().ifBlank { stored.privateKeyName }
    if (keyName.isNotBlank()) {
        val path = keys.resolveAbsolutePath(keyName)
            ?: error("private key not found for $keyName")
        return Auth.PublicKeyAuth(path)
    }
    if (draft.password.isNotEmpty()) {
        return Auth.PasswordAuth(draft.password.toCharArray())
    }
    val blob = stored.passwordBlob
        ?: error("password slot empty but no private key configured")
    // decrypt → CharArray + wipe intermediate buffers (move from ViewModel)
    …
}
```

---

## UX changes (product-visible)

These are intentional, locked in grilling — not accidental refactors:

1. **Cold start / load**: password field is empty; UI shows stored-password
   status from `hasStoredPassword` (e.g. helper text / chip
   "Saved password on device").
2. **Save with empty password field**: no longer wipes the blob. Users who
   want to delete the stored password tap **Remove saved password**.
3. **Clear**: still wipes the whole connection form including the blob.
4. **Forget enrolled host**: uses draft port (bugfix).

UI wiring notes:

- After `save`, set `draft = outcome.draftForUi` and refresh
  `hasStoredPassword` from outcome.
- Show Remove-saved-password only when `hasStoredPassword`.
- `passwordFingerprint` (DEBUG) still runs on the typed password **before**
  clearing the local field — stays in UI, not in profile.

---

## Wiring

```kotlin
// HanTermApplication
val connectionProfile: ConnectionProfile by lazy {
    DefaultConnectionProfile(
        store = SharedPreferencesProfileStore(AppPreferences(this)),
        cipher = AndroidKeystoreCipherAdapter(),
        keys = EncryptedPrivateKeyVaultAdapter(this),
        hosts = KnownHostsEnrollmentAdapter(this),
    )
}

val connectionRuntime: ConnectionRuntime by lazy { … }  // already exists / planned
```

```kotlin
// HanTermAppViewModel.runConnect
val prepared = profile.prepareConnect(draft ?: profile.load().draft)
    .getOrElse { … }
if (!isNetworkAvailable()) { … }
return runtime.connect(prepared.host, prepared.port, prepared.username, prepared.auth)
```

`AppPreferences` remains for `fontSize` and migration flags. Connection-field
writers in UI are deleted (`saveConfig`, `loadInitialConfig`,
`applyDraftForConnect`, `resolveAuth`, `importPrivateKey` body moves).

Typealias shim (one Sprint): `ui.ConnectionDraft` →
`typealias ConnectionDraft = com.taosun.hanterm.data.profile.ConnectionDraft`
if needed for gradual import migration; prefer updating imports in the same PR.

---

## Tests

| Suite | What |
|---|---|
| `ConnectionProfileTest` (new, prefer pure JUnit + fakes) | load never fills password; save empty keeps blob; prepareConnect empty keeps blob; prepareConnect nonempty materializes Auth without requiring decrypt; clearStoredPassword; clearAll preserves fontSize via store contract; importKey updates name; forgetHost records `(host, port)` |
| Migrate / delete | `ConnectionDraftTest` cases that pin `applyDraftForConnect` / empty-save wipe → rewrite against profile interface |
| `HanTermAppViewModelTest` | Mock `ConnectionProfile.prepareConnect`; drop decrypt assertions |
| Compose UI | Update Save/Clear flows; add Remove-saved-password visibility when `hasStoredPassword` |
| Keep | `EncryptedPrivateKeyStoreTest`, `AppPreferencesTest` (fontSize / blob encode), `KnownHostsStoreTest` — adapters still covered at their own seams |

Old UI helper unit tests that only re-test moved logic become waste once
profile-interface tests exist — delete (replace-don't-layer).

---

## Migration steps

1. Add `data/profile/` types + `ConnectionProfile` + `DefaultConnectionProfile` + ports/adapters + `ConnectionProfileTest`.
2. Wire singleton in `HanTermApplication`; inject into ViewModel factory + ConfigScreen.
3. Switch ViewModel Connect path to `prepareConnect`.
4. Switch ConfigScreen Save / Clear / import / forget; add Remove-saved-password.
5. Delete `saveConfig` / `loadInitialConfig` / `applyDraftForConnect` / `resolveAuth` / UI KeyStore imports; move `ConnectionDraft` package.
6. Update `docs/ARCHITECTURE.md` § modules + `CONTEXT.md` if terms shift.
7. Run `./gradlew :app:testDebugUnitTest`.

---

## Open follow-ups (explicitly deferred)

- Placeholder / i18n string for "Saved password on device".
- Whether `clearAll` should also delete key files under `filesDir/keys/`.
- Collapsing `TerminalPane` three-arg overload (ConnectionRuntime residual —
  separate candidate).
