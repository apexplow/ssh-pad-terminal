# SSH Terminal for Android Pad — 技术设计文档 v2

> **核心命题**：能不能把 Android 输入法体系和终端键盘体系正确解耦。
> 如果这块打通了，就真的做出了 Termius/Termux 没解决好的平板 SSH 工具。

---

## 项目概览（修订后）

| 维度        | 决策                                  | 备注                        |
| --------- | ----------------------------------- | ------------------------- |
| **平台**    | 原生 Android (Kotlin)                 | —                         |
| **最低版本**  | Android 10 (API 29)                 | —                         |
| **UI 风格** | 深色主题，Warp 风格                        | Material3                 |
| **SSH 库** | SSHJ 0.38+                          | 需配置 BouncyCastle provider |
| **终端核心**  | Termux terminal-emulator 库          | Apache 2.0，不自研状态机         |
| **内置字体**  | JetBrainsMono Nerd Font             | —                         |
| **私钥存储**  | 文件在 App 私有目录 + 口令在 Android Keystore | 混合方案                      |
| **Mosh**  | **移至 v1.1**                         | v1.0 不做                   |
| **分发方式**  | GitHub 自编译 APK                      | —                         |

---

## MVP 范围（收缩后）

### v1.0 做

- SSH 登录（密码 + 私钥认证）
- 终端显示（xterm-256color，基于 Termux terminal-emulator）
- **中文 IME 完整输入**（核心差异化）
- 主机管理（列表 + 分组 + 新增/编辑）
- 深色平板 UI

### v1.0 不做

| 功能 | 原因 |
|------|------|
| SFTP | 锦上添花 |
| 端口转发 | 非核心 |
| Mosh | 复杂度高，先验证核心价值 |
| 命令历史面板 | 非核心 |

---

## 关键决策校正

### 决策 1：终端核心不自研

**错误做法**：从零自研 `Canvas + ANSI 状态机 + 双缓冲 + CJK 双宽字符`

