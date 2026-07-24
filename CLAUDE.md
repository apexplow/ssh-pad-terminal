# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **当前架构契约的权威来源是 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)** — 本文件是 AI agent 操作手册,只保留 Hard constraints / Routing invariants / 测试规范,不再重复描述当前态.
>
> 历史设计推导: [`implementation_plan.md`](implementation_plan.md)(顶部有 deprecation banner).
> 行为规范: [`docs/GEARS_SPEC.md`](docs/GEARS_SPEC.md).

---

## What this project is

An Android tablet SSH client (`com.taosun.hanterm`, `HanTerm`) whose whole reason to exist is correctly decoupling the Android IME pipeline from a terminal keyboard pipeline — making Chinese pinyin IMEs (Gboard, Sogou) work naturally inside a remote SSH shell, which Termius/Termux get wrong. Sprint 2 added real SSH transport via SSHJ + BouncyCastle. Sprint 3+ (multi-host, SFTP, Mosh) is **out of scope** for any change unless explicitly requested. **ZMODEM receive (`sz` → Downloads)** and **trzsz receive (`tsz` → Downloads, works inside tmux)** are approved in-app capabilities (orthogonal to SFTP).

`docs/ARCHITECTURE.md` is the authoritative description of current state (capabilities, modules, keepalive strategy, lifecycle invariants, decision index). Read it before changing anything in `terminal/` or `ssh/` — most "obvious" tweaks (e.g. setting `TYPE_TEXT_FLAG_NO_SUGGESTIONS`) are deliberate omissions with documented reasons.

---

## Build / lint / test

Gradle wrapper (`./gradlew`) ships its own JDK 21 — no host JDK setup needed.

```bash
./gradlew :app:testDebugUnitTest         # all unit tests (Robolectric + JUnit)
./gradlew :app:assembleDebug             # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug              # install on the connected device
./gradlew :app:compileDebugUnitTestKotlin # fast: just compile tests, don't run them
```

