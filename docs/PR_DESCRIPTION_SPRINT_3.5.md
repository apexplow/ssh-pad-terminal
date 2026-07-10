# PR: Sprint 3.5 — SSHJ 0.38 → 0.40 upgrade, readInto test-debt cleanup, BC MRJAR packaging fix

> **Branch:** `chore/sprint-3.5-sshj-0.40-upgrade` → `main`
> **Commits ahead:** 5 (linear, no merge commits; see "Commit history" below)
> **Files touched:** 6 files, +285 / −27 (approx; see per-commit stats below)
> **Test status:** **323 tests total, 0 failures, 0 errors, 11 skipped, 0 `@Ignore`** (re-verified 2026-07-09 with `--rerun-tasks`)

---

## Summary

This PR is a hardening/cleanup pass, not a feature PR — no user-visible behavior changes. It bundles three commits of test-debt cleanup plus a dependency bump that turned out to need a follow-up packaging fix:

1. **`1665ff4` un-Ignore 4 `readInto` timing tests** (`SshSessionWriteTest`) — Sprint 2.5 left these `@Ignore`'d because `runBlocking + delay(50)` was non-deterministic against `SshSession.writeExecutor`. Fixed with:
   - `test_readInto_invokesSinkForEachBatch` / `closesTransportOnEof` / `closesTransportOnSinkException`: append `session.awaitWriteQueueDrained()` after `ioJob.join()`, matching the existing `test_readInto_socketTimeout_isTranslatedToFriendlyMessage` pattern.
   - `test_readInto_doesNotCloseTransportOnCancellation` (the P0 one — pins README §12's "cancellation ≠ close session" invariant that lets an Activity recreation re-attach to a live session via `ActiveSshSessionStore`): replaced `delay(50)` with a `FakeTransport.beforeRead` one-shot hook that counts down a `CountDownLatch` synchronously inside `readBytes()`, plus a new `CANCEL_SENTINEL` + `enqueueCancellation()` that `readBytes()` converts to a `CancellationException`. This sidesteps `Thread.interrupt()` racing with `withContext`'s `InterruptedException → CancellationException` conversion (observed to occasionally take the wrong catch arm).
2. **`6ab7755` silence 6 compiler warnings** in `main` (no behavior change): `PublicKeyAuthProvider.kt` enum `when` → `if-else`, `@Suppress("UNUSED_PARAMETER")` on `KnownHostsVerifier.kt`, drop unused `keyCode` param from `KeyMapper.resolve`/`toAnsiSequence` (4 main call sites + 11 test sites updated), `@Suppress("DEPRECATION")` on the two `KeyMapEntry` ctors using `KeyEvent.characters`.
3. **`56cc4b9` docs(readme): sync test counts** for the above two commits.
4. **`e4487b2` bump SSHJ 0.38.0 → 0.40.0 + un-Ignore 2 Ed25519 tests** (`PublicKeyAuthProviderTest`) — SSHJ 0.40 (2026-06-29) shipped 4 upstream fixes (#989, #959, #993, #908) that changed how Ed25519 PKCS#8/OpenSSH-v1 keys are parsed. No production source changes were needed (every SSHJ symbol/call maps 1:1 to 0.40). The test fixture helper `writeOpenSshPem` had to be rewritten because BC 1.78's `JcaMiscPEMGenerator` emits PKCS#8 (`-----BEGIN PRIVATE KEY-----`) for `EdECPrivateKey`, and SSHJ 0.40's `PKCS8KeyFile` hard-rejects the Ed25519 OID (`PKCS8KeyFile.java:383`). New helper: parse `EdECPrivateKey.encoded` via BC's `PrivateKeyInfo` → extract the 32-byte raw seed → `Ed25519PrivateKeyParameters` → `OpenSSHPrivateKeyUtil.encodePrivateKey` (already in `bcprov-jdk18on`, no new dep) → wrap in a `PemObject("OPENSSH PRIVATE KEY", ...)` so `KeyProviderUtil.detectKeyFileFormat` classifies it correctly.
5. **`25f6490` fix(build): exclude colliding BC OSGi MRJAR manifest** — discovered *after* the above 4 commits landed and after the original Sprint 3.5 handoff was written. SSHJ 0.40 transitively upgrades `bcprov`/`bcpkix`/`bcutil` from the declared `1.78.1` pin to **`1.80.2`** (confirmed via `./gradlew :app:dependencies`: `bcprov-jdk18on:1.78.1 -> 1.80.2`), and all three 1.80.x JARs ship an identical `META-INF/versions/9/OSGI-INF/MANIFEST.MF`. `mergeDebugJavaResource` failed with "3 files found with path ...MANIFEST.MF" and the APK could not be assembled. Fix: exclude every copy of that path in `app/build.gradle.kts` packaging options — Android's runtime has no use for OSGi metadata.

---

## Why this matters / risk

The riskiest part of this PR is not the test un-Ignoring (mechanical, well-isolated) but the **transitive BouncyCastle version drift**: `CLAUDE.md`'s "Hard constraints" section states `bcprov-jdk18on:1.78.1` must be the bundled version — that statement is **no longer accurate** as of `e4487b2`. Gradle resolves it to `1.80.2` regardless of the declared version, because sshj 0.40's own dependency graph demands `[1.80,1.81)`. This PR does not change that pin declaration (still says `1.78.1` in `app/build.gradle.kts`), it only fixes the packaging fallout. Updating `CLAUDE.md`/`README.md`/`docs/GEARS_SPEC.md` to reflect the new reality is being tracked as a separate doc-sync task in this same hardening sprint (see the sprint's other commits).

Everything else is either test-only (`SshSessionWriteTest`, `PublicKeyAuthProviderTest`) or warning-silencing with no behavioral change.

---

## Files of interest (review start points)

| Stream | Start here | Why |
|---|---|---|
| readInto un-Ignore | [`app/src/test/java/com/example/sshterminal/ssh/SshSessionWriteTest.kt`](../app/src/test/java/com/example/sshterminal/ssh/SshSessionWriteTest.kt) | `FakeTransport.beforeRead` + `CANCEL_SENTINEL` are new test-only scaffolding; production `SshSession.readInto` is untouched. |
| Ed25519 fixture rewrite | [`app/src/test/java/com/example/sshterminal/ssh/auth/PublicKeyAuthProviderTest.kt`](../app/src/test/java/com/example/sshterminal/ssh/auth/PublicKeyAuthProviderTest.kt) | `writeOpenSshPem` is test fixture code, not production — but it's worth understanding since it exercises the exact `KeyFormat.OpenSSHv1` detection path that `PublicKeyAuthProvider` relies on in production. |
| SSHJ bump | [`app/build.gradle.kts`](../app/build.gradle.kts) | 1-line version bump (`e4487b2`) + 17-line packaging exclusion block (`25f6490`). Run `./gradlew :app:dependencies --configuration debugRuntimeClasspath \| grep -i bouncycastle` to reproduce the 1.80.2 transitive resolution locally. |
| Warning sweep | [`app/src/main/java/com/example/sshterminal/terminal/KeyMapper.kt`](../app/src/main/java/com/example/sshterminal/terminal/KeyMapper.kt) | `resolve(event)` / `toAnsiSequence(event)` dropped the unused `keyCode: Int` param — 4 main call sites (`TerminalView.kt` x3, `TerminalInputConnection.kt` x1) + 11 test sites in `KeyEventRoutingTest.kt` updated in lockstep. |

---

## Routing invariants — unaffected

No `KeyMapper` routing *behavior* changed (only the unused parameter was dropped) — the load-bearing rules in `CLAUDE.md` §"Routing invariants" are untouched. `KeyEventRoutingTest` (42 cases) is green.

---

## Test status

`./gradlew :app:testDebugUnitTest --rerun-tasks` re-run on `chore/sprint-3.5-sshj-0.40-upgrade` at HEAD `25f6490` (2026-07-09):

- **BUILD SUCCESSFUL in 3m 7s** — 323 tests total, 0 failures, 0 errors, 11 skipped, 0 `@Ignore`
- 11 skipped = `EncryptedPrivateKeyStoreTest` 6 `assumeTrue` (Robolectric sandbox has no real AndroidKeyStore) + `PublicKeyAuthProviderEncryptedTest` 5 `assumeTrue` (release-build-only path)
- `./gradlew :app:assembleDebug --rerun-tasks` — **BUILD SUCCESSFUL in 41s**, `app-debug.apk` 33 MB, no `MANIFEST.MF` collision

| Suite | Before this PR | After this PR |
|---|---|---|
| `SshSessionWriteTest` | 12 active + 4 `@Ignore` | 16 active + 0 `@Ignore` |
| `PublicKeyAuthProviderTest` | 3 active + 2 `@Ignore` | 5 active + 0 `@Ignore` |
| Repo-wide | 312 total / 306 active / 6 `@Ignore` / 11 `@Assume`* | 323 total / 312 active / 0 `@Ignore` / 11 `@Assume` |

*repo-wide "before" count reconstructed from the Sprint 3 GEARS_SPEC snapshot; not independently re-verified since it predates this branch.

To verify locally:

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks
# Reports: app/build/reports/tests/testDebugUnitTest/index.html
# XML:     app/build/test-results/

./gradlew :app:assembleDebug --rerun-tasks
# APK: app/build/outputs/apk/debug/app-debug.apk

./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -i bouncycastle
# Expect: bcprov-jdk18on:1.78.1 -> 1.80.2 (transitively forced by sshj 0.40)
```

---

## Commit history

All 5 commits are authored by `st6098770633 <st609877963@gmail.com>` with `Co-Authored-By: Claude <noreply@anthropic.com>` trailers (unlike the older `feat/alt-buffer-cursor-scroll` branch, none of these 5 are missing the trailer):

```
25f6490 fix(build): exclude colliding BC OSGi MRJAR manifest in packaging
e4487b2 chore(deps): bump sshj 0.38.0 → 0.40.0 + un-Ignore 2 Ed25519 tests
56cc4b9 docs(readme): sync test counts for Sprint 3.5
6ab7755 chore: silence 6 compiler warnings in main
1665ff4 test(ssh): un-Ignore readInto tests with deterministic awaitWriteQueueDrained
```

No rewrites needed — the branch is already a clean linear history off `feat/alt-buffer-cursor-scroll`'s tip (`bde94d0`). Squashing to 1 commit is an option if the maintainer wants a single "Sprint 3.5" entry in `main`'s history, but the 5 commits are already small and reviewable individually, so this PR description does not recommend squashing.

**Rebase note**: `feat/alt-buffer-cursor-scroll` (if it hasn't already been merged) should probably be fast-forwarded/rebased onto this branch's tip before either merges to `main` — this branch already contains `feat/alt-buffer-cursor-scroll`'s history plus the 5 Sprint 3.5 commits (confirmed via `git log main..chore/sprint-3.5-sshj-0.40-upgrade`).

---

## Reviewer checklist

- [ ] `./gradlew :app:testDebugUnitTest --rerun-tasks` is green on the maintainer's machine (323 / 0 fail / 0 error / 11 skip)
- [ ] `./gradlew :app:assembleDebug` succeeds (BC MRJAR exclusion doesn't silently break something else in `mergeDebugJavaResource`)
- [ ] No new `@Ignore` was introduced (was 6, now 0)
- [ ] `SshSession.readInto`'s cancellation contract (`test_readInto_doesNotCloseTransportOnCancellation`) still asserts `transport.closeCalled == false` and no `onClose` fire
- [ ] `BouncyCastleBootstrap.ensureRegistered()` is still called from `SshClient.connect` (idempotent) — unaffected by this PR, but worth re-confirming given the BC version drift
- [ ] Aware that `CLAUDE.md`'s "BouncyCastle must be the bundled `bcprov-jdk18on:1.78.1`" line is now stale (see "Why this matters" above) — doc fix tracked separately in this sprint

---

## Out of scope (per `CLAUDE.md`)

- Multi-host list / groups, SFTP, port forwarding, ProxyJump, Mosh
- TrueColor terminal type, xterm mouse protocols beyond SGR-wheel
- OpenSSH 7.x/8.x/9.x + dropbear/busybox compatibility matrix (real-device only, tracked as a manual checklist in this sprint's other deliverables)
- `KeyStoreManager` under Robolectric (real-device-only per `CLAUDE.md`)

---

## Follow-ups (tracked in this sprint, not this PR)

- Sync `CLAUDE.md`, `README.md` (architecture dependency line still says `sshj 0.38.0`), and `docs/GEARS_SPEC.md` (test-count header/inventory still reflects the pre-Sprint-3.5 snapshot) to the state verified above.
- Real-device regression pass for the three SSH auth paths (password, Ed25519, RSA) now that `writeOpenSshPem`'s production-equivalent parsing path (`PublicKeyAuthProvider` loading real OpenSSH-v1 Ed25519 keys) has new SSHJ 0.40 code underneath it — the unit tests cover the fixture round-trip but not a real sshd handshake.
- `CLAUDE.md`'s BC 1.78.1 pin should be either updated to reflect the transitive 1.80.2 reality, or the pin should be made explicit (`app/build.gradle.kts` could force-resolve `1.78.1` if the maintainer wants to keep it hard-pinned instead of advisory) — this PR does not make that decision.