**正确做法**：直接引入 [Termux terminal-emulator](https://github.com/termux/termux-app/tree/master/terminal-emulator)（Apache 2.0），它已经解决了：

- 光标定位
- 选择区域
- 行折叠
- 颜色属性（256color + TrueColor）
- 宽字符（CJK fullwidth）
- 控制序列（CSI/OSC/DCS）
- 退格/删除与本地回显一致性

我们只需要：
1. 把 `terminal-emulator` 作为 module 引入
2. 在它的 `TerminalView` 之上，重写输入链路（`InputConnection`）
3. 不动渲染层

> **架构约束**：`terminal-emulator` 作为黑盒核心使用，项目代码只负责三件事：
> `TerminalView` 的输入处理、`InputConnection` 的 IME 对接、SSH 数据流的接入。
> **任何试图重写渲染层或修改 terminal-emulator 内部的冲动，都应先提出 Issue 讨论，不允许直接动手。**

### 决策 2：`InputType.TYPE_NULL` 换掉

**错误做法**：`InputType.TYPE_NULL`，会破坏 IME 的候选词、删除、光标移动能力

**正确做法**：

```kotlin
// TerminalView.kt
override fun onCheckIsTextEditor(): Boolean = true

override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                         InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                         InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD  // 抑制自动补全

    outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
                          EditorInfo.IME_FLAG_NO_FULLSCREEN or
                          EditorInfo.IME_FLAG_NO_EXTRACT_UI

    outAttrs.initialSelStart = 0
    outAttrs.initialSelEnd = 0
    return TerminalInputConnection(this, session)
}
```

**目标**："不要自动弹软键盘" ≠ "不要文本编辑能力"。
软键盘的显示/隐藏由 `WindowInsetsController` 或 `InputMethodManager` 手动控制。

### 决策 3：私钥存储升级为混合方案

| 层 | 存储位置 | 内容 |
|----|----------|------|
| 私钥文件 | `filesDir/keys/*.pem` | 加密后的私钥文件 |
| 加密密钥 | Android Keystore | AES-256 密钥，用于加密私钥文件 |

```kotlin
// KeyStoreManager.kt
object KeyStoreManager {
    private const val KEY_ALIAS = "ssh_key_encryption_key"

    fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build())
                }.generateKey()
        }
        return (ks.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
}
```

> **威胁边界声明**：此方案防御的是「其他普通应用读取私钥文件」的场景（Android 沙箱 + Keystore 联合保护）。
> 它**不防御**以下场景：设备被 root 后的 root 进程读取、adb backup 迁移（可通过 `android:allowBackup="false"` 缓解）、调试器附加到本进程。
> 对于个人自用、非 root 设备的使用场景，此方案安全性足够；若需更高保护，可在 `KeyGenParameterSpec` 中追加 `setUserAuthenticationRequired(true)` 要求生物识别解锁。

---

## 输入链路设计（核心）

### 双链路分离原则

```
物理键盘按键
    │
    ├─── 功能键 / 快捷键 (Ctrl+C, Tab, 方向键, F1-F12...)
    │         │
    │         └──► onKeyDown() → 直接转换为 ANSI 转义序列 → SSH Channel
    │
    └─── 字符输入 (字母/数字/符号 + 中文 IME)
              │
              └──► InputConnection 回调 → TerminalInputBuffer → SSH Channel
```

**关键约束**：两条链路必须互斥，不能重复发送。
外接键盘场景下，很多 IME 会同时发 `KeyEvent` 和 `InputConnection` 回调。

### KeyEvent 路由规则表（验收标准）

| 事件类型 | 条件 | 处理链路 | 行为 |
|----------|------|----------|------|
| 可打印字符（字母/数字/符号） | 无 Ctrl/Alt 修饰，无组合状态 | InputConnection | `onKeyDown` 返回 `false`，由系统分发给 `commitText()` |
| 可打印字符 | 有 Ctrl 或 Alt 修饰，`isComposing == false` | `onKeyDown` → `KeyMapper.ctrlSequence` | 转义为对应 ASCII 控制字节（A-Z → `0x01-0x1A`、`\` → `0x1C`、`]` → `0x1D`，含历史的 C/D/Z/`[`/Esc），**吞掉**不传 InputConnection。覆盖 tmux 前缀 Ctrl+B、bash readline Ctrl+A/E/F/K/L/N/P/R/U/W、less Ctrl+G/Q、telnet escape Ctrl+]、SIGQUIT Ctrl+\ 等业内标准快捷键。Ctrl+V 故意不映射，仍走"可打印字符"路径让 IME 输出字面 "V" |
| 可打印字符 + Ctrl/Alt | **`isComposing == true`（IME 组合中）** | **`onKeyDown` → `KeyMapper.ctrlSequence`** | **写入对应 ASCII 控制字节并调用 `finishComposingText()` 强制结束拼音会话。** 这条规则是为了让 tmux 前缀 `Ctrl+B D`、bash 快捷键等"修饰键+字母"复合命令在中文 IME 模式下仍然生效——物理 Ctrl/Alt 修饰键是"硬键盘信号"，不属于拼音字母。无修饰的单纯键（ESC 单按、Shift+Tab、方向键、F1-F12 等）依旧走 IME 路径，由 IME 决定如何处理（取消候选、删除字符等） |
| `KEYCODE_DEL`（退格） | `isComposing == true` | InputConnection | `onKeyDown` 返回 `false`，由 IME 删除拼音字母 |
| `KEYCODE_DEL`（退格） | `isComposing == false` | `onKeyDown` | 发送 `0x7F`（DEL）到 SSH，**吞掉** |
| `KEYCODE_ENTER` | `isComposing == true` | InputConnection | `onKeyDown` 返回 `false`，由 IME 确认上屏 |
| `KEYCODE_ENTER` | `isComposing == false` | `onKeyDown` | 发送 `\r` 到 SSH，**吞掉** |
| Tab、方向键、F1-F12 | 任意 | `onKeyDown` | 转义为对应 ANSI 序列，**吞掉** |
| **Ctrl+Shift+V** | 任意 | `onKeyDown` + `dispatchKeyEventPreIme` | **最高优先级**——Paste 判定先于 Ctrl+V 字节路径，详见 `KeyMapper.isPasteShortcut`。`onKeyDown` 路径与 PreIme 路径都会把系统剪贴板 UTF-8 写入 SSH |
| **Ctrl+Space / Shift+Space** | **任意** | **严格吞掉** | **这是 IME 语言切换快捷键，属于 IME 内部事务，绝对不能透传到远端 SSH Session** |
| **IME 内部语言切换快捷键 (KEYCODE_LANGUAGE_SWITCH 等)** | **任意** | **严格吞掉** | **如上，属 IME 内部事务** |
| IME `commitText()` 回调 | 任意 | InputConnection | 将文本 UTF-8 编码发送到 SSH，不经过 `onKeyDown` |

> **可测试标准**：每个表格行都应有一个对应的 unit test 或手工测试用例，在 Sprint 1 结束前全部过绿。

### ——————————————————————————————
### ✅ 核心 Bug 复现路径 [P0]: SSH 内中文打字出现所有典型 Bug

**用户挑所提供的复现路径（已验证）**：

```
Termux 本地终端
    ├── 英文模式打字    →  正常 ✅
    ├── 切换中英文         →  正常 ✅
    └── 中文模式打字    →  正常 ✅

Termux 内 SSH 到远端服务器后
    ├── 英文模式打字    →  正常 ✅
    ├── 切换到中文模式   →  切换动作本身正常 ✅
    └── 切换后，用中文打字 →  💥 发生了我们要解决的所有问题
```

**中文打字时出现的典型病状**（即该项目 MVP 目标要全部消灭的）：
- 拼音字母漏入终端（`n`, `i`, `h`, `a`, `o` 一个个出现在 Shell 提示符里）
- 退格键既删了拼音字母，又向远端发送了 DEL
- `Ctrl+C` 取消输入法组合后，远端进程也收到了 SIGINT
- 指定汉字上屏后，远端收到了汉字 + 一些拼音残留字符

**正确的根因**：
`Termux TerminalView` 在 SSH 嵌套场景下，未能正确实现双链路互斥。
中文输入时的拼音阶段产生的 `KeyEvent`（字母键）同时走了两条路：

```
外接键盘按下 "n"
    ├── 路径1: onKeyDown() 视之为普通字母 → 发送字母 "n" 到 SSH Session (BUG!)
    └── 路径2: IME 收到 KeyEvent → 更新拼音组合状态 (OK)

结果：远端收到了 "n"（拼音字母），同时 IME 在屏幕上显示候选词
用户选定“你”字 → commitText("你") 发就了 → 远端收到的是 "n" + "你"
```

**这正是我们这个项目的核心差异化价值**：Termux/Termius 在 SSH 场景下都没有解决这个问题。
我们的 `TerminalInputConnection` 通过 `isComposing` 状态 + `onKeyDown` 返回 `false` 的双链路互斥设计，将彻底封平这个病根。

**验收标准**：见 `TerminalInputConnection` 方法验收规格表和测试计划中的手工 E2E 路径。

### 去重策略

```kotlin
// TerminalView.kt
override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    // Sprint 3.6 补充：如果 session 为 null，必须返回 true 消费按键，
    // 否则 Termux 的 handleKeyCode 会抛出 NPE。
    if (session == null) return true
    // 如果 IME 正在组合（中文候选词选择中），物理键走特殊处理
    if (mTerminalInputConnection.isComposing()) {
        return handleKeyDuringComposing(keyCode, event)
    }

    // 可打印字符：KeyEvent.isPrintingKey() == true 时，
    // 交给 InputConnection 处理，onKeyDown 返回 false 让系统继续分发
    if (event.isPrintingKey() && !event.isCtrlPressed && !event.isAltPressed) {
        return false  // 交给 InputConnection.commitText() 处理
    }

    // 控制字符、功能键：直接转义并发送
    val sequence = KeyMapper.toAnsiSequence(keyCode, event) ?: return false
    session.write(sequence)
    return true
}
```

### TerminalInputConnection 完整实现

> **Sprint 3.6 补充说明**：在断线重连或更换 `TerminalEndpoint` 时，必须调用 `InputMethodManager.restartInput(view)`。因为 IME（如 Gboard）会缓存旧的 `InputConnection` 实例，如果不通知 IMM 丢弃缓存，重连后的输入事件仍会发往旧的（已断开的）连接，导致输入死锁。

```kotlin
class TerminalInputConnection(
    private val view: TerminalView,
    private val session: SshSession
) : BaseInputConnection(view, true) {

    private val composingBuffer = StringBuilder()
    @Volatile private var isComposing = false

    // ⚠️ 关键：快照字段，解决跨方法调用的状态漂移问题
    // 场景：Gboard 会先调 setComposingText("") 将 isComposing 置 false，
    //       再在同一事务内调 deleteSurroundingText(1,0)。
    //       此时若直接读 isComposing 会误判为"非组合" → 发 DEL 到远端（BUG）。
    // 规则：lastComposingSnapshot 在每次 setComposingText/commitText/
    //       finishComposingText 调用前保存上一时刻的 isComposing 值，
    //       deleteSurroundingText 使用快照而非当前值做判断。
    @Volatile private var lastComposingSnapshot = false

    fun isComposing(): Boolean = isComposing

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        lastComposingSnapshot = isComposing          // 先存快照
        composingBuffer.clear()
        composingBuffer.append(text)
        isComposing = text.isNotEmpty()
        view.showComposingHint(text.toString())
        return super.setComposingText(text, newCursorPosition)
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        lastComposingSnapshot = isComposing          // 先存快照
        composingBuffer.clear()
        isComposing = false
        view.hideComposingHint()
        if (text.isNotEmpty()) {
            session.write(text.toString().toByteArray(Charsets.UTF_8))
        }
        return super.commitText(text, newCursorPosition)
    }

    override fun finishComposingText(): Boolean {
        lastComposingSnapshot = isComposing          // 先存快照
        composingBuffer.clear()
        isComposing = false
        view.hideComposingHint()
        return super.finishComposingText()
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        return super.setComposingRegion(start, end)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        return super.setSelection(start, end)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        // ⚠️ 最高危险区：必须使用 lastComposingSnapshot 而非 isComposing
        if (lastComposingSnapshot) {
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
        repeat(beforeLength) { session.write(byteArrayOf(0x7F)) }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) return true
        if (isComposing) return true
        val sequence = KeyMapper.toAnsiSequence(event.keyCode, event) ?: return false
        session.write(sequence)
        return true
    }
}
```

### TerminalInputConnection 方法验收规格

> 以下每一项均为可独立测试的验收条件。Sprint 1 结束前，所有 ✅ 项必须通过 mock session 验证。

---

#### `setComposingText(text, newCursorPosition)`

**触发时机**：IME 拼音组合阶段，每次候选词变化时调用（可能连续多次）

| # | 验收条件 |
|---|---------|
| ✅ | 调用后 `isComposing()` 返回 `true`（当 `text` 非空时） |
| ✅ | 拼音 hint 浮层显示当前 `text` 内容（如 `"ni"`） |
| ✅ | **不向 SSH 发送任何字节** |
| ✅ | `text` 为空字符串时，等价于 `finishComposingText()`，`isComposing` 变为 `false` |
| ✅ | 连续调用（`"n"` → `"ni"` → `"nin"`）不产生累积错误，每次覆盖前一次 |

**禁止行为**：调用此方法时向 SSH 写入任何数据

---

#### `commitText(text, newCursorPosition)`

**触发时机**：用户从候选词列表选定汉字，或直接敲下英文字符（非组合状态）

| # | 验收条件 |
|---|---------|
| ✅ | 将 `text` 以 **UTF-8 编码**完整写入 SSH channel |
| ✅ | 调用后 `isComposing()` 返回 `false` |
| ✅ | 拼音 hint 浮层隐藏 |
| ✅ | `text` 为空字符串时为 no-op，不写入任何字节 |
| ✅ | 中文字符（如 `"你好"`）的多字节序列完整发送，不截断 |
| ✅ | 在非组合状态下直接 `commitText("a")` 也能正确发送（英文快速输入路径） |

**边界条件**：`newCursorPosition` 参数在终端语境下无意义，忽略即可，但不能因此抛异常

---

#### `finishComposingText()`

**触发时机**：IME 关闭、输入法切换、用户按 Esc 取消候选词

| # | 验收条件 |
|---|---------|
| ✅ | 调用后 `isComposing()` 返回 `false` |
| ✅ | 拼音 hint 浮层隐藏 |
| ✅ | composingBuffer 清空 |
| ✅ | **不向 SSH 发送任何字节**（取消 ≠ 提交） |
| ✅ | **幂等性**：在已经是 `isComposing == false` 的状态下再次调用，不崩溃、不副作用 |

**最常见的翻车点**：把"取消"误判为"提交空字符串"，导致向 SSH 发送了多余的换行或空格

---

#### `deleteSurroundingText(beforeLength, afterLength)`

**触发时机**：IME 内部的删除操作（退格键经过 IME 路由后到达此处）

| # | 状态 | 验收条件 |
|---|------|---------|
| ✅ | `isComposing == true` | 委托 `super.deleteSurroundingText()`，让 IME 自行管理拼音缓冲区，**不向 SSH 发送任何字节** |
| ✅ | `isComposing == false` | 向 SSH 发送 `beforeLength` 个 `0x7F`（DEL 字符），**不发送其他内容** |
| ✅ | `isComposing == false` | `afterLength` 参数**忽略**（终端没有"光标后删除"的 IME 语义） |
| ✅ | 任意状态 | `beforeLength == 0 && afterLength == 0` 时为 no-op，不崩溃 |
| ✅ | 任意状态 | `beforeLength > 1` 时发送正确数量的 DEL 字节（批量退格） |

**最常见的翻车点**：`isComposing == true` 时也向 SSH 发了 DEL，导致远端删了正在编辑的内容

---

#### `setComposingRegion(start, end)`

**触发时机**：某些 IME（如 Gboard）在开始组合前调用，标记哪段文本处于"组合中"状态

| # | 验收条件 |
|---|---------|
| ✅ | 调用 `super.setComposingRegion(start, end)` 维持 IME 内部状态一致性 |
| ✅ | **不向 SSH 发送任何字节** |
| ✅ | 不因此更改 `isComposing` 标志（`isComposing` 只由 `setComposingText` 驱动） |
| ✅ | 参数 `start == end`（空区间）时不崩溃 |

**设计说明**：终端没有真实的"文本缓冲区位置"概念，此方法仅用于维持 IME 状态机稳定，防止 IME 因收不到预期回调而进入异常状态

---

#### `setSelection(start, end)`

**触发时机**：IME 更新内部光标位置时调用（通常在 `commitText` 或 `setComposingText` 之后）

| # | 验收条件 |
|---|---------|
| ✅ | 调用 `super.setSelection(start, end)` 维持 IME 内部状态一致性 |
| ✅ | **不向 SSH 发送任何字节** |
| ✅ | **不移动终端光标**（终端光标由远端 SSH server 控制，不受此影响） |
| ✅ | 任意 `start`/`end` 值（包括 `0,0`）都不抛异常 |

**设计说明**：若不实现此方法，部分 IME 会因内部 selection 状态紊乱而停止发送 `setComposingText`，表现为"中文输入突然失效"

---

#### 附加项：`sendKeyEvent(KeyEvent)`

**触发时机**：少数 IME 通过 `InputConnection.sendKeyEvent()` 而非 `onKeyDown()` 分发物理键事件

| # | 验收条件 |
|---|---------|
| ✅ | `ACTION_DOWN` 事件：调用与 `onKeyDown()` 相同的 `KeyMapper` 逻辑，发送对应 ANSI 序列 |
| ✅ | `ACTION_UP` 事件：忽略（终端不需要 key-up 事件） |
| ✅ | 与 `onKeyDown()` 的去重保证：同一物理按键不会同时触发两条链路 |

---

#### 完整验收 Checklist（Sprint 1 结束前）

```
输入链路 - TerminalInputConnection
  [ ] setComposingText：拼音阶段只显示 hint，不发 SSH
  [ ] setComposingText：连续调用无累积错误
  [ ] setComposingText：空字符串等价于 finishComposingText
  [ ] commitText：UTF-8 完整发送，包括多字节中文
  [ ] commitText：非组合状态的英文直接输入路径
  [ ] commitText：空文本 no-op
  [ ] finishComposingText：不发 SSH，幂等
  [ ] deleteSurroundingText：组合中不发 SSH
  [ ] deleteSurroundingText：非组合时发正确数量 0x7F
  [ ] deleteSurroundingText：afterLength 忽略
  [ ] setComposingRegion：只调 super，不发 SSH
  [ ] setSelection：只调 super，不移动终端光标
  [ ] sendKeyEvent：ACTION_DOWN 路由到 KeyMapper
  [ ] 全程无"重复字符"现象（onKeyDown + InputConnection 双发）
