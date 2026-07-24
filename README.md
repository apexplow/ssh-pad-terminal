# HanTerm

> Android 平板原生 SSH 客户端。**核心差异化**:正确解耦 Android 输入法体系与终端键盘体系 —— 让中文拼音 IME 在远程 SSH 会话里像本地输入一样工作。

[![Min SDK: 34](https://img.shields.io/badge/min%20SDK-34%20(Android%2014)-blue)](#技术栈)

> **当前架构契约** → [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). 本 README 只做概览与快速上手,**当前态、模块图、keepalive 策略、连接生命周期**等请以 ARCHITECTURE.md 为准.
>
> **Breaking (Issue #19 → Issue #40)**: `minSdk` = **34 (Android 14)**,`targetSdk` / `compileSdk` = **36 (Android 16)**. Issue #19 把基线从 29 抬到 36,Issue #40 在 P3 阶段把它从 36 放回 34(Android 14 及以下不再支持,Android 10–13 设备不在 v1 上市覆盖范围).Android 13 之下的设备仍可通过历史版本(若有)获取.

---

## 解决什么问题

Termius、Termux 等主流 SSH 工具在平板上的中文输入体验都有缺陷:

| 问题 | 后果 |
|---|---|
| `InputType.TYPE_NULL` 锁死文本编辑能力 | IME 候选词、拼音删除、光标移动失效 |
| `TYPE_TEXT_FLAG_NO_SUGGESTIONS` 一刀切 | 中文 / 日文 / 韩文候选词不显示,只能按字母 |
| 物理键盘 + IME 双链路同时触发 | 一个按键重复发两遍 |
| 退格键不分组合状态 | 拼音输入中途按退格,远端被误删 |
| 取消输入(ESC)误判为提交 | 多余的换行/空格发到 SSH |

本项目从零围绕**输入法体系解耦**重新设计,验证一块长期被忽视的平板 SSH 体验。

---

## 当前状态

| Sprint | 状态 | 关键交付 |
|---|---|---|
| **Sprint 0** 基础设施 | ✅ 完成 | Gradle 8.9 + JDK 17 + AGP 8.7.3 + Kotlin 1.9.24,集成 Termux terminal-emulator / terminal-view v0.118,深色 Compose UI 骨架 |
| **Sprint 1** IME 核心 | ✅ 完成 | `TerminalInputConnection`(Gboard `userInImeContext` 锁存标志)+ `KeyMapper.KeyResolution`(Send/Swallow/Ignore 三态)+ `MockEchoSession`,`KeyStoreManager`(AES-256-GCM)+ `AppPreferences` 数据层 |
| **Sprint 1.5** UI 接线 | ✅ 完成 | `ConfigScreen` 接入 `AppPreferences` + `KeyStoreManager`(Plan C 加密 slot)+ SAF 私钥导入;`HanTermApp` 顶层拿 `LocalContext`;密码字段在 Save 后立即从本地 state 清掉,留存只走加密 blob;音量键调字号持久化 |
| **Sprint 2** 真 SSH | ✅ 完成(`feature/sprint-2-real-ssh`) | SSHJ 0.38 + BouncyCastle 1.78.1 接入,密码 + Ed25519/RSA 私钥认证,`SshClient`/`SshSession`/`SshTransport`(4 方法窄接口)+ `ChannelTransport`,xterm-256color + ECHO/ICANON PTY 分配,SIGWINCH 跟踪实测 grid 尺寸,30 s SSH keepalive + 60 s SO_TIMEOUT 防御 NAT 静默断开,`SshErrorMessages.friendly()` 把 SocketTimeoutException / ConnectException / banner-read 失败等转成单行可读英文,`AppLog` + `ConnectionLogPanel` 让用户在 app 内复制日志,`CrashHandler` 把崩溃栈写到 `filesDir/crash.log` 并在 Config 顶部展示 |
| Sprint 2.5 收尾 | ✅ 完成 | ✅ `SshSessionWriteTest` 的 `readInto` 取消契约翻转为「不关 session」(Activity 重建可复用);✅ `TerminalView` 加 alt-buffer 滚动 NPE 守卫(`OnTouchListener` + `dispatchGenericMotionEvent`)+ 6 个回归用例;✅ `TerminalView.onLayout` 重测内层 Termux view 填满 wrapper(1/4-screen 回归)+ PTY resize race 修复(`force=true` 穿透 debounce, TV-PTY-02)+ 2 个 `TerminalViewLayoutTest` 用例;✅ **Sprint 2.5 S1** known_hosts TOFU store + 21 个新测试(`SshClientHostKeyWiringTest` / `KnownHostsStoreTest` / `KnownHostsVerifierTest`);✅ **S2** 私钥 AES-256-GCM 加密存储 + `EncryptedPrivateKeyStore`;✅ **S3+S4** debug log 与 auth 诊断 gating(`ConfigScreenDebugLogGateTest` + `LegacyDebugLogCleanupTest` + 各 `*LogGateTest`);🟡 剩余 6 个 `@Ignore` 的 readInto 时序用例(自然结束路径,运行时序 flake);🟡 SSH 服务器兼容性矩阵(dropbear / busybox sshd) |
| **Sprint 2.5+** vim/nano KeyMapper 数据驱动重构 | ✅ 完成(`docs/code-review-2026-06-24`) | 把 `KeyMapper` 从手写 `when` 块改为 `KEY_MAP: List<KeyMapEntry>` 数据驱动路由表(21 条 entry,首匹配胜出);补全 7 个 vim/nano 缺漏的键位 —— `KEYCODE_ESCAPE`(无 Ctrl)→ `0x1B` / `Shift+Tab`→ `ESC[Z` / `KEYCODE_INSERT`→ `ESC[2~` / `Ctrl+^`→ `0x1E` / `Ctrl+_`→ `0x1F` / `Ctrl+@`→ `0x00` / `Ctrl+?`→ `0x7F`;新加 `KeyMapDoc.kt` 的 `ProgramUsage` + `KeyMapEntry` data class 给每条 entry 配结构化 vim/nano/bash 文档;修复 `Ctrl+ESC` 路由(原本 regression 到 Ignore)与 `KEYCODE_CIRCUMFLEX`/`KEYCODE_UNDERSCORE` 在 Android KeyEvent 不存在改用 `getCharacters()` 匹配;`KeyEventRoutingTest` 加 11 个 case(7 个新键 + ESC-while-composing + end-to-end + meta-test + Ctrl+ESC),从 31 → 42 case。详见 [§架构 / KeyMapper.kt](#keymapperkt-数据驱动路由表) 和 [`docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md`](docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md) |
| **Sprint 3** 体验补完(GEARS Modules 15 / 17) | ✅ 完成(`feat/alt-buffer-cursor-scroll`) | **Module 15** 横屏分栏布局(`ui/LayoutDecision.kt` 纯函数 + `HanTermApp.kt` landscape → 两栏 `Row`,`LayoutDecisionTest` 4 case pin 2×2 真值表,portrait 与 fullscreen 路径 BYTE-FOR-BYTE 不动);**Module 17** `SshSession` 关闭原因区分(`ssh/SessionCloseReason.kt` sealed class + `lastCloseReason` `@Volatile` 字段 + `close(userInitiated = true)` 同步写入 + 单点 `setCloseReasonUnlessUserInitiated()`,`SshSessionWriteTest` 4 个新 `scr_ts_*` case pin SCR-CL-01..02 / SCR-TP-01;`HanTermApp` 三条 user-initiated 路径同步发信号). **Module 16 (snippet)** 已在 2026-07-22 **开源前删除** — 见 [`docs/ARCHITECTURE.md` §3](docs/ARCHITECTURE.md#3-已删除的能力-开源前清理) |
| **Sprint 3+** SSH keepalive + transport decoupling | ✅ 完成(`2009c30` + `7ff9958` + `f932666`) | 三个 commit 把"transport 层"做成可替换的,**且**修了一个 sshj 默认配置下永远检测不到死连接的真实 bug:**`feat(terminal): PtyBridge abstraction — symmetric view/transport endpoints`**(`2009c30`)—— 新增 `terminal/PtyBridge.kt` 接口(`view: PtyEndpoint` / `transport: PtyEndpoint` 两个对称端 + `resize(cols, rows)` 信号 + `setResizeListener` + 幂等 `close()`)+ `terminal/BufferedPtyBridge.kt`(两个 `LinkedBlockingQueue<Any>` + `EOF` 哨兵 + `synchronized(closeLock)` 守护 close-vs-write 竞态,空写静默 no-op,close 后写入也是 no-op,close 自动 EOF 两端,关闭后 read 返 `null` 且**永久**保持 `null`)+ `terminal/PtyBridgeEndpoint.kt`(把 `TerminalEndpoint.write(bytes)` 一行转 `bridge.view.write(bytes)`,生产 IME 链不需要改一行)。**`feat(terminal,ssh): wire PtyBridge into production circuit`**(`7ff9958`)—— 新增 `ssh/SshBridgeAdapter.kt`(两条 IO 协程:**outbound** `bridge.transport.read() → session.write`,**inbound** `session.readInto { bytes -> bridge.transport.write(bytes) }`,inbound `finally` 关 bridge 让 view-side read 看到 EOF;**resize** `bridge.setResizeListener { c,r -> session.resizePty(c,r) }`)。`HanTermApp.handleConnectOutcome` 现在装 `BufferedPtyBridge` + `SshBridgeAdapter(session, bridge).start(bridgeScope)` + `PtyBridgeEndpoint(bridge)` 三件套(`bridgeScope` 是独立 `CoroutineScope(SupervisorJob + Dispatchers.IO)`,不和 UI scope 共享);teardown 顺序固定为 `bridge.close() → adapterJob.cancel() → sshClient.disconnect() → ActiveSshSessionStore.clear()`,避免 inbound 协程 hold 着死 session。**`fix(ssh): active dead-peer keepalive detection + atomic disconnect()`**(`f932666`)—— 2026-07-02 review 找到 sshj 默认 `KeepAliveProvider.HEARTBEAT` 只写 `SSH_MSG_IGNORE` 不等回复,**永远检测不到对端已死**,改用 `KeepAliveProvider.KEEP_ALIVE`(`KeepAliveRunner`)主动探测 + `maxAliveCount = SSH_KEEPALIVE_MAX_ALIVE_COUNT`(3 次未回 → `ConnectionException(CONNECTION_LOST)`);同时 `SshClient.disconnect` 内部 `var sshRef` 改 `AtomicReference<SSHClient?>`,`disconnect(userInitiated)` 用 `getAndSet(null)` 单点赢家执行拆 keepalive + 拆 sshj,**其它并发 / 重入 caller 一律 no-op**(原来是无锁 data race,Disconnect 按钮 + writeExecutor 上的 `onClose` hook + `onSessionClosed` 三路并发时会触发竞态)。`SshClient.connect` 在 kex 之后把 `client.connection.keepAlive.keepAliveInterval = 30s` 与 `(client.connection.keepAlive as KeepAliveRunner).maxAliveCount = 3` 设上去(SC-CN-09)。新增 4 个测试类:`PtyBridgeTest`(19 case:transport→view / view→transport 顺序 + EOF + close 幂等 + 空写 no-op + 阻塞读直到写 / 阻塞读直到 close + 8 线程并发写不丢不重)+ `PtyBridgeEndpointTest`(3 case:forward 到 transport 端 / 空写 no-op / close 后写 no-op)+ `SshBridgeAdapterTest`(5 case:outbound 抵达 transport / inbound 抵达 view / resize 触发 PTY resize / session EOF → close bridge / bridge.close() 切断 outbound)+ `SshClientKeepAliveTest`(5 case:`buildSshjConfig` 显式选 KEEP_ALIVE + `disconnect` 从未 connect 时不抛 + `disconnect` 幂等 + **并发两线程 disconnect 只 close 一次** + close 抛异常被吞掉) |
| **Sprint 3.5** SSHJ 0.40 升级 + 测试债务清理 | ✅ 完成 | SSHJ 0.38.0 → 0.40.0(无生产代码改动,`BouncyCastleBootstrap.kt` / `Auth.kt` 的 kdoc 引用从已删除的 `KeyPairUtils` 更新为 `Ed25519KeyFactory`);`PublicKeyAuthProviderTest` 的 `writeOpenSshPem` 测试 fixture 重写(BC 1.78 的 `JcaMiscPEMGenerator` 给 `EdECPrivateKey` 吐 PKCS#8,SSHJ 0.40 的 `PKCS8KeyFile` 硬拒绝 Ed25519 OID,改用 BC 的 `OpenSSHPrivateKeyUtil.encodePrivateKey` 编码为真正的 OpenSSH v1 wire format),un-Ignore 2 个 Ed25519 测试;`SshSessionWriteTest` 用 `FakeTransport.beforeRead` 钩子 + `CANCEL_SENTINEL` 替换 `delay(50)`,un-Ignore 4 个 `readInto` 时序测试(含 P0 的取消不关 session 契约);6 个编译器警告清零(`KeyMapper.resolve`/`toAnsiSequence` 去掉未用的 `keyCode` 参数,连带更新 4 个生产调用点 + 11 个测试调用点);**follow-up**:sshj 0.40 把 BouncyCastle 透传升级到 1.80.2,三个 1.80.x 的 BC JAR 都带同一份 `META-INF/versions/9/OSGI-INF/MANIFEST.MF`,导致 `mergeDebugJavaResource` 冲突报错,`app/build.gradle.kts` 加 packaging 排除规则修复。测试从 279 active/6 `@Ignore` → **323 总数 / 312 active / 0 `@Ignore` / 11 `assumeTrue`**。详见 [`docs/PR_DESCRIPTION_SPRINT_3.5.md`](docs/PR_DESCRIPTION_SPRINT_3.5.md) |
| **Sprint 3.6** 稳定性加固与品牌重塑 | ✅ 完成 | 修复断线重连后 Gboard 缓存旧 `InputConnection` 导致输入死锁的 bug (`TerminalView.bindEndpoint` 增加 `imm.restartInput(this)`)；修复文本选择期间按键导致 `TerminalView.handleKeyCode` 遭遇 null session 的崩溃 (`dispatchKeyEvent` 拦截 + `onKeyDown` 消费)；项目包名重构为 `com.taosun.hanterm` 并正式定名 HanTerm。 |
| **Sprint 3.7** tmux session 切换器(GEARS Module 19) | ⛔ 已删除(2026-07-22) | 详见 [`docs/ARCHITECTURE.md` §3](docs/ARCHITECTURE.md#3-已删除的能力-开源前清理). 历史上 Sprint 3.7 实现了 Module 19,后被识别为“代码图 ≠ 产品图”的 feature island,2026-07-22 开源前清理时整组删除(`TmuxSession*.kt` + `ShellIntegration*.kt` + `RemoteCommandExecutor.kt` + `SshjRemoteCommandExecutor.kt` + shell integration raw resources + `SnippetStore.kt` + `TmuxDrawer.kt` + `SnippetPanel.kt` + `SnippetPayload.kt` 全部从生产代码移除,测试同步删除). |
| Sprint 4+ 主机管理 / SFTP / Mosh | 📋 远期 | 见 [路线图](#路线图) |

当前在平板上**配置主机 / 保存密码(Keystore AES-256-GCM) / 导入并加密私钥 / known_hosts TOFU 校验 / 音量键调字号 / vim / nano 全快捷键 / 双指翻页 scrollback / 横屏双栏布局 / Disconnect 后不再误弹 "Connection Closed" 红覆盖 / in-app 诊断日志 + 崩溃栈 + Copy logs / `PtyBridge` 把 transport 层做成可替换 / `SshClient.disconnect` 并发原子**。剩下的是多主机列表、SFTP、Mosh、SSH-keepalive 服务器兼容性矩阵等 Sprint 4+ 工作。完整当前能力清单见 [`docs/ARCHITECTURE.md` §2](docs/ARCHITECTURE.md#2-当前能力-shipped-capabilities)。

---

## 快速上手

### 环境要求

| 工具 | 版本 | 安装 |
|---|---|---|
| JDK | 21+ | `sdk install java 21.0.7-tem`(项目 `gradlew` 自带 Temurin 21,无需本机 JDK) |
| Android SDK | platform-36 + build-tools 36.0.0 | `sdkmanager "platforms;android-36" "build-tools;36.0.0"` |
| Git | 任意 | — |

### 克隆 + 构建

```bash
git clone git@github.com:st6098770633/ssh-pad-terminal.git
cd ssh-pad-terminal

# 跑测试(首次约 5-10 分钟,后续命中缓存 < 30s)
./gradlew :app:testDebugUnitTest

# 出包(产物在 app/build/outputs/apk/debug/app-debug.apk)
./gradlew :app:assembleDebug

# 安装到连着的设备
./gradlew :app:installDebug
```

### 远程文件落到平板（`tsz` / `sz`）

**推荐（tmux 内外都可用）**：远程安装 [trzsz](https://trzsz.github.io)，用 `tsz`：

```bash
# 远程（Debian/Ubuntu 示例）
pip install trzsz   # 或发行版包
tsz app/build/outputs/apk/debug/app-debug.apk
tsz docs/note.md
```

**非 tmux 的普通 shell** 也可继续用经典 `lrzsz` 的 `sz`（ZMODEM；**不要在 tmux 里跑 `sz`**，tmux 会弄坏协议）：

```bash
sz docs/note.md
```

App 在 PTY 字节流里自动识别 `::TRZSZ:TRANSFER:S:…`（trzsz）与 ZMODEM ZRQINIT，把文件写入系统 **Downloads**，Snackbar 提示 `Saved to Downloads: …`。不需要 SFTP UI；`trz` / `rz` 上传尚未实现。

### 跑测试

```bash
./gradlew :app:testDebugUnitTest
```

报告位置:`app/build/reports/tests/testDebugUnitTest/index.html`,XML 在 `app/build/test-results/`。

---

## 架构

### 数据流(SSH → 屏幕)

```
SSH Server
  │ (TCP, SSH_MSG_CHANNEL_DATA)
  ▼
SshSession.readInto / PtyBridge   [IO 协程]
  │
  ▼
InboundTransferRouter.onInbound   [trzsz tsz | ZMODEM sz；回复走 TerminalEndpoint.write]
  │ display
  ▼
TerminalEmulator.append(bytes, len) [Termux 黑盒]
  │
  ▼
Channel<Unit>(CONFLATED)            [节流、防爆栈]
  │
  ▼
[UI 线程消费协程]
  │
  ▼
termuxView.invalidate()             [VSync 统一重绘]
  │
  ▼
屏幕
```

远程 `tsz file` /（非 tmux）`sz file` 时 router 进入 capture：协议帧不上屏，文件写入 MediaStore Downloads，Snackbar 提示 `Saved to Downloads: …`。

### 数据流(PtyBridge 电路 — 当前生产路径)

```
SSH Server
  │ (TCP, SSH_MSG_CHANNEL_DATA)
  ▼
SshSession.readInto(bytes → sink)   [IO 协程]
  │  ChannelTransport.readBytes()
  ▼
SshBridgeAdapter.inbound coroutine  [IO,bridgeScope]
  │  sink: bridge.transport.write(bytes)
  ▼
BufferedPtyBridge  [两端 + 两个 LinkedBlockingQueue + EOF 哨兵]
  │
  ├── bridge.view.read()       ── view-side reader 消费
  ▼
TerminalPane 的 IO 协程
  │  bridge.view.read() → emulator.append(bytes, len)   [Termux 黑盒]
  ▼
termuxView.invalidate()        [VSync 统一重绘]
  │
  ▼
屏幕

(用户输入)
  IME 链 / KeyMapper
  ▼
TerminalEndpoint.write(bytes) = PtyBridgeEndpoint.write(bytes)
  ▼
bridge.view.write(bytes)
  ▼
BufferedPtyBridge
  ▼
bridge.transport.read() ← SshBridgeAdapter.outbound coroutine
  ▼
SshSession.write(bytes) → writeExecutor → transport.write
```

**两路 IO 都过 bridge**:`SshBridgeAdapter.start(scope)` 同时启动 outbound 与 inbound 两个 `async(Dispatchers.IO)`,任一自然结束(EOF / 异常 / 结构化取消)都会 `bridge.close()` 让另一路看到 `read() == null` 干净退出。`bridgeScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }` 与 UI scope 解耦,UI 取消不会带走 bridge 协程,反之亦然。

### 数据流(用户输入 → SSH)

```
物理键盘按键 (KeyEvent)
  │
  ├── 可打印字符 + 无 Ctrl/Alt
  │     │
  │     └──► InputConnection.commitText() → UTF-8 → SshSession.write
  │                                          │
  │                                          └─► writeExecutor (单线程)
  │                                                  └─► transport.write + flush
  │
  ├── Ctrl/Alt 修饰 / 功能键
  │     │
  │     └──► onKeyDown() → KeyMapper.resolve() → endpoint.write(ANSI)
  │                                                       │
  │                                                       └─► writeExecutor
  │
  └── IME 组合中(拼音阶段)
        │
        └──► InputConnection.setComposingText() → 本地 hint 浮层,**不发 SSH**
```

**关键约束**:两条链路互斥,不可重复发送。`KeyMapper.KeyResolution` 把每条物理键决策分成三类 —— `Send`(转发字节并吞掉)/ `Swallow`(吞掉不转发,留给 IME)/ `Ignore`(返回 false 交给 InputConnection)。详见 [`implementation_plan.md` §输入链路设计](implementation_plan.md)。

### 数据流(双指翻页 scrollback)

```
用户双指按在 wrapper TerminalView 上
  │
  ▼
wrapper.dispatchTouchEvent
  │
  ▼
ScrollbackController.onTouchEvent  [wrapper 反射调用,UI 线程]
  │  - pointerCount<2 → PassThrough(单指原样转发)
  │  - pointerCount>=2 → 记初始 centroidY,set isInScrollback=true → Consumed
  │  - 后续 ACTION_MOVE:更新 finalY
  │  - ACTION_UP (最后一次抬手):commitGesture
  │     - 计算 dy = finalY - initialY
  │     - 与 lineSpacing * mRows / 2 阈值比较
  │     - |dy| > threshold:反射调 innerView.doScroll(ev, ±mRows) 翻一页
  │     - |dy| ≤ threshold:no-op
  │     - 翻完后读 innerView.mTopRow:== 0 → 自动退出 scrollback
  │
  ▼
innerView (com.termux.view.TerminalView)  [第 3 分支 safe,mutate mTopRow]
  │
  ▼
屏幕重绘(可见内容 = buffer[mTopRow .. mTopRow + mRows - 1])

新输出计数(IO 线程):
  transcriptOutput.write(bytes, len) [UI 线程 post 后触发]
    → if isInScrollback:scrollbackController.onTranscriptWrite(len, columns)
       → MutableStateFlow.update { copy(pendingOutputCount += lines) }  [线程安全]

Banner 订阅(Compose):
  terminal.scrollbackState.collectAsState()  [LaunchedEffect 拥有,dispose 自动 cancel]
```

### 模块划分

```
:app/
├── terminal/               ★ IME 与渲染
│   ├── TerminalView.kt          继承 FrameLayout,内嵌 Termux.TerminalView;
│   │                            重写 IME / 物理键 / 报告 PTY 尺寸变化;
│   │                            Sprint 2.5 加 alt-buffer 滚动 NPE 守卫
│   │                            (OnTouchListener + dispatchGenericMotionEvent
│   │                            + isAltBufferScrollCrashPath 谓词)
│   ├── TerminalInputConnection  IME 5 方法(含 Gboard userInImeContext 锁存)
│   ├── KeyMapper.kt             **数据驱动路由表**:`KEY_MAP: List<KeyMapEntry>`
│   │                            (21 条 entry,首匹配胜出);`KeyResolution` 4 态
│   │                            (Send / Swallow / Ignore / Paste);`resolve()`
│   │                            遍历 `KEY_MAP` 选首个 match;`KeyMapDoc.kt`
│   │                            的 `ProgramUsage` + `KeyMapEntry` 配每条 entry
│   │                            的结构化 vim/nano/bash 文档
│   ├── KeyMapDoc.kt             `ProgramUsage`(mode + effect) + `KeyMapEntry`
│   │                            (description + match + verdict + vim/nano/bash)
│   │                            data class,纯文档结构,无运行时行为
│   ├── TerminalEndpoint.kt      SAM 接口(`MockEchoSession` 与 `PtyBridgeEndpoint` 实现)
│   ├── PtyBridge.kt             transport-可替换的抽象(`view` / `transport` 两端 +
│   │                            `resize` + `setResizeListener` + 幂等 `close()`,
│   │                            是 mosh / 本地 shell 落点的"missing middle");
│   │                            `PtyEndpoint`(对称两端)
│   ├── BufferedPtyBridge.kt     PtyBridge v1 实现:两条 LinkedBlockingQueue +
│   │                            EOF 哨兵 + synchronized(closeLock) 守护
│   │                            close-vs-write 竞态;空写 no-op;close 后写入
│   │                            no-op 且 read 永远返 null
│   ├── PtyBridgeEndpoint.kt     `TerminalEndpoint` 适配器:`write(bytes)` =
│   │                            `bridge.view.write(bytes)`,IME 链零改动
│   ├── TerminalComposingView    拼音 hint 回调
│   ├── ScrollbackController.kt 双指翻页手势状态机:gestureActive 标志 + 反射调
│   │                            innerView.doScroll(MotionEvent, ±mRows);
│   │                            scrollToBottom 用反射直接写 innerView.mTopRow=0;
│   │                            onTranscriptWrite 用 MutableStateFlow.update
│   │                            累计 pendingOutputCount(线程安全,无 AtomicInteger)
│   ├── MockEchoSession          Sprint 1 mock,断线兜底
│   └── FontSizeController       音量键字号调整的跨层桥(Compose State + Channel)
│
├── data/                   凭据持久化
│   ├── crypto/KeyStoreManager.kt    Android Keystore AES-256-GCM(Plan C 加密 slot)
│   └── prefs/AppPreferences.kt      SharedPreferences:host / port / user / 私钥名 /
│                                    加密密码 blob / 字号
│
├── ssh/                    ★ Sprint 2 真 SSH
│   ├── SshClient.kt            SSHJ 0.40 连接编排 + Auth dispatch + 三道防线 keepalive
│   │                            (HEARTBEAT + TCP + FGS nudge,见 ARCHITECTURE.md §5) +
│   │                            `AtomicReference<SSHClient?>` 单点 disconnect 赢家
│   ├── SshBridgeAdapter.kt     把 `SshSession` 接进 `PtyBridge.transport` 的两路 IO 协程
│   │                            + resize 转发;`start(scope)` 返 `Job`,cancel = 拆桥
│   ├── SshSession.kt           TerminalEndpoint 实现 + readInto(单线程 write exec);
│   │                            Sprint 2.5:readInto 取消路径不再 close(session
│   │                            生命周期由 SshClient.disconnect 单点拥有)
│   ├── SshTransport.kt         4 方法窄接口(write / readBytes / resizePty / close)
│   ├── ChannelTransport.kt     生产实现,包 SSHJ Channel + 强制 flush
│   ├── SshConfig.kt            DEFAULT_PORT/TERM/PTY/CONNECT_TIMEOUT/SO_TIMEOUT 等常量
│   ├── SshErrorMessages.kt     Throwable → 单行可读英文(含 sshj cause 链回溯)
│   ├── SshException.kt         内部异常(友好 message + 原 cause)
│   ├── BouncyCastleBootstrap.kt 幂等注册 BouncyCastle JCE provider
│   ├── ActiveSshSessionStore.kt  进程级 AtomicReference<SshSession?>,让重建后的
│   │                            Activity 重新绑定到仍存活的 session(分屏 / 进程
│   │                            死亡 + 恢复场景)
│   └── auth/
│       ├── Auth.kt             sealed class PasswordAuth / PublicKeyAuth
│       ├── SshAuthProvider.kt  strategy 接口
│       ├── PasswordAuthProvider.kt
│       └── PublicKeyAuthProvider.kt  PEM(RSA + Ed25519)加载
│
├── ui/                     Compose 装配
│   ├── HanTermApp.kt           顶层:ConnectionState 状态机 + Connect/Disconnect 接线
│   ├── ConfigScreen.kt         表单 + Crash 日志展示 + 私钥 SAF 导入
│   ├── TerminalPane.kt         AndroidView 包装 + IO 协程驱动 emulator.append;
│   │                            Box 叠 ScrollbackBanner 在 AndroidView 之上
│   ├── ScrollbackBanner.kt     Compose 横幅:hidden by default;isInScrollback 时
│   │                            显示 "↑ 滚回历史",有新输出时附 "▼ N 行新输出"
│   │                            (coerceAtMost 9999);整行 clickable → scrollToBottom
│   └── ConnectionLogPanel.kt   AppLog 内嵌查看器 + Copy logs 按钮
│
├── logging/AppLog.kt       filesDir/app.log 文件 sink(轮转 256KB)+ Logcat 镜像
├── net/NetworkAvailability.kt  ConnectivityManager 在线判断
│
└── theme/                  Warp 风格深色 + JetBrainsMono 字体

:external
└── com.termux:terminal-emulator / terminal-view v0.118.0   ← 黑盒引入,基本不改
└── com.hierynomus:sshj 0.40.0(Sprint 3.5 从 0.38.0 升级)+ bcprov-jdk18on 声明 1.78.1,
    但 sshj 0.40 的依赖图要求 `[1.80,1.81)`,Gradle 实际透传解析到 1.80.2
    (`bcprov/bcpkix/bcutil-jdk18on` 三者一致);声明的 1.78.1 目前只是 advisory,
    见 CLAUDE.md "Hard constraints"
```

---

## 关键设计决策

### 1. 终端核心不自研

**错误**:从零写 `Canvas + ANSI 状态机 + 双缓冲 + CJK 双宽字符`。

**正确**:引入 [Termux terminal-emulator](https://github.com/termux/termux-app/tree/master/terminal-emulator)(Apache 2.0)。它已经解决光标定位、选择区域、CJK 宽字符、控制序列(CSI/OSC/DCS)、退格与本地回显一致性。

**约束**:`terminal-emulator` 黑盒使用,**任何修改其内部的冲动都必须先提 Issue 讨论**。

### 2. `InputType.TYPE_NULL` 与 `NO_SUGGESTIONS` 都不能留

**错误 A**:`InputType.TYPE_NULL` — IME 候选词、拼音删除、光标移动全废。

**错误 B**:`TYPE_TEXT_FLAG_NO_SUGGESTIONS` — 中文 / 日文 / 韩文 IME 的候选词 UI 被吞掉,看似"干净"但 IME 无法做 composing。

**正确**(`TerminalView.kt:onCreateInputConnection`):

```kotlin
outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
    InputType.TYPE_TEXT_VARIATION_NORMAL or
    InputType.TYPE_TEXT_FLAG_MULTI_LINE   // optional but harmless on API 29+
outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
    EditorInfo.IME_FLAG_NO_FULLSCREEN or
    EditorInfo.IME_FLAG_NO_EXTRACT_UI
```

**回顾**:Sprint 1 起先用了 `NO_SUGGESTIONS`,Sprint 2 接入真 SSH 后 `1d3b62a fix(ime): drop VISIBLE_PASSWORD/NO_SUGGESTIONS so Chinese IME can compose` 才把这层补回去。

### 3. Gboard 的"setComposingText("") → deleteSurroundingText" 竞态

Gboard 在用户取消拼音时,会先 `setComposingText("")` 把 `isComposing` 翻成 false,然后**在同一事务**调 `deleteSurroundingText(1, 0)`。

如果 `deleteSurroundingText` 直接读 `isComposing`,会判定为"非组合"并把 DEL 发到 SSH —— 用户明明在取消拼音,远端却被删了一个字符。

**正确**:`TerminalInputConnection` 维护一个独立的 `userInImeContext` 锁存标志 —— 一旦进过 composing / commit / finish,锁存保持 true,直到一次真正的"非 IME 退格"后清零。后续的 `deleteSurroundingText` 始终优先走 IME 通道。

### 4. `SshTransport` 4 方法窄接口

SSHJ 的 `Channel` 是 700 行抽象类,30+ 抽象方法,mock 出来既脆弱又跟 sshj 版本强耦合。`SshSession` 只用 4 个动作:`write / readBytes / resizePty / close`。

**正确**:抽 `SshTransport` 接口,生产用 `ChannelTransport`,测试用 `FakeTransport`(LinkedBlockingQueue + 录制写入)。新增 SSHJ 大版本时,接口不变,`ChannelTransport` 改实现即可,测试无需重写。

### 5. `writeExecutor` 单线程串行 outbound

`SshSession.write` 自身非阻塞,但底层 SSHJ 的 `OutputStream.write` 不能并发调用(会把两次 keystroke 的字节交错,远端 bash 看到 `l" "s` 之类的乱码)。

**正确**:`Executors.newSingleThreadExecutor { Thread(it, "SshSession-write") }`,`write` 与 `resizePty` 都走这条队列,串行抵达 socket。`SshSessionWriteTest` 验证多字节累积顺序、empty 写是 no-op、`close` 幂等、`awaitWriteQueueDrained` 测试用钩子。

### 6. SSH keepalive + 原子 disconnect

完整当前策略在 [`docs/ARCHITECTURE.md` §5](docs/ARCHITECTURE.md#5-ssh-keepalive-当前策略). 摘要: **HEARTBEAT 单向 IGNORE 10s**(让 NAT 别老化映射) + **TCP keepalive 25s 窗口**(`SshClient.configureTcpKeepAlive`) + **FGS-driven nudge 3s**(`SshKeepAliveService` 防 Doze) + **`SO_TIMEOUT_MS = 60_000`** 兜底.

**绝不要**用 `KeepAliveProvider.KEEP_ALIVE`(`KeepAliveRunner` + `keepalive@openssh.com` + want-reply)— 该方案在 2026-07-11 postmortem 中被判定**自杀健康连接**(BG-KA-04,见 `docs/BACKGROUND_SSH_KEEPALIVE_POSTMORTEM_2026-07-11.md` §阶段 D).

**原子 disconnect**:`SshClient.disconnect` 用 `AtomicReference<SSHClient?>.getAndSet(null)` 单点赢家,Disconnect 按钮 + writeExecutor `onClose` + UI error handler 三路并发安全;close 失败被 `runCatching` 吞,绝不抛回 caller.

### 7. 凭据存储 = Keystore + SAF 文件

- **密码**:Android Keystore AES-256-GCM,密文 Base64 进 `AppPreferences.KEY_ENCRYPTED_PASSWORD`(`KeyStoreManager.encrypt/decrypt`)
- **私钥文件**:`filesDir/keys/*.pem`,由 SAF `OpenDocument` 导入,文件名走 `sanitizeFileName`
- **解密的明文**只在内存里活几毫秒,`saveConfig` 后立刻 `password = ""` 清本地 state

**威胁边界**:防御"其他普通应用读私钥 / 密码"。**不防御**:root 设备、adb backup 迁移、调试器附加。可选 `setUserAuthenticationRequired(true)` 升级到生物识别解锁。

### 8. host key 校验 = v1.0 暂不实现

`SshClient` ~~默认装 `PromiscuousVerifier`~~ —— **Sprint 2.5 S1 已替换为 `KnownHostsVerifier`**(`Module 11` + 21 个新测试),在 Sprint 3 之前即已走通 TOFU known_hosts store + MITM 防护路径。v1.0 历史上有意先用 `PromiscuousVerifier`,Sprint 2.5 S1 起 fail-closed。

### 9. 双链路分离去重

| 事件 | 处理链路 | 行为 |
|---|---|---|
| 可打印字符(无 Ctrl/Alt) | `InputConnection.commitText()` | `onKeyDown` 返回 `false`,系统分发 |
| 可打印字符 + Ctrl/Alt(全 ASCII 控制集 A-Z + `\` + `]` + `ESC`) | `onKeyDown` → `KeyMapper.resolve()` → `ctrlControlByte` | 转 xterm 控制字节(26 字母 → `0x01-0x1A`,`\` → `0x1C`,`]` → `0x1D`,`[` + `ESC` → `0x1B`),`KeyResolution.Send` **吞掉**不传 InputConnection。`KEYCODE_V` 故意不映射(Ctrl+V 留给 IME 出字面 "V");`Ctrl+0..9` / `Ctrl+@` / `Ctrl+^` / `Ctrl+_` / `Ctrl+?` 在 Sprint 2.5+ 重构里补全,见下 |
| `KEYCODE_DEL`(组合中) | `InputConnection.deleteSurroundingText` | `onKeyDown` 返回 `false`,IME 自管 |
| `KEYCODE_DEL`(非组合) | `onKeyDown` | 发 `0x7F`(DEL),**吞掉** |
| `KEYCODE_FORWARD_DEL` | `onKeyDown` | 发 `ESC[3~`,**吞掉** |
| IME 组合中(拼音) | `setComposingText` | 本地 hint,**不发 SSH** |
| IME 提交(汉字上屏) | `commitText` | UTF-8 发 SSH,清 composing 状态 |
| Ctrl+Space / Shift+Space / KEYCODE_LANGUAGE_SWITCH | `onKeyDown` → `KeyMapper.resolve()` | `KeyResolution.Swallow` —— 吞掉,IME 内部事 |
| Ctrl+Shift+V | `onKeyDown` → `KeyMapper.resolve()` | `KeyResolution.Paste` —— 读剪贴板写 UTF-8(必须在 Ctrl+V 之前判,否则会被 Gboard / 谷歌拼音吞掉) |

**Sprint 2.5+ 新增的 vim/nano 键位**(Sprint 1 路由表里全部 `Ignore` / 缺失,Sprint 2.5+ 补到 `KeyMapper` 的 `KEY_MAP`):

| 事件 | 字节 | vim | nano | bash |
|---|---|---|---|---|
| `KEYCODE_ESCAPE`(无 Ctrl) | `0x1B` | insert/visual→normal | 取消当前操作 | 取消未完成命令 |
| `KEYCODE_TAB` + Shift(Back-Tab) | `ESC[Z` | 部分配置下 `gT`(反向 tab) | 撤销缩进 | 反向补全 |
| `KEYCODE_INSERT` | `ESC[2~` | normal: toggle insert/replace | 无 native binding | 无 native binding |
| `Ctrl+^`(KEYCODE_CIRCUMFLEX 不存在 → 改用 `getCharacters() == "^"` 匹配) | `0x1E` (RS) | alt file | 无 | 无 |
| `Ctrl+_`(KEYCODE_UNDERSCORE 不存在 → 改用 `getCharacters() == "_"` 匹配) | `0x1F` (US) | undo(compatible 模式) | 跳到行号 | 无 |
| `Ctrl+@` | `0x00` (NUL) | 常用 `<C-@>` 用户映射 | set mark | set-mark |
| `Ctrl+?` | `0x7F` (DEL) | 等价 DEL | 等价 DEL | 等价 DEL |

完整规则表见 [`implementation_plan.md` §KeyEvent 路由规则表](implementation_plan.md)。`KEY_MAP` 21 条 entry 的逐条文档见 `KeyMapper.kt` 表上方 kdoc + [`docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md`](docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md)。

### 10. 用户日志与崩溃栈的内嵌可见

**问题**:`adb logcat` 是诊断金标准,但平板用户多半没装 adb。

**正确**:
- `AppLog`(Sprint 2 增):所有 `SshClient.connect` / `SshSession.readInto` 失败的 throwable + 友好 message + 完整 stacktrace 写进 `filesDir/app.log`(轮转 256 KB),同时镜像到 Logcat
- `ConnectionLogPanel`:失败 overlay 上有 "Show logs" / "Copy logs" 按钮 —— 一键把整段日志贴到剪贴板
- `CrashHandler`:在 `Thread.setDefaultUncaughtExceptionHandler` 上挂一层,把栈写 `filesDir/crash.log`,下次启动 `ConfigScreen` 顶部展示并支持 Copy / Dismiss
- `reader` 线程的 "Software caused connection abort" 不算崩溃,单独排除(详见 `MainActivity.kt:isHandledTransportAbort`)

### 11. 分屏 / 进程死亡后保留终端界面

**问题**:平板用户的常用操作是分屏。默认 Android 在分屏(`screenSize` / `screenLayout` 变化)时销毁重建 MainActivity,Compose 的 `remember` 状态(`activeSession` / `showTerminal`)被复位,用户瞬间被踢回登录页 —— 即便 SSH 会话仍存活于 `SshKeepAliveService` 保活的进程里。日志里也容易观察到这个症状:连接成功 → 进 split-screen → `activeSession` 变 `null` → 立即 `SshClient.disconnect()` → 用户在登录页看到 "Disconnected"。

**正确**(两层互补):

1. **`AndroidManifest.xml`** `MainActivity` 声明 `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode|density|fontScale|locale"`,所有这些配置变更不再重建 Activity。`TerminalView` 的 PTY resize listener 在 View 重测时自动把新尺寸以 SIGWINCH 推给远端,终端 grid 跟着新 size 走,IO 协程不中断。99% 的配置变更都吃在这里。

2. **进程级 holder + `rememberSaveable`** 兜底(configChanges 救不到的少数情形:低内存被杀后系统恢复、用户从 recents 滑动清掉后重开):
   - `ActiveSshSessionStore`(进程级 `AtomicReference<SshSession?>`)持有 live session 引用
   - `HanTermApp` 首屏 `remember { ActiveSshSessionStore.get() }` 重新绑定;`showTerminal` / `connectionState` 用 `rememberSaveable` + 自定义 `listSaver` 序列化(Connecting 在恢复时降级为 Disconnected,旧协程已死)
   - 所有 Connect / Disconnect 路径同步 `set` / `clear` store,避免重建后绑到已死的 session
   - `SshSession.readInto` 的 `finally` 改为**取消不 close** —— session 生命周期由 `SshClient.disconnect()` 单点拥有,reader 只是临时消费者(详见 §12)

### 12. `SshSession` 生命周期 ≠ `readInto` 协程生命周期

**问题**:原契约 `SshSession.readInto` 的 `finally { close() }` 在协程被取消时也会执行,等价于"上层取消 reader = 关掉 session"。在 §11 描述的 Activity 重建场景下,Compose 取消旧 `LaunchedEffect` → `readInto` 取消 → `close()` 顺手关 session → 新 Activity 拿不到可重绑的引用。即便有 `ActiveSshSessionStore`,被关掉的 session 也不再"live"。

**正确**:`readInto` 的 `finally` 区分语义:

| 退出原因 | `finally` 行为 |
|---|---|
| EOF(`readBytes()` 返 null) | `close()` — 自然结束 |
| `SocketException` / `SocketTimeoutException` / `SSHException` | `close()` — 传输层已死,无意义继续 |
| `sink` 抛异常 | `close()` — 避免泄漏 socket |
| 协程 `CancellationException` | **不** close — session 仍是 live,可被下一个 reader 复用 |

session 生命周期由 `SshClient.disconnect()` 单点拥有(`SshClient.connect` 起 keepalive 前台服务,`SshClient.disconnect` 拆服务再拆 sshj 客户端)。`SshSessionWriteTest` 中 `@Ignore` 的 `test_readInto_closesTransportOnCancellation` 已翻转为 `test_readInto_doesNotCloseTransportOnCancellation`,断言取消时 `transport.closeCalled` 必须为 `false`,且 `onClose` hook 不会被触发。

### 13. PTY resize race:注册 listener 时的 fire-once 必须强制穿透 debounce

**问题**(TV-PTY-02):`TerminalView` 的构造函数在内层 Termux view 上挂了 `addOnLayoutChangeListener`,首次布局跑起来调 `reportPtyResize` —— 但此时 `ptyResizeListener` 还是 null(Spring 的 `LaunchedEffect` 在 `TerminalPane` 里稍后才注册),SIGWINCH 被丢掉到地板;**与此同时** `lastResizeCols/Rows` 已经被填上正确的 wrapper-derived 尺寸(200×62)。然后 `LaunchedEffect` 跑起来,调 `setPtyResizeListener`,fire-once 副作用再跑一次 `reportPtyResize`,**撞到 debounce 检查** `if (cols == lastResizeCols && rows == lastResizeRows) return` —— 被同样丢掉。结果:SSH PTY 永远停在 `SshConfig.DEFAULT_PTY_COLS=80 / DEFAULT_PTY_ROWS=24`,tmux 用 80×24 渲染在 200×71 的可见 grid 里,状态栏落在屏幕中央。这个 bug 在 IME 弹起时自愈(新的布局 pass 给了不同尺寸,debounce 通过),所以用户看到"轻点屏幕后状态栏突然跳到底部"。

**正确**(`TerminalView.kt:reportPtyResize(force: Boolean = false)`):
- 默认 `force = false`,所有原有调用点保持 SIGWINCH-spam 防护
- 仅 `setPtyResizeListener` 的 fire-once 用 `force = true` —— 让注册时的协议必发 fire 始终抵达新绑定的 session,即便之前的 layout pass 已经把 `lastResizeCols/Rows` 填好

`TerminalViewLayoutTest.setPtyResizeListener_invokesListenerImmediately_afterLayoutPass` 用 mockk 注入带真实字体指标的 `TerminalRenderer`(Robolectric 的字体影子返回 0,会提前在 zero-metrics 防御 guard 短路掉),端到端 pin 住 race 路径。

### 14. 双指翻页 scrollback:复用内层 view 的 doScroll,不直接写 buffer

**问题**:Termux 的 `TerminalView` 自带手势滚动(单指 / 滚轮),在平板上从未真正响应过。`com.termux.terminal.TerminalEmulator` 没有 `mTopRow` / `mTotalRows` 字段(它们在 `TerminalBuffer` 上,是包私有的),所以"直接 mutate emulator scrollback"在 Java 公开 API 层面根本走不通。

**尝试过的方案**:
- (a) 反射访问 buffer 的包私有字段 → 越界,违反 CLAUDE.md "don't modify com.termux internals"
- (b) 反射 inner view 的私有 `mTopRow` → 等于反射 com.termux internals,同上违反
- (c) 通过 `emulator.append("ESC[NS".toByteArray(), ...)` 让 emulator 自己处理 SU/SD → SU/SD 改变的是屏幕内容(滚动),不是 viewport 位置,无法"翻页看历史"
- (d) 改用 line-by-line 的 `emulator.mTopRow += (-deltaY / lineSpacing)` 增量控制 → `mTopRow` 不在 emulator 上,不可写

**正确**(双指 + 翻页粒度,不是逐行):wrapper `TerminalView` 的 `dispatchTouchEvent` 在 `super` 之前调 `scrollbackController.onTouchEvent(ev)`:
- 单指 → `PassThrough`(原有路径不动)
- 双指起手 → 记 centroidY,`isInScrollback = true`,返回 `Consumed`(双指事件**永不**到内层 view)
- `ACTION_UP` 提交手势:若 `|dy| > lineSpacing * mRows / 2` 则反射调 `com.termux.view.TerminalView.doScroll(lastMoveEvent, ±mRows)` 翻一页;否则 no-op
- 翻完后读 `innerView.mTopRow`(包私有,反射)—— 为 0 则自动退出 scrollback
- alt-buffer 模式(用户在 vim/less/htop 内)→ consume 但**不**调 doScroll,避免 branch-2 NPE;远端 TUI 自己管滚动

**反射范围**:仅 `doScroll(MotionEvent, Int)` 方法和 `mTopRow` 字段,**不**触碰 `TerminalBuffer` 或 `TerminalEmulator` 的私有状态。和现有 `AltBufferScrollCrashGuardTest` 反射复现 NPE 是同一模式。

**关键不变量**(用 `ScrollbackControllerTest` 16 个 + `TerminalViewScrollbackWiringTest` 3 个 pin):
- 双指起手 → `isInScrollback = true`(`TV-SB-01`)
- 翻页阈值 `> lineSpacing * mRows / 2` → `doScroll(±mRows)` + mTopRow 变 ±mRows(`TV-SB-02`)
- 翻页未达阈值 → mTopRow 不动(`TV-SB-03`)
- `scrollToBottom()` → mTopRow=0 + `isInScrollback=false`(`TV-SB-04`)
- 新输出到达(`isInScrollback==true`)→ `pendingOutputCount += max(1, len/columns)`(`TV-SB-05`)
- alt-buffer 模式双指 → consume 但不调 doScroll(`TV-SB-06`)

**为什么不逐行**:refinement 路线上的 `applyMove + deltaRows + clamp` 方案需要触碰 `TerminalBuffer.getActiveTranscriptRows()` / `mTotalRows` 私有状态,引入的反射面比"复用 doScroll"宽得多。**先做翻页**让"看 git log / npm install 长输出"的核心场景可用;逐行 / fling 动量留作下个 sprint 的 UX refinement,届时如果 Termux 上游不暴露 API 再讨论"在 AndroidView 上叠自己的渲染层"。

**已有边界**(明确不实现):
- 不做 fling / 惯性(手松即停)
- 不做 scrollbar / minimap / 历史搜索(只有顶部横幅)
- 不改 `transcriptRows`(沿用 Termux 默认)
- 不影响单指路径(长按选词、单指轻点聚焦全部不变)
- alt-buffer 模式下双指不滚(给远端 TUI 让位)

### 15. `SshSession` 关闭原因区分:同步写入的 `lastCloseReason` `@Volatile`,穿越异步 socket 关闭

**问题**(Module 17):Disconnect 按钮触发的"用户主动断"和 readInto 失败触发的"连接断了"在 UI 上历来被混在一起 —— 不只是文案模糊,而是真实的 race:

1. Disconnect 处理函数先 `activeSession = null`,**然后**调 `sshClient.disconnect()`,后者同步拆 sshj socket;
2. Compose 的 `LaunchedEffect` 取消旧协程**不是同步**的,要在下次 recomposition 才发生;
3. 如果 socket 关闭先于协程取消达到 `readInto` 的 blocking read,`readBytes()` 抛 `SocketException`,`catch` 块按正常失败走;
4. `TerminalPane` 的 `finally` 里 `if (isActive)` 检查此时仍是 `true`(协程未被标记取消)→ 仍然调 `onSessionClosed` → UI 弹红色 "Connection Closed" overlay,用户眼睁睁看自己点的 Disconnect 给自己弹了个错。

**错误应对**:把 `onSessionClosed` 的 message 文案分类("user" vs "remote") —— 治标,根因仍在 race。

**正确**(Sprint 3 / Module 17):

- `ssh/SessionCloseReason.kt`:`sealed class SessionCloseReason { object UserInitiated; object RemoteEof; data class TransportError(message); data class SinkError(message) }`(SCR-RS-01)
- `SshSession.lastCloseReason: @Volatile SessionCloseReason = RemoteEof`,**`close(userInitiated: Boolean = false)` 同步写**:当 `userInitiated == true`,把 `lastCloseReason = UserInitiated` 作为**第一条语句**,再 enqueue 异步 `transport.close()`(SCR-CL-01)。即便 socket close 在毫秒后抵达正在跑的 `readInto`,它看到的 `lastCloseReason` 已经是 `UserInitiated`
- 单点 set 方法 `setCloseReasonUnlessUserInitiated(newReason)`:所有 `readInto` 的退出分支(EOF / SocketException / SocketTimeoutException / SSHException / sink 抛)统一走它,内部先 `if (lastCloseReason is UserInitiated) return else lastCloseReason = newReason` —— 单一 invariant 守护,未来加新 catch 分支也不会绕过(SCR-CL-02,防 regression)
- `TerminalPane.kt:finally`:`if (isActive && session.lastCloseReason !is SessionCloseReason.UserInitiated) onSessionClosed(...)`,SCR-TP-01 直接破 race
- `SshClient.disconnect(userInitiated: Boolean = false)` 经 `onClose = { ui → disconnect(ui) }` 透传信号(默认 `false` 保现有 call site 不变)
- `HanTermApp.kt` 三条 user-initiated 路径(BackHandler 双击 / BackHandler snackbar action / pre-connect Disconnect 按钮)**先**抓 `activeSession` 引用 → `session.close(userInitiated = true)` 同步设标志 → 兜底 `sshClient.disconnect()`(`activeSession == null` 防御,SCR-UI-02)

**关键不变量**(用 `SshSessionWriteTest` 4 个新 `scr_ts_*` case pin):
- `close(userInitiated = true)` → 立刻让 `lastCloseReason == UserInitiated`,并发的 `readInto` 看 SocketException **不**覆盖
- `readInto` 走 EOF 退出且无前置 `UserInitiated` → `lastCloseReason == RemoteEof`
- `readInto` 走 SocketException 且无前置 `UserInitiated` → `lastCloseReason == TransportError(SshErrorMessages.friendly(e))`(用 friendly 文案 pin,future `SshErrorMessages` 重构不会悄悄改 UI 字符串)
- 默认 `close()`(无参)→ 不设 `UserInitiated`(SCR-CL-03;保现有 call site 行为不变)

**为什么是 sealed class 不是 enum**:UI 端故意不暴露 `SessionCloseReason` 类型本身 —— `HanTermApp.onSessionClosed: (String) -> Unit` 回调签名保持不变(SCR-NOT-IN-SCOPE),只有 `TerminalPane` 内部用 sealed 类判定;data class 变体携带的 `message` 是 debug 用,UI 字串照旧走 `failureReason ?: "Connection closed by remote"`。

---

### 16. `PtyBridge`:transport-可替换的"PTY-shaped"中间层

**问题**:Sprint 2 把 `SshSession.readInto { bytes -> emulator.append(bytes, len) }` 直接焊在了 `TerminalPane` 里(`ui/TerminalPane.kt:120-123`),"remote → emulator"这条路径没有任何 seam 可以塞入别的数据源。本地 `bash` / mosh / `forkpty()` 子进程都"会发 PTY-shaped 字节",但当前代码接不进来 —— 改 `TerminalPane` 就要重新过 IME 链路 + scrollback + composing hint 那堆不变量,blast radius 太大。

**正确**(Sprint 3+ / `2009c30` + `7ff9958`):抽出 `terminal/PtyBridge.kt` 接口,Unix-PTY 风味但**不要求**底层有真 kernel PTY:

- `view: PtyEndpoint` —— 表现端(IME 链 / `emulator.append`)
- `transport: PtyEndpoint` —— 远端(今天 = `SshSession`,明天 = mosh / 本地 shell)
- `resize(cols, rows)` + `setResizeListener((Int, Int) -> Unit)` —— 镜像 `TerminalView.setPtyResizeListener`(包括 fire-once)
- 幂等 `close()`,**两端同时 EOF**,之后 read 永远返 `null`,write / resize 永远 no-op

两端互为"逆视图":

```
transport.write(bytes) ──► view.read()
  view.write(bytes)  ──► transport.read()
```

字节从一端进去,**只**出现在另一端的 read 上,**不是** loopback。

**v1 实现**(`BufferedPtyBridge`):两条 `LinkedBlockingQueue<Any>` + 单例 `EOF` 哨兵(`===` 引用比较,合法零字节 payload 不会撞)。`close()` 用 `synchronized(closeLock)` 守护 `closed.compareAndSet(false, true) + 双 put(EOF)`:任何并发 writer 要么在 `closed=true` 之前完成 `put(bytes)`(字节会被 reader 先于 EOF 读到),要么看到 `closed=true` 直接 no-op,字节永远不会被 EOF 哨兵"夹住丢在后面"。`null-on-EOF` 形状刻意对齐 `SshTransport.readBytes` —— 现有 `bytes ?: break` 形式的 reader 不用改。

**生产接线**(`SshBridgeAdapter`,`7ff9958`):两条 IO 协程 + resize 转发,全靠 `PtyBridge` 这一个 seam 把 SSH session 接进来:

- **outbound** (`Dispatchers.IO` async):`bridge.transport.read() ?: return` → `session.write(bytes)` —— 用户键入字节上行
- **inbound** (`Dispatchers.IO` async):`session.readInto { bytes -> bridge.transport.write(bytes) }`,`finally { bridge.close() }` —— 远端输出下行 + 任一自然结束(`readInto` 退出分支覆盖 EOF / SocketException / SocketTimeoutException / SSHException / 结构化取消)都把 bridge 关掉让对端 reader 看到 EOF,outbound 跟着 `read() == null` 干净退出
- **resize**:`bridge.setResizeListener { c, r -> session.resizePty(c, r) }`,**必须**在 structured-concurrency block 之外注册 —— bridge 的 listener slot 是单槽全局共享,注册晚于任何 layout pass 都会丢第一个尺寸
- **`bridgeScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }`** 与 UI scope 解耦 —— UI 取消(BackHandler / Disconnect)不会带走 bridge 协程,bridge 协程抛错也不会污染 UI scope
- **`teardownConnection()`** 顺序固定:`bridge.close()` → `adapterJob.cancel()` → `sshClient.disconnect()` → `ActiveSshSessionStore.clear()`;`bridge.close()` 先于 cancel 是为了让 inbound 的 `finally` 看到的不是空 transport 而是已经 EOF 的 bridge,避免 inbound 协程退出时把"已关掉的 session"再 close 一遍
- **`PtyBridgeEndpoint(bridge)`** 是 `TerminalEndpoint` 的零成本一行适配(`override fun write(bytes) = bridge.view.write(bytes)`),IME 链 / `KeyMapper` / `pasteFromClipboard` 一行不用改

**为什么是 v1 就把它做对**:不是为本地 shell / mosh 准备的(那些还在 Sprint 4+)—— 是为**测试**准备的。`PtyBridgeTest` 19 个 case 全部纯 JUnit,无 Robolectric / 无 sshj mock / 无 emulator 启动:8 线程并发写不丢不重(`concurrentTransportWrites_doNotCorruptViewReads` + `concurrentViewWrites_doNotCorruptTransportReads`,8000 chunk × 64 byte = 512 KiB 全员到位)、`close_signalsEofOnBothSides` / `close_isIdempotent` 三个 close path 收敛、空写 no-op、阻塞读直到写 / 阻塞读直到 close(500 ms unblock 时间断言)、`writeThenClose_drainAllQueuedBytes_onBothSides` 验证 close 不会丢已经入队的字节。`PtyBridgeEndpointTest` 3 个 case 验证 forward 到 transport 端(不是 view 端 loopback)+ 空写 / close 后写 no-op。`SshBridgeAdapterTest` 5 个 case 验证 adapter 接 `FakeTransport` 时 inbound / outbound / resize 全链路通顺,session EOF → bridge.close → view.read 看到 null 一气呵成。

**已知边界**(明确不实现):
- v1 queue 无界(`LinkedBlockingQueue` 默认 `Integer.MAX_VALUE`);生产 impl v2 加 `capacity` 参数,不在本 PR 范围
- `BufferedPtyBridge` 的 `close()` 现在一次性 EOF 两端;v2 可以加"单端 close" 语义让 mosh 等场景独立关 transport 不影响 view,本 PR 不开这个口子
- 仍以 `SshSession` 为唯一 transport;`MoshBridgeAdapter` / `LocalShellBridgeAdapter` 留作 Sprint 4+

---

## 测试

测试总数 **359(346 活跃 + 0 `@Ignore` + 13 `assumeTrue` 运行时门控)**,分为 39 个测试类、4 类目标。Sprint 3.5 SSHJ 升级:0.38 → 0.40,un-Ignore 2 个 Ed25519 PEM 加载测试(`PublicKeyAuthProviderTest.test_loadKeyProvider_ed25519Pem_producesMatchingPublicKey` + `test_loadedEd25519Key_hasEdEcPrivateKeyType`)。0 剩余 `@Ignore`(Sprint 3.5 移除 6 个 readInto 时序 + 2 个 SSHJ 0.38 Ed25519 fixture 限制);13 个 `assumeTrue` 门控 = `EncryptedPrivateKeyStoreTest` 6 个(Robolectric 沙箱下 AndroidKeyStore AES-GCM 不可用)+ `PublicKeyAuthProviderEncryptedTest` 5 个(只在 release build 跑)+ `SshClientKeepAliveTest` 2 个。所有失败立刻在 `app/build/reports/tests/` 出 HTML。

Sprint 3.5 收尾加固还补齐了 `docs/GEARS_SPEC.md` 里剩的两个"一测缺口":`TIC-DS-04`(`TerminalInputConnection` 的 `userInImeContext` latch 重置)和 `TV-FS-01`(`TerminalView.setTextSize` 同值幂等)。其中 `TIC-DS-04` 按 spec 字面写测试时,发现 `userInImeContext` 在整个代码历史里从未被真正重置过——用户第一次拼音上屏之后,任何一次软键盘删除都会被永久当成"IME 上下文"吞掉,永远发不到 SSH。已修复为"读取即消费"的一次性 latch(在 `deleteSurroundingText` 顶部读取并清零,而不是放在只有 latch 已经是 `false` 时才能走到的"写 DEL"分支里——那个位置结构上永远不可达)。

### 单元测试总览

| 测试类 | 数量 | 框架 | 覆盖 |
|---|---|---|---|
| `TerminalInputConnectionTest` | 19 | Robolectric | IME 5 方法 + Gboard 竞态 + 锁存标志(Sprint 3.5 补 `TIC-DS-04` latch 重置回归) |
| `TerminalInputConnectionReconnectTest` | 1 | Robolectric | 重连后清理旧 `InputConnection` 缓存，防止输入死锁 |
| `TerminalViewClientNullSessionTest` | 2 | Robolectric | `TerminalViewClient.onKeyDown` / `onKeyUp` return `true` to guard against null session |
| `TermuxViewKeyDownNullSessionCrashGuardTest` | 3 | Robolectric | `TerminalView` NPE 守卫: 拦截并消费按键，防止 null session 崩溃 |
| `ZmodemFilterTest` | 6 | 纯 JUnit | ZMODEM 协议过滤、状态机转换、CAN 取消与文件落盘 |
| `TrzszFilterTest` | 7 | 纯 JUnit | trzsz `tsz` 接收、codec、directory 拒绝、tmux junk |
| `InboundTransferRouterTest` | 2 | 纯 JUnit | trzsz/ZMODEM 互斥分流 |
| `KeyEventRoutingTest` | **42**(Sprint 2.5+ 加 11:7 个新键 + ESC-while-composing + end-to-end + meta-test + Ctrl+ESC) | Robolectric | 物理键 View 链路路由决策表(含 Ctrl A-Z + `\` + `]` + `ESC` 全 ASCII 控制集 + 7 个 vim/nano 新键 + 数据驱动表 meta-test) |
| `AltBufferScrollCrashGuardTest` | 6 | Robolectric | alt-buffer 滚动 NPE 守卫(predicate + 反射复现上游 NPE + 触摸/滚轮拦截) |
| `ScrollbackControllerTest` | 16 | Robolectric | 双指翻页状态机:多指起手 + 阈值 + doScroll 反射 + alt-buffer 守卫 + `scrollToBottom` + `onTranscriptWrite` 累计 + 指针转换边缘 |
| `TerminalViewScrollbackWiringTest` | 3 | Robolectric | wrapper 接入:`scrollbackController` 懒加载 + `isInScrollback` getter + `scrollToBottom` 重置 mTopRow + `setScrollbackListener` 注册时 fire-once |
| `TerminalViewLayoutTest` | 3 | Robolectric | `onLayout` 1/4-screen 回归(内层 Termux view 在 FrameLayout 重测)+ `setPtyResizeListener` 注册时 fire-once race(GEARS TV-PTY-02,需 mockk 注入 `TerminalRenderer` 真实字体指标)+ `setTextSize` 同值幂等(Sprint 3.5, TV-FS-01) |
| `AppPreferencesTest` | 13 | Robolectric | 数据层读写 / clear / hasUsableCredentials / 加密 blob 边界 |
| `EncryptedPrivateKeyStoreTest` | 8(Sprint 2.5 S2) | Robolectric | 私钥 AES-256-GCM 加密 slot 的写入 / 读取 / 损坏恢复 / `setUserAuthenticationRequired` 边界 |
| `AppLogTest` | 13 | Robolectric | 文件 sink / 轮转 / 并发写 / Logcat 镜像 + Sprint 2.5 S3 诊断级别 gating |
| `ConnectionDraftTest` | 2 | Robolectric | `applyDraftForConnect` 不误清空已存密码 |
| `ConfigScreenDebugLogGateTest` | 6(Sprint 2.5 S3) | Robolectric | debug 日志开关在 `ConfigScreen` 渲染时正确反映到 `AppLog` 级别 |
| `LegacyDebugLogCleanupTest` | 3(Sprint 2.5 S3) | Robolectric | 旧版本遗留 debug 日志在升级后被清理,不留敏感凭据到 `app.log` |
| `SshConfigTest` | 6 | 纯 JUnit | 默认值 pin,防误改 |
| `SshSessionWriteTest` | 16 活跃 + 0 `@Ignore`(Sprint 3 M17 加 4:`scr_ts_01` race 验证 / `scr_ts_02` EOF→`RemoteEof` / `scr_ts_02` SocketException→`TransportError` 含 friendly 文案 pin / `scr_ts_02` 默认 `close()` 不设 `UserInitiated`;**Sprint 3.5 移除 4 个 readInto 时序测试 `@Ignore`** — 文件从 12 活跃 + 6 `@Ignore` → 16 活跃 + 0 `@Ignore`,覆盖 `SS-RI-01` EOF break / `SS-RI-02` P0 取消不关 session / `SS-RI-06` sink 抛仍关 / `SS-RI-07/08` sink 收批 + in-order) | 纯 JUnit | `write` / `resizePty` / `close` 幂等 + readInto 正常路径(sink 收批 + EOF 关 transport + sink 抛仍关 + 取消不关 session)+ `SessionCloseReason` race-fix |
| `SshErrorMessagesTest` | 17 | 纯 JUnit | Throwable → 友好文案全分支(含 sshj cause 链 + 自引用保护) |
| `SshClientHostKeyWiringTest` | 8(Sprint 2.5 S1) | 纯 JUnit | `SshClient` 装 `KnownHostsVerifier` 而非 `PromiscuousVerifier`,known_hosts 路径接通 |
| `KnownHostsStoreTest` | 11(Sprint 2.5 S1) | 纯 JUnit | `KnownHostsStore` 读写 / 更新 / 文件 IO / 格式解析 |
| `KnownHostsVerifierTest` | 10(Sprint 2.5 S1) | 纯 JUnit | verifier trust / mismatch / unknown 三态,MITM 防护路径 |
| `ActiveSshSessionStoreTest` | 4 | 纯 JUnit | 进程级 holder set / get / replace / 幂等 clear |
| `PublicKeyAuthProviderTest` | 5 活跃 + 0 `@Ignore`(Sprint 3.5 SSHJ 0.40 升级 un-Ignore 2 个 Ed25519 测试) | 纯 JUnit + bcprov | RSA + Ed25519 PEM 加载 round-trip + `EdECPrivateKey` 类型断言 + missing file 异常 |
| `PublicKeyAuthProviderEncryptedTest` | 0 活跃 + 5 `@Ignore`(Sprint 2.5 S2) | 纯 JUnit + bcprov | 加密私钥路径(release-only,本地 dev 跳过) |
| `PublicKeyAuthProviderLogGateTest` | 2(Sprint 2.5 S3) | 纯 JUnit | 私钥失败路径不写敏感字节到 log |
| `PasswordAuthProviderLogGateTest` | 3(Sprint 2.5 S3) | 纯 JUnit | 密码失败路径不写密码到 log |
| `PtyBridgeTest` | **19**(Sprint 3+ PtyBridge) | 纯 JUnit | `BufferedPtyBridge` 双向流顺序 / EOF / close 幂等 / 空写 no-op / 阻塞读直到写或 close / 8 线程并发写不丢不重 / close 后写入被丢弃 / null-stays-null |
| `PtyBridgeEndpointTest` | **3**(Sprint 3+ PtyBridge) | 纯 JUnit | `PtyBridgeEndpoint.write` forward 到 transport 端(非 loopback)+ 空写 no-op + close 后写 no-op |
| `SshBridgeAdapterTest` | **5**(Sprint 3+ PtyBridge) | 纯 JUnit | `SshBridgeAdapter` 两路 IO + resize 全链路:outbound 抵达 transport / inbound 抵达 view / resize 触发 PTY resize / session EOF → 干净关 bridge / `bridge.close()` 切断 outbound |
| `SshClientKeepAliveTest` | **5** | Robolectric + mockk | `buildSshjConfig_usesOneWayHeartbeat_notReplyCountingKeepAlive`(SC-CN-09,2026-07-11 postmortem 后反向 pin:`keepAliveProvider == KeepAliveProvider.HEARTBEAT`;当前 keepalive 策略详见 [`docs/ARCHITECTURE.md` §5](docs/ARCHITECTURE.md#5-ssh-keepalive-当前策略)) + `disconnect` 幂等 + 并发两线程 disconnect 只 close 一次 + close 抛异常被吞(SC-DC-03) |
| `LayoutDecisionTest` | **4**(Sprint 3 M15) | 纯 JUnit | `shouldUseSplitLayout(orientation, showTerminal)` 2×2 真值表(pin SL-OR-01..03 + SL-TS-01):portrait/landscape × showTerminal true/false |
| `SnippetStoreTest` / `SnippetPayloadTest` / `TmuxSessionParserTest` / `TmuxSessionSourceTest` / `SshjRemoteCommandExecutorTest` / `ShellIntegrationStateTest` / `TmuxDrawerUiTest` | ⛔ 已删除 | (n/a) | **2026-07-22 开源前清理** 整组删除 — 见 [`docs/ARCHITECTURE.md` §3](docs/ARCHITECTURE.md#3-已删除的能力-开源前清理) |

### 关键测试用例

#### IME 链路(`TerminalInputConnectionTest`)
| 用例 | 验证 |
|---|---|
| `test_setComposingText_updatesStateButDoesNotWriteToSsh` | 拼音阶段不发包 |
| `test_commitText_sendsUtf8BytesAndClearsComposing` | 汉字 UTF-8 发包 + 清 composing |
| `test_commitText_emptyTextIsNoOp` | 空文本防误发 |
| `test_deleteSurroundingText_whenComposing_doesNotSendDel` | 组合中退格不发包 |
| `test_deleteSurroundingText_whenIdle_sendsDelSequence` | 非组合发 `0x7F` |
| `test_finishComposingText_clearsStateButDoesNotWriteToSsh` | 取消输入不发包 |
| Gboard 竞态套件 | `setComposingText("")` 后 `deleteSurroundingText` 仍走 IME |

#### 物理键路由(`KeyEventRoutingTest`)
| 用例 | 验证 |
|---|---|
| `test_printableChar_isHandledByImePath_notView` | 可打印字符 View 返回 false |
| `test_ctrlC_writesInterruptAndConsumesEvent` | Ctrl+C 发 `0x03` 并吞掉 |
| `test_enter_writesCarriageReturn` | Enter 发 `\r` |
| `test_backspaceWhenIdle_writesDelByte` | 退格(非组合)发 `0x7F` |
| `test_backspaceWhileComposing_isRoutedToIme_noDelWritten` | **关键**:退格(组合中)走 IME,View 不发 DEL |
| `test_arrowUp_writesAnsiCursorSequence` | 方向键发 `ESC[A` |
| Ctrl+Space / Shift+Space / LANGUAGE_SWITCH | 全部 `Swallow`,**绝不外泄 SSH** |
| `test_keyMapTable_isWellFormed` | **Sprint 2.5+ 元测试**:遍历 `KeyMapper.entriesForTest()`,断言 20 个 `knownEvents`(从 31 个旧测试提取)都能匹配到至少一条 `KeyMapEntry`,防止后续重构意外漏路由 |
| `test_escapeAlone_writesEscByte` | **Sprint 2.5+**:物理 ESC 键 → `0x1B`(vim insert/visual→normal 模式必备) |
| `test_ctrlEscape_writesEscByte` | **Sprint 2.5+**:Ctrl+ESC → `0x1B`(与 Ctrl+[ 同字节;`ctrlControlByte` 表里的 `KEYCODE_ESCAPE` 防止路由 regression) |
| `test_escape_whileComposing_isPassedToIme` | **Sprint 2.5+ 关键**:IME 拼音组合中按 ESC → View 返回 false、**不写** `0x1B` 到 SSH,IME 保留取消组合的语义 |
| `test_shiftTab_writesBackTabSequence` | **Sprint 2.5+**:Shift+Tab → `ESC[Z`(vim `gT` / nano 撤销缩进) |
| `test_insertKey_writesInsertSequence` | **Sprint 2.5+**:物理 Insert 键 → `ESC[2~`(vim 切换 insert/replace 模式) |
| `test_ctrlCaret_writesRsByte` | **Sprint 2.5+**:Ctrl+^ → `0x1E` (RS)(vim alt-file;测试用反射设 `mCharacters="^"` + `KEYCODE_UNKNOWN` + `META_CTRL_ON`) |
| `test_ctrlUnderscore_writesUsByte` | **Sprint 2.5+**:Ctrl+_ → `0x1F` (US)(vim undo / nano go-to-line) |
| `test_ctrlAt_writesNulByte` | **Sprint 2.5+**:Ctrl+@ → `0x00` (NUL)(bash set-mark / nano set mark) |
| `test_ctrlSlash_writesDelByte` | **Sprint 2.5+**:Ctrl+? → `0x7F` (DEL)(备选 DEL 字节,与 `KEYCODE_DEL` 同) |
| `test_newKeys_endToEnd_throughView_writeExpectedBytes` | **Sprint 2.5+ 集成**:7 个新键通过 `TerminalView.onKeyDown` 端到端验证,捕"View 层漏加新键"类 bug |

#### Sprint 2 SSH 链路
| 用例 | 验证 |
|---|---|
| `SshSessionWriteTest.test_write_forwardsBytesVerbatimToTransport` | 写出字节与原序列一致 |
| `SshSessionWriteTest.test_write_multipleCallsAccumulateInOrder` | 多笔 write FIFO,UTF-8 边界正确 |
| `SshSessionWriteTest.test_close_isIdempotent` | `close()` 多次调用,`onClose` 只触发一次 |
| `SshSessionWriteTest.test_readInto_socketTimeout_isTranslatedToFriendlyMessage` | **回归**:SocketTimeoutException → "Connection timed out. Check your network and the server's address.",原异常保留在 `cause` |
| `SshSessionWriteTest.test_readInto_doesNotCloseTransportOnCancellation`(`@Ignore`) | **回归(Sprint 2.5)**:取消 readInto 协程**不**关 transport、**不**触发 `onClose`,session 留给下次 reader 复用 |
| `SshErrorMessagesTest.test_socketTimeoutException_withBannerReadFrame_returnsBannerMessage` | banner-read 失败特殊文案 |
| `SshErrorMessagesTest.test_causeChain_unwrapsSshjWrapping` | sshj 双层 wrap 也能回溯到 `SocketTimeoutException` |
| `SshErrorMessagesTest.test_causeChain_handlesSelfReferentialCause` | 自引用 `cause` 不死循环 |
| `PublicKeyAuthProviderTest` | Ed25519 / RSA PKCS#8 PEM round-trip |

#### Alt-buffer 滚动 NPE 守卫(`AltBufferScrollCrashGuardTest`)
| 用例 | 验证 |
|---|---|
| `test_normalScrollback_isNotFlaggedAsCrashPath` | 正常 scrollback 不被拦截,`mTopRow` 路径走通 |
| `test_altBufferWithoutMouseTracking_isFlaggedAsCrashPath` | alt-buffer + 鼠标追踪关 = 命中守卫 |
| `test_altBufferWithMouseTracking_isNotFlaggedAsCrashPath` | 鼠标追踪开启(`mouse=a`)时放行,doScroll 走 `sendMouseEvent` 不会 NPE |
| `test_innerViewDoScroll_inAltBuffer_throwsNpeOnRawCall` | **回归**:反射直接调 `doScroll(-3)` 复现上游 AAR 的 NPE,确保守卫依赖的 bug 路径仍然存在(若上游修复,这条会反向 fail 提醒重评守卫) |
| `test_wrapper_installsTouchListenerOnInnerView` | 守卫的 `OnTouchListener` 真的装在内层 view 上 |
| `test_wrapper_consumesMouseWheelScrollInAltBuffer` | 蓝牙鼠标 `ACTION_SCROLL` 也被 `dispatchGenericMotionEvent` 守卫拦截 |

#### View 几何 + PTY resize race(`TerminalViewLayoutTest`)
| 用例 | 验证 |
|---|---|
| `innerView_matchesWrapperSize_afterFirstLayout` | **回归**:FrameLayout 的 `onLayout` 在 super 之后用 wrapper 的实际像素重新 measure 内层 Termux view。`com.termux.view.TerminalView.onMeasure` 读 emulator 的 80×24 网格算 intrinsic size,无视我们的 `MATCH_PARENT` —— 没有重测,内层 view 会停在 ~640×336 锁死在左上角 1/4 屏 |
| `setPtyResizeListener_invokesListenerImmediately_afterLayoutPass` | **回归(TV-PTY-02)**:PTY listener 注册时必须立刻用当前 wrapper 尺寸 fire 一次。`OnLayoutChangeListener` 第一次布局跑时 listener 还是 null,SIGWINCH 被丢掉但 `lastResizeCols/Rows` 已被填;后续 `setPtyResizeListener` 触发的 fire-once 会被 debounce 同样丢掉,SSH PTY 永远停在 80×24,tmux 状态栏落在屏幕中间。用 mockk 注入 `TerminalRenderer` 真实字体指标来绕过 Robolectric 的 zero-metrics 影子 |

#### 进程级 session 持守(`ActiveSshSessionStoreTest`)
| 用例 | 验证 |
|---|---|
| `test_get_returnsNullWhenEmpty` | 空 store 返 `null`,UI 退回登录页 |
| `test_set_thenGet_returnsSameReference` | 引用一致,避免 sshj socket 失联 |
| `test_set_replacesPreviousSession` | 二次 connect 覆盖旧的 |
| `test_clear_isIdempotent` | 多次 `clear` 安全,不抛 NPE |

#### PtyBridge(`PtyBridgeTest` + `PtyBridgeEndpointTest` + `SshBridgeAdapterTest`)
| 用例 | 验证 |
|---|---|
| `viewRead_afterTransportWrite_returnsBytes` | transport 写 → view 读的单向流 |
| `viewRead_preservesOrderAcrossTransportWrites` | 多笔 transport 写按入队顺序被 view 读出 |
| `close_signalsEofOnBothSides` | 一次 `close()` 同时 EOF 两端,任一 read 都不卡死 |
| `close_isIdempotent` | 多次 `close()` 安全;`endpoint.close()` 也汇聚到 bridge.close |
| `viewWrite_afterClose_isNoOp` / `transportWrite_afterClose_isNoOp` | close 后写入被丢,read 不返延迟字节 |
| `viewRead_blocksUntilTransportWrite` / `transportRead_blocksUntilViewWrite` | 阻塞读被对端写入唤醒(unblock < 1 s) |
| `viewRead_blocksUntilClose` / `transportRead_blocksUntilClose` | 阻塞读被 close 唤醒(unblock < 500 ms) |
| `concurrentTransportWrites_doNotCorruptViewReads` / `concurrentViewWrites_doNotCorruptTransportReads` | **回归**:8 线程 × 1000 写 × 64 byte 双向并发写,reader 端拿到的总字节数精确等于 8×1000×64 = 512 KiB,无丢失无重复;close 不被卡住 |
| `writeThenClose_drainAllQueuedBytes_onBothSides` | close 不会丢已经入队的字节(EOF 在所有数据被 drain 之后才被 reader 看到) |
| `PtyBridgeEndpointTest.write_forwardsToBridgeView_andTransportCanRead` | **回归**:adapter 必须把 IME 字节 forward 到 transport 端,**不是** view 端 loopback;如果 forward 错了,view.read 会回显自己的 IME 输入 |
| `PtyBridgeEndpointTest.emptyWrite_isSilentNoOp` / `writeAfterBridgeClose_isNoOp` | 空写 / close 后写都被丢 |
| `SshBridgeAdapterTest.adapter_outboundBytes_arriveAtTransport` | view 写入抵达 sshj transport(FakeTransport.recordedWrites) |
| `SshBridgeAdapterTest.adapter_inboundBytes_arriveAtView` | sshj transport.enqueueRead → bridge.view.read 拿到字节 |
| `SshBridgeAdapterTest.adapter_resizeFiresPtyResize` | bridge.resize(cols, rows) → FakeTransport.resizeCalls 收到 (cols, rows) |
| `SshBridgeAdapterTest.adapter_eofFromSession_closesInbound_andClosesBridgeCleanly` | **回归**:session.readInto 走 EOF → bridge.view.read 看到 null,无 hang / 无 zombie transport |
| `SshBridgeAdapterTest.adapter_closeBridge_closesOutbound_andPropagatesEofToView` | **回归**:bridge.close() 后 outbound 不再产生新 transport.write,view.read 看到 EOF |

#### Active dead-peer keepalive + atomic disconnect(`SshClientKeepAliveTest`)
| 用例 | 验证 |
|---|---|
| `buildSshjConfig_usesOneWayHeartbeat_notReplyCountingKeepAlive` | **pin(SC-CN-09,2026-07-11 postmortem 后反向)**:断言 `config.keepAliveProvider == KeepAliveProvider.HEARTBEAT`(sshj `Heartbeater` 单向 `SSH_MSG_IGNORE`,**不**等回复). 历史 commit `f932666` 曾强制 `KEEP_ALIVE` + `maxAliveCount = 3`,BG-KA-04 判定该策略在 Tailscale / Doze 路径会自杀健康连接;已退回 `HEARTBEAT` + TCP keepalive + FGS nudge 三保险,死对端检测靠 TCP 层与 `SO_TIMEOUT` 而非 SSH 层 want-reply — 见 [`docs/ARCHITECTURE.md` §5](docs/ARCHITECTURE.md#5-ssh-keepalive-当前策略) 与 postmortem `docs/BACKGROUND_SSH_KEEPALIVE_POSTMORTEM_2026-07-11.md` §阶段 D. |
| `disconnect_isANoOp_whenNeverConnected` | `connect` 没跑过就 `disconnect` 不能抛 |
| `disconnect_isIdempotent_secondAndThirdCallsAreNoOps` | **回归(SC-DC-03)**:三次 `disconnect()` 调用,`fakeClient.close()` 正好执行 1 次,`sshRef` 收尾为 `null` |
| `disconnect_concurrentCallers_closeTheUnderlyingClientExactlyOnce` | **回归(SC-DC-03)**:两线程同时调 `disconnect`,`CountDownLatch` 起跑;`fakeClient.close()` 仍然只被调 1 次 —— 旧 `var sshRef` 的 data race 在这里会 fail |
| `disconnect_swallowsButDoesNotCrashOn_closeFailure` | **回归**:sshj 内部 close 抛 `IllegalStateException` 也被 `runCatching` 吞掉记 `AppLog.e`,绝不抛回 UI / writeExecutor 线程 |

### 手工联调(平板真机)

1. 蓝牙 / USB 实体键盘 + 搜狗 / Gboard,`vim` Insert 模式输入中文,确认拼音阶段无字母掉到终端
2. 输入中按 `ESC` 取消,确认不收到多余换行 / 空格,且远端 `vim` 退出 Normal 模式(Sprint 2.5+:`KEYCODE_ESCAPE` 单独走 `0x1B` 路由,不再像 Sprint 1 那样被 `Ignore`)
3. 拼音中途按退格,确认不发 DEL 到远端
4. 非组合状态按 `Ctrl+C` / `Ctrl+D` / `Tab`,确认终端收到控制信号
5. 真 SSH 主机(host / port / user / 密码或私钥任选),填表单 → Save → Connect → 在终端跑 `top` / `vim`
6. 故意填错密码,确认错误 overlay 弹出友好文案 + "Show logs" / "Copy logs" 可用(Sprint 2.5 S3:不写密码到 `app.log`)
7. 拔网线或服务器关停,确认 30 s 内 overlay 弹出而非永久冻屏
8. 音量上 / 下调整字号,杀进程重启后字号保持
9. **分屏保活**:连接 SSH → 进系统 split-screen,确认终端 pane 留在原位、PTY grid 跟随新尺寸(SIGWINCH 生效)、IO 循环不中断、不再被踢回登录页;拖动 split 分隔条改变尺寸,远端 `stty size` 应跟着变
10. **alt-buffer 滚动 TUI**:在终端跑 `vim` / `less` / `htop` / `tmux` / `fzf` 等 TUI,单指拖动滚动,确认**不闪退**(守卫消费 ACTION_MOVE);`:set mouse=a` 后再拖,应能正常滚动 TUI 内容(走 `sendMouseEvent` 路径不被守卫拦截)
11. **蓝牙鼠标滚轮**:TUI 内拨鼠标滚轮,确认**不闪退**(走 `dispatchGenericMotionEvent` 守卫消费 ACTION_SCROLL)
12. **双指翻页 scrollback**(新增):
    - 跑 `seq 1 1000`,**双指**在屏幕上向上滑 → 顶部出现 "↑ 滚回历史" 横幅,内容回滚一页(单指上滑不动;必须双指)
    - 连续双指上滑多次 → 每次一页,翻到 transcript 顶部后 mTopRow 不再变化
    - 双指向下滑 → 内容前进一页;翻到 mTopRow=0 时横幅自动消失
    - 翻回一页后等几秒,让远程 `watch -n 1 'date'` 跑几行 → 横幅右侧出现 "▼ 5 行新输出" 徽章(数字累加,coerceAtMost 9999)
    - 点横幅任意位置 → 跳到底部,徽章清零,banner 消失
    - 翻回多页后**长按**选词仍能选中文本(`mTopRow` 状态不影响 Termux 的 ActionMode 选区)
    - 跑 `vim`(alt buffer)后双指滑动 → **不闪退**;双指事件被消费但不调 doScroll(alt-buffer 守卫)
    - 旋转平板后翻页状态保留(横幅位置正常,mTopRow 被内层 view clamp 在合法范围)
    - 改字号(音量键)后双指翻页阈值自动跟随新 lineSpacing(下一次手势生效)
    - Ctrl+Shift+V 粘贴 / Ctrl+Space 切输入法 / Ctrl+C 中断 在 scrollback 模式下都正常工作
13. **Sprint 2.5+ vim / nano 全键位**(这是 Sprint 2.5+ 重构的真机验收清单):
    - 启动 `vim`,`i` 进 Insert 模式,输入几个字符,按 **物理 ESC** → 回到 Normal 模式(`:` 能进 command 模式)
    - Insert 模式下按 **Shift+Tab** → 退一格缩进(或用户配置的 `gT`)
    - 按 **Insert 键** → vim 在 normal 模式下切换 insert/replace 模式
    - 按 **Ctrl+^** → vim 切换到 alternate file
    - 启动 `nano`,按 **Ctrl+O** → writeOut,按 **Ctrl+X** → exit,按 **Ctrl+W** → search,按 **Ctrl+_** → go-to-line
    - 启动 `less` / `htop` / `top` / `tmux`,验证方向键 / Home / End / PageUp / PageDown / F1–F12 行为与 PC 键盘一致

---

## 路线图

### Sprint 2.5(短期,1 周内)
- [x] `SshSessionWriteTest` 的 `readInto` 取消契约翻转为「不关 session」(分屏 / 进程死亡路径必备);`OnEof` / `OnSinkException` 两条自然结束路径仍 `@Ignore` 留作 Sprint 2.5 后续时序稳定性工作
- [x] `TerminalView` alt-buffer 滚动 NPE 守卫 + 6 个回归用例(防 Termux 上游 AAR 改回非崩溃路径时守卫误留)
- [x] **S1**: known_hosts TOFU store + 21 个新测试(`SshClientHostKeyWiringTest` / `KnownHostsStoreTest` / `KnownHostsVerifierTest`);`SshClient` 替换 `PromiscuousVerifier` 为 `KnownHostsVerifier`,`SshErrorMessages.friendly` 加 host-key-mismatch 文案
- [x] **S2**: 私钥 AES-256-GCM 加密 slot + `EncryptedPrivateKeyStore`(从 `AppPreferences` 拿密文 slot,从 `filesDir/keys/` 拿文件,`KeyStoreManager` 提供主密钥);`PublicKeyAuthProviderEncryptedTest` 5 个 release-only 用例
- [x] **S3 + S4**: debug log 与 auth 诊断 gating(`ConfigScreenDebugLogGateTest` 6 个 + `LegacyDebugLogCleanupTest` 3 个 + `*LogGateTest` 5 个);`AppLog.setLevel(Level.WARN)` 默认收口,`SshClient` / `SshSession` 错误信息走 `friendly()` 不带 stacktrace
- [x] **vim/nano KeyMapper 数据驱动重构**:见状态表对应行;`docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md` + `docs/superpowers/plans/2026-06-29-vim-nano-keymapper.md`
- [x] **双指翻页 scrollback**(`ScrollbackController` + 反射 `com.termux.view.TerminalView.doScroll(MotionEvent, Int)` + Compose `ScrollbackBanner` 顶部浮层 + 新输出 `▼ N 行新输出` 徽章 + 翻回 mTopRow=0 自动退;19 个新测试,5 条真机手测 case,见决策 #14 / `docs/superpowers/specs/2026-06-30-gesture-scrollback-design.md` + `docs/superpowers/plans/2026-06-30-gesture-scrollback.md`)。**banner Compose UI 单元测试延后到真机手测**(Robolectric + Compose UI Activity 注册复杂,价值低于成本)
- [ ] OpenSSH 7.x / 8.x / 9.x 兼容性矩阵(dropbear / busybox sshd 也跑一遍)
- [ ] `KeyStoreManager` 在 Robolectric 下的最小冒烟(目前明确放在真机矩阵)
- [ ] 真机手测 vim `ESC` 回 normal 模式 + nano `Ctrl+O/X/W`(Sprint 2.5+ 新键的端到端验证)

### Sprint 3(P3,2-4 周)

2026-07-02 三个独立任务已拆分并全部完成(`feat/alt-buffer-cursor-scroll` 分支),完整 Given-When-shall 行为规范见 `docs/GEARS_SPEC.md` 对应 Module。

- [x] 平板横屏布局优化(`ui/LayoutDecision.kt` + `HanTermApp` landscape → 两栏 `Row`,portrait 与 fullscreen 路径 BYTE-FOR-BYTE 不动)—— 见 [`docs/GEARS_SPEC.md` Module 15](docs/GEARS_SPEC.md#module-15-landscape-split-layout-sprint-3-s1);4 个 `LayoutDecisionTest` case pin 2×2 真值表;Compose 渲染走手工真机
- [x] 命令 Snippet(M16)— **2026-07-22 开源前整组删除**(`SnippetStore.kt` + `SnippetPanel.kt` + `SnippetPayload.kt`),详见 [`docs/ARCHITECTURE.md` §3](docs/ARCHITECTURE.md#3-已删除的能力-开源前清理)
- [x] tmux session 切换器(M19)+ Bash/Zsh shell integration + side-band `RemoteCommandExecutor` — **2026-07-22 开源前整组删除**,详见 [`docs/ARCHITECTURE.md` §3](docs/ARCHITECTURE.md#3-已删除的能力-开源前清理)
- [x] `SshSession` 关闭原因区分(根因解决 Disconnect 红色 overlay 误弹的真实 race,不是文案模糊)—— 见 [`docs/GEARS_SPEC.md` Module 17](docs/GEARS_SPEC.md#module-17-session-close-reason-disambiguation-sprint-3-s3);`SshSessionWriteTest` 加 4 个 `scr_ts_*` case(race + EOF→`RemoteEof` + SocketException→`TransportError` + 默认 `close()` 不污染 race 标记);3 个 user-initiated 路径(BackHandler 双击 / BackHandler snackbar / pre-connect Disconnect 按钮)同步调用 `session.close(userInitiated = true)`

候选,未排入本轮 Sprint 3(需要显式立项才启动,见 `CLAUDE.md`"Out of scope"):
- 多主机列表 + 分组 + 新增 / 编辑 / 删除

> `known_hosts TOFU store` 已在 Sprint 2.5 S1 完成(`SshClient` 已替换 `PromiscuousVerifier` 为 `KnownHostsVerifier`,见 [`docs/GEARS_SPEC.md` Module 11](docs/GEARS_SPEC.md#module-11-security--host-fingerprint-sprint-25-s1)),不再是 Sprint 3 / Sprint 4 待办。

### Sprint 3+ hardening(已落地,无新功能)

三个 commit 把 Sprint 2 之后发现的"sshj 默认配置 bug"和"transport 层不可替换"两个遗留问题一并修了:

- [x] **`feat(terminal): PtyBridge abstraction`**(`2009c30`)—— 新增 `terminal/PtyBridge.kt` + `terminal/BufferedPtyBridge.kt` + `terminal/PtyBridgeEndpoint.kt`,把 view / transport 拆成对称两端 + resize 信号 + 幂等 close,19 + 3 = 22 个新测试(`PtyBridgeTest` 19 case 含 8 线程并发不丢不重 / `PtyBridgeEndpointTest` 3 case pin IME 字节 forward 到 transport 端非 loopback)
- [x] **`feat(terminal,ssh): wire PtyBridge into production circuit`**(`7ff9958`)—— 新增 `ssh/SshBridgeAdapter.kt`(两路 IO 协程 + resize 转发),`HanTermApp` 装 `BufferedPtyBridge` + `SshBridgeAdapter(session, bridge).start(bridgeScope)` + `PtyBridgeEndpoint(bridge)` 三件套;`bridgeScope` 与 UI scope 解耦;`teardownConnection()` 顺序固定 `bridge.close → adapterJob.cancel → sshClient.disconnect → ActiveSshSessionStore.clear`。`SshBridgeAdapterTest` 5 case end-to-end 覆盖 outbound / inbound / resize / session EOF 路径 / bridge.close 切断 outbound
- [x] **`fix(ssh): atomic disconnect()`**(`f932666` 后续 commit 调整)— `SshClient.disconnect` 把 `var sshRef` 改成 `AtomicReference<SSHClient?>`,`getAndSet(null)` 单点赢家执行拆 keepalive + 拆 sshj,其它并发 / 重入 caller 一律 no-op;`close` 失败 `runCatching` + `AppLog.e` 记日志,不抛回 caller。`SshClientKeepAliveTest` 5 case pin `disconnect_isIdempotent_secondAndThirdCallsAreNoOps` + `disconnect_concurrentCallers_closeTheUnderlyingClientExactlyOnce` (CountDownLatch 两线程同跑,close 仍只 1 次) + `disconnect_swallowsButDoesNotCrashOn_closeFailure` (SC-DC-03). **注意**: `f932666` 当时同步 commit 的 `KEEP_ALIVE` + `maxAliveCount = 3` 探测在 2026-07-11 postmortem 中判定自杀(BG-KA-04),已退回 HEARTBEAT + TCP + FGS nudge 三保险 — 当前策略见 [`docs/ARCHITECTURE.md` §5](docs/ARCHITECTURE.md#5-ssh-keepalive-当前策略).

### Sprint 3.5 SSHJ 0.40 升级 + 测试债务清理(已落地,`chore/sprint-3.5-sshj-0.40-upgrade`,已 push 未合并)

不新增功能,纯依赖升级 + 测试可靠性收口:

- [x] **`test(ssh): un-Ignore readInto tests with deterministic awaitWriteQueueDrained`**(`1665ff4`)—— `SshSessionWriteTest` 4 个因 `runBlocking + delay(50)` 时序不确定而 `@Ignore` 的 `readInto` 用例(含 P0 的"取消不关 session"契约)改用 `session.awaitWriteQueueDrained()` 显式排空 + `FakeTransport.beforeRead` 钩子 + `CANCEL_SENTINEL` 替代 `Thread.interrupt()`,消除 flake
- [x] **`chore: silence 6 compiler warnings in main`**(`6ab7755`)—— 无行为改动;`KeyMapper.resolve`/`toAnsiSequence` 去掉未用的 `keyCode` 参数(4 个生产调用点 + 11 个测试调用点同步更新)
- [x] **`chore(deps): bump sshj 0.38.0 → 0.40.0 + un-Ignore 2 Ed25519 tests`**(`e4487b2`)—— 生产代码零改动(所有 sshj 符号 1:1 映射到 0.40);`PublicKeyAuthProviderTest` 的 `writeOpenSshPem` fixture 因 SSHJ 0.40 的 `PKCS8KeyFile` 硬拒绝 Ed25519 OID 而重写为走真正的 OpenSSH v1 wire format(BC `OpenSSHPrivateKeyUtil.encodePrivateKey`),un-Ignore 2 个 Ed25519 测试
- [x] **`fix(build): exclude colliding BC OSGi MRJAR manifest in packaging`**(`25f6490`)—— sshj 0.40 把 BouncyCastle 透传升级到 1.80.2(声明的 1.78.1 现在只是 advisory),三个 1.80.x BC JAR 携带同一份 OSGi manifest 导致打包冲突,`app/build.gradle.kts` 加排除规则修复

测试从 279 active / 6 `@Ignore` → **323 总数 / 312 active / 0 `@Ignore` / 11 `assumeTrue`**。详见 [`docs/PR_DESCRIPTION_SPRINT_3.5.md`](docs/PR_DESCRIPTION_SPRINT_3.5.md)。

**遗留 follow-up**(本轮加固 sprint 已跟进,见下):
- [x] `CLAUDE.md` 的 BC 1.78.1 硬 pin 描述同步为 advisory,记录 MRJAR manifest 冲突排查经验
- [x] 补齐 `docs/GEARS_SPEC.md` 剩余的两个"一测缺口"`TIC-DS-04` / `TV-FS-01`;`TIC-DS-04` 的测试过程中发现并修复了 `TerminalInputConnection.userInImeContext` latch 永不重置的真实 bug(拼音上屏一次后,所有后续退格都会被永久吞掉发不到 SSH),详见该 spec 条目
- [ ] SSHJ 0.40 + BC 1.80.2 下的密码/Ed25519/RSA 三条认证路径 + OpenSSH 兼容性矩阵 + vim/nano(含 `TIC-DS-04` 修复)真机回归 —— 清单已产出:[`docs/REAL_DEVICE_CHECKLIST_SPRINT_3.5.md`](docs/REAL_DEVICE_CHECKLIST_SPRINT_3.5.md),待真机执行

### Sprint 4+(P4,远期)
- [x] ZMODEM 无感知下载（远程 `sz` → 平板 Downloads；`terminal/zmodem/`，非 SFTP；勿在 tmux 内使用）
- [x] trzsz 无感知下载（远程 `tsz` → 平板 Downloads；`terminal/trzsz/`；tmux 内外可用）
- [ ] SFTP 文件管理(SSHJ `SFTPClient`)
- [ ] 端口转发
- [ ] 跳板 / ProxyJump
- [ ] Mosh(复杂度高,最后评估)
- [ ] TrueColor 终端类型(目前 `xterm-256color`)
- [ ] 鼠标协议(`xterm` mouse modes)
- [ ] ZMODEM `rz` / trzsz `trz` 上传（对称能力，未做）

---

## 贡献

项目刚启动,接受所有形式的反馈:

- 🐛 Bug 报告 → GitHub Issues
- 💡 设计讨论 → GitHub Discussions(欢迎先开 Issue 讨论架构决策,特别是任何修改 terminal-emulator 的提案)
- 🔧 提 PR → fork + feature branch,跑 `./gradlew :app:testDebugUnitTest` 全绿后提

**不要做**(违反任一 PR 直接关):
- 自研 ANSI 状态机 / ScreenBuffer / 终端渲染
- 修改 `terminal-emulator` 内部源码(除非先开 Issue 达成共识)
- 引入未在 `implementation_plan.md` 中提到的库

---

## 文档

- [`implementation_plan.md`](implementation_plan.md) — 完整技术设计
- [`test_plan.md`](test_plan.md) — 测试计划
- [`SPRINT_0_1_DONE.md`](SPRINT_0_1_DONE.md) — Sprint 0+1 完成记录 + 验收证据
- [`HANDOFF.md`](HANDOFF.md) — Codex → Claude Code 交接记录(开发过程留底)
- [`docs/REVIEW_2026-06-24.md`](docs/REVIEW_2026-06-24.md) — 代码审查报告(Sprint 2)
- [`docs/GEARS_SPEC.md`](docs/GEARS_SPEC.md) — 行为规范(Sprint 0/1/1.5/2/2.5 + Sprint 3 Modules 15-17 全部已实现,共 ~316 GEARS spec)
- [`docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md`](docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md) — vim/nano KeyMapper 重构设计 spec
- [`docs/superpowers/plans/2026-06-29-vim-nano-keymapper.md`](docs/superpowers/plans/2026-06-29-vim-nano-keymapper.md) — vim/nano KeyMapper 6-task 实施计划

---

## License

待定(Sprint owner 未决定). Termux terminal-emulator 是 Apache 2.0;本项目主体先 private 仓库运营. 决定后会在 [`docs/ARCHITECTURE.md` §1](docs/ARCHITECTURE.md#1-项目是什么) 与 `LICENSE` 文件同步更新.

---

**Maintainer**: [@st6098770633](https://github.com/st6098770633)