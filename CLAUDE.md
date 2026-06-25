# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## What this project is

An Android tablet SSH client (`com.example.sshterminal`, `SshTerm`) whose whole reason to exist is correctly decoupling the Android IME pipeline from a terminal keyboard pipeline — making Chinese pinyin IMEs (Gboard, Sogou) work naturally inside a remote SSH shell, which Termius/Termux get wrong. Sprint 2 added real SSH transport via SSHJ + BouncyCastle. Sprint 3+ (multi-host, SFTP, known_hosts TOFU, Mosh) is **out of scope** for any change unless explicitly requested.

The complete design rationale lives in `implementation_plan.md`. Read it before changing anything in `terminal/` or `ssh/` — most "obvious" tweaks (e.g. setting `TYPE_TEXT_FLAG_NO_SUGGESTIONS`) are deliberate omissions with documented reasons.

---

## Build / lint / test

Gradle wrapper (`./gradlew`) ships its own JDK 17 — no host JDK setup needed.

```bash
./gradlew :app:testDebugUnitTest         # all unit tests (Robolectric + JUnit)
./gradlew :app:assembleDebug             # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug              # install on the connected device
./gradlew :app:compileDebugUnitTestKotlin # fast: just compile tests, don't run them
```

To run a single test class:
```bash
./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.KeyEventRoutingTest"
```

Reports land in `app/build/reports/tests/testDebugUnitTest/index.html`. XML in `app/build/test-results/`.

There is **no separate `lint` task** wired up — Kotlin compiler warnings in the Gradle build are the lint signal.

---

## High-level architecture

Two layers, separated by `TerminalEndpoint` (a single-method `fun interface { fun write(ByteArray) }`):

**`terminal/` — IME + rendering. The "core" that must stay stable.**
- `TerminalView` (FrameLayout wrapping `com.termux.view.TerminalView`): owns the Termux emulator, the `TerminalInputConnection`, and the dual-input-link routing in `onKeyDown` (physical keys via `KeyMapper`; printable chars fall through to `InputConnection`).
- `TerminalInputConnection`: IME 5-method implementation with a `userInImeContext` latch that defeats the Gboard `setComposingText("")` → `deleteSurroundingText` race.
- `KeyMapper`: every `KeyEvent` resolves to one of `Send` / `Swallow` / `Ignore` / `Paste`. See "Routing invariants" below.
- `MockEchoSession`: Sprint 1 stand-in, still wired as the disconnect fallback.
- `FontSizeController`: Compose ↔ Volume-key bridge.

**`ssh/` — Sprint 2 transport. Newer, more churn OK.**
- `SshClient` (requires **application** Context — the init check enforces this): SSHJ 0.38 connection orchestration, starts `SshKeepAliveService` on success.
- `SshSession`: implements `TerminalEndpoint.write`; `readInto(sink)` is a suspending function that hops to `Dispatchers.IO`; serializes all outbound through a single-thread `writeExecutor` named `SshSession-write`.
- `SshTransport` (interface, 4 methods: `write` / `readBytes` / `resizePty` / `close`) + `ChannelTransport` (production) + `FakeTransport` (tests). SSHJ's `Channel` has 30+ abstract methods; the narrow interface keeps tests independent of sshj version bumps.
- `auth/`: sealed `Auth` → `PasswordAuthProvider` / `PublicKeyAuthProvider` (PEM: Ed25519 + RSA via BouncyCastle).
- `SshErrorMessages.friendly(Throwable)`: sshj cause-chain unwrap → single-line English. This is the user-facing message; the original `Throwable` is preserved as `SshException.cause`.
- `SshConfig`: tunables — keepalive 30 s, SO_TIMEOUT 60 s, PTY 80×24, `xterm-256color`, connect 15 s.

**`data/` — credentials.**
- `crypto/KeyStoreManager`: Android Keystore AES-256-GCM with 12-byte IV ‖ ciphertext self-contained payload.
- `prefs/AppPreferences`: `SharedPreferences` for host/port/username/fontSize; password goes in as an encrypted blob (`KEY_ENCRYPTED_PASSWORD`), private key file as a name (the file itself lives in `filesDir/keys/`).

**`ui/` — Compose assembly.**
- `SshTermApp`: top-level state machine (`ConnectionState`), Connect/Disconnect wiring, falls back to `MockEchoSession` on failure.
- `ConfigScreen`: form + crash banner + SAF private-key import. The plaintext password lives in local state only long enough to call `KeyStoreManager.encrypt`, then is cleared from state.
- `TerminalPane`: `AndroidView` wrapper that runs the IO coroutine driving `emulator.append(bytes)`.