```

### 输入状态机

```
          ┌─────────────┐
          │   Idle      │  ← 没有 IME 活动
          └──────┬──────┘
                 │ setComposingText("n")
                 ▼
          ┌─────────────┐
          │  Composing  │  ← 拼音阶段，本地显示 hint
          └──────┬──────┘
       ┌─────────┴─────────┐
       │ commitText("你")   │ finishComposingText()
       ▼                   ▼
  ┌─────────┐         ┌─────────┐
  │ Commit  │         │ Cancel  │
  │ 发到远端 │         │ 丢弃    │
  └────┬────┘         └────┬────┘
       │                   │
       └────────┬──────────┘
                ▼
          ┌─────────────┐
          │   Idle      │
          └─────────────┘
```

---

## 终端数据流

```
SSH Server
    │
    │  (TCP Stream, SSH_MSG_CHANNEL_DATA)
    ▼
SshSession.read()  [IO Coroutine]
    │
    ▼
TerminalEmulator.process(bytes)  ← 更新内部 ScreenBuffer
    │  (发送 RefreshSignal)
    ▼
[Kotlin Channel (CONFLATED)]
    │  (节流、防爆栈)
    ▼
[UI 线程消费协程]
    │  收到 Signal 后调用
    ▼
TerminalView.postInvalidateOnAnimation()
    │  利用系统 VSync 统一重绘
    ▼
