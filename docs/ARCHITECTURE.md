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
| Known-hosts TOFU 校验 + MITM 防护 | `ssh/security/KnownHostsStore.kt` + `KnownHostsVerifier.kt` |
| 凭据 AES-256-GCM 加密(Android Keystore + SAF 私钥文件) | `data/profile/ConnectionProfile` + `data/crypto/*` |
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
│   ├── KnownHostsStore.kt              AtomicFile 持久化 known_hosts
│   └── KnownHostsVerifier.kt           sshj HostKeyVerifier 实现(TOFU + 交互式 prompt)
│
├── ui/                           Compose 装配
│   ├── HanTermApp.kt                   顶层状态机(装 ConnectionRuntime)
│   ├── HanTermAppViewModel.kt          UI 态;凭据走 ConnectionProfile;连接资源 proxy 自 runtime
│   ├── ConfigScreen.kt                 表单 + crash banner;SAF 读 bytes → profile.importKey
│   ├── ConnectionFormSection.kt / FingerprintSection.kt / CrashLogCard.kt / ConfigActions.kt
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
3. **FGS-driven nudge**:`SshKeepAliveService.startForegroundService` + `nudgeTransportKeepAlive()` 每 `FGS_SSH_KEEPALIVE_NUDGE_SECONDS = 3s` 走 FGS 自己的 `HandlerThread` 写 `SSH_MSG_IGNORE`. 作用:Doze 暂停 sshj `Heartbeater` 线程时,FGS 仍在 “perceptible” 优先级,probe 仍能落.
4. **SO_TIMEOUT 兜底**:`SshConfig.SO_TIMEOUT_MS = 60_000`. socket read 超时抛 `SocketTimeoutException`,`SshErrorMessages.friendly()` 转单行提示.

未来谁要“更主动”探测对端死亡 — 先读 `docs/BACKGROUND_SSH_KEEPALIVE_POSTMORTEM_2026-07-11.md` §阶段 D;改 `KeepAliveProvider.KEEP_ALIVE` 等同于重新自杀.

## 6. 连接生命周期 & 关键不变量

**单一入口**: 所有连接资源的创建 / 拆除走 `ConnectionRuntime.connect()` / `.disconnect()`。凭据与连接字段走 `ConnectionProfile`(`prepareConnect` → `ConnectPrepared` → runtime)。`ConnectionRuntime` 与 `ConnectionProfile` 由 `HanTermApplication` 进程级持有；`HanTermAppViewModel` 只做网络 pre-flight + UI 态(snackbar / log panel / composing hint),并把 runtime 的 `state` / `view` proxy 成 Compose `State`。`TerminalPane` 吃一个能力面 `ConnectionView`(`write` / `read` / `resize` / `lastCloseReason`),不接触 `SshSession` / `PtyBridge`。

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
| `ssh/` | 纯 JUnit + Robolectric + mockk | `SshSessionWriteTest`(16 case)/ `SshErrorMessagesTest`(17 case)/ `SshClientKeepAliveTest`(5 case)/ `SshClientHostKeyWiringTest`(8 case)等 |
| `ssh/auth/` | 纯 JUnit + bcprov | Ed25519 / RSA / 加密私钥路径 |
| `ssh/security/` | Robolectric | `KnownHostsStoreTest`(11 case)/ `KnownHostsVerifierTest`(10 case) |
| `data/crypto/` + `data/prefs/` | Robolectric | 加密 slot + 损坏恢复 |
| `logging/` + `ui/` | Robolectric | 轮转 / Logcat 镜像 / ConfigScreen log gate |

## 9. 决策索引

| 决策 | 详情 |
|---|---|
| 终端核心不自研 | 引入 termux `terminal-emulator` 黑盒 |
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