**`logging/AppLog`** writes to `filesDir/app.log` (256 KB rotation) + Logcat mirror. `MainActivity` installs `CrashHandler` writing to `filesDir/crash.log`, which `ConfigScreen` shows on next launch with Copy/Dismiss. The reader thread's expected "Software caused connection abort" is filtered out (see `MainActivity.isHandledTransportAbort`).

---

## Routing invariants (do not regress)

From `implementation_plan.md` §"KeyEvent 路由规则表" — these are the non-negotiable routing rules. Any change to `KeyMapper` or `TerminalView.onKeyDown` must keep them:

| Event | Path | Verdict |
|---|---|---|
| Printable char, no Ctrl/Alt | `InputConnection.commitText` | `Ignore` (View returns `false`) |
| Printable char + Ctrl/Alt | `onKeyDown` → `KeyMapper.ctrlSequence` | `Send` of the ASCII control byte (xterm convention; 26 letters A-Z → 0x01-0x1A, `\` → 0x1C, `]` → 0x1D). Ctrl+V deliberately not mapped — falls through to the printable-key path so the IME emits a literal "V". Ctrl+Shift+V still wins as Paste (see row below) because the Paste verdict is checked first in `KeyMapper.resolve` |
| `KEYCODE_DEL` mid-composition | `InputConnection.deleteSurroundingText` | `Ignore` (View returns `false`) |
| `KEYCODE_DEL` idle | `onKeyDown` → `KeyMapper` | `Send 0x7F` |
| IME composing (pinyin) | `setComposingText` | local hint, **never** bytes to SSH |
| IME commit (汉字上屏) | `commitText` | UTF-8 to SSH, clear composing |
| Ctrl+Space / Shift+Space / `KEYCODE_LANGUAGE_SWITCH` | `onKeyDown` → `KeyMapper` | `Swallow` — must never reach SSH |
| Ctrl+Shift+V | `onKeyDown` → `KeyMapper` | `Paste` — read clipboard, write UTF-8 |
| `KEYCODE_DEL` mid-composition, **and** `verdict == Swallow` | `onKeyDown` | still return `true` |

The `userInImeContext` latch in `TerminalInputConnection` is load-bearing for the Gboard `setComposingText("") → deleteSurroundingText` race. It latches on any composing/commit/finish, and only resets after a non-IME DEL actually reaches SSH. Don't move the reset point.

`KeyMapper.KeyResolution` is a 4-state sealed class (`Send` / `Swallow` / `Ignore` / `Paste`). The legacy `toAnsiSequence` wrapper collapses Swallow/Ignore/Paste to `null` — only `Send` carries bytes. `Paste` in particular must not be silently reinterpreted as raw bytes.

---

## Hard constraints

These are load-bearing and re-litigating them is explicitly listed in `README.md` as a PR-closing offense:

- **Do not modify `com.termux:terminal-emulator` / `terminal-view` internals.** It's a JitPack black box. Open an issue first.
- **Do not self-write an ANSI state machine, ScreenBuffer, or terminal renderer.** Everything render-side goes through the Termux emulator.
- **Do not introduce libraries not listed in `implementation_plan.md`.** No DI framework, no navigation library, no UI kit beyond Compose Material3.
- **Do not implement known_hosts TOFU, SFTP, multi-host list, Mosh, port forwarding** — Sprint 3+ scope, requires an explicit ask.
- **Do not push to git** (no remote configured); do not merge.
- **Do not write tests that actually connect to a real SSH server.** All SSH-related tests must use `FakeTransport` / mocks / Robolectric. Real sshd testing happens on a tablet, not in `app/src/test`.
- **`SshClient` requires an `applicationContext`** — the init check enforces this. Don't bypass it; the leak would only surface across configuration changes.
- **`SshConfig.SO_TIMEOUT_MS` is already in milliseconds.** sshj's `setTimeout` forwards straight to `Socket.setSoTimeout`. The earlier `/1000` bug capped banner reads at 60 ms; never re-introduce it.
- **BouncyCastle must be the bundled `bcprov-jdk18on:1.78.1`.** Android's system "BC" on API 29 is ~1.62, too old for sshj 0.38's PKCS#8 PEM helpers. `BouncyCastleBootstrap.ensureRegistered()` is idempotent and called from `SshClient.connect`.

---

## Test conventions

- **Robolectric** (`org.robolectric:robolectric:4.13`) for tests that touch Android framework classes: anything in `terminal/`, `data/prefs/`, `logging/`, `ui/`.
- **Pure JUnit** for ssh logic: `SshConfigTest`, `SshErrorMessagesTest`, `SshSessionWriteTest`, `auth/PublicKeyAuthProviderTest`. These run faster and don't need Android resources.
- `bcpkix-jdk18on:1.78.1` is a **test** dependency only — it brings the `org.bouncycastle.openssl.jcajce.*` PEM helpers used by `PublicKeyAuthProviderTest` to write Ed25519 keys in OpenSSH v1 format.
- `app/build.gradle.kts` sets `testOptions.unitTests.isIncludeAndroidResources = true` so Robolectric can resolve resources.
- 4 `@Ignore`'d cases in `SshSessionWriteTest` are documented "Sprint 2.5" follow-ups — JUnit + coroutine cancellation timing flakes. Don't blindly delete the `@Ignore`s.
- When a `SshErrorMessages` test references a "self-referential cause" or "sshj cause chain", it is asserting loop safety — the unwalker must handle `t == t.cause`.

---

## SSH error handling

Every failure path (connect, auth, kex, channel-open, read-loop) flows through `SshErrorMessages.friendly(t)` and returns `Result.failure(SshException(msg, t))`. The `SshException.cause` is always the original throwable for engineers; the message is the user-facing one-line hint.

`SshSession.readInto` returns `Result.failure` for `SocketException` / `SocketTimeoutException` / sshj `SSHException` and **always** closes the transport in `finally`. `CancellationException` is rethrown unwrapped so structured concurrency works. The UI's status line uses `t.message ?: t.javaClass.simpleName` — keep error messages human-readable, never expose sshj class names.

`MainActivity.isHandledTransportAbort(t)` filters the expected "Software caused connection abort" from the reader thread out of the crash overlay — this is the normal teardown signal, not a bug.

---

## Process / foreground service

`SshClient.connect` starts `SshKeepAliveService` on success; `disconnect` stops it **before** closing sshj (ordering matters — see kdoc on `disconnect`). The service runs `startForegroundService` with a notification summarising `user@host:port`. Failures to start/stop the service are caught by `runCatching` so they never block a working connect or a clean teardown.

`AndroidManifest.xml` declares the service and the `FOREGROUND_SERVICE` permission; if you add a new service do the same.

---

## Files to read before changing anything

| If you're touching… | Read first |
|---|---|
| `terminal/TerminalView.kt`, `TerminalInputConnection.kt`, `KeyMapper.kt` | `implementation_plan.md` §"输入链路设计" + §"KeyEvent 路由规则表"; both `KeyEventRoutingTest` and `TerminalInputConnectionTest` |
| `ssh/SshClient.kt`, `SshSession.kt` | `implementation_plan.md` §"SSHJ 在 Android 上的正确配置"; `SshSessionWriteTest`, `SshErrorMessagesTest` |
| `ssh/auth/` | `PublicKeyAuthProviderTest` (PEM round-trip) |
| `data/crypto/KeyStoreManager.kt` | `AppPreferencesTest` (encrypted-blob boundaries) |
| `ui/SshTermApp.kt`, `ui/ConfigScreen.kt` | `AppPreferencesTest`, `ConnectionDraftTest`, `ConnectionLogPanel` source |
| `logging/AppLog.kt` | `AppLogTest` (rotation, concurrent writes, Logcat mirror) |
| Project-wide design | `docs/REVIEW_2026-06-24.md` (Sprint 2 review) |

---

## Git workflow

- Branch for the sprint you were assigned; current `docs/code-review-2026-06-24` is a docs branch, **main** is the integration branch.
- Commits use Conventional Commits style (`feat(ssh): …`, `fix(ime): …`, `test(terminal): …`).
- Do **not** push, do **not** merge — the maintainer does both.
- Drop transient `PROMPT*` files before committing (the Sprint 2 cleanup explicitly commits `chore: drop transient Claude prompt file`).

---

## Out of scope (don't volunteer)

These are explicitly listed as deferred in `README.md` §"路线图" — wait for an explicit ask before implementing:

- known_hosts TOFU store (`PromiscuousVerifier` is the deliberate v1.0 default)
- Multi-host list / groups / add-edit-delete UI
- SFTP, port forwarding, ProxyJump
- Mosh (deferred to last evaluation)
- TrueColor terminal type (currently `xterm-256color`)
- xterm mouse protocols
- OpenSSH 7.x / 8.x / 9.x compatibility matrix, dropbear / busybox sshd validation
- KeyStoreManager under Robolectric (currently tested on real device only)
- 横屏平板布局优化

Also: don't add CI, don't add release signing, don't add ProGuard rules beyond the Compose defaults — none of that infrastructure exists yet.