屏幕显示
```

**渲染限制 (Rendering Constraint)**：
1. IO 线程严格禁止直接调用 `invalidate()`
2. IO 线程只能发送 `RefreshSignal`
3. `RefreshSignal` 必须通过 `Channel(CONFLATED)` 传递
4. UI 线程收到 Signal 后调用 `postInvalidateOnAnimation()`
5. 最终渲染频率由 Android 系统的 VSync 决定

用户输入
    │
    ├── onKeyDown() → KeyMapper → session.write(escape_sequence)
    │
    └── InputConnection.commitText() → session.write(utf8_bytes)
```

---

## 模块划分与边界

```
:terminal-emulator/          ← Termux 库（直接引入，基本不改）
    TerminalEmulator.kt
    TerminalSession.kt
    ScreenBuffer.kt

:app/
├── terminal/
│   ├── TerminalView.kt            ★ 继承(Extend) Termux 的 TerminalView，保留手势，重写输入
│   ├── TerminalInputConnection.kt ★ (核心) 剥离的 IME 处理类
│   ├── KeyMapper.kt               物理按键 -> ANSI 序列映射
│   ├── TerminalViewModel.kt       生命周期托管 (接受后台被杀的设定)
│   └── ComposingBar.kt            ★ 固定在屏幕底部的中文拼音候选 UI
│
├── ssh/
│   ├── SshClient.kt
│   ├── SshSession.kt              双向数据流（Coroutine + Channel），含 PTY & SIGWINCH
│   └── auth/
│       ├── PasswordAuthProvider.kt
│       └── PublicKeyAuthProvider.kt
│
├── data/
│   ├── prefs/
│   │   └── AppPreferences.kt      ★ (降级) SharedPreferences 单主机存储
│   └── crypto/
│       └── KeyStoreManager.kt     Android Keystore 封装
│
└── theme/
    ├── Color.kt                   Warp 风格深色配色
    ├── Type.kt                    JetBrainsMono 字体注册
    └── Theme.kt
```

