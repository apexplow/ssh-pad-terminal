# SSH Pad Terminal

> Android 平板原生 SSH 客户端。**核心差异化**:正确解耦 Android 输入法体系与终端键盘体系 —— 让中文拼音 IME 在远程 SSH 会话里像本地输入一样工作。

[![Status: Sprint 2.5+ 完成](https://img.shields.io/badge/status-Sprint%202.5%2B%20%E5%AE%8C%E6%88%90-brightgreen)](#当前状态)
[![Min SDK: 29](https://img.shields.io/badge/min%20SDK-29%20(Android%2010)-blue)](#技术栈)
[![License: TBD](https://img.shields.io/badge/license-TBD-lightgrey)](#license)

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
| **Sprint 1.5** UI 接线 | ✅ 完成 | `ConfigScreen` 接入 `AppPreferences` + `KeyStoreManager`(Plan C 加密 slot)+ SAF 私钥导入;`SshTermApp` 顶层拿 `LocalContext`;密码字段在 Save 后立即从本地 state 清掉,留存只走加密 blob;音量键调字号持久化 |
| **Sprint 2** 真 SSH | ✅ 完成(`feature/sprint-2-real-ssh`) | SSHJ 0.38 + BouncyCastle 1.78.1 接入,密码 + Ed25519/RSA 私钥认证,`SshClient`/`SshSession`/`SshTransport`(4 方法窄接口)+ `ChannelTransport`,xterm-256color + ECHO/ICANON PTY 分配,SIGWINCH 跟踪实测 grid 尺寸,30 s SSH keepalive + 60 s SO_TIMEOUT 防御 NAT 静默断开,`SshErrorMessages.friendly()` 把 SocketTimeoutException / ConnectException / banner-read 失败等转成单行可读英文,`AppLog` + `ConnectionLogPanel` 让用户在 app 内复制日志,`CrashHandler` 把崩溃栈写到 `filesDir/crash.log` 并在 Config 顶部展示 |
| Sprint 2.5 收尾 | ✅ 完成 | ✅ `SshSessionWriteTest` 的 `readInto` 取消契约翻转为「不关 session」(Activity 重建可复用);✅ `TerminalView` 加 alt-buffer 滚动 NPE 守卫(`OnTouchListener` + `dispatchGenericMotionEvent`)+ 6 个回归用例;✅ `TerminalView.onLayout` 重测内层 Termux view 填满 wrapper(1/4-screen 回归)+ PTY resize race 修复(`force=true` 穿透 debounce, TV-PTY-02)+ 2 个 `TerminalViewLayoutTest` 用例;✅ **Sprint 2.5 S1** known_hosts TOFU store + 21 个新测试(`SshClientHostKeyWiringTest` / `KnownHostsStoreTest` / `KnownHostsVerifierTest`);✅ **S2** 私钥 AES-256-GCM 加密存储 + `EncryptedPrivateKeyStore`;✅ **S3+S4** debug log 与 auth 诊断 gating(`ConfigScreenDebugLogGateTest` + `LegacyDebugLogCleanupTest` + 各 `*LogGateTest`);🟡 剩余 3 个 `@Ignore` 的 readInto 时序用例(自然结束路径,运行时序 flake);🟡 SSH 服务器兼容性矩阵(dropbear / busybox sshd) |
| **Sprint 2.5+** vim/nano KeyMapper 数据驱动重构 | ✅ 完成(`docs/code-review-2026-06-24`) | 把 `KeyMapper` 从手写 `when` 块改为 `KEY_MAP: List<KeyMapEntry>` 数据驱动路由表(21 条 entry,首匹配胜出);补全 7 个 vim/nano 缺漏的键位 —— `KEYCODE_ESCAPE`(无 Ctrl)→ `0x1B` / `Shift+Tab`→ `ESC[Z` / `KEYCODE_INSERT`→ `ESC[2~` / `Ctrl+^`→ `0x1E` / `Ctrl+_`→ `0x1F` / `Ctrl+@`→ `0x00` / `Ctrl+?`→ `0x7F`;新加 `KeyMapDoc.kt` 的 `ProgramUsage` + `KeyMapEntry` data class 给每条 entry 配结构化 vim/nano/bash 文档;修复 `Ctrl+ESC` 路由(原本 regression 到 Ignore)与 `KEYCODE_CIRCUMFLEX`/`KEYCODE_UNDERSCORE` 在 Android KeyEvent 不存在改用 `getCharacters()` 匹配;`KeyEventRoutingTest` 加 11 个 case(7 个新键 + ESC-while-composing + end-to-end + meta-test + Ctrl+ESC),从 31 → 42 case。详见 [§架构 / KeyMapper.kt](#keymapperkt-数据驱动路由表) 和 [`docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md`](docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md) |
| Sprint 3+ 主机管理 / SFTP / Mosh | 📋 远期 | 见 [路线图](#路线图) |

v1.0 + Sprint 2.5 已在平板上**配置主机 / 保存密码(Keystore AES-256-GCM) / 导入并加密私钥(AES-256-GCM → `filesDir/keys/`) / known_hosts TOFU 校验 / debug log + auth 诊断 gating / 通过音量键调字号 / 重连后数据持久化 / 在 app 内看诊断日志与崩溃栈并复制 / 跑 vim 和 nano 时所有标准快捷键可用(ESC / Shift+Tab / Insert / Ctrl+^ / Ctrl+_ / Ctrl+@ / Ctrl+? 等)**。剩下的是多主机列表、SFTP、Mosh、SSH-keepalive 服务器兼容性矩阵等 Sprint 3+ 工作。

---

## 快速上手

### 环境要求

| 工具 | 版本 | 安装 |
|---|---|---|
| JDK | 17+ | `sdk install java 17.0.11-tem`(项目 `gradlew` 自带,无需本机 JDK 17) |
| Android SDK | platform-34 + build-tools 34.0.0 | `sdkmanager "platforms;android-34" "build-tools;34.0.0"` |
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
SshSession.readInto(bytes → sink)   [IO 协程,接 Dispatchers.IO]
  │  ChannelTransport.readBytes()
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
│   ├── TerminalEndpoint.kt      SAM 接口(`MockEchoSession` 与 `SshSession` 都实现)
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
│   ├── SshClient.kt            SSHJ 0.38 连接编排 + Auth dispatch + 30s keepalive
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
│   ├── SshTermApp.kt           顶层:ConnectionState 状态机 + Connect/Disconnect 接线
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
└── com.hierynomus:sshj 0.38.0 + bcprov-jdk18on 1.78.1
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

### 6. SSH keepalive + SO_TIMEOUT 双保险

长挂在移动网络上的 SSH 会话会被 NAT 静默吃光路径 —— 操作系统几小时都不会发 TCP RST,`readInto` 永远阻塞,用户看到的是一个冻住的终端。

**正确**(`SshConfig.SSH_KEEPALIVE_INTERVAL_SECONDS = 30`):SSHJ `Connection.setKeepAlive(30s)`,在 sshj 自己的 KeepAlive 线程上每 30 秒发心跳,绝大多数移动 NAT(60-120 s 超时)会被这条主动探测到。

**再一道保险**(`SO_TIMEOUT_MS = 60_000`):即便 keepalive 也丢了,socket 读阻塞上限 60 秒,`SocketTimeoutException` → `SshErrorMessages.friendly()` 转成 "Connection timed out. Check your network and the server's address."。

### 7. 凭据存储 = Keystore + SAF 文件

- **密码**:Android Keystore AES-256-GCM,密文 Base64 进 `AppPreferences.KEY_ENCRYPTED_PASSWORD`(`KeyStoreManager.encrypt/decrypt`)
- **私钥文件**:`filesDir/keys/*.pem`,由 SAF `OpenDocument` 导入,文件名走 `sanitizeFileName`
- **解密的明文**只在内存里活几毫秒,`saveConfig` 后立刻 `password = ""` 清本地 state

**威胁边界**:防御"其他普通应用读私钥 / 密码"。**不防御**:root 设备、adb backup 迁移、调试器附加。可选 `setUserAuthenticationRequired(true)` 升级到生物识别解锁。

### 8. host key 校验 = v1.0 暂不实现

`SshClient` 默认装 `PromiscuousVerifier` —— MITM 防护留给 Sprint 3 的 TOFU known_hosts store。这是有意的取舍:v1.0 优先验证"能不能连上",不阻挡开发联调。

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
   - `SshTermApp` 首屏 `remember { ActiveSshSessionStore.get() }` 重新绑定;`showTerminal` / `connectionState` 用 `rememberSaveable` + 自定义 `listSaver` 序列化(Connecting 在恢复时降级为 Disconnected,旧协程已死)
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

---

## 测试

测试总数 **210 活跃 + 17 `@Ignore`**,分为 23 个测试类、4 类目标。所有失败立刻在 `app/build/reports/tests/` 出 HTML。

### 单元测试总览

| 测试类 | 数量 | 框架 | 覆盖 |
|---|---|---|---|
| `TerminalInputConnectionTest` | 11 | Robolectric | IME 5 方法 + Gboard 竞态 + 锁存标志 |
| `KeyEventRoutingTest` | **42**(Sprint 2.5+ 加 11:7 个新键 + ESC-while-composing + end-to-end + meta-test + Ctrl+ESC) | Robolectric | 物理键 View 链路路由决策表(含 Ctrl A-Z + `\` + `]` + `ESC` 全 ASCII 控制集 + 7 个 vim/nano 新键 + 数据驱动表 meta-test) |
| `AltBufferScrollCrashGuardTest` | 6 | Robolectric | alt-buffer 滚动 NPE 守卫(predicate + 反射复现上游 NPE + 触摸/滚轮拦截) |
| `ScrollbackControllerTest` | 16 | Robolectric | 双指翻页状态机:多指起手 + 阈值 + doScroll 反射 + alt-buffer 守卫 + `scrollToBottom` + `onTranscriptWrite` 累计 + 指针转换边缘 |
| `TerminalViewScrollbackWiringTest` | 3 | Robolectric | wrapper 接入:`scrollbackController` 懒加载 + `isInScrollback` getter + `scrollToBottom` 重置 mTopRow + `setScrollbackListener` 注册时 fire-once |
| `TerminalViewLayoutTest` | 2 | Robolectric | `onLayout` 1/4-screen 回归(内层 Termux view 在 FrameLayout 重测)+ `setPtyResizeListener` 注册时 fire-once race(GEARS TV-PTY-02,需 mockk 注入 `TerminalRenderer` 真实字体指标) |
| `AppPreferencesTest` | 13 | Robolectric | 数据层读写 / clear / hasUsableCredentials / 加密 blob 边界 |
| `EncryptedPrivateKeyStoreTest` | 8(Sprint 2.5 S2) | Robolectric | 私钥 AES-256-GCM 加密 slot 的写入 / 读取 / 损坏恢复 / `setUserAuthenticationRequired` 边界 |
| `AppLogTest` | 13 | Robolectric | 文件 sink / 轮转 / 并发写 / Logcat 镜像 + Sprint 2.5 S3 诊断级别 gating |
| `ConnectionDraftTest` | 2 | Robolectric | `applyDraftForConnect` 不误清空已存密码 |
| `ConfigScreenDebugLogGateTest` | 6(Sprint 2.5 S3) | Robolectric | debug 日志开关在 `ConfigScreen` 渲染时正确反映到 `AppLog` 级别 |
| `LegacyDebugLogCleanupTest` | 3(Sprint 2.5 S3) | Robolectric | 旧版本遗留 debug 日志在升级后被清理,不留敏感凭据到 `app.log` |
| `SshConfigTest` | 6 | 纯 JUnit | 默认值 pin,防误改 |
| `SshSessionWriteTest` | 8 活跃 + 4 `@Ignore` | 纯 JUnit | `write` / `resizePty` / `close` 幂等,readInto 异常翻译 + 取消不关 session |
| `SshErrorMessagesTest` | 17 | 纯 JUnit | Throwable → 友好文案全分支(含 sshj cause 链 + 自引用保护) |
| `SshClientHostKeyWiringTest` | 8(Sprint 2.5 S1) | 纯 JUnit | `SshClient` 装 `KnownHostsVerifier` 而非 `PromiscuousVerifier`,known_hosts 路径接通 |
| `KnownHostsStoreTest` | 11(Sprint 2.5 S1) | 纯 JUnit | `KnownHostsStore` 读写 / 更新 / 文件 IO / 格式解析 |
| `KnownHostsVerifierTest` | 10(Sprint 2.5 S1) | 纯 JUnit | verifier trust / mismatch / unknown 三态,MITM 防护路径 |
| `ActiveSshSessionStoreTest` | 4 | 纯 JUnit | 进程级 holder set / get / replace / 幂等 clear |
| `PublicKeyAuthProviderTest` | 3 活跃 + 2 `@Ignore` | 纯 JUnit + bcprov | Ed25519 / RSA PEM round-trip |
| `PublicKeyAuthProviderEncryptedTest` | 0 活跃 + 5 `@Ignore`(Sprint 2.5 S2) | 纯 JUnit + bcprov | 加密私钥路径(release-only,本地 dev 跳过) |
| `PublicKeyAuthProviderLogGateTest` | 2(Sprint 2.5 S3) | 纯 JUnit | 私钥失败路径不写敏感字节到 log |
| `PasswordAuthProviderLogGateTest` | 3(Sprint 2.5 S3) | 纯 JUnit | 密码失败路径不写密码到 log |

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

2026-07-02 已完成任务拆分,3 个任务互相独立(触及文件两两不相交),可任意顺序 / 并行认领。每个任务的完整 Given-When-shall 行为规范见 `docs/GEARS_SPEC.md` 对应 Module(状态:📋 Planned,尚未实现)。

- [ ] 平板横屏布局优化(目前 Config + TerminalPane 同屏,横屏显示密度偏低)—— 见 [`docs/GEARS_SPEC.md` Module 15](docs/GEARS_SPEC.md#module-15-landscape-split-layout-sprint-3-s1)
- [ ] 命令 Snippet(常用命令收藏)—— 见 [`docs/GEARS_SPEC.md` Module 16](docs/GEARS_SPEC.md#module-16-command-snippets-sprint-3-s2)
- [ ] `SshSession` 关闭原因区分(readInto 失败的"连接断了"和 Disconnect 按钮的"用户主动断"存在真实竞态,而不只是文案模糊)—— 见 [`docs/GEARS_SPEC.md` Module 17](docs/GEARS_SPEC.md#module-17-session-close-reason-disambiguation-sprint-3-s3)

候选,未排入本轮 Sprint 3(需要显式立项才启动,见 `CLAUDE.md`"Out of scope"):
- 多主机列表 + 分组 + 新增 / 编辑 / 删除

> `known_hosts TOFU store` 已在 Sprint 2.5 S1 完成(`SshClient` 已替换 `PromiscuousVerifier` 为 `KnownHostsVerifier`,见 [`docs/GEARS_SPEC.md` Module 11](docs/GEARS_SPEC.md#module-11-security--host-fingerprint-sprint-25-s1)),不再是 Sprint 3 待办。

### Sprint 4+(P4,远期)
- [ ] SFTP 文件管理(SSHJ `SFTPClient`)
- [ ] 端口转发
- [ ] 跳板 / ProxyJump
- [ ] Mosh(复杂度高,最后评估)
- [ ] TrueColor 终端类型(目前 `xterm-256color`)
- [ ] 鼠标协议(`xterm` mouse modes)

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
- [`docs/GEARS_SPEC.md`](docs/GEARS_SPEC.md) — 行为规范(Sprint 0-2.5 已实现 + Sprint 3 Module 15-17 任务拆分 spec,尚未实现)
- [`docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md`](docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md) — vim/nano KeyMapper 重构设计 spec
- [`docs/superpowers/plans/2026-06-29-vim-nano-keymapper.md`](docs/superpowers/plans/2026-06-29-vim-nano-keymapper.md) — vim/nano KeyMapper 6-task 实施计划

---

## License

待定(TBD)。Termux terminal-emulator 是 Apache 2.0,本项目主体尚未决定开源协议,先 private 仓库运营。

---

**Maintainer**: [@st6098770633](https://github.com/st6098770633)