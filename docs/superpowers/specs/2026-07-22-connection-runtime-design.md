# ConnectionRuntime Deep-Module Extraction — Design Spec

**Date**: 2026-07-22
**Status**: Implemented (amended 2026-07-22 — capability ConnectionView + process-scoped Application owner; ActiveSshSessionStore removed)
**Scope**: HanTerm SSH connection runtime. Pull all the SSH-session resource
ownership (SshClient / SshSession / BufferedPtyBridge / SshBridgeAdapter /
SshKeepAliveService / bridgeScope / teardown order)
out of `HanTermAppViewModel` and into a single deep module held by
`HanTermApplication`. The UI talks to one runtime object and consumes a
minimal `ConnectionView` capability surface (`write` / `read` / `resize` /
`lastCloseReason`).

## Amendments (process-scoped closeout)

| Topic | Decision |
|---|---|
| Owner | `HanTermApplication.connectionRuntime(...)` — process-scoped; tests may inject ephemeral runtime via `HanTermApp(connector=…)` |
| ViewModel dispose | Cancels UI mirrors only — never `runtime.dispose()` |
| ConnectionView | Interface capability surface, not `endpoint+bridge+session` data clump |
| ActiveSshSessionStore | **Removed** — process-scoped runtime replaces it |
| Reconnect | Connected → connect performs full teardown first (no SSH client leak) |
| Connect vs disconnect race | Epoch token; late handshake success discarded |

See also [`docs/ARCHITECTURE.md`](../../ARCHITECTURE.md) §6 and root [`CONTEXT.md`](../../../CONTEXT.md).

---

## Original problem (historical)

After Sprint 2.5 → Sprint 3 the connection runtime grew incrementally across
the codebase. The original extraction moved resource ownership into
`ConnectionRuntime` while keeping a transitional `ConnectionView` data clump
and Compose-scoped construction. The closeout above finishes that migration.

(Remaining historical sections below describe the first extraction; prefer the
Amendments table and ARCHITECTURE.md for current contracts.)

## Problem

After Sprint 2.5 → Sprint 3 the connection runtime grew incrementally across
the codebase:

| Owner today | Resource held |
|---|---|
| `HanTermAppViewModel` | `_activeSession`, `_bridge`, `_adapterJob`, `_endpoint`, `bridgeScope` |
| `HanTermAppViewModel` (via call) | `ActiveSshSessionStore.set/clear` (process-scoped holder) |
| `HanTermAppViewModel` (via call) | `connector: SshConnector` (SshClient) |
| `SshClient.connect` (via call) | `SshKeepAliveService.start/stop` |
| `HanTermApp.kt` (3 call sites: lines 351–353, 707–709, 752–754) | passes `endpoint + bridge + sshSession` into `TerminalPane` |

`TerminalPane` consumes **three nullable connection facts** (endpoint /
bridge / session) just to know how to drain bytes, register a resize listener,
and surface a `SessionCloseReason`. None of those three things are meaningful
to the view in isolation — they're the shape of the transport plumbing, and
they leak the runtime's internals into the UI layer.

`teardownConnection` (HanTermAppViewModel.kt:281–290) and `handleConnectOutcome`
(lines 232–279) both implement the eight-step teardown / seven-step connect
sequencing in two different places. The order matters:

- `bridge.close()` MUST happen **before** `adapterJob.cancel()` so the
  outbound coroutine sees `bridge.transport.read() == null` and exits cleanly
  instead of being torn out mid-take on the `LinkedBlockingQueue`.
- `adapterJob.cancel()` MUST happen **before** `connector.disconnect()` so the
  inbound coroutine's `session.readInto` is cancelled before sshj pulls the
  socket — otherwise an unhandled `SocketException` races the structured-cancel
  and shows up in `AppLog` as a phantom error.
- `connector.disconnect()` MUST happen **before** `ActiveSshSessionStore.clear()`
  so the FGS-nudge callback (which references the sshj client through
  `SshClient.sshRef`) doesn't observe a half-torn-down state.