---

## SSHJ 在 Android 上的正确配置

```kotlin
// SshClient.kt
class SshClient {
    private val ssh = SSHClient().apply {
        Security.addProvider(BouncyCastleProvider())
    }

    suspend fun connect(host: String, port: Int): Result<SshSession> =
        runCatching {
            withContext(Dispatchers.IO) {
                ssh.connect(host, port)
                SshSession(ssh)
            }
        }
}
```

---

## 开发顺序（按优先级）

### Sprint 0 — 基础设施准备（1天）
1. 建立 Android Studio 工程
2. 引入 `terminal-emulator` 作为 Library module 或 JAR
3. 初始化 `TerminalView`（继承自 Termux 原生类，保留手势和选中能力）
4. 配置 JetBrainsMono 字体
5. **[GitHub 初始化]**：在服务器上使用 `git init` 初始化工程，并通过 `gh repo create` (或直接添加 SSH remote) 在 GitHub 创建远端仓库并推送第一次提交。后续每个 Sprint 结束必须 push 代码。

### Sprint 1 — 彻底解决 IME 核心问题（重点，1周）

1. 实现 `TerminalView`：
   - `onCheckIsTextEditor()` 返回 true
   - `onCreateInputConnection()` 返回正确 EditorInfo
3. 实现 `TerminalInputConnection`（完整 5 个方法）
4. 实现 `KeyMapper`（物理键 → ANSI 转义表）
5. 实现 `MockEchoSession`：将收到的字节原样回显到终端，替代真实 SSH
6. **编写 Robolectric 单元测试**：针对 `TerminalInputConnectionTest`，覆盖所有的 IME 回调和输入状态机边界。
7. 在 mock session 和自动化测试下执行 KeyEvent 路由规则表的全部用例

