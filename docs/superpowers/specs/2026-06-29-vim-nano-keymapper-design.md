# Vim/Nano KeyMapper: 数据驱动重构 + 缺失键位补全

| 维度       | 值                                                  |
|------------|----------------------------------------------------|
| 日期       | 2026-06-29                                          |
| 范围       | `terminal/KeyMapper.kt` + 同目录新文件 + 同 module 测试 |
| 目标用户   | 长期在 Android 平板外接键盘上用 vim 和 nano 工作的工程师 |
| 上一份设计 | `implementation_plan.md` §"输入链路设计"             |
| 跟其他 sprint 的关系 | 独立 PR;与 Sprint 2.5 (Modules 11-14) 并行,无跨模块耦合 |

---

## 1. 目标 & 范围

### 1.1 目标

- **补缺**:把明显错误的几个键位接上 — `KEYCODE_ESCAPE`(无 Ctrl)、`Shift+Tab`、`Ctrl+^`、`Ctrl+_`、`Ctrl+@`、`Ctrl+?`、`KEYCODE_INSERT`。
- **重构**:把 `KeyMapper` 的路由表从手写 `when` 块改成"数据驱动的 list-of-entries"。
- **文档化**:给每个 entry 加 vim(normal/insert/visual/command) + nano + bash readline 的预期行为,以结构化字段形式固化在代码里,而不是散落在 markdown。
- **零回归**:`KeyMapper.resolve` / `toAnsiSequence` 对外契约不变;`KeyEventRoutingTest` 现有 31 个 case 一字不改通过。

### 1.2 显式不做

- 不修改 `Ctrl+V` 故意不映射的现状 — IME 兼容性比 nano 的 next-page 优先级高。
- 不引入 `Ctrl+0..9` 的映射 — 三个目标程序都没有 vanilla binding。
- 不重写 `TerminalInputConnection` / `TerminalView.onKeyDown` 的双链路互斥逻辑。
- 不动 `terminal-emulator` / `terminal-view` 内部。
- 不加新的第三方依赖。
- 不加 dump / generator — 这份设计只把文档"贴近代码",不主动产出 markdown。

---

## 2. 数据结构

新建 `terminal/KeyMapDoc.kt`,放两个 data class:

```kotlin
/**
 * 单个键在某个程序中的预期行为。
 *
 * `mode` 是程序内的"模式"概念。我们支持以下 mode 字符串:
 *  - "normal"  : vim normal 模式
 *  - "insert"  : vim insert 模式
 *  - "visual"  : vim visual / visual-block / visual-line 模式
 *  - "command" : vim command-line 模式(:, /, ?)
 *  - "any"     : 模式无关(nano、bash、或者真无模式区分)
 *
 * `effect` 是简短人类可读描述,**只描述 vanilla 默认绑定**,不列举用户自定义 leader 映射。
 */
data class ProgramUsage(
    val mode: String,
    val effect: String,
)

/**
 * 路由表的一行。
 *
 * 运行时只看 `match` 和 `verdict`;`description` / `vim` / `nano` / `bash` / `note` 是
 * 给人类读者看的结构化文档,以后可以序列化到 markdown / yaml 而不需反射。
 *
 * - `match`   : "这个 KeyEvent 是不是命中本条?" — 纯谓词。
 * - `verdict` : "命中之后做什么?" — 产出一个 KeyResolution。必须是 lambda 而非常量,
 *              因为 Alt+letter 的 verdict 依赖 `event.unicodeChar`。
 */
data class KeyMapEntry(
    val description: String,
    val match: (KeyEvent) -> Boolean,
    val verdict: (KeyEvent) -> KeyResolution,
    val vim: List<ProgramUsage> = emptyList(),
    val nano: List<ProgramUsage> = emptyList(),
    val bash: List<ProgramUsage> = emptyList(),
    val note: String? = null,
)
```

### 2.1 为什么需要这个设计