That ordering is currently enforced by *two* methods in the ViewModel — and
neither has a test asserting the order. Add a fourth resource (a real
SshKeepAliveService stub injected for testing, a future reconnect path that
needs to swap the bridge without disconnecting, etc.) and the duplication
gets worse.

The architecture review's Strong candidate #2 names this fan-out explicitly
(参见 `/tmp/architecture-review-20260722-0226.html` §"connection-runtime").

## Non-Goals

- No public API change for `SshClient` / `SshConnector` / `SshSession`. The
  runtime sits **on top** of those, doesn't replace them.
- No reconnect-without-disconnect path in this spec. The current
  `handleConnectOutcome` already tears down the previous bridge before
  building the new one — keep that, just move it inside the runtime.
- No new `HanTermAppViewModelTest` cases beyond what already exists; the
  existing 359-test suite is the regression net.
- No change to `PtyBridge` / `BufferedPtyBridge` / `SshBridgeAdapter` /
  `SshKeepAliveService` / `ActiveSshSessionStore`. The runtime uses them as
  building blocks.
- No change to `TerminalPane`'s public signature. It still takes a
  `TerminalEndpoint` and the two nullable plumbing facts, but **the runtime
  exposes those three as a single `ConnectionView`** — the call sites collapse
  from `endpoint = viewModel.endpoint.value, bridge = viewModel.bridge.value,
  sshSession = viewModel.activeSession.value` (3 lines) to
  `view = runtime.view` (1 line).
- No removal of `ActiveSshSessionStore`. It's still the process-scoped holder
  for Activity-recreation survival; the runtime just owns the read/clear
  calls instead of the ViewModel.
- No change to the `HanTermApp` Composable's snackbar / back-handler / battery-
  opt / font-size plumbing. This is runtime extraction, not UI rewrite.
- No DI framework. The runtime is constructed in `HanTermApplication` (which
  already exists and currently wires `SshClient`); the ViewModel gets a
  reference through its existing constructor seam.

## Decisions

