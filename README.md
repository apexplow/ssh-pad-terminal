<p align="center">
  <img src="docs/images/hanterm-icon.svg" width="128" alt="HanTerm Icon">
</p>

# HanTerm

**一款纯键盘驱动的 Android 平板 SSH 客户端。核心差异化：正确支持中文输入法。**

> 🧪 **验证环境**: vivo Pad 3 Pro + Gboard（Google 拼音输入法）
> ⚠️ **当前限制**: 暂不支持软键盘（屏幕键盘）。HanTerm 依赖物理键盘 (POGO / 蓝牙 / USB) 驱动完整输入路由。连接前请确保已接入物理键盘。

[![Min SDK: 34](https://img.shields.io/badge/min%20SDK-34%20(Android%2014)-blue)]()
[![Target SDK: 36](https://img.shields.io/badge/target%20SDK-36%20(Android%2016)-purple)]()
[![License: MIT](https://img.shields.io/badge/License-MIT-green)]()
[![Gradle](https://img.shields.io/badge/Gradle-8.11.1-lightgrey)]()

> 当前架构契约 → [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。本 README 只做概览和快速上手，当前态、模块图、keepalive 策略、连接生命周期等以 ARCHITECTURE.md 为准。

---

## 解决了什么问题？

平板上用 SSH 客户端接上物理键盘，切到中文输入法——几乎所有现有工具都翻车：

| 问题 | 表现 |
|------|------|
| 拼音漏入终端 | 敲 "n i h a o"，远端 shell 出现一个一个的字母 |
| 退格键误删远端 | 中文候选词时按退格，既删了拼音又往远端发了个 DEL |
| `Ctrl+C` 一箭双雕 | 既取消了输入法候选词，又给远端进程发了 SIGINT |
| 编码污染 | 上屏的汉字混着拼音残留一起发了过去 |

**Termius、JuiceSSH、Termux 都知道这个 bug，但都没修。** 原因很简单——`InputType.TYPE_NULL` 一刀切禁用了 IME，然后各个项目在它上面堆 hack，越堆越烂。

HanTerm 的解法：**正确接入 `InputConnection`，用状态机区分 IME 组合态和空闲态，每条按键事件走且只走一条路。** 不是绕着 IME 走，是正确接上它。

---

## 功能

| 功能 | 状态 |
|------|------|
| SSH 登录（密码 + Ed25519/RSA 私钥） | ✅ |
| xterm-256color 终端（Termux 终端核心） | ✅ |
| **中文/日文/韩文 IME 输入——零拼音透传** | ✅ **核心差异** |
| 物理键盘完整 ANSI 转义路由（Ctrl/Alt/功能键） | ✅ |
| vim/nano 全键位覆盖（21 条数据驱动映射表） | ✅ |
| 主机密钥验证（TOFU + known_hosts + MITM 检测） | ✅ |
| 双指翻页 + 新输出徽章 + 自动回底 | ✅ |
| 音量键调节字号 | ✅ |
| 平板横屏双栏布局（左侧配置 + 右侧终端） | ✅ |
| 内置诊断日志查看 + 崩溃报告 | ✅ |
| 连接保活（SSH 心跳 + TCP keepalive + 前台服务） | ✅ |
| ZMODEM / trzsz 文件接收（`sz` / `tsz` → 下载目录） | ✅ |

### v1.0 之后

| 功能 | 优先级 |
|------|--------|
| 多主机列表 + 分组 | 规划中 |
| Mosh 协议 | 规划中 |
| SFTP 文件浏览 | 规划中 |

## 快速上手

### 环境

| 工具 | 版本 |
|------|------|
| JDK | 21+（项目 `gradlew` 自带 Temurin 21） |
| Android SDK | platform-36 + build-tools 36.0.0 |
| 设备 | Android 14+（minSdk = 34） |

### 构建

```bash
git clone git@github.com:apexplow/ssh-pad-terminal.git
cd ssh-pad-terminal

# 运行测试（312 个活跃用例，0 @Ignore）
./gradlew :app:testDebugUnitTest

# 构建 debug APK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# 安装到已连接的设备
./gradlew :app:installDebug

# Release AAB（需环境变量签名）
export KEYSTORE_PATH=/secure/path/release.jks
export KEYSTORE_PASSWORD=***
export KEY_ALIAS=...
export KEY_PASSWORD=***
./gradlew :app:bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

### 从服务器接收文件

```bash
# tmux 内外均可（推荐）：远程安装 trzsz
ssh user@host
pip install trzsz
tsz some-file.txt
# → 自动存到设备 Downloads/

# 非 tmux 环境：经典 lrzsz 的 sz 也可用
sz some-file.txt
```

## 架构

```
                                                                        
                    HanTerm App                                         
                                                                        
 配置屏           InputDispatcher               终端屏                  
                  ├ isComposing?                                        
                  │ → 走 IME 还是直发                                    
                  │ → KeyMapper / InputConnection                       
                  └────────┬──────────                                   
                           │                                            
                    PtyBridge       对称 I/O seam                       
                    view ↔ transport                                    
                    ┌──┴──┐                                             
                Termux   SshBridgeAdapter                                
               Emulator  in/out 两路                                    
                    ┌────┴────┐                                         
                 SshClient (SSHJ 0.40 + BouncyCastle)                   
                    ┌────┴────┐                                         
                   SSH Server                                           
```

### 关键设计决策

1. **终端核心不自研**——直接引入 [Termux terminal-emulator](https://github.com/termux/termux-app)（Apache 2.0），它已经解决了 ANSI 状态机、CJK 宽字符、回滚缓冲。我们只覆写输入链路。

2. **正确实现 `InputConnection`**——不用 Termux 的 `InputType.TYPE_NULL`（那会打爆 IME）。我们的 `TerminalInputConnection` 实现了标准 IME 5 个回调方法，带组合态/空闲态状态机。

3. **`InputDispatcher` 是唯一路由决策者**——每条按键事件过同一个决策点，检查 `isComposing`、修饰键状态、已知按键模式 → 输出 `Send | Swallow | Ignore`。

4. **PtyBridge 抽象层**——输入输出走对称的 `BufferedPtyBridge`，transport 层（SSH / 本地 shell / Mosh）可完整替换，UI 层一行不改。

5. **进程级 ConnectionRuntime**——活跃 SSH 会话跨 Activity 重建存活（旋转屏幕、分屏、主题切换都不会断开）。

### 项目结构

```
app/src/main/java/com/taosun/hanterm/
├── terminal/           ★ IME + 渲染核心
│   ├── TerminalView.kt          Termux.TerminalView 包装
│   ├── InputDispatcher.kt       ★ 路由决策者
│   ├── TerminalInputConnection.kt  IME 5 方法适配器
│   ├── ImeKeyRouter.kt          View 层按键事件路由
│   ├── KeyMapper.kt             数据驱动 ANSI 映射表（21 条）
│   ├── PtyBridge.kt             对称 I/O seam
│   ├── BufferedPtyBridge.kt     生产实现
│   ├── TerminalComposingView.kt 拼音 hint 浮层
│   ├── ScrollbackController.kt  双指翻页
│   ├── MockEchoSession.kt       断线兜底
│   └── FontSizeController.kt    音量键字号
│
├── ssh/                SSH 传输层（SSHJ 0.40）
│   ├── SshClient.kt             连接/断开/保活
│   ├── SshBridgeAdapter.kt      PtyBridge ↔ SshSession 接线
│   ├── SshSession.kt            读写执行器 + PTY
│   ├── ConnectionRuntime.kt     ★ 连接生命周期单一所有者
│   ├── SshKeepAliveService.kt   前台服务
│   └── auth/                    密码认证 / 公钥认证
│
├── ssh/security/       主机密钥验证
│   ├── KnownHostsStore.kt       AtomicFile 持久化
│   ├── KnownHostsVerifier.kt    TOFU + MITM 检测
│   └── CanonicalHostKeyFingerprint.kt
│
├── ui/                 Compose UI
│   ├── HanTermApp.kt            顶层壳 + 权限管理
│   ├── HanTermAppViewModel.kt   状态所有者
│   ├── TerminalPane.kt          终端画面
│   ├── ConfigScreen*.kt         连接表单
│   └── components/              终端 / 连接栏等组件
│
└── data/               凭据存储
    ├── profile/                 ConnectionProfile
    ├── crypto/                  KeyStoreManager + 加密私钥
    └── prefs/                   AppPreferences
```

### 输入路由状态机

```
物理按键
    │
    ├── isComposing == true（IME 正在拼拼音）
    │     └──► InputConnection 处理。onKeyDown 返回 false。
    │          不向 SSH 发送任何字节。
    │
    ├── 可打印字符，无 Ctrl/Alt，非组合状态
    │     └──► InputConnection.commitText() → UTF-8 → SSH
    │
    ├── Ctrl/Alt 修饰键 或 功能键
    │     └──► KeyMapper.resolve() → ANSI 转义序列 → SSH
    │          事件被吞掉，不传给 IME。
    │
    └── 语言切换快捷键（Ctrl+Space, Shift+Space, 等）
          └──► 严格吞掉。绝不送到 SSH。
```

所有输入路由逻辑在 [`InputDispatcher.kt`](app/src/main/java/com/taosun/hanterm/terminal/InputDispatcher.kt)。

## 测试

1,288 个活跃用例，0 个 `@Ignore`，11 个 `assumeTrue`。

```bash
./gradlew :app:testDebugUnitTest
# 报告：app/build/reports/tests/testDebugUnitTest/index.html
```

关键测试类：

| 测试 | 覆盖 |
|------|------|
| `TerminalInputConnectionTest` | IME 5 方法状态机 |
| `KeyEventRoutingTest` | 42 个路由用例（vim/nano/bash） |
| `InputDispatcherTest` | 组合态 vs 空闲态决策 |
| `SshClientHostKeyWiringTest` | TOFU 存储集成 |
| `KnownHostsVerifierTest` | MITM 检测 + v0→v1 迁移 |
| `TerminalView*Test` | 回滚、alt-buffer、NPE 守卫 |

## 技术栈

| 组件 | 选择 | 原因 |
|------|------|------|
| 平台 | Android (Kotlin, Compose) | 原生平板 UI |
| 最低/目标 SDK | 34 (Android 14) / 36 (Android 16) | FGS specialUse 需要 34+ |
| SSH 库 | [SSHJ 0.40.0](https://github.com/hierynomus/sshj) | 纯 Java，Android 兼容 |
| 终端核心 | [Termux terminal-emulator v0.118](https://github.com/termux/termux-app) | Apache 2.0，支持 CJK |
| 加密 | BouncyCastle 1.80.2 + Android Keystore | AES-256-GCM 私钥加密 |
| UI | Material 3 (Compose BOM 2024.10) | 现代 Android 设计 |
| 字体 | JetBrainsMono Nerd Font（内置） | 编程连字 |
| 测试 | Robolectric 4.16 + JUnit 4 + MockK | SDK 36 矩阵 |

## 安全

- **私钥**：AES-256-GCM 加密，密钥由 Android Keystore 硬件级保护
- **密码**：加密存于 SharedPreferences blob，永不存明文
- **主机密钥**：TOFU 验证 + known_hosts 持久化，密钥变更时提示 MITM
- **日志**：敏感数据（主机/用户名/指纹）标记为 `ConnectionMetadata` → debug 写 logcat，release 不写文件
- **不从远端下载代码**：不下载或执行远程 dex/jar/so
- **不需要 root**：标准 Android 权限

## 设计原则

- **键盘优先**——所有功能不碰屏幕可操作。默认无边框 UI，面板只在快捷键触发时浮现。
- **状态可见**——连接状态、输入法模式、键盘焦点通过非侵入式指示器一直可见。
- **平板原生**——横屏优先，双栏分屏布局，沉浸全屏模式。
- **默认防御**——Activity 设 `configChanges`，旋转/分屏不会杀掉会话。

## License

[MIT](LICENSE)

内置的 [Termux terminal-emulator](https://github.com/termux/termux-app) 库基于 Apache 2.0。
[SSHJ](https://github.com/hierynomus/sshj) 基于 Apache 2.0 / BSD 2-Clause。

---

*为平板 SSH 体验本该有的样子而造。*