| 现在的痛点                                                    | 修后                                                       |
|--------------------------------------------------------------|------------------------------------------------------------|
| 加一个新键要在 `ctrlSequence`、`when (keyCode)`、kdoc 三处改 | 在 `KEY_MAP` 加一行 entry                                  |
| `Ctrl+V` 故意不映射的 rationale 散在两个 kdoc                | 写在 entry 的 `note`,集中可见                              |
| 看 vim 预期行为只能翻 `implementation_plan.md`                | `entry.vim` 直接是结构化答案                                |
| 路由表"完整性"无测试保护                                      | 元测试 `test_keyMapTable_isWellFormed` 锁定不变契约         |
| 文档是 markdown,代码改动后容易漂移                            | 文档是 entry 字段,跟代码同 commit 维护                      |

### 2.2 不破坏的边界

`KeyResolution`、`KeyMapper.resolve(keyCode, event)`、`KeyMapper.toAnsiSequence(keyCode, event)` 三个对外 API 形状不变,只是 `resolve` 内部从手写 `when` 改成遍历 `KEY_MAP`。这意味着:

- `KeyEventRoutingTest` 的 31 个现有断言不变
- `TerminalInputConnection.sendKeyEvent` 调 `toAnsiSequence` 的地方不变
- `TerminalView.dispatchKeyEventPreIme` 和 `onKeyDown` 调 `resolve` 的地方不变

---

## 3. 路由顺序 & 新增键位

### 3.1 路由顺序(从最优先到默认)

`KEY_MAP` 是一个 `List<KeyMapEntry>`,**首匹配胜出**。顺序是契约,key 顺序在表上方有一段 kdoc 写明每一条规则的优先级理由。

```
1.  Ctrl+Shift+V  → Paste                              [必须在 Ctrl+V 字节路径前赢]
2.  IME 切语言    → Swallow                            [Ctrl+Space / Shift+Space / KEYCODE_LANGUAGE_SWITCH]
3.  Ctrl+letter   → Send(0x01-0x1A)                    [A-Z 除 V,加 Ctrl+[、Ctrl+]、Ctrl+\]
4.  Alt+letter    → Send(ESC + 字符)                   [仅当 event.unicodeChar > 0]
5.  KEYCODE_ESCAPE(无 Ctrl) → Send(0x1B)               [★ 新增]
6.  KEYCODE_TAB + Shift     → Send(ESC[Z)              [★ 新增,Back-Tab;Ctrl+Shift+Tab 也命中]
7.  KEYCODE_TAB(无 Shift)   → Send(\t)                 [bare Tab 和 Ctrl+Tab 都命中,Ctrl+Tab 与 Ctrl+I 字节同]
8.  KEYCODE_ENTER           → Send(\r)                 [Ctrl+Enter 与 Ctrl+M 字节同]
9.  KEYCODE_DEL             → Send(0x7F)               [Backspace 键]
10. KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT → ANSI 光标序列
11. KEYCODE_MOVE_HOME/END              → ESC[H / ESC[F
12. KEYCODE_PAGE_UP/DOWN               → ESC[5~ / ESC[6~
13. KEYCODE_FORWARD_DEL                → ESC[3~
14. KEYCODE_INSERT                     → ESC[2~         [★ 新增]
15. KEYCODE_F1..F12                    → 标准 ANSI
16. 默认 → Ignore
```

### 3.2 新增键位详表

| 键                          | 字节             | 现状     | 改后             | vim                                      | nano               | bash              |
|-----------------------------|------------------|----------|------------------|------------------------------------------|--------------------|-------------------|
| `KEYCODE_ESCAPE`(无 Ctrl)   | 0x1B             | `Ignore` | `Send(0x1B)`     | insert→normal / visual→normal / 取消操作 | 取消当前操作       | 取消未完成命令    |
| `KEYCODE_TAB` + Shift       | `ESC[Z`          | `Ignore` | `Send(ESC[Z)`    | 部分配置下 `gT`(反向 tab)                | 撤销缩进           | 反向补全          |
| `KEYCODE_INSERT`            | `ESC[2~`         | `Ignore` | `Send(ESC[2~)`   | normal: 切换 insert / replace 模式       | 无 native binding  | 无 native binding |
| `Ctrl+^` (KEYCODE_CIRCUMFLEX) | 0x1E (RS)      | `Ignore` | `Send(0x1E)`     | normal: 切换 alternate file              | 无                 | 无                |
| `Ctrl+_` (KEYCODE_UNDERSCORE) | 0x1F (US)      | `Ignore` | `Send(0x1F)`     | normal: undo(`compatible` 模式)          | 跳到指定行号       | 无                |
| `Ctrl+@` (KEYCODE_AT)         | 0x00 (NUL)     | `Ignore` | `Send(0x00)`     | 无 native binding(常用 `<C-@>` 映射)    | Set mark           | set-mark          |
| `Ctrl+?` (KEYCODE_SLASH)      | 0x7F (DEL)     | `Ignore` | `Send(0x7F)`     | normal: 同 DEL 行为                      | 同 Backspace       | 同 Backspace      |