To run a single test class:
```bash
./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.terminal.KeyEventRoutingTest"
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
- `SshClient` (requires **application** Context — the init check enforces this): SSHJ 0.40 connection orchestration, starts `SshKeepAliveService` on success. Keepalive strategy (`HEARTBEAT` + TCP keepalive + FGS nudge) is documented in `docs/ARCHITECTURE.md` §5 — **不要**把 `KeepAliveProvider` 改成 `KEEP_ALIVE`(BG-KA-04 已证自杀).
- `SshSession`: implements `TerminalEndpoint.write`; `readInto(sink)` is a suspending function that hops to `Dispatchers.IO`; serializes all outbound through a single-thread `writeExecutor` named `SshSession-write`.
- `SshTransport` (interface, 4 methods: `write` / `readBytes` / `resizePty` / `close`) + `ChannelTransport` (production) + `FakeTransport` (tests). SSHJ's `Channel` has 30+ abstract methods; the narrow interface keeps tests independent of sshj version bumps.
- `auth/`: sealed `Auth` → `PasswordAuthProvider` / `PublicKeyAuthProvider` (PEM: Ed25519 + RSA via BouncyCastle).
- `SshErrorMessages.friendly(Throwable)`: sshj cause-chain unwrap → single-line English. This is the user-facing message; the original `Throwable` is preserved as `SshException.cause`.
- `SshConfig`: tunables — keepalive 30 s, SO_TIMEOUT 60 s, PTY 80×24, `xterm-256color`, connect 15 s.

**`data/` — credentials.**
- `crypto/KeyStoreManager`: Android Keystore AES-256-GCM with 12-byte IV ‖ ciphertext self-contained payload.
- `prefs/AppPreferences`: `SharedPreferences` for host/port/username/fontSize; password goes in as an encrypted blob (`KEY_ENCRYPTED_PASSWORD`), private key file as a name (the file itself lives in `filesDir/keys/`).

**`ui/` — Compose assembly.**
- `HanTermApp`: top-level state machine (`ConnectionState`), Connect/Disconnect wiring, falls back to `MockEchoSession` on failure.
- `ConfigScreen`: form + crash banner + SAF private-key import. The plaintext password lives in local state only long enough to call `KeyStoreManager.encrypt`, then is cleared from state.
- `TerminalPane`: `AndroidView` wrapper that runs the IO coroutine driving `emulator.append(bytes)`.

**`logging/AppLog`** writes to `filesDir/app.log` (256 KB rotation) + Logcat mirror. `MainActivity` installs `CrashHandler` writing to `filesDir/crash.log`, which `ConfigScreen` shows on next launch with Copy/Dismiss. The reader thread's expected "Software caused connection abort" is filtered out (see `MainActivity.isHandledTransportAbort`).

---

## Routing invariants (do not regress)

From `implementation_plan.md` §"KeyEvent 路由规则表" — these are the non-negotiable routing rules. Any change to `KeyMapper` or `TerminalView.onKeyDown` must keep them:

| Event | Path | Verdict |
|---|---|---|
| Printable char, no Ctrl/Alt | `InputConnection.commitText` | `Ignore` (View returns `false`) |
| Printable char + Ctrl/Alt | `onKeyDown` → `KeyMapper.ctrlSequence` | `Send` of the ASCII control byte (xterm convention; 26 letters A-Z → 0x01-0x1A, `\` → 0x1C, `]` → 0x1D). Ctrl+V deliberately not mapped — falls through to the printable-key path so the IME emits a literal "V". Ctrl+Shift+V still wins as Paste (see row below) because the Paste verdict is checked first in `KeyMapper.resolve`. **In composing state the same Ctrl/Alt-modified chord still writes its byte and force-ends the composing session first**, so tmux `Ctrl+B D`, bash `Ctrl+A`, etc. work even when the IME is in Chinese mode. Bare-letter Sends (ESC alone, DEL alone, arrows, F1-F12, Shift+Tab) remain on the IME-gate path while composing. |
| `KEYCODE_DEL` mid-composition | `InputConnection.deleteSurroundingText` | `Ignore` (View returns `false`) |
| `KEYCODE_DEL` idle | `onKeyDown` → `KeyMapper` | `Send 0x7F` |
| IME composing (pinyin) | `setComposingText` | local hint, **never** bytes to SSH |
| IME commit (汉字上屏) | `commitText` | UTF-8 to SSH, clear composing |
| Ctrl+Space / Shift+Space / `KEYCODE_LANGUAGE_SWITCH` | `onKeyDown` → `KeyMapper` | `Swallow` — must never reach SSH |
| Ctrl+Shift+V | `onKeyDown` → `KeyMapper` | `Paste` — read clipboard, write UTF-8 |
| `KEYCODE_DEL` mid-composition, **and** `verdict == Swallow` | `onKeyDown` | still return `true` |

The `userInImeContext` latch in `TerminalInputConnection` is load-bearing for the Gboard `setComposingText("") → deleteSurroundingText` race. It latches on any composing/commit/finish, and only resets after a non-IME DEL actually reaches SSH. Don't move the reset point.

`KeyMapper.KeyResolution` is a 4-state sealed class (`Send` / `Swallow` / `Ignore` / `Paste`). The legacy `toAnsiSequence` wrapper collapses Swallow/Ignore/Paste to `null` — only `Send` carries bytes. `Paste` in particular must not be silently reinterpreted as raw bytes.

**Owner of the routing state machine (Issue #14)**: as of #14 the entire routing table above lives in `terminal/InputDispatcher.kt` — `ImeKeyRouter` and `TerminalInputConnection` are thin adapters that translate platform events into `InputEvent` (`Key` / `ImeCommit` / `ImeComposing` / `ImeDelete` / `ImeFinishComposing`) and apply the returned `DispatchResult` (`Send` / `Swallow` / `Ignore` / `Paste` / `FinishComposingThenSend`). `composing` / `lastComposedDigits` state was consolidated from `TerminalInputConnection` into the dispatcher (`@Volatile` preserved). Any change to the routing rules means editing `InputDispatcher.dispatch` + `InputDispatcherTest` (50-case exhaustive matrix); the existing `KeyEventRoutingTest` (44 cases) + `TerminalInputConnectionTest` (20 cases) + `TerminalViewAltBufferImeRefreshTest` (3) + `TerminalInputConnectionReconnectTest` (1) all pin the adapter → dispatcher → endpoint wiring and must stay green.

---

## Hard constraints

These are load-bearing and re-litigating them is explicitly listed in `README.md` as a PR-closing offense:

- **Do not modify `com.termux:terminal-emulator` / `terminal-view` internals.** It's a JitPack black box. Open an issue first.
- **Do not self-write an ANSI state machine, ScreenBuffer, or terminal renderer.** Everything render-side goes through the Termux emulator.
- **Do not introduce libraries not listed in `implementation_plan.md`.** No DI framework, no navigation library, no UI kit beyond Compose Material3.
- **Do not implement SFTP, multi-host list, Mosh, port forwarding** — Sprint 3+ scope, requires an explicit ask. (ZMODEM `sz` receive is approved and lives in `terminal/zmodem/`; do not conflate it with SFTP.)
- Do not push to git (no remote configured); do not merge.
- **Do not write tests that actually connect to a real SSH server.** All SSH-related tests must use `FakeTransport` / mocks / Robolectric. Real sshd testing happens on a tablet, not in `app/src/test`.
- **`SshClient` requires an `applicationContext`** — the init check enforces this. Don't bypass it; the leak would only surface across configuration changes.
- **`SshConfig.SO_TIMEOUT_MS` is already in milliseconds.** sshj's `setTimeout` forwards straight to `Socket.setSoTimeout`. The earlier `/1000` bug capped banner reads at 60 ms; never re-introduce it.
- **BouncyCastle must not fall back to Android's system "BC"** (historical: API 29's system BC was ~1.62, too old for sshj's PKCS#8 PEM helpers; minSdk is now 36 per Issue #19, but the explicit register remains load-bearing). `BouncyCastleBootstrap.ensureRegistered()` is idempotent and called from `SshClient.connect`. The `bcprov-jdk18on:1.78.1` version declared in `app/build.gradle.kts` is now **advisory only**, not a hard pin: as of the Sprint 3.5 SSHJ 0.38→0.40 bump, sshj 0.40's own dependency graph demands `bcprov/bcpkix/bcutil-jdk18on [1.80,1.81)`, and Gradle resolves the transitive constraint over the declared one — run `./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -i bouncycastle` to see the actual resolved version (currently `1.80.2`) before assuming 1.78.1 semantics anywhere. **Known trap**: BC 1.80.x's `bcprov`/`bcpkix`/`bcutil` JARs each ship an identical `META-INF/versions/9/OSGI-INF/MANIFEST.MF` (an OSGi multi-release manifest), which collides in `mergeDebugJavaResource` ("3 files found with path..."). `app/build.gradle.kts`'s `packaging { resources { excludes += ... } }` block excludes it — don't remove that block when touching packaging config, and re-check it if BC is bumped again.

---

## Logging policy (Issue #13)

Every `AppLog.{d,i,w,e}` call routes through a `LogPolicy` (`logging/LogPolicy.kt`,
default = `BuildConfigAwareLogPolicy`) that classifies the entry and decides
whether it lands in `filesDir/app.log`, only Logcat, or is dropped. The
classification is visible at the call site — every sensitive call MUST pass an
explicit `classification = LogClassification.X`.

| Classification | When to use | Release behaviour | Debug behaviour |
|---|---|---|---|
| `Input` | IME composing text, committed 汉字, physical key codes, `unicodeChar` | `Drop` | `LogcatOnly` |
| `CredentialMetadata` | Password-derived fingerprints, private-key names | `Drop` | `LogcatOnly` |
| `ConnectionMetadata` | Host, port, username, `user@host:port`, FGS notification summary | `Drop` | `LogcatOnly` |
| `Diagnostic` | State-machine transitions, keepalive mechanics, scrollback | `File` | `File` |
| `Security` | Known-hosts TOFU prompts, host-key rejections | `File` | `File` |
| `Error` | `AppLog.e`/`w` defaults; sshj errors, SocketException, transport aborts | `File` | `File` |

The `d`/`i` defaults are `Diagnostic`; the `w`/`e` defaults are `Error`. New
call sites that log sensitive data must pass the explicit classification —
adding one without it is a privacy regression.

**Don't reintroduce `password=`, `sha256[0..16]=`, or `auth=PasswordAuth`/
`auth=PublicKeyAuth` tokens into any `AppLog.*` message body.** `auth::class.java.simpleName`
was removed from `HanTermAppViewModel.kt:147` and `SshClient.kt:271-277` in #13
because the `Error` classification reaches the file sink in both build types —
re-classifying alone was insufficient.

**Pre-init safety**: `AppLog.policy` defaults to a release-mode policy at
object construction so a log call before `Application.onCreate` finishes
`init` cannot reach the file sink. Don't change this default to a
`BuildConfig.DEBUG`-aware one — that would let a pre-init log leak in dev.

---

## Test conventions

- **Robolectric** (`org.robolectric:robolectric:4.16.1`, `@Config(sdk = [36])`) for tests that touch Android framework classes: anything in `terminal/`, `data/prefs/`, `logging/`, `ui/`. SDK 36 sandbox requires the JDK 21 that `gradlew` ships.
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

`AndroidManifest.xml` declares the service with `foregroundServiceType="specialUse"` plus `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` permissions; if you add a new service do the same. (Issue #19 dropped the pre-34 `dataSync` fallback — minSdk is 36.)

---

## Files to read before changing anything

| If you're touching… | Read first |
|---|---|
| `terminal/TerminalView.kt`, `TerminalInputConnection.kt`, `KeyMapper.kt`, `ImeKeyRouter.kt`, `InputDispatcher.kt` | `implementation_plan.md` §"输入链路设计" + §"KeyEvent 路由规则表"; `InputDispatcherTest` (50-case routing matrix, primary seam), `KeyEventRoutingTest` and `TerminalInputConnectionTest` (adapter→dispatcher→endpoint integration) |
| `terminal/zmodem/` | `ZmodemFilterTest` (lrzsz `sz` fixture); do not add a ZMODEM Gradle dependency |
| `terminal/trzsz/` | `TrzszFilterTest` / `InboundTransferRouterTest`; do not add a trzsz Gradle/npm dependency |
| `ssh/SshClient.kt`, `SshSession.kt` | `implementation_plan.md` §"SSHJ 在 Android 上的正确配置"; `SshSessionWriteTest`, `SshErrorMessagesTest` |
| `ssh/KeepAliveNudge.kt`, `ssh/KeepAliveNudgeRegistry.kt`, `ssh/SshClient.keepAliveNudge` (inner class), `ssh/SshKeepAliveService.kt` | `docs/ARCHITECTURE.md` §5.1 "The `KeepAliveNudge` seam (Issue #17)"; `KeepAliveNudgeRegistryTest` (registry atomicity), `SshClientKeepAliveNudgeTest` (inner-class sshRef null / live / write-throws), `SshClientKeepAliveTest.companionKeepAliveNudgeField_isRemoved_issue17` (negative pin) |
| `ssh/ConnectionRuntime.kt`, `ssh/TeardownState.kt` | `docs/ARCHITECTURE.md` §6 "Canonical teardown order" + issue #15 deferred half (off-Main sshj close); `ConnectionRuntimeTest` (`teardownState_*` cases are the primary seam — 4 new in #15; pre-existing `disconnect_*` cases pin the 7-step order; `disconnect_clearsRegistryBeforeStoppingFgsBeforeClosingSshj` + `connect_success_bindsKeepAliveNudgeToRegistry` + `abandonHandshake_clearsRegistry` are the Issue #17 8-step teardown pins). `disconnect()` is suspend — caller stamps `TearingDown` synchronously, then `withContext(ioDispatcher) { teardownInternal(...) }` hops to `Dispatchers.IO` so sshj's blocking `SSHClient.close()` never runs on Main. The original "non-suspend fire-and-forget" plan was rejected because `BufferedPtyBridge.Endpoint.read()` uses Java's blocking `LinkedBlockingQueue.take()`; see memory note `hanterm-ssh-bridgeadapter-io-children-vs-test-scheduler` for the diagnosis. |
| `ssh/auth/` | `PublicKeyAuthProviderTest` (PEM round-trip) |
| `ssh/security/` (`HostKeyFingerprint`, `CanonicalHostKeyFingerprint`, `HostFingerprint`, `KnownHostsStore`, `KnownHostsVerifier`, `HostKeyPrompt`) | `HostKeyFingerprintTest` (Issue #16 primary seam — real BC-generated Ed25519/RSA, pins canonical wire-bytes + JCA→SSH name shift + `algorithmVersion = 1` + UNKNOWN fail-closed); `KnownHostsStoreTest` (4-col legacy v0 + 5-col v1 round-trip); `KnownHostsVerifierTest` (`FakeFingerprint` injection + 3 v0/v1 cases); `SshClientHostKeyWiringTest.sc_khv_05` (reflection guard on sshj interface drift) |
| `data/crypto/KeyStoreManager.kt` | `AppPreferencesTest` (encrypted-blob boundaries) |
| `ui/HanTermApp.kt`, `ui/ConfigScreen.kt` | `AppPreferencesTest`, `ConnectionDraftTest`, `ConnectionLogPanel` source |
| `ui/ConnectionDraftEditor.kt`, `ui/ConfigDebug.kt` | `ConnectionDraftEditorTest`(Issue #18 primary seam,纯 JUnit,无 Robolectric);`ConfigScreenDebugLogGateTest` 仍钉住 `passwordFingerprint` / `appendDebugLog` 的 release/debug gate 行为 |
| `logging/AppLog.kt` | `AppLogTest` (rotation, concurrent writes, Logcat mirror) |
| Project-wide design | `docs/REVIEW_2026-06-24.md` (Sprint 2 review) |

---

## Git workflow

- Branch for the sprint you were assigned; current `docs/code-review-2026-06-24` is a docs branch, **main** is the integration branch.
- Commits use Conventional Commits style (`feat(ssh): …`, `fix(ime): …`, `test(terminal): …`).
- **Push and `gh pr create` are part of the agent workflow** (relaxed as of issue #16's handoff). After a sprint branch is feature-complete and the full test suite is green, push the branch to `origin` and open a PR against `main` with the issue body. The **maintainer still does `gh pr merge` and any subsequent cleanup** (e.g. deleting the feature branch after merge).
- Drop transient `PROMPT*` files before committing (the Sprint 2 cleanup explicitly commits `chore: drop transient Claude prompt file`).

---

## Out of scope (don't volunteer)

These are explicitly listed as deferred in `README.md` §"路线图" — wait for an explicit ask before implementing:

- Multi-host list / groups / add-edit-delete UI
- SFTP, port forwarding, ProxyJump (ZMODEM `sz` download is **not** SFTP — already shipped)
- Mosh (deferred to last evaluation)
- TrueColor terminal type (currently `xterm-256color`)
- xterm mouse protocols
- OpenSSH 7.x / 8.x / 9.x compatibility matrix, dropbear / busybox sshd validation
- KeyStoreManager under Robolectric (currently tested on real device only)
- 横屏平板布局优化

Also: don't add CI, don't add release signing, don't add ProGuard rules beyond the Compose defaults — none of that infrastructure exists yet.

---

## Architecture analysis report

A deep architectural analysis of HanTerm was generated on 2026-07-20 and lives outside the repo at:

`/home/tao/repo-analyses/hanterm-20260720/ANALYSIS_REPORT.md`

Contents of the report:

1. 开篇：一个被忽视的细分场景
2. 项目全景与架构分层
3. 竞品定位：为什么只有 HanTerm 选择重做
4. IME / 中文拼音链路设计哲学
5. SSH 传输层：4 方法窄接口与 PtyBridge 解耦
6. SSH Keepalive 三道防线：决策演变与 postmortem
7. UI 状态机与 Activity 重建保活
8. 凭据安全与日志基础设施
9. ZMODEM 无感知下载
10. 横切能力合集
11. 评价与反思
12. 附录
    - A. 架构全景图（Mermaid）
    - B. 关键测试矩阵
    - C. CLAUDE.md hard constraints 表
    - D. 术语表