| Question | Decision |
|---|---|
| Module name | `ssh.ConnectionRuntime` (lives next to `SshClient`, same package — keeps `internal` visibility on the SshClient handshake internals) |
| Ownership scope | The runtime owns **process-scoped** state (lifetime = `HanTermApplication`). Activity recreation is handled inside the runtime by re-reading `ActiveSshSessionStore` on first call, exactly like the ViewModel does today. |
| Threading | Single internal `CoroutineScope(SupervisorJob + Dispatchers.IO)` named `ConnectionRuntime-io`. The `bridgeScope` is now an implementation detail. The IO coroutines (outbound / inbound / watchdog) move into the runtime's scope — `bridgeScope` parameter on `SshBridgeAdapter.start()` is replaced by the runtime passing its own. |
| Public surface | `connect(draft) / disconnect() / state: StateFlow<ConnectionState> / view: ConnectionView / activeSession: StateFlow<SshSession?>` |
| ConnectionView | One data class bundling `endpoint: TerminalEndpoint + bridge: PtyBridge? + session: SshSession?`. **Not** a StateFlow — it's a snapshot of the three facts that `TerminalPane` consumes; rebuilt atomically on every connect/teardown. The ViewModel exposes it as `StateFlow<ConnectionView?>` so Compose recomposes only when the bundle identity changes. |
| Teardown | Single private `teardownInternal()` method called from `disconnect()`, from the inbound coroutine's `finally` (the existing `session.readInto` failure path), and from `onDispose()` for the case where the runtime outlives the ViewModel. All three call sites go through the same 8-step order — that's the entire point of the module. |
| Process-scoped holder | `ActiveSshSessionStore` stays as-is. The runtime writes the session on connect-success, reads on first compose (Activity-recreation survival), clears on disconnect. The ViewModel no longer references the store directly. |
| FGS lifecycle | The runtime owns `SshKeepAliveService.start/stop`. `connect()` calls `start` after `SshClient.connect` succeeds (matches today's line 248 ordering); `teardownInternal()` calls `stop` **before** `connector.disconnect()` (matches the CLAUDE.md "ordering matters" rule). |
| Re-entrancy | `connect()` checks `_state.value is Connecting` and bails (matches today's `HanTermAppViewModel.startConnect` line 120 guard). `disconnect()` is idempotent (matches today's `SshClient.disconnect` AtomicReference getAndSet pattern). |
| Tests | `ConnectionRuntimeTest` (new file, Robolectric for ApplicationContext + `mockk` for `SshClient`); `HanTermAppViewModelTest` loses the bridge/adapter/endpoint plumbing tests (they move into the runtime test). Existing `SshBridgeAdapterTest` / `PtyBridgeTest` / `PtyBridgeEndpointTest` / `SshSessionWriteTest` / `SshClientKeepAliveTest` are unchanged. |
| Migration path | Strangler fig: the runtime is added alongside the ViewModel; `HanTermAppViewModel` exposes `endpoint / bridge / activeSession` as `StateFlow`s that proxy the runtime's `view` / `activeSession`. Step-by-step removal in a follow-up Sprint. |

## Architecture

### Resource topology (before)

```
                       ┌─────────────────────────┐
                       │  HanTermAppViewModel    │
                       │  ─────────────────────  │
   SshConnector ──────▶│  _activeSession         │──▶ _endpoint ─▶ TerminalPane
   (SshClient)         │  _bridge                │──▶ _bridge   ─▶ TerminalPane
                       │  _adapterJob            │──▶ session   ─▶ TerminalPane
                       │  bridgeScope            │
                       │  ─────────────────────  │
   ActiveSshSession ──▶│  .set() / .clear()      │
   Store               └─────────────────────────┘
                                  │
                                  ▼
                       SshKeepAliveService (FGS)
                       SshBridgeAdapter (bridgeScope)
                       BufferedPtyBridge
                       SshClient / SshSession
```

UI sees 6 mutable facts. Order of teardown is duplicated in `teardownConnection`
+ `disconnect()`.

### Resource topology (after)

```
┌───────────────────────────────────────────────────────────┐
│                     ConnectionRuntime                     │
│  ─────────────────────────────────────────────────────    │
│  private val _state = MutableStateFlow<ConnectionState>(…) │
│  private val _view = MutableStateFlow<ConnectionView?>(…)  │
│  private val _activeSession = MutableStateFlow<SshSession?>│
│  private val ioScope = CoroutineScope(SupervisorJob+IO)   │
│                                                           │
│  private var sshClient: SshClient                          │
│  private var bridge: BufferedPtyBridge?                    │
│  private var adapterJob: Job?                              │
│  private var session: SshSession?                          │
│                                                           │
│  suspend fun connect(draft)        ──┐                    │
│  suspend fun disconnect()           ──┤                   │
│  val state: StateFlow<…>            ◀─┘  canonical        │
│  val view: StateFlow<ConnectionView?>       teardown       │
│  val activeSession: StateFlow<SshSession?>                  │
│                                                           │
│  private fun teardownInternal()                            │
└───────────────────────────────────────────────────────────┘
                                  │
                  ┌───────────────┼───────────────┐
                  ▼               ▼               ▼
        SshClient / SshSession  BufferedPtyBridge  SshKeepAliveService
        SshBridgeAdapter        ActiveSshSessionStore
```

UI sees 3 read-only flows + 2 command methods. Teardown order is in one place.

### Public API

```kotlin
package com.apexplow.hanterm.ssh

/**
 * Owns every live resource required to ferry bytes between the IME-driven
 * terminal view and a remote SSH shell, and the teardown order between them.
 *
 * Replaces the six-way fan-out the [com.apexplow.hanterm.ui.HanTermAppViewModel]
 * previously had to manage: ssh client / session / bridge / adapter job /
 * bridgeScope / process-scoped holder. UI talks to this object.
 *
 * Lifetime = process (constructed once in `HanTermApplication`); the
 * [com.apexplow.hanterm.ssh.ActiveSshSessionStore] still handles the
 * Activity-recreation case but only the runtime reads / writes it.
 */
class ConnectionRuntime(
    private val context: Context,                  // applicationContext — same constraint as SshClient
    private val connector: SshConnector,          // typically the SshClient instance
    private val idleTimeoutMs: Long = SshConfig.SSH_IDLE_TIMEOUT_MS,
) {
    /** UI-visible state machine. Drives the Connect button / status banner. */
    val state: StateFlow<ConnectionState>

    /** UI-visible bundle of (endpoint, bridge, session). `null` when not connected. */
    val view: StateFlow<ConnectionView?>

    /** Non-null iff a session is currently live. Drives the Disconnect button enabled state. */
    val activeSession: StateFlow<SshSession?>

    /**
     * Start a connection. Idempotent — bails if [state] is already `Connecting`.
     * On success, [state] becomes `Connected` and [view] is rebuilt atomically.
     * On failure, [state] becomes `Error` and [view] reverts to its pre-connect snapshot.
     */
    suspend fun connect(
        host: String,
        port: Int,
        username: String,
        auth: Auth,
    ): Result<SshConnectResult>

    /**
     * Tear down the live session, if any. Idempotent — safe to call from
     * Disconnect button, BackHandler, onSessionClosed, and the inbound
     * coroutine's `finally` block concurrently. The first caller runs the
     * canonical 8-step teardown; every other caller is a true no-op.
     *
     * Never throws — the same `runCatching` discipline the current
     * `SshClient.disconnect` uses.
     */
    suspend fun disconnect()

    /** Detach from process. Called from `HanTermApplication.onTerminate`. */
    fun dispose()
}

/** What `TerminalPane` consumes. Rebuilt atomically on every connect / teardown. */
data class ConnectionView(
    val endpoint: TerminalEndpoint,
    val bridge: PtyBridge?,
    val session: SshSession?,
)
```

### Canonical teardown order

```kotlin
private suspend fun teardownInternal() {
    val client = sshClientRef.getAndSet(null) ?: return  // already torn down — no-op
    AppLog.i(TAG, "teardown start")

    // 1. close bridge (puts EOF on both queues)
    bridge?.close()

    // 2. cancel adapter job (cancels outbound + inbound + watchdog)
    adapterJob?.cancelAndJoin()

    // 3. null out internal refs
    bridge = null
    adapterJob = null
    session = null

    // 4. stop FGS BEFORE sshj teardown (matches CLAUDE.md ordering rule;
    //    FGS nudge callback references SshClient.sshRef)
    SshKeepAliveService.stop(context)

    // 5. close sshj (synchronous teardown; releases socket)
    client.disconnect(userInitiated = true)

    // 6. clear process-scoped holder (lets a fresh connect re-stash)
    ActiveSshSessionStore.clear()

    // 7. publish the new view (endpoint falls back to MockEchoSession)
    _view.value = ConnectionView(
        endpoint = MockEchoSession(),
        bridge = null,
        session = null,
    )
    _activeSession.value = null

    // 8. publish state
    _state.value = ConnectionState.Disconnected
    AppLog.i(TAG, "teardown done")
}
```

`SshClient.disconnect` already uses `getAndSet(null)` for atomicity (see
`SshClientKeepAliveTest.disconnect_concurrentCallers_closeTheUnderlyingClientExactlyOnce`),
so the ViewModel + the inbound coroutine's finally + the Disconnect button all
collide safely on the same single runner.

### Wiring into `HanTermApplication`

```kotlin
class HanTermApplication : Application() {
    val runtime: ConnectionRuntime by lazy {
        ConnectionRuntime(
            context = applicationContext,
            connector = SshClient(applicationContext),
        )
    }
}
```

`HanTermAppViewModel` becomes:

```kotlin
class HanTermAppViewModel(
    private val runtime: ConnectionRuntime,   // injected via factory
    private val prefs: AppPreferences,
    private val context: Context,
) : ViewModel() {

    // Proxy three flows — UI still collects them by the same name.
    val endpoint: StateFlow<TerminalEndpoint> =
        runtime.view.map { it?.endpoint ?: MockEchoSession() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, MockEchoSession())

    val bridge: StateFlow<PtyBridge?> =
        runtime.view.map { it?.bridge }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val activeSession: StateFlow<SshSession?> = runtime.activeSession

    val connectionState: StateFlow<ConnectionState> = runtime.state

    fun startConnect(draft: ConnectionDraft? = null) {
        viewModelScope.launch { runtime.connect(/* … */) }
    }

    fun disconnect() {
        viewModelScope.launch { runtime.disconnect() }
    }
}
```

The ViewModel still holds the **non-connection** concerns (`onComposingHint`,
`snackbarHostState`, `toggleLogs`, `_logRefreshTick`, `_showLogs`). The
runtime owns only the connection concerns.

### HanTermApp call sites collapse

Three call sites currently pass three fields each:

```kotlin
// HanTermApp.kt:351–353 (and 707–709, 752–754)
TerminalPane(
    endpoint = viewModel.endpoint.value,
    bridge = viewModel.bridge.value,
    sshSession = viewModel.activeSession.value,
    onComposingHint = …,
    onPtyResize = …,
    onSessionChanged = …,
    onTerminalViewChanged = …,
    fontSize = viewModel.fontSize.value,
)
```

After:

```kotlin
TerminalPane(
    view = viewModel.connectionView.value,   // single read
    onComposingHint = …,
    onPtyResize = …,
    onSessionChanged = …,
    onTerminalViewChanged = …,
    fontSize = viewModel.fontSize.value,
)
```

`TerminalPane` is updated to accept a single `view: ConnectionView?` parameter
and destructure inside, **but** the existing `endpoint + bridge + sshSession`
three-arg overload is kept as a deprecated shim for one Sprint to keep the
back-compat surface minimal.

### Backwards compatibility

- `SshClient`, `SshConnector`, `SshSession`, `ActiveSshSessionStore`,
  `SshKeepAliveService`, `SshBridgeAdapter`, `BufferedPtyBridge`, `PtyBridge` —
  all unchanged. Public surface stable.
- `HanTermAppViewModel.endpoint / .bridge / .activeSession / .connectionState`
  — kept as proxies for one Sprint. UI tests that read these continue to work.
- `HanTermAppViewModel._adapterJob` / `_bridgeScope` — removed (no public
  surface, no test reads them). The runtime takes over.

### Tests (new + moved)

```
ssh/ConnectionRuntimeTest.kt  (new, ~12 cases)
  - connect_bailsWhenAlreadyConnecting
  - connect_publishesViewAndStateOnSuccess
  - connect_publishesErrorStateOnFailure (no view leak)
  - disconnect_isIdempotent
  - disconnect_concurrentCallers_teardownExactlyOnce
  - disconnect_stopsFgsBeforeClosingSshj           (the ordering invariant)
  - disconnect_clearsActiveSshSessionStore
  - reconnect_tearsDownPreviousBridgeBeforeBuildingNew
  - inboundFailure_callsTeardownAndPublishesError
  - watchdogFire_callsTeardownWithIdleReason
  - view_publishesAtomically (no half-built snapshot)
  - dispose_cancelsIoScope
```

`HanTermAppViewModelTest` keeps its existing flow-proxying assertions; the
"bridge / adapter / endpoint plumbing" cases move into `ConnectionRuntimeTest`.
Existing `SshBridgeAdapterTest` (5 cases) / `PtyBridgeTest` (19 cases) /
`PtyBridgeEndpointTest` (3 cases) / `SshSessionWriteTest` (16 cases) /
`SshClientKeepAliveTest` (5 cases) are unchanged.

### Migration sequencing (one Sprint)

1. Add `ConnectionRuntime` + `ConnectionView`. New file, no callers.
2. Wire `HanTermApplication.runtime`. New field, no callers yet.
3. `HanTermAppViewModel` constructor accepts `ConnectionRuntime`. Old fields
   (`_activeSession / _bridge / _adapterJob / _endpoint`) become derived
   proxies via `runtime.view`. App continues to run, ViewModelTest passes.
4. `HanTermApp` switches `endpoint + bridge + sshSession` triple to
   `connectionView.value` single read. Three call sites change at once.
5. Delete the `teardownConnection` body in the ViewModel — the runtime owns
   the order now. Delete `_adapterJob` / `_bridgeScope` private fields.
6. Add `ConnectionRuntimeTest`. Existing `HanTermAppViewModelTest` keeps only
   the non-connection cases (most of them).
7. Update `docs/ARCHITECTURE.md` §4 模块图 + §6 连接生命周期 to reference
   `ConnectionRuntime` as the new owner.

## Open questions

- **Backpressure when the ViewModel collects `runtime.view` via `stateIn`**.
  The bridge / session / endpoint triple is rebuilt on every connect — that
  fires one recomposition per connect, not per inbound byte. Safe, but if
  Compose recomposition cost becomes a concern (it won't, on a tablet, with
  three reads), the future move is to expose `endpoint` / `bridge` /
  `activeSession` as separate `StateFlow`s and let `TerminalPane` destructure.
  Decided against for this spec — the runtime's whole point is "one bundle,
  one recomposition".
- **Re-entrancy from `inbound finally`**. Today, `session.readInto`'s finally
  calls `bridge.close()`, then the inbound coroutine ends; the ViewModel's
  `onSessionClosed` callback (driven from `TerminalPane`'s `finally`) calls
  `teardownConnection`. After the move, `TerminalPane.finally` will call
  `runtime.disconnect()` (idempotent), and `runtime.disconnect()` is
  guarded by `getAndSet(null)` on `sshClientRef` — so even if
  `onSessionClosed` races the inbound finally, exactly one teardown runs.
  To-be-verified in `ConnectionRuntimeTest.disconnect_concurrentCallers_*`.
- **ActiveSshSessionStore as a public process-scope singleton**. The store
  outlives the runtime if the runtime is ever replaced (e.g. test mode with
  a fake). Decision: runtime owns the read/clear but does NOT own the
  store; the store remains an `object` for backward compat with the existing
  `ActiveSshSessionStoreTest` (11 cases).
- **No reconnect-without-disconnect API**. Two consecutive `connect()` calls
  without an intervening `disconnect()` go through the teardown → connect
  sequence (matches today's `handleConnectOutcome.onSuccess` line 239–240
  "close previous bridge before building new"). If a true hot-swap is needed
  later, it's a new method `swapTo(host, port, auth)`, not part of this spec.

## Reference

- Architecture review §"connection-runtime":
  `/tmp/architecture-review-20260722-0226.html`
- Handoff listing this as Strong candidate #2:
  `/tmp/hanterm-handoff.Qv56uF/HANDBACK.md` §"High priority"
- Current teardown sequencing:
  `app/src/main/java/com/taosun/hanterm/ui/HanTermAppViewModel.kt:281–290`
- Current connect sequencing:
  `app/src/main/java/com/taosun/hanterm/ui/HanTermAppViewModel.kt:232–279`
- Three call sites that pass the triple into TerminalPane:
  `app/src/main/java/com/taosun/hanterm/ui/HanTermApp.kt:351–353, 707–709, 752–754`
- FGS ordering rule: `docs/ARCHITECTURE.md` §6 + CLAUDE.md
  "`SshClient.connect` 启动 `SshKeepAliveService` 在 `connect` 成功时;
  `disconnect` **停止它**在关闭 sshj 之前(顺序很重要)"
- Atomic disconnect pattern being reused:
  `app/src/test/java/com/taosun/hanterm/ssh/SshClientKeepAliveTest.kt:99–124`