### 3.3 一个微妙点:`Ctrl+Tab` / `Ctrl+Enter`

当前代码在 `when (keyCode)` 块里把 `KEYCODE_TAB` 和 `KEYCODE_ENTER` 写成**无条件**发送 `\t` / `\r` — 也就是说 Ctrl+Tab 也会变成 `\t`(同 Ctrl+I = 0x09)、Ctrl+Enter 也会变成 `\r`(同 Ctrl+M = 0x0D)。

数据驱动表里**保留这个行为**:

- `KEYCODE_TAB` 的 match 是 `{ it.keyCode == KEYCODE_TAB && !isShiftPressed }`,**不检查 Ctrl**。这样 bare Tab 走它,Ctrl+Tab 也走它(Ctrl+I 字节同 0x09)。
- `KEYCODE_ENTER` 同理(match = `keyCode == KEYCODE_ENTER`,不检查 Ctrl)。
- 在 entry 的 `note` 写明 "Ctrl+Tab / Ctrl+Enter 也会命中此条;这与 Ctrl+I / Ctrl+M 产生相同字节,行为一致"。
- `Ctrl+I = 0x09` 和 `Ctrl+M = 0x0D` 两条 entry 的 match 是 `isCtrlPressed && keyCode == KEYCODE_I/M`,放在 `KEYCODE_TAB/ENTER` 之前,自然先匹配。两条 entry 的 verdict 都是 0x09 / 0x0D,文档可共用。
- 但 `KEYCODE_TAB + Shift` 必须在 `KEYCODE_TAB` **之前**(因为 Shift+Tab 是更特定的 match,先命中它得到 ESC[Z,而不是 fall through 到 \t)。

### 3.4 ESC 与 IME 组合的交互

新增 `KEYCODE_ESCAPE`(无 Ctrl) 不能破坏现有的"IME 组合中按 ESC 让 IME 取消组合"路径。这条路径在 `TerminalView.onKeyDown` 的早返回分支里:

```kotlin
if (connection?.isComposing() == true) {
    val verdict = KeyMapper.resolve(keyCode, event)
    if (verdict is KeyResolution.Swallow) return true
    if (verdict is KeyResolution.Paste) { ... }
    if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_ENTER) return false
    return false  // ← 组合中的 ESC 会走到这里
}
```

组合中的 ESC 命中 `KeyMapper.resolve` 后会得到 `KeyResolution.Send(0x1B)`,但早返回分支在判断 `verdict` 之前就 `return false` 把控制权让给 IME。所以 IME 继续拥有组合状态,用户按 ESC 取消拼音组合的体验不被破坏。

新测试 `test_escape_whileComposing_isPassedToIme` 把这点钉死。

---

## 4. 文档化策略 + 测试

### 4.1 文档位置

**`KeyMapper` 类顶部 kdoc** 放一段"读这张表"的指南,告诉读者:

- 表在哪里(`KEY_MAP` 私有 val)
- 路由顺序的关键
- vim 四种 mode 的定义
- 哪些键没 entry(就是 `Ignore`)

**每个 entry** 的 `description` 字段是自包含的 — 包含"输入 → 字节"的形式(如 `"Ctrl+A → 0x01 (SOH) · bash readline beginning-of-line"`),这样即使不读 vim/nano/bash 三个子表也知道这条规则在做什么。

**`note` 字段** 只在以下情况写:

- 有意的不对称(如 `Ctrl+V` 不映射的 rationale)
- 跨程序冲突(如 `Ctrl+I` 和 `KEYCODE_TAB` 都产生 0x09)
- 副作用警告(如 `Alt+letter` 的 ESC 前缀会让某些程序进入 Meta 模式)

### 4.2 新增测试

全部进 `KeyEventRoutingTest`,沿用现有 Robolectric 风格,加 9 个 case:

| 测试名                                                | 验证                                                |
|------------------------------------------------------|----------------------------------------------------|
| `test_escapeAlone_writesEscByte`                     | 物理 ESC 键 → 0x1B                                  |
| `test_ctrlEscape_writesEscByte`                      | Ctrl+ESC → 0x1B(与 Ctrl+[ 同字节)                  |
| `test_escape_whileComposing_isPassedToIme`           | IME 拼音组合中按 ESC → 返回 false,不写 0x1B        |
| `test_shiftTab_writesBackTabSequence`                | Shift+Tab → `ESC[Z`                                |
| `test_insertKey_writesInsertSequence`                | KEYCODE_INSERT → `ESC[2~`                          |
| `test_ctrlCaret_writesRsByte`                        | Ctrl+^ → 0x1E                                      |
| `test_ctrlUnderscore_writesUsByte`                   | Ctrl+_ → 0x1F                                      |
| `test_ctrlAt_writesNulByte`                          | Ctrl+@ → 0x00                                      |
| `test_ctrlSlash_writesDelByte`                       | Ctrl+? → 0x7F                                      |
| `test_keyMapTable_isWellFormed`                      | **元测试**:遍历 `KEY_MAP`,断言:每条 entry 的 match/verdict lambda 能正常返回 + 31 个旧 case 的 key event 至少有一条 entry 会 match |

元测试用 `internal` 的 `entriesForTest(): List<KeyMapEntry>` 访问 `KEY_MAP`,不暴露给生产代码。

### 4.3 不动的测试

`KeyEventRoutingTest` 的 31 个现有 case 一个字不改 — 因为 `KeyMapper.resolve` 的对外契约不变。`TerminalInputConnectionTest`、`AltBufferScrollCrashGuardTest` 等也不动。

---

## 5. 迁移路径 + 风险

### 5.1 改动文件清单

| 文件                                                          | 改动                                                                                  | 风险 |
|--------------------------------------------------------------|---------------------------------------------------------------------------------------|------|
| `terminal/KeyMapDoc.kt`(新)                                  | 新建,放 `ProgramUsage` + `KeyMapEntry` data class                                     | 零   |
| `terminal/KeyMapper.kt`                                      | 加 `KEY_MAP` 私有 val;`resolve()` 改成遍历;`toAnsiSequence` / `isPasteShortcut` / `isImeLanguageSwitch` 保留 | 中   |
| `app/src/test/.../KeyEventRoutingTest.kt`                    | 加 10 个新 case                                                                       | 低   |
| `app/src/main/.../MainActivity.kt` 等其他源文件               | 不动                                                                                  | 零   |

### 5.2 迁移步骤(可在 1 个 PR 内完成)

1. 新建 `KeyMapDoc.kt`,放 data class。
2. 在 `KeyMapper` 旁边加 `KEY_MAP` 表(从原 `resolve` / `ctrlSequence` 中搬运,**先不替换 resolve body**)。
3. 加元测试 `test_keyMapTable_isWellFormed`,断言 `KEY_MAP` 不为空 + 现有 31 个测试的 key event 至少有一条 entry 会 match(防 entry 顺序写错)。
4. 跑测试:必须**全绿**(旧的 `resolve` 还在用,这一步只是 sanity check)。
5. 把 `resolve()` body 改成遍历 `KEY_MAP.firstOrNull { it.match(event) }?.verdict(event) ?: Ignore`。
6. 删掉原来的 `ctrlSequence` / `isPasteShortcut` / `isImeLanguageSwitch` 直接调用,改为 entry 里的 lambda。
7. 加 7 个新键的 entry。
8. 跑全部测试,必须全绿。
9. 提交:1 个 commit,`refactor(terminal): data-driven KeyMapper + add missing vim/nano bindings`。

### 5.3 已知风险与对策

| 风险                                                                                          | 等级 | 对策                                                                                          |
|-----------------------------------------------------------------------------------------------|------|-----------------------------------------------------------------------------------------------|
| 重构时漏掉某个现有路由(例如 `Ctrl+G` 之前没想到)                                                | 中   | 步骤 3 的元测试,断言 31 个现有测试的全部 key event 都被至少一条 entry match;若漏,跑步骤 4 立刻红 |
| 新加的 `KEYCODE_ESCAPE`(无 Ctrl) 误伤 IME 取消组合场景                                          | 中   | `TerminalView.onKeyDown` 已有 mid-composition 早返回分支,ESC 走 false → IME 自己处理;测试 `test_escape_whileComposing_isPassedToIme` 钉死 |
| 表内 lambda 在每个 `resolve` 调用都跑一遍,性能比 `when` 慢                                      | 低   | Kotlin 单态化编译后 50 entry 的表与手写 `when` 性能差 < 1%;不是热路径,不需要优化              |
| 加 `KEYCODE_ESCAPE` entry 改变了原本"完全 Ignore"的语义                                         | 低   | 这是这个 PR 想要的修复;原 kdoc 已承认此决定是错的                                              |
| `KEYCODE_INSERT` 在很多 Android 软键盘上根本没有这个键                                          | 极低 | 物理键盘才有;软键盘用户不受影响                                                                |
| `Ctrl+@` 在美式键盘上需 Shift+2,实际 keyCode 是 `KEYCODE_2` 还是 `KEYCODE_AT` 取决于 IME       | 中   | 我们的 match 用 `KEYCODE_AT`;测试里直接构造 event 验证;真实 IME 行为需真机验证(Sprint 范围外) |
| vim 的 `<C-@>` 映射不通过 NUL(0x00) — vim 在 normal 模式收到 NUL 会 beep                   | 低   | 这是 vim 默认行为;用户若需 NUL 走自己的 `<C-@>` 映射,需要在 vimrc 里覆盖                        |

### 5.4 与 Sprint 2.5 的关系

CLAUDE.md 显示 `Sprint 2.5 Modules 11-14` 是 TOFU 存储、密码加密、debug log gating 等。**本 PR 与 Sprint 2.5 并行,无跨模块耦合** — 纯 `terminal/` 内部改动。PR 描述里要显式说明这点。

---

## 6. 验收标准

PR 描述里写明以下条件为"可合并":

- [ ] `KEY_MAP` 表至少 50 条 entry(估算:23 个 Ctrl+字母 + 5 个 Ctrl+符号 + 1 个 IME 切语言 + 1 个 Paste + 1 个 Alt+letter + 1 个 ESC + 1 个 Shift+Tab + 1 个 Tab + 1 个 Enter + 1 个 Del + 4 个方向键 + 2 个 Home/End + 2 个 PageUp/PageDown + 1 个 ForwardDel + 1 个 Insert + 12 个 F1-F12 ≈ 50+)
- [ ] `KeyEventRoutingTest` 现有 31 个 case 不修改通过
- [ ] 新增 10 个 case 全部通过
- [ ] `terminal/` 模块外任何文件 `git diff` 为空
- [ ] 提交信息遵循 Conventional Commits 风格
- [ ] kdoc 上没有 TBD / TODO / "FILL ME IN"
- [ ] 手动 e2e:真机外接键盘,启动 vim,`i` 输入几个字符,按 `ESC` 能回到 normal 模式,`:` 能进入 command 模式
- [ ] 手动 e2e:真机外接键盘,启动 nano,`Ctrl+O` 能 writeOut,`Ctrl+X` 能 exit,`Ctrl+W` 能 search

---

## 7. 不在本次范围(留作下一轮)

- 给 `KEY_MAP` 加 JSON / YAML 序列化,生成 markdown 文档(`dumpKeymap()` 工具)
- 给 vim / nano 加 on-screen 提示("当前 mode: NORMAL" 等)
- Sprint 3 范围里的 multi-host、known_hosts TOFU、SFTP、Mosh
- 在 `MainActivity` 加 Ctrl+Shift+L 之类的"硬件键盘 → app 内部命令"快捷键
