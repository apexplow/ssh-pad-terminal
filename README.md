# SSH Pad Terminal

> Android 平板原生 SSH 客户端。**核心差异化**:正确解耦 Android 输入法体系与终端键盘体系 —— 让中文拼音 IME 在远程 SSH 会话里像本地输入一样工作。

[![Status: Sprint 1.5](https://img.shields.io/badge/status-Sprint%201.5%20%E5%AE%8C%E6%88%90-brightgreen)](#当前状态)
[![Tests: 20/20](https://img.shields.io/badge/tests-20%2F20%20pass-success)](#验证)
[![Min SDK: 29](https://img.shields.io/badge/min%20SDK-29%20(Android%2010)-blue)](#技术栈)

---

## 解决什么问题

Termius、Termux 等主流 SSH 工具在平板上的中文输入体验都有缺陷:

| 问题 | 后果 |
|---|---|
| `InputType.TYPE_NULL` 锁死文本编辑能力 | IME 候选词、拼音删除、光标移动失效 |
| 物理键盘 + IME 双链路同时触发 | 一个按键重复发两遍 |
| 退格键不分组合状态 | 拼音输入中途按退格,远端被误删 |
| 取消输入(ESC)误判为提交 | 多余的换行/空格发到 SSH |

本项目从零围绕**输入法体系解耦**重新设计,验证一块长期被忽视的平板 SSH 体验。

---

## 当前状态

| Sprint | 状态 | 关键交付 |
|---|---|---|
| **Sprint 0** 基础设施 | ✅ 完成 | Gradle 8.9 + JDK 17 + AGP 8.7.3 + Kotlin 1.9.24,集成 Termux terminal-emulator v0.118,深色 Compose UI 骨架 |
| **Sprint 1** IME 核心 | ✅ 完成 | `TerminalInputConnection` 5 方法 + `KeyMapper` ANSI 转义 + `MockEchoSession`,Robolectric 6/6 测试绿,`KeyStoreManager`(AES-256-GCM)+ `AppPreferences` 数据层,debug APK 出包 24 MB |
| **Sprint 1.5** UI 接线 | ✅ 完成(`feature/sprint-1.5-config-persistence`) | `ConfigScreen` 接入 `AppPreferences` + `KeyStoreManager`(Plan C 加密 slot)+ SAF 私钥导入;`SshTermApp` 顶层拿 `LocalContext`;新增 14 个 Robolectric 用例(AppPreferences 8 + KeyEvent 路由表 6);密码字段在 Save 后立即从本地 state 清掉,留存只走加密 blob |
| Sprint 2 真 SSH | 📋 计划 | SSHJ 库接入,密码 + Ed25519 认证,PTY 分配 + SIGWINCH |
| Sprint 3+ 主机管理/SFTP/Mosh | 📋 远期 | 见 [路线图](#路线图) |

**Sprint 1.5 后已无功能性遗留** —— APK 装上平板可保存主机/密码(Keystore AES-256-GCM 加密)/私钥(SAF 导入到 `filesDir/keys/`),关 app 重启数据持久化。剩余事项全部是 Sprint 2+ 的范围。

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
SshSession.read()        [IO Coroutine]              ← Sprint 2 待实现
  │
  ▼
TerminalEmulator.process(bytes)    [Termux 黑盒]
  │ (RefreshSignal)
  ▼
Channel<Unit>(CONFLATED)           [节流、防爆栈]
  │
  ▼
[UI 线程消费协程]
  │
  ▼
TerminalView.postInvalidateOnAnimation()    [VSync 统一重绘]
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
  │     └──► InputConnection.commitText() → UTF-8 → session.write()
  │
  ├── Ctrl/Alt 修饰 / 功能键
  │     │
  │     └──► onKeyDown() → KeyMapper.toAnsiSequence() → session.write()
  │
  └── IME 组合中(拼音阶段)
        │
        └──► InputConnection.setComposingText() → 本地 hint 浮层,**不发 SSH**
```

**关键约束**:两条链路互斥,不可重复发送。详见 [`implementation_plan.md` §输入链路设计](implementation_plan.md)。

### 模块划分

```
:terminal-emulator/        ← Termux 库,黑盒引入,基本不改
    TerminalEmulator.kt
    TerminalView.kt

:app/
├── terminal/               ★ Sprint 1 核心
│   ├── TerminalView.kt          继承 Termux.TerminalView,重写输入
│   ├── TerminalInputConnection  IME 5 方法完整实现
│   ├── KeyMapper.kt             物理键 → ANSI 转义
│   ├── TerminalEndpoint.kt      数据出口接口(Sprint 2 接 SSHJ)
│   ├── TerminalComposingView    拼音 hint 回调
│   └── MockEchoSession          Sprint 1 mock,验证用
│
├── data/                   Sprint 1 数据层
│   ├── crypto/KeyStoreManager.kt    Android Keystore AES-256-GCM
│   └── prefs/AppPreferences.kt      SharedPreferences 单主机
│
├── ui/                     Compose 骨架(Sprint 1.5 待接数据层)
│   ├── SshTermApp.kt
│   ├── ConfigScreen.kt
│   └── TerminalPane.kt
│
└── theme/                  Warp 风格深色 + JetBrainsMono 字体
```

---

## 关键设计决策

### 1. 终端核心不自研

**错误**:从零写 `Canvas + ANSI 状态机 + 双缓冲 + CJK 双宽字符`。

**正确**:引入 [Termux terminal-emulator](https://github.com/termux/termux-app/tree/master/terminal-emulator)(Apache 2.0)。它已经解决光标定位、选择区域、CJK 宽字符、控制序列(CSI/OSC/DCS)、退格与本地回显一致性。

**约束**:`terminal-emulator` 黑盒使用,**任何修改其内部的冲动都必须先提 Issue 讨论**。

### 2. `InputType.TYPE_NULL` 换掉

**错误**:`InputType.TYPE_NULL`,IME 候选词、拼音删除、光标移动全废。

**正确**:`TYPE_CLASS_TEXT | NO_SUGGESTIONS | VARIATION_VISIBLE_PASSWORD` + IME 自管软键盘弹/收。

### 3. 私钥存储 = 文件 + Keystore 混合

- **私钥文件**:`filesDir/keys/*.pem`(加密后)
- **加密密钥**:Android Keystore AES-256,KeyGenParameterSpec 锁定 `PURPOSE_ENCRYPT|DECRYPT + BLOCK_MODE_GCM`

**威胁边界**:防御"其他普通应用读私钥"场景。**不防御**:root 设备、adb backup 迁移、调试器附加。可选 `setUserAuthenticationRequired(true)` 升级到生物识别解锁。

### 4. 双链路分离去重

| 事件 | 处理链路 | 行为 |
|---|---|---|
| 可打印字符(无 Ctrl/Alt) | `InputConnection.commitText()` | `onKeyDown` 返回 `false`,系统分发 |
| 可打印字符 + Ctrl/Alt | `onKeyDown` | 转 ANSI,**吞掉**不传 InputConnection |
| `KEYCODE_DEL`(组合中) | `InputConnection.deleteSurroundingText` | `onKeyDown` 返回 `false`,IME 自管 |
| `KEYCODE_DEL`(非组合) | `onKeyDown` | 发 `0x7F`(DEL),**吞掉** |
| IME 组合中(拼音) | `setComposingText` | 本地 hint,**不发 SSH** |
| IME 提交(汉字上屏) | `commitText` | UTF-8 发 SSH,清 composing 状态 |

完整规则表见 [`implementation_plan.md` §KeyEvent 路由规则表](implementation_plan.md)。

---

## 测试

### Robolectric 单元测试(20/20 绿)

#### `TerminalInputConnectionTest`(Sprint 1,6 个 — IME 链路)
| 用例 | 验证 |
|---|---|
| `test_setComposingText_updatesStateButDoesNotWriteToSsh` | 拼音阶段不发包 |
| `test_commitText_sendsUtf8BytesAndClearsComposing` | 汉字 UTF-8 发包 + 清 composing |
| `test_commitText_emptyTextIsNoOp` | 空文本防误发 |
| `test_deleteSurroundingText_whenComposing_doesNotSendDel` | 组合中退格不发包 |
| `test_deleteSurroundingText_whenIdle_sendsDelSequence` | 非组合发 `0x7F` |
| `test_finishComposingText_clearsStateButDoesNotWriteToSsh` | 取消输入不发包 |

#### `KeyEventRoutingTest`(Sprint 1.5,6 个 — 物理键 View 链路,补 Sprint 1 盲区)
| 用例 | 验证 |
|---|---|
| `test_printableChar_isHandledByImePath_notView` | 可打印字符 View 返回 false(交给 IME) |
| `test_ctrlC_writesInterruptAndConsumesEvent` | Ctrl+C 发 0x03 并吞掉 |
| `test_enter_writesCarriageReturn` | Enter 发 `\r` |
| `test_backspaceWhenIdle_writesDelByte` | 退格(非组合)发 0x7F |
| `test_backspaceWhileComposing_isRoutedToIme_noDelWritten` | **关键**:退格(组合中)走 IME,View 不发 DEL |
| `test_arrowUp_writesAnsiCursorSequence` | 方向键发 ESC[A |

#### `AppPreferencesTest`(Sprint 1.5,8 个 — 数据层)
| 用例 | 验证 |
|---|---|
| `test_saveAndLoadRoundTrip_hostPortUsername` | 基本字段持久化 |
| `test_saveAndLoadRoundTrip_privateKeyName` | 私钥名字段 |
| `test_clear_wipesAllFields` | clear 全清 |
| `test_hasUsableCredentials_returnsTrueWhenPasswordSet` | 密码有效 |
| `test_hasUsableCredentials_returnsTrueWhenPrivateKeySet` | 私钥有效 |
| `test_hasUsableCredentials_returnsFalseWhenBothBlank` | 凭据无效 |
| `test_getEncryptedPassword_returnsNullWhenNotSet` | Plan C:未设置返回 null |
| `test_getEncryptedPassword_returnsNullForEmptyBlob` | Plan C:**空 blob 也返回 null**(Sprint 1.5 bugfix) |

### 手工联调(平板真机)

1. 蓝牙/USB 实体键盘 + 搜狗/Gboard,`vim` Insert 模式输入中文,确认拼音阶段无字母掉到终端
2. 输入中按 `ESC` 取消,确认不收到多余换行/空格,且远端 `vim` 退出 Normal 模式
3. 拼音中途按退格,确认不发 DEL 到远端
4. 非组合状态按 `Ctrl+C` / `Ctrl+D` / `Tab`,确认终端收到控制信号

---

## 路线图

### Sprint 2(P2,1-2 周)
- [ ] 引入 [SSHJ](https://github.com/hierynomus/sshj) 0.38+
- [ ] `SshClient.connect()`(密码 + Ed25519)
- [ ] `SshSession` 双向数据流(Coroutine + Channel)
- [ ] PTY 分配 + xterm-256color
- [ ] SIGWINCH 处理(横竖屏切换)
- [ ] SSH 兼容性验证:OpenSSH 7.x / 8.x / 9.x

### Sprint 3+(P3,远期)
- [ ] 主机列表 + 分组 + 新增/编辑
- [ ] 私钥导入加密(via Android SAF)
- [ ] 平板横屏布局优化
- [ ] 命令 Snippet
- [ ] SFTP 文件管理
- [ ] 端口转发
- [ ] Mosh(复杂度高,最后评估)

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

- [`implementation_plan.md`](implementation_plan.md) — 完整技术设计,563 行
- [`test_plan.md`](test_plan.md) — 测试计划
- [`SPRINT_0_1_DONE.md`](SPRINT_0_1_DONE.md) — Sprint 0+1 完成记录 + 验收证据
- [`HANDOFF.md`](HANDOFF.md) — Codex → Claude Code 交接记录(开发过程留底)

---

## License

待定(TBD)。Termux terminal-emulator 是 Apache 2.0,本项目主体尚未决定开源协议,先 private 仓库运营。

---

**Maintainer**: [@st6098770633](https://github.com/st6098770633)