9. 极简配置页 UI（Compose）：仅配置单个主机、端口、用户名、密码/私钥
10. `AppPreferences`：基于 SharedPreferences 存储上述配置
11. 私钥导入 + KeyStoreManager 加密
---

## 验证计划

| 测试项 | 验证方法 | Sprint |
|--------|----------|--------|
| 中文 IME 输入（候选词上屏） | 外接键盘 + 搜狗/Gboard | 1 |
| 退格键正确行为 | 组合中退格 vs 非组合退格 | 1 |
| Ctrl+C / Ctrl+D / Tab | 功能键不重复发送 | 1 |
| SSH 密码认证 | 公网 Linux 服务器 | 2 |
| SSH Ed25519 私钥认证 | — | 2 |
| OpenSSH 版本兼容性 | 7.x / 8.x / 9.x | 2 |
| xterm-256color 显示 | 运行 htop / vim | 2 |
| 主机保存/编辑 | — | 3 |
| 私钥导入加密 | — | 3 |
| 平板横屏布局 | — | 3 |

---

## 参考资源

- [Termux terminal-emulator 源码](https://github.com/termux/termux-app/tree/master/terminal-emulator) — 直接引入的终端核心
- [Termux TerminalView.java](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java) — 研究其 InputConnection 的不足之处
- [SSHJ GitHub](https://github.com/hierynomus/sshj) — SSH 库
- [Android InputConnection 文档](https://developer.android.com/reference/android/view/inputmethod/InputConnection) — IME API 完整说明
- [JetBrainsMono Nerd Font](https://github.com/ryanoasis/nerd-fonts/releases) — 内置字体
