# SSH Pad Terminal

> Android 平板原生 SSH 客户端。**核心差异化**:正确解耦 Android 输入法体系与终端键盘体系 —— 让中文拼音 IME 在远程 SSH 会话里像本地输入一样工作。

[![Status: Sprint 2 完成](https://img.shields.io/badge/status-Sprint%202%20%E5%AE%8C%E6%88%90-brightgreen)](#当前状态)
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
| Sprint 2.5 收尾 | 📋 短期 | `SshSessionWriteTest` 中 4 个 `@Ignore` 的 readInto 时序用例、`TerminalViewCrashTest` 删除后回归用例、未做的 known_hosts TOFU、误差判定 SSH 服务器兼容性矩阵 |
| Sprint 3+ 主机管理 / SFTP / Mosh | 📋 远期 | 见 [路线图](#路线图) |

v1.0 可在平板上**配置主机 / 保存密码(Keystore AES-256-GCM)/ 导入私钥(SAF → `filesDir/keys/`)/ 通过音量键调字号 / 重连后数据持久化 / 在 app 内看诊断日志与崩溃栈并复制**。剩下的是 host-key 校验、多主机列表、SFTP、SSH-keepalive 服务器兼容性矩阵等 Sprint 3+ 工作。

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

### 模块划分

```
:app/
├── terminal/               ★ IME 与渲染
│   ├── TerminalView.kt          继承 FrameLayout,内嵌 Termux.TerminalView;
│   │                            重写 IME / 物理键 / 报告 PTY 尺寸变化
│   ├── TerminalInputConnection  IME 5 方法(含 Gboard userInImeContext 锁存)
│   ├── KeyMapper.kt             KeyResolution 三态 + 物理键 → ANSI
│   ├── TerminalEndpoint.kt      SAM 接口(`MockEchoSession` 与 `SshSession` 都实现)
│   ├── TerminalComposingView    拼音 hint 回调
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
│   ├── SshSession.kt           TerminalEndpoint 实现 + readInto(单线程 write exec)
│   ├── SshTransport.kt         4 方法窄接口(write / readBytes / resizePty / close)
│   ├── ChannelTransport.kt     生产实现,包 SSHJ Channel + 强制 flush
│   ├── SshConfig.kt            DEFAULT_PORT/TERM/PTY/CONNECT_TIMEOUT/SO_TIMEOUT 等常量
│   ├── SshErrorMessages.kt     Throwable → 单行可读英文(含 sshj cause 链回溯)
│   ├── SshException.kt         内部异常(友好 message + 原 cause)
│   ├── BouncyCastleBootstrap.kt 幂等注册 BouncyCastle JCE provider
│   └── auth/
│       ├── Auth.kt             sealed class PasswordAuth / PublicKeyAuth
│       ├── SshAuthProvider.kt  strategy 接口
│       ├── PasswordAuthProvider.kt
│       └── PublicKeyAuthProvider.kt  PEM(RSA + Ed25519)加载
│
├── ui/                     Compose 装配
│   ├── SshTermApp.kt           顶层:ConnectionState 状态机 + Connect/Disconnect 接线
│   ├── ConfigScreen.kt         表单 + Crash 日志展示 + 私钥 SAF 导入
│   ├── TerminalPane.kt         AndroidView 包装 + IO 协程驱动 emulator.append
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
| 可打印字符 + Ctrl/Alt | `onKeyDown` → `KeyMapper.resolve()` | 转 ANSI,`KeyResolution.Send` **吞掉**不传 InputConnection |
| `KEYCODE_DEL`(组合中) | `InputConnection.deleteSurroundingText` | `onKeyDown` 返回 `false`,IME 自管 |
| `KEYCODE_DEL`(非组合) | `onKeyDown` | 发 `0x7F`(DEL),**吞掉** |
| IME 组合中(拼音) | `setComposingText` | 本地 hint,**不发 SSH** |
| IME 提交(汉字上屏) | `commitText` | UTF-8 发 SSH,清 composing 状态 |
| Ctrl+Space / Shift+Space / KEYCODE_LANGUAGE_SWITCH | `onKeyDown` → `KeyMapper.resolve()` | `KeyResolution.Swallow` —— 吞掉,IME 内部事 |

完整规则表见 [`implementation_plan.md` §KeyEvent 路由规则表](implementation_plan.md)。

### 10. 用户日志与崩溃栈的内嵌可见

**问题**:`adb logcat` 是诊断金标准,但平板用户多半没装 adb。

**正确**:
- `AppLog`(Sprint 2 增):所有 `SshClient.connect` / `SshSession.readInto` 失败的 throwable + 友好 message + 完整 stacktrace 写进 `filesDir/app.log`(轮转 256 KB),同时镜像到 Logcat
- `ConnectionLogPanel`:失败 overlay 上有 "Show logs" / "Copy logs" 按钮 —— 一键把整段日志贴到剪贴板
- `CrashHandler`:在 `Thread.setDefaultUncaughtExceptionHandler` 上挂一层,把栈写 `filesDir/crash.log`,下次启动 `ConfigScreen` 顶部展示并支持 Copy / Dismiss
- `reader` 线程的 "Software caused connection abort" 不算崩溃,单独排除(详见 `MainActivity.kt:isHandledTransportAbort`)

---

## 测试

测试总数 ~80+,分为 9 个测试类、4 类目标。所有失败立刻在 `app/build/reports/tests/` 出 HTML。

### 单元测试总览

| 测试类 | 数量 | 框架 | 覆盖 |
|---|---|---|---|
| `TerminalInputConnectionTest` | 11 | Robolectric | IME 5 方法 + Gboard 竞态 + 锁存标志 |
| `KeyEventRoutingTest` | 10 | Robolectric | 物理键 View 链路路由决策表 |
| `AppPreferencesTest` | 11 | Robolectric | 数据层读写 / clear / hasUsableCredentials / 加密 blob 边界 |
| `AppLogTest` | 13 | Robolectric | 文件 sink / 轮转 / 并发写 / Logcat 镜像 |
| `ConnectionDraftTest` | 2 | Robolectric | `applyDraftForConnect` 不误清空已存密码 |
| `SshConfigTest` | 6 | 纯 JUnit | 默认值 pin,防误改 |
| `SshSessionWriteTest` | 8 活跃 + 4 `@Ignore` | 纯 JUnit | `write` / `resizePty` / `close` 幂等,readInto 异常翻译 |
| `SshErrorMessagesTest` | 17 | 纯 JUnit | Throwable → 友好文案全分支(含 sshj cause 链 + 自引用保护) |
| `PublicKeyAuthProviderTest` | 5 | 纯 JUnit + bcprov | Ed25519 / RSA PEM round-trip |

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

#### Sprint 2 SSH 链路
| 用例 | 验证 |
|---|---|
| `SshSessionWriteTest.test_write_forwardsBytesVerbatimToTransport` | 写出字节与原序列一致 |
| `SshSessionWriteTest.test_write_multipleCallsAccumulateInOrder` | 多笔 write FIFO,UTF-8 边界正确 |
| `SshSessionWriteTest.test_close_isIdempotent` | `close()` 多次调用,`onClose` 只触发一次 |
| `SshSessionWriteTest.test_readInto_socketTimeout_isTranslatedToFriendlyMessage` | **回归**:SocketTimeoutException → "Connection timed out. Check your network and the server's address.",原异常保留在 `cause` |
| `SshErrorMessagesTest.test_socketTimeoutException_withBannerReadFrame_returnsBannerMessage` | banner-read 失败特殊文案 |
| `SshErrorMessagesTest.test_causeChain_unwrapsSshjWrapping` | sshj 双层 wrap 也能回溯到 `SocketTimeoutException` |
| `SshErrorMessagesTest.test_causeChain_handlesSelfReferentialCause` | 自引用 `cause` 不死循环 |
| `PublicKeyAuthProviderTest` | Ed25519 / RSA PKCS#8 PEM round-trip |

### 手工联调(平板真机)

1. 蓝牙 / USB 实体键盘 + 搜狗 / Gboard,`vim` Insert 模式输入中文,确认拼音阶段无字母掉到终端
2. 输入中按 `ESC` 取消,确认不收到多余换行 / 空格,且远端 `vim` 退出 Normal 模式
3. 拼音中途按退格,确认不发 DEL 到远端
4. 非组合状态按 `Ctrl+C` / `Ctrl+D` / `Tab`,确认终端收到控制信号
5. 真 SSH 主机(host / port / user / 密码或私钥任选),填表单 → Save → Connect → 在终端跑 `top` / `vim`
6. 故意填错密码,确认错误 overlay 弹出友好文案 + "Show logs" / "Copy logs" 可用
7. 拔网线或服务器关停,确认 30 s 内 overlay 弹出而非永久冻屏
8. 音量上 / 下调整字号,杀进程重启后字号保持

---

## 路线图

### Sprint 2.5(短期,1 周内)
- [ ] `SshSessionWriteTest` 中 4 个 `@Ignore` 的 readInto 时序用例(JUnit + coroutine 取消的稳定性)
- [ ] OpenSSH 7.x / 8.x / 9.x 兼容性矩阵(dropbear / busybox sshd 也跑一遍)
- [ ] `KeyStoreManager` 在 Robolectric 下的最小冒烟(目前明确放在真机矩阵)

### Sprint 3(P3,2-4 周)
- [ ] known_hosts TOFU store(`SshClient` 替换 `PromiscuousVerifier`)
- [ ] 多主机列表 + 分组 + 新增 / 编辑 / 删除
- [ ] 平板横屏布局优化(目前 Config + TerminalPane 同屏,横屏显示密度偏低)
- [ ] 命令 Snippet(常用命令收藏)
- [ ] `SshSession` 暴露真实错误事件(目前 readInto 失败的"连接断了"和 Disconnect 按钮的"用户主动断"在 UI 难区分)

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

---

## License

待定(TBD)。Termux terminal-emulator 是 Apache 2.0,本项目主体尚未决定开源协议,先 private 仓库运营。

---

**Maintainer**: [@st6098770633](https://github.com/st6098770633)