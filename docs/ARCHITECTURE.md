# HanTerm — Architecture (current state)

> **权威当前架构契约.** 新贡献者(包括 AI agent)以本文件为准;其它文档(`README.md` / `CLAUDE.md` / `implementation_plan.md` / `docs/GEARS_SPEC.md` / postmortem)按各自角色补充历史与决策推导,不再重复描述当前态.
>
> 最后修订: 2026-07-22,与工作树 `fix/terminal-ime-restart-on-reconnect` 同步(ConnectionRuntime 进程级收口 / 能力面 ConnectionView)。

## 1. 项目是什么

HanTerm(`com.taosun.hanterm`)是 Android 平板上的 SSH 客户端. 全部差异化价值在于:**让中文拼音 IME(Gboard / 搜狗)在远程 SSH shell 里像本地输入一样工作** — Termius / Termux 等主流工具在平板上做不到. Sprint 2 接入真 SSH 传输(SSHJ + BouncyCastle),Sprint 3+ 解耦 connection runtime 让 transport 可替换(Sprint 4+ 候选 mosh / 本地 shell).

**License**: TBD(待 Sprint owner 决定);仓库目前 private.

## 2. 当前能力 (shipped capabilities)

| 能力 | 入口 / 文件 |
|---|---|
| IME pipeline 解耦(Gboard / 搜狗 中文拼音候选词) | `terminal/TerminalInputConnection.kt`(适配) + `terminal/InputDispatcher.kt`(路由策略 owner,Issue #14)|
| 物理键盘 Ctrl/Alt 路由(`xterm` 控制字节,26 字母 + `\` + `]` + `[` + `Esc`) | `terminal/InputDispatcher.kt` → `terminal/KeyMapper.kt`(数据驱动 `KEY_MAP`,`InputDispatcher` 的唯一 caller) |
| 双指翻页 scrollback + 新输出徽章 + 自动回底 | `terminal/ScrollbackController.kt` + `ui/ScrollbackBanner.kt` |
| Alt-buffer 滚动 NPE 守卫(vim/less/htop 内单指拖不闪退) | `terminal/TerminalView.kt` `isAltBufferScrollCrashPath` |
| SSH 连接(SSHJ 0.40 + BouncyCastle 1.80.2) + Ed25519 / RSA / 密码 三种认证 | `ssh/SshClient.kt` + `ssh/auth/` |
| Known-hosts TOFU 校验 + MITM 防护(Issue #16:基于 sshj `KeyType.putPubKeyIntoBuffer` 算 canonical wire bytes,带 `algorithmVersion` 迁移字段) | `ssh/security/HostKeyFingerprint.kt`(模块抽象)+ `CanonicalHostKeyFingerprint.kt`(生产实现)+ `KnownHostsStore.kt` + `KnownHostsVerifier.kt` |
| 凭据 AES-256-GCM 加密(Android Keystore + SAF 私钥文件) | `data/profile/ConnectionProfile` + `data/crypto/*` |
| 凭据编辑意图单一 owner(sealed `DraftIntent` + `StateFlow` 状态 + 测试用 `DebugLogSink` 端口) | `ui/ConnectionDraftEditor.kt`(Issue #18)|
| 进程级 ConnectionRuntime(Activity 重建保活) | `ssh/ConnectionRuntime.kt` + `HanTermApplication` |
| SSH keepalive(`HEARTBEAT` 单向 + TCP keepalive + FGS nudge,详见 §5) | `ssh/SshClient.kt` + `ssh/SshKeepAliveService.kt` |
| PtyBridge transport-可替换抽象 | `terminal/PtyBridge.kt` + `BufferedPtyBridge.kt` + `PtyBridgeEndpoint.kt` |
| `SshBridgeAdapter` 把 session 接进 PtyBridge(inbound/outbound/resize 三路协程) | `ssh/SshBridgeAdapter.kt` |
| `SessionCloseReason` race-fix(Disconnect 后不再误弹红 overlay) | `ssh/SessionCloseReason.kt` + `SshSession.close(userInitiated = true)` 同步写入 |
| ZMODEM 静默接收(`sz` → MediaStore Downloads;**不要**在 tmux 内) | `terminal/zmodem/` |
| trzsz 静默接收(`tsz` → MediaStore Downloads;tmux 内外可用) | `terminal/trzsz/` |
| 平板横屏双栏布局(`Row` 左 Config + 右 Preview) | `ui/LayoutDecision.kt` + `HanTermApp.kt` |
| `AppLog` 文件 sink + `LogPolicy` 敏感分类审计 + `CrashHandler` 崩溃栈展示 + `ConnectionLogPanel` in-app 查看 | `logging/AppLog.kt` + `logging/LogPolicy.kt` + `MainActivity.kt` + `ui/ConnectionLogPanel.kt` |
| `FontSizeController` 音量键调字号 | `terminal/FontSizeController.kt` |

## 3. 已删除的能力 (开源前清理)

下列 capability 在开源准备阶段被识别为 “无 production caller 的 feature islands”,代码图 ≠ 产品图,已于 2026-07-22 删除:

| 已删除 capability | 历史模块 | 删除原因 |
|---|---|---|
| tmux session 切换器(右侧抽屉) | `terminal/TmuxSession.kt` / `TmuxSessionParser.kt` / `TmuxSessionSource.kt` + `ui/TmuxDrawer.kt` | UI 在 Sprint 3.7 撤销;side-band `tmux list-sessions` 执行通道与 `RemoteCommandExecutor` / `SshjRemoteCommandExecutor` / shell integration 整套仍分配资源但已无 caller |
| Bash/Zsh shell integration(终端标题上报) | `terminal/ShellIntegrationState.kt` / `ShellIntegrationInstaller.kt` + `app/src/main/res/raw/hanterm_shell_integration_{bash,zsh}.sh` | 与 tmux 切换器同因;`TerminalView.setShellIntegrationListener` + `TerminalPane.onShellIntegrationState` 已一并移除 |
| Side-band SSH `RemoteCommandExecutor` 通道 | `ssh/RemoteCommandExecutor.kt` / `SshjRemoteCommandExecutor.kt` | tmux 探测的唯一 consumer;`SshSession.executeRemoteCommand()` / `commandExecutor` 同步删除;`SshConfig.REMOTE_COMMAND_TIMEOUT_MS` / `REMOTE_COMMAND_OUTPUT_LIMIT_BYTES` 常量同步删除 |
| 命令 Snippet(底部 ModalBottomSheet 收藏) | `data/prefs/SnippetStore.kt` + `ui/SnippetPanel.kt` + `ui/SnippetPayload.kt` | UI 在 Sprint 3.7 撤销;数据层被一并删除(无迁移路径,见 `docs/REVIEW_2026-06-24.md` 路线图) |

**后续如果任一项要恢复** — 必须显式立项 + 重新走评审;**禁止**按历史 README / GEARS_SPEC 反向实现.

## 4. 模块图(只画当前 ship)

```
:app/
├── terminal/                     ★ IME + 渲染 (核心,变更需极谨慎)
│   ├── TerminalView.kt               FrameLayout + Termux.TerminalView 包装
│   ├── InputDispatcher.kt            ★ 路由策略 owner(Issue #14):composing + lastComposedDigits + 全部 InputEvent → DispatchResult 决策
│   ├── TerminalInputConnection.kt    IME 5 方法 + Gboard userInImeContext latch(适配层,薄)
│   ├── ImeKeyRouter.kt               View.onKeyDown + dispatchKeyEventPreIme(适配层,薄)
│   ├── KeyMapper.kt                  数据驱动 KEY_MAP(21 条 entry,`InputDispatcher` 唯一 caller)
│   ├── TerminalEndpoint.kt           SAM 接口
│   ├── PtyBridge.kt / BufferedPtyBridge.kt / PtyBridgeEndpoint.kt   双向 seam
│   ├── TerminalComposingView.kt      拼音 hint 回调
│   ├── ScrollbackController.kt       双指翻页手势
│   ├── MockEchoSession.kt            Sprint 1 mock,断线兜底
│   └── FontSizeController.kt         音量键字号
│
├── data/                         凭据与连接画像
│   ├── profile/                  ConnectionProfile(load/save/prepareConnect)
│   ├── crypto/                   KeyStoreManager + EncryptedPrivateKeyStore
│   └── prefs/                    AppPreferences(fontSize + profile 字段 adapters)
│   ├── crypto/
│   │   ├── KeyStoreManager.kt          Keystore AES-256-GCM
│   │   └── EncryptedPrivateKeyStore.kt filesDir/keys/*.pem.enc 加密 slot
│   └── prefs/AppPreferences.kt         SharedPreferences(无明文密码)
│
├── ssh/                          Sprint 2/3 真 SSH
│   ├── ConnectionRuntime.kt            ★ 连接资源单一 owner(session/bridge/adapter/FGS/teardown)
│   ├── ConnectionView.kt               write/read/resize/lastCloseReason 能力面
│   ├── ConnectionState.kt              Disconnected/Connecting/Connected/Error
│   ├── TeardownState.kt                Idle/TearingDown/Complete(finalState) — Issue #15 7 步 teardown 生命周期
│   ├── SshClient.kt                    SSHJ 0.40 编排 + TCP keepalive + 原子 disconnect
│   ├── SshBridgeAdapter.kt             PtyBridge 与 SshSession 三路接线
│   ├── SshSession.kt                   TerminalEndpoint 实现 + writeExecutor 单线程
│   ├── SshTransport.kt + ChannelTransport.kt  + FakeTransport.kt
│   ├── SshConfig.kt                    PTY/timeout 常量
│   ├── SshErrorMessages.kt + SshException.kt  友好文案
│   ├── SessionCloseReason.kt           UserInitiated race-fix
│   ├── SshKeepAliveService.kt          FGS nudge(防 Doze)
│   ├── BouncyCastleBootstrap.kt
│   └── auth/  Password / PublicKey / Auth
│
├── ssh/security/
│   ├── HostKeyFingerprint.kt           模块抽象(interface + FingerprintResult,Issue #16)
│   ├── CanonicalHostKeyFingerprint.kt  生产实现(KeyType.fromKey + putPubKeyIntoBuffer + SHA-256/Base64)
│   ├── HostFingerprint.kt              存储行类型(3 字段:keyType + fingerprintBase64 + algorithmVersion)
│   ├── KnownHostsStore.kt              AtomicFile 持久化 known_hosts(5 列格式,接受 4 列 legacy v0)
│   ├── HostKeyPrompt.kt + HostKeyPromptRequest
│   └── KnownHostsVerifier.kt           sshj HostKeyVerifier 实现(TOFU + 交互式 prompt + v0→v1 自动迁移)
│
├── ui/                           Compose 装配
│   ├── HanTermApp.kt                   顶层状态机(装 ConnectionRuntime + ConnectionDraftEditor)
│   ├── HanTermAppViewModel.kt          UI 态;凭据走 ConnectionProfile;连接资源 proxy 自 runtime
│   ├── ConnectionDraftEditor.kt        ★ Issue #18 编辑意图 owner(sealed DraftIntent + StateFlow 状态 + DebugLogSink 端口);无 Compose 依赖
│   ├── ConfigScreen.kt                 无状态 view adapter;只收集 editor 状态 + 转发 DraftIntent
│   ├── ConnectionFormSection.kt / FingerprintSection.kt / CrashLogCard.kt / ConfigActions.kt
│   ├── ConfigDebug.kt                  提取出来的 `passwordFingerprint` + `appendDebugLog` + `DebugLogSink` 接口 + `AndroidDebugLogSink` 默认实现
│   ├── PrivateKeyImporter.kt           SAF byte-read seam(`readPrivateKeyFromUri`)
│   ├── TerminalPane.kt                 AndroidView + IO 协程(吃 ConnectionView)
│   ├── ScrollbackBanner.kt             顶部 "↑ 滚回历史" 横幅
│   ├── ConnectionLogPanel.kt           in-app 日志查看
│   ├── LayoutDecision.kt               纯函数横屏布局决策
│   ├── AppIcons.kt
│   └── HostKeyPromptDialog.kt          Compose host-key TOFU 提示
│
├── terminal/zmodem/             ZMODEM sz → MediaStore Downloads
├── terminal/trzsz/              trzsz tsz → MediaStore Downloads
├── terminal/inbound/            InboundTransferRouter(trzsz/ZMODEM 互斥)
│
├── logging/AppLog.kt            filesDir/app.log(轮转 256KB)+ Logcat
├── logging/LogPolicy.kt         LogClassification × LogDestination(BuildConfigAwareLogPolicy)
├── net/NetworkAvailability.kt
└── theme/

:external
└── com.termux:terminal-emulator / terminal-view v0.118.0(黑盒,不改)
└── com.hierynomus:sshj 0.40.0 + bcprov-jdk18on(advisory 1.78.1,实际解析 1.80.2)
```

## 5. SSH keepalive 当前策略

> **关键决策:绝不**用 `KeepAliveProvider.KEEP_ALIVE`(`KeepAliveRunner` + `keepalive@openssh.com` + want-reply). 该方案在 Tailscale / Doze 路径会因回复未及时到达而**自杀健康连接**(BG-KA-04). 历史推导见 `docs/BACKGROUND_SSH_KEEPALIVE_POSTMORTEM_2026-07-11.md`.

当前实现是**三道防线组合**:

1. **SSH 层 heartbeat**:`SshClient.buildSshjConfig()` 显式 `keepAliveProvider = KeepAliveProvider.HEARTBEAT`. sshj `Heartbeater` 每 `SshConfig.SSH_KEEPALIVE_INTERVAL_SECONDS = 10s` 写一个单向 `SSH_MSG_IGNORE` 包,**不**等回复. 作用:让 NAT 别把映射老化掉.
2. **TCP 层 keepalive**:`SshClient.configureTcpKeepAlive()` 在 `client.socket` 上反射调 `Os.setsockoptInt(IPPROTO_TCP, TCP_KEEPIDLE=4, 10)` / `TCP_KEEPINTVL=5, 5` / `TCP_KEEPCNT=6, 3`. 25 s 检测窗口. 作用:Doze 不暂停 kernel 探针,后台真死时能 RST.
3. **FGS-driven nudge**:`SshKeepAliveService.startForegroundService` + `KeepAliveNudgeRegistry.get()?.nudge()` 每 `FGS_SSH_KEEPALIVE_NUDGE_SECONDS = 3s` 走 FGS 自己的非-daemon `Thread.sleep` 循环写 `SSH_MSG_IGNORE`(`Handler.postDelayed` 后台被 OEM 推迟过 — BG-KA-05). 作用:Doze 暂停 sshj `Heartbeater` 线程时,FGS 仍在 “perceptible” 优先级,probe 仍能落. 绑定的 `KeepAliveNudge` 实现见下方 Issue #17 seam.
4. **SO_TIMEOUT 兜底**:`SshConfig.SO_TIMEOUT_MS = 60_000`. socket read 超时抛 `SocketTimeoutException`,`SshErrorMessages.friendly()` 转单行提示.

未来谁要“更主动”探测对端死亡 — 先读 `docs/BACKGROUND_SSH_KEEPALIVE_POSTMORTEM_2026-07-11.md` §阶段 D;改 `KeepAliveProvider.KEEP_ALIVE` 等同于重新自杀.

### 5.1 The `KeepAliveNudge` seam (Issue #17)

FGS 那一层之前是通过 `SshClient.companion` 的 `AtomicReference<(() -> Boolean)?>` + `hasKeepAliveNudge()` / `nudgeTransportKeepAlive()` 静态方法跟 `SshClient` 耦合的 — 隐式全局,没法注入 fake,stale callback 可能 outlive teardown。Issue #17 把这一层显式化为三个角色:

```kotlin
fun interface KeepAliveNudge { fun nudge(): Boolean }  // 能力面
object KeepAliveNudgeRegistry { fun set(n: KeepAliveNudge?); fun get(): KeepAliveNudge? }  // 进程级 binding
val keepAliveNudge: KeepAliveNudge  // SshClient 上的 inner class 实例,写 SSH_MSG_IGNORE 到 live sshj SSHClient.transport
```

绑定 / 解绑顺序(全部由 `ConnectionRuntime` 拥有,`SshClient` 不再认识 service):

| 路径 | 谁写 registry | 谁停 FGS |
|---|---|---|
| `ConnectionRuntime.handleConnectSuccess`(connect 成功) | `set(sshResult.keepAliveNudge)`,**然后** `SshKeepAliveService.start(...)` — FGS 第一个 tick(≤ 3s)就拿到活的 nudge | start |
| `ConnectionRuntime.teardownInternal`(full disconnect,7 步变成 8 步) | `set(null)`(新 step 5,旧 step 5 之前) → `SshKeepAliveService.stop`(旧 step 5) → `connector.disconnect`(旧 step 6 → step 7) | stop |
| `ConnectionRuntime.abandonHandshake`(handshake 被 epoch 失效丢弃) | `set(null)` → `connector.disconnect` → `SshKeepAliveService.stop` | stop |
| `SshClient.disconnect` 安全网(`SshSession.onClose` hook 路径,SSH writeExecutor 线程上跑,先于 `HanTermAppViewModel.onSessionClosed` 到主线程) | `set(null)` + `SshKeepAliveService.stop` — 跟 runtime 的 teardown 重复但 idempotent(`Context.stopService` 是) | stop |

为什么 `SshClient.disconnect` 还要保留 FGS stop(安全网,而不是只靠 runtime):`SshSession.close()` → `onClose` hook → `SshClient.disconnect` 跑在 sshj 的单线程 `writeExecutor` 上,**先于** main 线程的 `HanTermAppViewModel.onSessionClosed` → `ConnectionRuntime.disconnect` 走完。中间这个窗口里 FGS 仍可能在 tick,没安全网就会写到已关的 sshj transport。double-stop 是 idempotent 的。

**为什么是 registry,不是 intent extras**:Service 是 Android 实例化的(`startForegroundService` → `onStartCommand`),没法构造器注入;registry 是显式 seam,让 service 只依赖 `KeepAliveNudge` 不知道连接器是谁。同一个 pattern 可以泛化到未来的 transport(local shell / mosh) — 它们各自注册自己的 `KeepAliveNudge` 实现到 `ConnectionRuntime.handleConnectSuccess`。

Test 主缝:`KeepAliveNudgeRegistryTest`(6 例,纯 JUnit) + `SshClientKeepAliveNudgeTest`(5 例,`inner class` 的 sshRef null / not-connected / not-running / live / write-throws 路径) + `ConnectionRuntimeTest` 的 `disconnect_clearsRegistryBeforeStoppingFgsBeforeClosingSshj` + `connect_success_bindsKeepAliveNudgeToRegistry` + `abandonHandshake_clearsRegistry` 三个新例。

## 6. 连接生命周期 & 关键不变量

**单一入口**: 所有连接资源的创建 / 拆除走 `ConnectionRuntime.connect()` / `.disconnect()`。凭据与连接字段走 `ConnectionProfile`(`prepareConnect` → `ConnectPrepared` → runtime)。`ConnectionRuntime` 与 `ConnectionProfile` 由 `HanTermApplication` 进程级持有；`HanTermAppViewModel` 只做网络 pre-flight + UI 态(snackbar / log panel / composing hint),并把 runtime 的 `state` / `view` proxy 成 Compose `State`。`TerminalPane` 吃一个能力面 `ConnectionView`(`write` / `read` / `resize` / `lastCloseReason`),不接触 `SshSession` / `PtyBridge`。

**`ConnectionDraftEditor` 是配置 UI 唯一的所有者**: `ConfigScreen` 是 stateless view adapter — 每个用户操作通过 `editor.onIntent(DraftIntent)` 转发;`editor` 暴露 `draft` / `status` / `hasStoredPassword` / `lastSavedFingerprint` 四个 `StateFlow`,UI 通过 `collectAsState()` 订阅。`editor` 的 lifetime 绑 `ConfigScreen` composition(由 `rememberCoroutineScope()` + `remember { ... }` 持有),不进 `HanTermApplication` 进程级。**`prepareConnect` 是 side-effecting write**(连 connect 路径也会持久化 typed password);`ConnectionDraftEditor` **不** 拥有这条路径 — `HanTermAppViewModel.runConnect` 才有。`DebugLogSink` 接口让 editor 在纯 JUnit 下可测,不接触 `android.util.Log` / `AppLog` / `Context.filesDir`。

**Issue #15 — `TeardownState` seam**: `ConnectionRuntime` 还暴露 `teardownState: StateFlow<TeardownState>`,三态 `Idle` / `TearingDown` / `Complete(finalState)`。`disconnect()` 同步盖 `TearingDown`(意图已接收);`teardownInternal` step 7 之后盖 `Complete`(7 步已落地)。`finally` 兜底保证即使 step 6/7 抛错也会走 `Complete`,observer 不会卡在 `TearingDown`。fire-and-forget 异步化(把 `sshj.SSHClient.close()` 拉离 UI 线程)是 #15 的另一面,需要先把 `adapterJob.cancelAndJoin()` 与 StandardTestDispatcher 的虚拟时间在测试里对齐(目前在跑的真实 IO 子任务不 tick 虚拟时间,launched 路径会卡)—— 这部分留到后续 PR,本 PR 只做 observable state seam。

**Activity 重建保活**: `AndroidManifest.xml` 的 `MainActivity` `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode|density|fontScale|locale"` 吃下 99% 配置变更;剩余少数由进程级 `ConnectionRuntime`(Application 持有)保活 live session + `rememberSaveable(connectionState, showTerminal)` 兜底 UI 路由。ViewModel `dispose()` 只取消 UI mirror,不 `runtime.dispose()`。

**`SshSession.readInto` 取消契约**: 协程被取消**不**关闭 session — `finally` 区分 `CancellationException`(skip close)vs 其它出口(close). 让重建后的 reader 能复用同一 session. `SshSessionWriteTest` 用 `awaitWriteQueueDrained` + `FakeTransport.beforeRead` 钩子 + `CANCEL_SENTINEL` 替代 `delay(50)` 防 flake.

**`SessionCloseReason` race-fix**: `SshSession.close(userInitiated = true)` **同步**写 `lastCloseReason = UserInitiated` 在 enqueue 异步 `transport.close()` **之前**. `ConnectionRuntime.disconnect(userInitiated = true)` 在拆 bridge 之前调用它,所以 `TerminalPane` 的 finally 跳过 "Connection Closed" overlay.`setCloseReasonUnlessUserInitiated()` 是唯一 `readInto` 退出分支写入点;新增 catch 自动遵守 SCR-CL-02.

**Canonical teardown 顺序**(固定,编码在 `ConnectionRuntime.teardownInternal`):
1. (user-initiated) stamp UserInitiated on the live session
2. `bridge.close()` — 两侧队列 EOF,outbound 干净退出
3. `adapterJob.cancelAndJoin()` — 必须在 bridge.close **之后**
4. null 内部 refs
5. `SshKeepAliveService.stop` — **FGS 在 sshj 之前**(CLAUDE.md "ordering matters")
6. `connector.disconnect(userInitiated = true)` — 同步拆 sshj
7. 发布 idle `ConnectionView` + `state = Disconnected|Error`

`bridge.close()` 先于 cancel 是为了让 inbound `finally` 看到的不是空 transport 而是已 EOF 的 bridge.`teardownGuard: AtomicReference` 保证 Disconnect 按钮 / inbound finally / BackHandler 三路并发只跑一次 teardown。`Connected` 时再次 `connect` 先完整 teardown 再握手,避免旧 SSH client 泄漏。in-flight connect 用 epoch 令牌,并发 disconnect 会使迟到的 handshake success 被丢弃。

**`SshClient.disconnect` 原子性**: `sshRef: AtomicReference<SSHClient?>`,`getAndSet(null)` 单点赢家执行拆 keepalive + 拆 sshj;其它并发 / 重入 caller 走 no-op. close 抛异常被 `runCatching` 吞,不污染 UI / writeExecutor 线程.

**IO scope**: `ConnectionRuntime` 内部 `CoroutineScope(SupervisorJob + ioDispatcher)` 承载 adapter 三路协程(outbound / inbound / watchdog)。UI scope 取消不带走 bridge 协程;runtime.dispose() 才 cancel IO scope。

**Issue #16 — `HostKeyFingerprint` 模块 + `algorithmVersion` 迁移字段**: TOFU host-key 指纹之前用 `Base64(SHA-256(key.toString()))` —— sshj `PublicKey.toString()` 是 authorized_keys 文本格式("algorithm base64-wire [comment]"),既**不**是 canonical wire bytes(与 `ssh-keygen -lf` 不一致),也**不**稳定(BC/sshj 版本升一下就漂,所有 enrolled host 瞬间被误判为 MITM)。#16 把它拆成独立模块:

- **`HostKeyFingerprint`(interface)** + **`CanonicalHostKeyFingerprint`(生产实现)**:用 sshj `KeyType.fromKey(key).putPubKeyIntoBuffer(Buffer.PlainBuffer(), key)` 取 canonical wire bytes,`SHA-256` + Base64 编码。输出 = `ssh-keygen -lf -E sha256` 的格式,用户可与标准工具对照。`KeyType.UNKNOWN`(sshj 不识别的 key 类型)抛 `IllegalArgumentException` — sshj 0.40 的 `HostKeyVerifier.verify` 拿不到 keyType 就 refuse, fail-closed。
- **JCA→SSH 名称移位**:production 输出的 `keyType` 是 SSH 名(`"ssh-ed25519"`, `"ssh-rsa"`),不是 JCA 名(`"Ed25519"`, `"RSA"`)。`HostKeyFingerprintTest` 的 `ed25519_keyTypeIsSshName` / `rsa_keyTypeIsSshName` 钉住这一移位。
- **`HostFingerprint`(存储行类型)新增 `algorithmVersion: Int` 字段**:v0 = pre-#16 toString-hash 遗留格式,只在读 4 列旧文件时盖 0;v1 = canonical wire bytes(当前唯一由 production 写出的版本)。`algorithmVersion` 让未来 hash 算法升级(比如换 SHA-512)可以**确定性迁移**而不是静默失效。
- **`KnownHostsStore` 文件格式扩展到 5 列**(`host\tport\tkeyType\tfp\talgorithmVersion`),parser 同时接受 4 列 legacy v0 行并盖 `algorithmVersion = 0`。`khs_st_07..09` 三个 case 钉住新旧格式 round-trip + 混合文件解析。
- **`KnownHostsVerifier` 委托给 injected `HostKeyFingerprint`**:verify 流程变成 `presented = fingerprint.compute(key)`,然后四分支:
  1. `existing == null` → 首次连接路径(原行为,不变)
  2. `existing.algorithmVersion == currentVersion && existing == presented` → 命中(KHV-VF-03)
  3. `existing.algorithmVersion == 0 && existing.fp == legacyFingerprint(key) && existing.keyType == legacyKeyType(key)` → **v0→v1 自动迁移**:重新算老 fingerprint 确认是同一把 key,原地覆写成 v1(无 prompt)。这是唯一诚实的"自动迁移"路径 — 因为 v0 哈希是 toString 字节,v1 是 wire 字节,直接 byte-equal 永远 false
  4. else → 旧 mismatch 路径(KHV-VF-04/05):prompt(已 wired)或 refuse

**v0 自动迁移的诚实语义**:升级后第一次连同一 host,如果 BC/sshj 在 enroll 到升级之间没改 `toString()` 格式(最常见),v0 行原地变 v1,无感;如果改了(就是 #16 修的那个 bug 的存在理由),走第 4 分支弹"host key changed"对话框让用户重新信任。比 #16 之前的状态**严格更好**:之前 BC/sshj 任何 bump 都会让每个 host 弹一次 false MITM,训练用户点穿警告 —— 那才是真的安全 regression。

**Test 隔离模式**:`HostKeyFingerprint` 是模块的 primary seam(7 case,真实 BC 密钥);`KnownHostsVerifierTest` 注入 `FakeFingerprint` 让 TOFU 状态机断言不依赖真实 crypto(18 case = 13 原有 + 3 v0/v1 + 2 findExistingAlgorithms);`SshClientHostKeyWiringTest` 加 `sc_khv_05_hostKeyVerifierInterfaceHasExactlyTwoAbstractMethods` 反射 guard sshj 接口漂移(防 #16 删除的 dead `Signature` override 因 sshj 升版变回 abstract 而编译挂)。

## 7. 输入链路路由不变量

完整规则表见 `CLAUDE.md` §"Routing invariants" 与 `implementation_plan.md` §"KeyEvent 路由规则表". 本文件不重复,只列骨架:

- **IME 路径**(可打印字符 + 无 Ctrl/Alt,以及 IME 组合中)走 `TerminalInputConnection`;View 返回 `false`,事件分发给系统 IME.
- **物理键路径**(Ctrl/Alt 修饰键 / 功能键)走 `InputDispatcher.dispatch(InputEvent.Key)` → `KeyMapper.resolve()` → `DispatchResult.Send` 字节吞掉.
- **CTRL+SHIFT+V / CTRL+SPACE / SHIFT+SPACE / LANGUAGE_SWITCH** 走 `Swallow` / `Paste`(`InputDispatcher.dispatch` 决策)— 绝不到 SSH(除 Ctrl+Shift+V 走 clipboard 读取).
- **`userInImeContext` latch**: 仍在 `TerminalInputConnection`(适配层,Issue #14 保留);Gboard `setComposingText("") → deleteSurroundingText` race 的守护;`read-then-consume` 一次性 latch(Sprint 3.5 修复,见 `docs/GEARS_SPEC.md` §TIC-DS-04).`InputDispatcher` 的 `composing` 状态与该 latch 解耦 — latch 由 `TerminalInputConnection.deleteSurroundingText` 在 `dispatch` 调用**之前**消费,dispatcher 决策仅看自己的 `composing` flag.
- **TIC-SK-05(Gboard 软键盘 ENTER 在 composing 中)**: `TerminalInputConnection.sendKeyEvent` 适配层 workaround — 强制结束 composing + 写 `0x0D`,否则 pinyin 会话卡死(cursor-agent Chinese-prompt deadlock). `InputDispatcher.dispatch(Key(ENTER))` 在 composing 下仍返回 `Ignore`(与 onKeyDown 一致);适配层是唯一知道 sendKeyEvent 路径需要这一不对称的地方.

## 8. 测试矩阵

详细列表与文件位置见 `app/build/reports/tests/testDebugUnitTest/index.html`. 类别概览:

| 类别 | 框架 | 覆盖 |
|---|---|---|
| `terminal/` IME / 物理键 / 渲染 | Robolectric | `InputDispatcherTest`(50 case,Issue #14 primary seam)/ `KeyEventRoutingTest`(44 case,View → adapter → dispatcher → endpoint 集成)/ `TerminalInputConnectionTest`(20 case,IC → dispatcher 集成)/ `TerminalViewAltBufferImeRefreshTest`(3 case)/ `TerminalInputConnectionReconnectTest`(1 case)/ `TerminalViewLayoutTest`(3 case)/ `AltBufferScrollCrashGuardTest`(6 case)/ `ScrollbackControllerTest`(16 case)等 |
| `terminal/zmodem` / `trzsz` | 纯 JUnit + Robolectric | 协议帧 + MediaStore 落地 |
| `ssh/` | 纯 JUnit + Robolectric + mockk | `SshSessionWriteTest`(16 case)/ `SshErrorMessagesTest`(17 case)/ `SshClientKeepAliveTest`(5 case)/ `SshClientHostKeyWiringTest`(11 case,含 #16 `sc_khv_05` interface drift 守卫)等 |
| `ssh/auth/` | 纯 JUnit + bcprov | Ed25519 / RSA / 加密私钥路径 |
| `ssh/security/` | Robolectric + bcprov(Issue #16 真实 sshj 密钥夹具) | `HostKeyFingerprintTest`(7 case,Issue #16 primary seam,Ed25519/RSA 真实 BC 密钥 + algorithmVersion + JCA→SSH name 移位 + UNKNOWN fail-closed)/ `KnownHostsStoreTest`(14 case,含 3 个 #16 format 迁移 case)/ `KnownHostsVerifierTest`(18 case,含 FakeFingerprint 注入 + 3 个 #16 v0/v1 case) |
| `data/crypto/` + `data/prefs/` | Robolectric | 加密 slot + 损坏恢复 |
| `logging/` + `ui/` | Robolectric + 纯 JUnit | 轮转 / Logcat 镜像 / ConfigScreen log gate / `ConnectionDraftEditorTest`(Issue #18 primary seam,纯 JUnit + `kotlinx-coroutines-test`,23 例)|

## 9. 决策索引

| 决策 | 详情 |
|---|---|
| 终端核心不自研 | 引入 termux `terminal-emulator` 黑盒 |
| Host-key 指纹 = canonical SSH wire bytes(Issue #16) | `KeyType.fromKey + putPubKeyIntoBuffer` + SHA-256/Base64;不沿用 `key.toString()`(`ssh/security/HostKeyFingerprint.kt` 详);`algorithmVersion` 字段在 `HostFingerprint` 上持久化,支持未来 hash 升级;v0→v1 自动迁移通过 recompute legacy fingerprint 比对(见 §6 "Issue #16 — `HostKeyFingerprint` 模块") |
| `InputType.TYPE_NULL` / `NO_SUGGESTIONS` 不能留 | `implementation_plan.md` §"KeyEvent 路由规则表" + commit `1d3b62a` |
| Gboard `setComposingText("") → deleteSurroundingText` race | `TerminalInputConnection.userInImeContext` 一次性 latch(GEARS TIC-DS-04) |
| `SshTransport` 4 方法窄接口 | 解耦 sshj 700 行抽象;`SshSession` 用 `write / readBytes / resizePty / close` |
| `writeExecutor` 单线程 outbound | SSHJ OutputStream 不能并发,串行保证字节序 |
| SSH keepalive 当前策略 | 本文件 §5 + postmortem §阶段 D |
| 凭据存储 = Keystore + SAF | 防御普通应用;不防御 root / adb backup / debugger |
| `LogPolicy` 集中式敏感数据日志策略(Issue #13) | `logging/LogPolicy.kt` 6 个 `LogClassification` × `LogDestination`,默认 `BuildConfigAwareLogPolicy`;`AppLog` 在 `writeLine` 内 consult policy(安全分类=Drop,非安全=File);调用点必须显式传 `classification` 让审计可见 |
| Host-key TOFU | Sprint 2.5 S1 起 fail-closed(替换 v1.0 PromiscuousVerifier) |
| `PtyBridge` 抽象 | Sprint 3+ `2009c30` + `7ff9958`;为 mosh / 本地 shell 准备的 seam |
| `SessionCloseReason` race-fix | Sprint 3 M17;`close(userInitiated = true)` 同步写 |
| Activity 重建保活 | `configChanges` 99% + 进程级 `ConnectionRuntime`(Application) + `rememberSaveable` 兜底 |
| 双链路分离去重 | 物理键 vs IME 互斥;`KeyResolution` 4 态(Send / Swallow / Ignore / Paste);Sprint 4 起路由策略 owner 是 `InputDispatcher.dispatch(InputEvent) → DispatchResult`,适配层(`ImeKeyRouter` / `TerminalInputConnection`)只做平台 plumbing |
| 凭据编辑意图单一化(Issue #18) | `ConnectionDraftEditor` 持有 `draft` / `status` / `hasStoredPassword` / `lastSavedFingerprint` 四个 `StateFlow` + `onIntent(DraftIntent)`;`DebugLogSink` 让测试不碰 `android.util.Log` / `AppLog` / `Context`;`Success` 自动 2s 清,`Error` 黏住;`ImportKey` 2MB 上限 |
| 双指翻页 scrollback | 反射 `doScroll(MotionEvent, ±mRows)` + Compose 顶部 banner + 新输出徽章 |
| alt-buffer 滚动 NPE 守卫 | `OnTouchListener` + `dispatchGenericMotionEvent` 拦截 |
| TCP keepalive libcore 反射 | `Os.setsockoptInt` / `ForwardingOs` 双路径,任意一步失败静默回退 |

## 10. Out of scope(开源前已确认不做)

- 多主机列表 / 分组 / 新增-编辑-删除 UI
- SFTP / 端口转发 / ProxyJump
- Mosh(复杂度高,留到最后评估)
- TrueColor terminal type(当前 `xterm-256color`)
- xterm mouse protocols
- OpenSSH 7.x / 8.x / 9.x 兼容性矩阵(dropbear / busybox sshd)
- `KeyStoreManager` 在 Robolectric 下的最小冒烟(真机矩阵)
- 横屏布局进一步优化(双栏已 ship,fine-tune 留给后续)
- CI / release signing / ProGuard(基础设施尚未建立)

## 11. 角色分工(其它文档的角色)

| 文档 | 角色 |
|---|---|
| `README.md` | 概览 + 快速上手 + 与 ARCHITECTURE 的指针;**不重复**当前态细节 |
| `CLAUDE.md` | AI agent 操作手册:Hard constraints + Routing invariants + 测试规范;当前态指向 ARCHITECTURE |
| `implementation_plan.md` | 历史设计稿 + 决策推导(ADR 性质);**顶部已加 deprecation banner**,新贡献者不应按其指导实现 |
| `docs/GEARS_SPEC.md` | 行为规范(Given-When-shall),按 Module 编号;Module 16 / 19 标“已删除” |
| `docs/BACKGROUND_SSH_KEEPALIVE_POSTMORTEM_2026-07-11.md` | KeepAlive 决策历史 ADR,本文件 §5 的历史背景 |
| `docs/REVIEW_2026-06-24.md` | Sprint 2 review,历史 ADR |
| `docs/superpowers/specs/` + `plans/` | 设计 spec 与实施计划 |
| `LICENSE` | 待定 |

---

**Maintainer**: [@st6098770633](https://github.com/st6098770633)