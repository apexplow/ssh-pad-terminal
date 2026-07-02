# Vim/Nano KeyMapper 数据驱动重构 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `terminal/KeyMapper.kt` 重构为数据驱动的路由表 + 补全 7 个 vim/nano 缺漏的键位(`KEYCODE_ESCAPE` 无 Ctrl、`Shift+Tab`、`KEYCODE_INSERT`、`Ctrl+^`、`Ctrl+_`、`Ctrl+@`、`Ctrl+?`)+ 给每个映射加结构化文档。

**Architecture:** 新建 `KeyMapDoc.kt` 放 `ProgramUsage` + `KeyMapEntry` data class;`KeyMapper` 内部从手写 `when` 改为遍历一个 `List<KeyMapEntry>`(首匹配胜出);每个 entry 自带 vim/nano/bash 行为字段。路由表通过 `internal fun entriesForTest()` 暴露给同 module 测试,生产 API(`resolve`、`toAnsiSequence`)形状不变。

**Tech Stack:** Kotlin、Robolectric 4.13、JUnit 4、AGP Kotlin DSL、Android SDK 33、com.termux:terminal-emulator(无版本变化)、com.termux:terminal-view(无版本变化)。

**Reference spec:** `docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md`

---

## File Structure

| 路径                                                                                  | 类型   | 职责                                                                          |
|--------------------------------------------------------------------------------------|--------|------------------------------------------------------------------------------|
| `app/src/main/java/com/example/sshterminal/terminal/KeyMapDoc.kt`                    | 新建   | `ProgramUsage` + `KeyMapEntry` data class,纯文档数据结构,无运行时行为            |
| `app/src/main/java/com/example/sshterminal/terminal/KeyMapper.kt`                    | 改     | 加 `KEY_MAP` 私有 val;`resolve()` body 改为遍历;`entriesForTest()` 内部访问     |
| `app/src/test/java/com/example/sshterminal/terminal/KeyEventRoutingTest.kt`          | 改     | 加 1 个元测试 + 10 个新 case(7 个新键 + ESC 组合 + Ctrl+ESC + 1 个 KeyEvent 入口用例) |

`KEY_MAP` 预计 ~19 条 entry(按行为分组:1 Paste + 1 IME 切语言 + 1 Ctrl+letter 组 + 1 Alt+letter + 1 ESC + 4 个新 Ctrl+symbol + 1 Shift+Tab + 1 Tab + 1 Enter + 1 Del + 1 方向键组 + 1 Home/End + 1 PageUp/PageDown + 1 ForwardDel + 1 Insert + 1 F1-F12),详细分项见 §3.1 spec。

---

## Task 1: 新建 `KeyMapDoc.kt` 数据类

**Files:**
- Create: `app/src/main/java/com/example/sshterminal/terminal/KeyMapDoc.kt`

- [ ] **Step 1: 创建文件**

写入以下内容(完整复制粘贴,无任何占位符):

```kotlin
package com.example.sshterminal.terminal

import android.view.KeyEvent

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
 *
 * 一些程序在某些 mode 下没绑定 — 用一个 `ProgramUsage("any", "no native binding")` 行,
 * 不要再用空字符串或 null。
 */
data class ProgramUsage(
    val mode: String,
    val effect: String,
)

/**
 * 路由表的一行。
 *
 * 运行时只看 [match] 和 [verdict];[description] / [vim] / [nano] / [bash] / [note] 是
 * 给人类读者看的结构化文档,以后可以序列化到 markdown / yaml 而不需要反射。
 *
 * - [match]   : "这个 KeyEvent 是不是命中本条?" — 纯谓词。
 * - [verdict] : "命中之后做什么?" — 产出一个 KeyResolution。必须是 lambda 而非常量,
 *              因为 Alt+letter 的 verdict 依赖 `event.unicodeChar`,不同 event 不同字节。
 *
 * 注意 `match` 和 `verdict` 都是 `KeyEvent` 形参的 lambda,不要只接受 `keyCode` —
 * Alt+letter / Ctrl+letter / 等的判定都需要看 meta state。
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

- [ ] **Step 2: 编译验证**

```bash
./gradlew :app:compileDebugKotlin
```

预期:`BUILD SUCCESSFUL`。

如果报错,99% 是 import 写错(`KeyResolution` 在同 package 不需要 import,但 `KeyEvent` 需要)。修到 BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/KeyMapDoc.kt
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "chore(terminal): add KeyMapDoc data classes" -m "Pure data structures for the data-driven KeyMapper routing table. No
runtime behavior yet; this commit only establishes ProgramUsage +
KeyMapEntry. Will be consumed by the refactored KeyMapper in the
next commit." -m "Ref: docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md §2"
```

---

## Task 2: 添加元测试 `test_keyMapTable_isWellFormed`(失败)

**Files:**
- Modify: `app/src/test/java/com/example/sshterminal/terminal/KeyEventRoutingTest.kt`(在文件末尾追加)

- [ ] **Step 1: 写测试**

在 `KeyEventRoutingTest.kt` 末尾,`// helpers` 注释行**之前**追加:

```kotlin
    // -----------------------------------------------------------------------
    // Meta-test for the data-driven KEY_MAP table.
    //
    // The spec requires two things to be true for the routing table to be
    // safe to ship:
    //  1. KEY_MAP is non-empty (otherwise nothing routes).
    //  2. Every existing test's key event is matched by at least one entry —
    //     if a refactor accidentally drops a route, this catches it before
    //     the rest of the test suite has to.
    //
    // The list of events is hard-coded from the test cases above. If a
    // future test adds a new event type, append it here too — otherwise
    // the meta test passes but the new event may still be unrouted.
    // -----------------------------------------------------------------------

    @Test
    fun test_keyMapTable_isWellFormed() {
        val entries = KeyMapper.entriesForTest()
        assertTrue("KEY_MAP must be non-empty", entries.isNotEmpty())

        val knownEvents = listOf(
            // Printable char (test_printableChar_isHandledByImePath_notView)
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A),
            // Ctrl+letter (test_ctrlA/B/E/L/R/U/W)
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_R, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_U, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, KeyEvent.META_CTRL_ON),
            // Ctrl+C (test_ctrlC_writesInterruptAndConsumesEvent)
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON),
            // Ctrl+\ Ctrl+] (test_ctrlBackslash / test_ctrlRightBracket)
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACKSLASH, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.META_CTRL_ON),
            // Enter / DEL
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL),
            // Arrow up
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP),
            // IME language switch
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.META_SHIFT_ON),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_LANGUAGE_SWITCH),
            // Paste
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
            // Ctrl+V alone (must NOT match the Paste entry — must fall through to printable-key path)
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON),
            // Shift+V alone (must NOT match the Paste entry)
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, KeyEvent.META_SHIFT_ON),
        )

        for (ev in knownEvents) {
            val matched = entries.any { it.match(ev) }
            assertTrue(
                "event keyCode=${ev.keyCode} metaState=${ev.metaState} must be matched by some entry in KEY_MAP",
                matched,
            )
        }
    }
```

- [ ] **Step 2: 运行测试,看它失败**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.KeyEventRoutingTest.test_keyMapTable_isWellFormed"
```

预期:**编译失败**,错误信息大致是 `Unresolved reference: entriesForTest`。这是预期的 — 红灯。

如果意外地通过(说明编译就失败了,或者表已经存在),停下来查清楚 — 不要继续。

- [ ] **Step 3: 提交失败的测试**

```bash
git add app/src/test/java/com/example/sshterminal/terminal/KeyEventRoutingTest.kt
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "test(terminal): add meta-test for KEY_MAP coverage (red)" -m "Drives Task 3: this test will pass once entriesForTest() returns
a non-empty list and matches every existing test's key event. Until
then it fails to compile, which is the red signal we want." -m "Ref: docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md §4.2"
```

---

## Task 3: 加 `KEY_MAP` 和 `entriesForTest()` 到 `KeyMapper`

**Files:**
- Modify: `app/src/main/java/com/example/sshterminal/terminal/KeyMapper.kt`

- [ ] **Step 1: 在 `KeyMapper` 顶部加 `entriesForTest()` + 整个 `KEY_MAP` 表 + 两个 lookup 辅助函数**

**先**用 `Read` 看一下 `KeyMapper.kt` 当前的结构,确认我们要在 `object KeyMapper {` 块的哪个位置插入。

预期结构:
- 第 49 行附近:`object KeyMapper {`
- 接着是 `fun resolve(...)` 和 `fun toAnsiSequence(...)`
- 之后是 `isImeLanguageSwitch`、`isPasteShortcut`、`ctrlSequence`

我们在 `KeyMapper` 的**末尾**(在 `private fun ctrlSequence(keyCode: Int): ByteArray?` 后面,`object` 关闭 `}` 之前)插入以下代码:

```kotlin
    /**
     * Test-only accessor for the routing table. Exposed as `internal` so the
     * `src/test` source set can iterate it for the meta-test in
     * `KeyEventRoutingTest.test_keyMapTable_isWellFormed`. Production code
     * MUST NOT call this — the routing table is consulted through
     * [resolve], which is the single public entry point and also the only
     * place we control the first-match-wins ordering.
     *
     * The `internal` visibility means same-module access only; an APK
     * outside this module (none exists today) cannot reach it.
     */
    internal fun entriesForTest(): List<KeyMapEntry> = KEY_MAP

    // Routing table. First match wins. See class kdoc for the ordering rationale.
    // Order: Paste → IME switch → Ctrl+letter → Alt+letter → ESC → Ctrl+symbol set
    //        → Shift+Tab → Tab → Enter → Del → cursor keys → Home/End → PageUp/Down
    //        → ForwardDel → Insert → F-keys. New entries (★) are the additions
    //        from the 2026-06-29 vim/nano support design.
    private val KEY_MAP: List<KeyMapEntry> = listOf(
        // 1. Paste shortcut — must beat the Ctrl+V byte path.
        KeyMapEntry(
            description = "Ctrl+Shift+V → Paste (must beat Ctrl+V byte path)",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_V && ev.isCtrlPressed && ev.isShiftPressed },
            verdict = { KeyResolution.Paste },
            vim = listOf(ProgramUsage("any", "no native binding — terminal intercepts paste")),
            nano = listOf(ProgramUsage("any", "no native binding — terminal intercepts paste")),
            bash = listOf(ProgramUsage("any", "no native binding — terminal intercepts paste")),
        ),

        // 2. IME language switch — must NEVER reach the remote shell.
        KeyMapEntry(
            description = "IME language switch (Ctrl+Space, Shift+Space, KEYCODE_LANGUAGE_SWITCH) → Swallow",
            match = { ev ->
                (ev.keyCode == KeyEvent.KEYCODE_SPACE && (ev.isCtrlPressed || ev.isShiftPressed)) ||
                    ev.keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH
            },
            verdict = { KeyResolution.Swallow },
            vim = listOf(ProgramUsage("any", "must NEVER reach vim — IME-internal toggle")),
            nano = listOf(ProgramUsage("any", "must NEVER reach nano — IME-internal toggle")),
            bash = listOf(ProgramUsage("any", "must NEVER reach bash — IME-internal toggle")),
            note = "Per implementation_plan.md P0 — these are IME-internal, not terminal input",
        ),

        // 3. Ctrl+letter (A-Z except V) — xterm ASCII control bytes.
        KeyMapEntry(
            description = "Ctrl+letter (A-Z except V) → xterm ASCII control bytes (0x01-0x1A)",
            match = { ev -> ev.isCtrlPressed && ctrlControlByte(ev.keyCode) != null },
            verdict = { ev ->
                KeyResolution.Send(byteArrayOf(ctrlControlByte(ev.keyCode)!!.toByte()))
            },
            vim = listOf(
                ProgramUsage("insert", "ETX (Ctrl+C) exits insert mode; others depend on plugin bindings"),
                ProgramUsage("normal", "many letters are vim's own — Ctrl+R=redo, Ctrl+F=pgdn, Ctrl+W=window, etc."),
                ProgramUsage("command", "Ctrl+C aborts the command line"),
            ),
            nano = listOf(ProgramUsage("any", "vanilla bindings: Ctrl+O=writeOut, Ctrl+X=exit, Ctrl+W=search, Ctrl+K=cut, Ctrl+U=uncut, etc.")),
            bash = listOf(ProgramUsage("any", "readline: Ctrl+A/E=line begin/end, Ctrl+R=reverse-i-search, Ctrl+K=kill-to-eol, Ctrl+U=kill-line, Ctrl+W=kill-word, Ctrl+L=clear, etc.")),
            note = "KEYCODE_V intentionally omitted — Ctrl+V alone falls through to printable-key path so the IME emits a literal 'V'. Ctrl+Shift+V is the Paste entry above.",
        ),

        // 4. Alt+letter — xterm Meta convention.
        KeyMapEntry(
            description = "Alt+letter (unicodeChar > 0) → ESC + letter (xterm Meta convention)",
            match = { ev -> ev.isAltPressed && !ev.isCtrlPressed && ev.unicodeChar > 0 },
            verdict = { ev ->
                val payload = ev.unicodeChar.toChar().toString().toByteArray(Charsets.UTF_8)
                KeyResolution.Send(byteArrayOf(0x1B) + payload)
            },
            vim = listOf(ProgramUsage("normal", "Meta-key — many plugins bind M-x, M-w, etc. as leader keys")),
            nano = listOf(ProgramUsage("any", "Meta-key — less used than Ctrl in vanilla nano")),
            bash = listOf(ProgramUsage("emacs", "readline's emacs mode binds M-f, M-b, M-d, M-<, M->, etc.")),
        ),

        // 5. KEYCODE_ESCAPE (no Ctrl) — vim normal-mode exit. [★ NEW]
        KeyMapEntry(
            description = "KEYCODE_ESCAPE (no Ctrl) → 0x1B (ESC) — vim normal-mode exit",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_ESCAPE && !ev.isCtrlPressed },
            verdict = { KeyResolution.Send(byteArrayOf(0x1B.toByte())) },
            vim = listOf(
                ProgramUsage("insert", "exit to normal mode"),
                ProgramUsage("visual", "exit to normal mode"),
                ProgramUsage("replace", "exit to normal mode"),
                ProgramUsage("command", "abort command line back to normal mode"),
            ),
            nano = listOf(ProgramUsage("any", "cancel current operation")),
            bash = listOf(ProgramUsage("any", "cancel incomplete command line")),
        ),

        // 5b. Ctrl+^ (KEYCODE_CIRCUMFLEX) → 0x1E (RS) — vim alt-file. [★ NEW]
        KeyMapEntry(
            description = "Ctrl+^ (KEYCODE_CIRCUMFLEX) → 0x1E (RS) — vim alternate file",
            match = { ev -> ev.isCtrlPressed && ev.keyCode == KeyEvent.KEYCODE_CIRCUMFLEX },
            verdict = { KeyResolution.Send(byteArrayOf(0x1E.toByte())) },
            vim = listOf(ProgramUsage("normal", "switch to alternate file")),
            nano = listOf(ProgramUsage("any", "no native binding")),
            bash = listOf(ProgramUsage("any", "no native binding")),
        ),

        // 5c. Ctrl+_ (KEYCODE_UNDERSCORE) → 0x1F (US) — vim undo / nano go-to-line. [★ NEW]
        KeyMapEntry(
            description = "Ctrl+_ (KEYCODE_UNDERSCORE) → 0x1F (US) — vim undo (compatible mode) / nano go-to-line",
            match = { ev -> ev.isCtrlPressed && ev.keyCode == KeyEvent.KEYCODE_UNDERSCORE },
            verdict = { KeyResolution.Send(byteArrayOf(0x1F.toByte())) },
            vim = listOf(ProgramUsage("normal", "undo (in compatible mode)")),
            nano = listOf(ProgramUsage("any", "go to line number")),
            bash = listOf(ProgramUsage("any", "no native binding")),
        ),

        // 5d. Ctrl+@ (KEYCODE_AT) → 0x00 (NUL) — bash set-mark / nano set mark. [★ NEW]
        KeyMapEntry(
            description = "Ctrl+@ (KEYCODE_AT) → 0x00 (NUL) — bash set-mark / nano set mark",
            match = { ev -> ev.isCtrlPressed && ev.keyCode == KeyEvent.KEYCODE_AT },
            verdict = { KeyResolution.Send(byteArrayOf(0x00.toByte())) },
            vim = listOf(ProgramUsage("normal", "no native binding (commonly remapped to <C-@>)")),
            nano = listOf(ProgramUsage("any", "set mark")),
            bash = listOf(ProgramUsage("any", "set-mark")),
        ),

        // 5e. Ctrl+? (KEYCODE_SLASH) → 0x7F (DEL) — alternative DEL byte. [★ NEW]
        KeyMapEntry(
            description = "Ctrl+? (KEYCODE_SLASH) → 0x7F (DEL) — alternative DEL byte (same as bare Backspace)",
            match = { ev -> ev.isCtrlPressed && ev.keyCode == KeyEvent.KEYCODE_SLASH },
            verdict = { KeyResolution.Send(byteArrayOf(0x7F.toByte())) },
            vim = listOf(ProgramUsage("normal", "delete one char before cursor")),
            nano = listOf(ProgramUsage("any", "delete one char before cursor")),
            bash = listOf(ProgramUsage("any", "delete one char before cursor")),
        ),

        // 6. Shift+Tab — Back-Tab. [★ NEW]
        KeyMapEntry(
            description = "KEYCODE_TAB + Shift → ESC[Z (Back-Tab)",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_TAB && ev.isShiftPressed && !ev.isCtrlPressed },
            verdict = { KeyResolution.Send("\u001B[Z".toByteArray(Charsets.UTF_8)) },
            vim = listOf(ProgramUsage("normal", "in some configs, `gT` — previous tab")),
            nano = listOf(ProgramUsage("any", "un-indent current line")),
            bash = listOf(ProgramUsage("any", "reverse tab completion")),
        ),

        // 7. KEYCODE_TAB (no Shift) — bare Tab and Ctrl+Tab both match.
        KeyMapEntry(
            description = "KEYCODE_TAB (no Shift) → \\t (HT) — bare Tab and Ctrl+Tab both match",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_TAB && !ev.isShiftPressed },
            verdict = { KeyResolution.Send("\t".toByteArray(Charsets.UTF_8)) },
            vim = listOf(ProgramUsage("insert", "insert literal tab (or spaces with :set expandtab)")),
            nano = listOf(ProgramUsage("any", "insert tab / trigger completion")),
            bash = listOf(ProgramUsage("any", "trigger completion")),
            note = "Ctrl+Tab also matches (Ctrl+I produces the same byte 0x09 — see Ctrl+letter entry above)",
        ),

        // 8. KEYCODE_ENTER — bare Enter and Ctrl+Enter both match.
        KeyMapEntry(
            description = "KEYCODE_ENTER → \\r (CR) — bare Enter and Ctrl+Enter both match",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_ENTER },
            verdict = { KeyResolution.Send("\r".toByteArray(Charsets.UTF_8)) },
            vim = listOf(ProgramUsage("insert", "newline")),
            nano = listOf(ProgramUsage("any", "newline")),
            bash = listOf(ProgramUsage("any", "execute command")),
            note = "Ctrl+Enter also matches (Ctrl+M produces the same byte 0x0D — see Ctrl+letter entry above)",
        ),

        // 9. KEYCODE_DEL — Backspace key.
        KeyMapEntry(
            description = "KEYCODE_DEL → 0x7F (DEL) — Backspace on most Android keyboards",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_DEL },
            verdict = { KeyResolution.Send(byteArrayOf(0x7F.toByte())) },
            vim = listOf(ProgramUsage("insert", "delete one char before cursor")),
            nano = listOf(ProgramUsage("any", "delete one char before cursor")),
            bash = listOf(ProgramUsage("any", "delete one char before cursor")),
        ),

        // 10. KEYCODE_DPAD_* — ANSI cursor sequences.
        KeyMapEntry(
            description = "KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT → ANSI cursor sequences",
            match = { ev -> ev.keyCode in cursorKeyCodes },
            verdict = { ev ->
                val seq = when (ev.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> "\u001B[A"
                    KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B[B"
                    KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001B[C"
                    KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B[D"
                    else -> error("unreachable: cursorKeyCodes membership is the match gate")
                }
                KeyResolution.Send(seq.toByteArray(Charsets.UTF_8))
            },
            vim = listOf(ProgramUsage("normal", "h/j/k/l equivalent")),
            nano = listOf(ProgramUsage("any", "move cursor")),
            bash = listOf(ProgramUsage("any", "no default binding (readline emacs mode uses Ctrl+B/F/N/P)")),
        ),

        // 11. KEYCODE_MOVE_HOME/END.
        KeyMapEntry(
            description = "KEYCODE_MOVE_HOME → ESC[H, KEYCODE_MOVE_END → ESC[F",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_MOVE_HOME || ev.keyCode == KeyEvent.KEYCODE_MOVE_END },
            verdict = { ev ->
                val seq = if (ev.keyCode == KeyEvent.KEYCODE_MOVE_HOME) "\u001B[H" else "\u001B[F"
                KeyResolution.Send(seq.toByteArray(Charsets.UTF_8))
            },
            vim = listOf(ProgramUsage("normal", "^/$ — begin/end of line")),
            nano = listOf(ProgramUsage("any", "begin/end of line")),
            bash = listOf(ProgramUsage("any", "begin/end of line")),
        ),

        // 12. KEYCODE_PAGE_UP/DOWN.
        KeyMapEntry(
            description = "KEYCODE_PAGE_UP → ESC[5~, KEYCODE_PAGE_DOWN → ESC[6~",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_PAGE_UP || ev.keyCode == KeyEvent.KEYCODE_PAGE_DOWN },
            verdict = { ev ->
                val seq = if (ev.keyCode == KeyEvent.KEYCODE_PAGE_UP) "\u001B[5~" else "\u001B[6~"
                KeyResolution.Send(seq.toByteArray(Charsets.UTF_8))
            },
            vim = listOf(ProgramUsage("normal", "Ctrl+F/Ctrl+B equivalent (page down/up)")),
            nano = listOf(ProgramUsage("any", "page down/up")),
            bash = listOf(ProgramUsage("any", "no default binding")),
        ),

        // 13. KEYCODE_FORWARD_DEL.
        KeyMapEntry(
            description = "KEYCODE_FORWARD_DEL → ESC[3~ (forward delete)",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_FORWARD_DEL },
            verdict = { KeyResolution.Send("\u001B[3~".toByteArray(Charsets.UTF_8)) },
            vim = listOf(ProgramUsage("insert", "delete one char after cursor")),
            nano = listOf(ProgramUsage("any", "delete one char after cursor")),
            bash = listOf(ProgramUsage("any", "delete one char after cursor")),
        ),

        // 14. KEYCODE_INSERT — vim mode toggle. [★ NEW]
        KeyMapEntry(
            description = "KEYCODE_INSERT → ESC[2~ (Insert key, vim mode-toggle)",
            match = { ev -> ev.keyCode == KeyEvent.KEYCODE_INSERT },
            verdict = { KeyResolution.Send("\u001B[2~".toByteArray(Charsets.UTF_8)) },
            vim = listOf(ProgramUsage("normal", "toggle insert / replace mode")),
            nano = listOf(ProgramUsage("any", "no native binding")),
            bash = listOf(ProgramUsage("any", "no native binding")),
        ),

        // 15. F1-F12 — function key sequences.
        KeyMapEntry(
            description = "F1-F12 → standard ANSI function-key sequences",
            match = { ev -> functionKeyBytes(ev.keyCode) != null },
            verdict = { ev -> KeyResolution.Send(functionKeyBytes(ev.keyCode)!!) },
            vim = listOf(ProgramUsage("normal", "F1=Help, others depend on user config")),
            nano = listOf(ProgramUsage("any", "F1=Help, others unused in vanilla")),
            bash = listOf(ProgramUsage("any", "no default binding")),
        ),
    )

    private val cursorKeyCodes = setOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    /**
     * Maps a Ctrl-modified key to the corresponding ASCII control byte. `null`
     * means "this key has no Ctrl mapping" — the caller treats that as a
     * fall-through to the next entry in [KEY_MAP].
     *
     * Surface: A-Z (except V) + `\` (0x1C) + `]` (0x1D). KEYCODE_V omitted
     * intentionally so Ctrl+V alone keeps falling through to the printable-
     * key path (the IME emits a literal "V"). Ctrl+Shift+V is the Paste
     * entry higher in [KEY_MAP].
     */
    private fun ctrlControlByte(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_A -> 0x01
        KeyEvent.KEYCODE_B -> 0x02
        KeyEvent.KEYCODE_C -> 0x03
        KeyEvent.KEYCODE_D -> 0x04
        KeyEvent.KEYCODE_E -> 0x05
        KeyEvent.KEYCODE_F -> 0x06
        KeyEvent.KEYCODE_G -> 0x07
        KeyEvent.KEYCODE_H -> 0x08
        KeyEvent.KEYCODE_I -> 0x09
        KeyEvent.KEYCODE_J -> 0x0A
        KeyEvent.KEYCODE_K -> 0x0B
        KeyEvent.KEYCODE_L -> 0x0C
        KeyEvent.KEYCODE_M -> 0x0D
        KeyEvent.KEYCODE_N -> 0x0E
        KeyEvent.KEYCODE_O -> 0x0F
        KeyEvent.KEYCODE_P -> 0x10
        KeyEvent.KEYCODE_Q -> 0x11
        KeyEvent.KEYCODE_R -> 0x12
        KeyEvent.KEYCODE_S -> 0x13
        KeyEvent.KEYCODE_T -> 0x14
        KeyEvent.KEYCODE_U -> 0x15
        KeyEvent.KEYCODE_W -> 0x17
        KeyEvent.KEYCODE_X -> 0x18
        KeyEvent.KEYCODE_Y -> 0x19
        KeyEvent.KEYCODE_Z -> 0x1A
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0x1B
        KeyEvent.KEYCODE_BACKSLASH -> 0x1C
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x1D
        else -> null
    }

    /**
     * Maps a function-key code to its ANSI escape sequence. `null` means
     * "not a function key" — the caller treats that as a fall-through.
     */
    private fun functionKeyBytes(keyCode: Int): ByteArray? = when (keyCode) {
        KeyEvent.KEYCODE_F1 -> "\u001BOP".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F2 -> "\u001BOQ".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F3 -> "\u001BOR".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F4 -> "\u001BOS".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F5 -> "\u001B[15~".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F6 -> "\u001B[17~".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F7 -> "\u001B[18~".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F8 -> "\u001B[19~".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F9 -> "\u001B[20~".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F10 -> "\u001B[21~".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F11 -> "\u001B[23~".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_F12 -> "\u001B[24~".toByteArray(Charsets.UTF_8)
        else -> null
    }
```

**重要**:`resolve()` 和 `toAnsiSequence()` 的 body **不要改** — 这一步只是把表 + helper 准备好,真正的路由切换在 Task 4。所以现在 `KEY_MAP` 还没被使用,但元测试能通过(它只断言 `entriesForTest()` 存在且非空)。

- [ ] **Step 2: 编译验证**

```bash
./gradlew :app:compileDebugKotlin
```

预期:`BUILD SUCCESSFUL`。

如果看到 "unresolved reference" 之类的 import 错误,检查:
- `KeyMapEntry` 是不是已经 import(它和 `KeyMapper` 在同 package)
- `KeyResolution` 同 package 不需要 import
- `KeyEvent` 需要 import

- [ ] **Step 3: 运行元测试,看它通过**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.KeyEventRoutingTest.test_keyMapTable_isWellFormed"
```

预期:`BUILD SUCCESSFUL` + 测试 PASS。

- [ ] **Step 4: 运行所有现有 KeyEventRoutingTest,确保 0 回归**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.KeyEventRoutingTest"
```

预期:31 个原有 case + 1 个新元测试,**全部 PASS**。如果有任何现有 case 现在失败,**停下来** — 不要继续,因为这意味着 `KEY_MAP` 的某个 entry match 逻辑跟旧 `resolve` 不一致。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/KeyMapper.kt
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat(terminal): add KEY_MAP routing table with all 19 entries" -m "Ports every existing KeyMapper.resolve rule into a data-driven
List<KeyMapEntry> with per-program (vim/nano/bash) documentation.
Adds 7 new entries for missing vim/nano bindings (ESC alone,
Shift+Tab, KEYCODE_INSERT, Ctrl+^/_/@/?). The resolve() body is
unchanged in this commit — the table is consulted only by the
new entriesForTest() accessor and the next commit will switch
resolve to iterate the table. All 31 existing tests + the new
meta-test pass." -m "Ref: docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md §3, §4"
```

---

## Task 4: 切换 `resolve()` body 使用 `KEY_MAP`

**Files:**
- Modify: `app/src/main/java/com/example/sshterminal/terminal/KeyMapper.kt`

- [ ] **Step 1: 替换 `resolve()` body**

找到当前的 `fun resolve(keyCode: Int, event: KeyEvent): KeyResolution {` 函数(在 `KeyMapper` 顶部)。整个 `resolve` 函数体替换为:

```kotlin
    fun resolve(keyCode: Int, event: KeyEvent): KeyResolution {
        for (entry in KEY_MAP) {
            if (entry.match(event)) return entry.verdict(event)
        }
        return KeyResolution.Ignore
    }
```

整个 `KeyMapper.resolve` 函数的形状现在变成:

```kotlin
    fun resolve(keyCode: Int, event: KeyEvent): KeyResolution {
        for (entry in KEY_MAP) {
            if (entry.match(event)) return entry.verdict(event)
        }
        return KeyResolution.Ignore
    }
```

**注意**:`keyCode` 形参暂时保留(虽然新 body 不直接用它),因为这是 public API,改签名会破坏调用方。后续如果确认所有调用方都用 `event.keyCode` 拿 keyCode,可以再加一个 commit 把形参删掉。**这次不要删形参**。

- [ ] **Step 2: 编译验证**

```bash
./gradlew :app:compileDebugKotlin
```

预期:`BUILD SUCCESSFUL`。

- [ ] **Step 3: 运行所有 KeyEventRoutingTest,验证 0 回归**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.KeyEventRoutingTest"
```

预期:32 个 case(31 旧 + 1 元测试)全部 PASS。

如果有失败,**停下来**检查:
1. `KEY_MAP` 中对应 entry 的 `match` lambda 跟旧 `resolve` 的判定逻辑是否一致
2. 顺序:新 `KEY_MAP` 是从最优先到默认,旧 `resolve` 也是 — 顺序必须等价
3. 常见陷阱:`KEYCODE_V + isCtrlPressed + isShiftPressed` 必须被 Paste entry 先命中(顺序对了就 OK)

- [ ] **Step 4: 运行整个 terminal/ test 包,确保 0 回归**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.*"
```

预期:所有 `terminal/*Test` 全 PASS。包括 `TerminalInputConnectionTest`、`AltBufferScrollCrashGuardTest`、`TerminalViewLayoutTest`。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/KeyMapper.kt
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "refactor(terminal): switch KeyMapper.resolve to iterate KEY_MAP" -m "Replaces the hand-written when-block in resolve() with a loop over
the data-driven routing table. Behavior is identical: all 32
KeyEventRoutingTest cases pass, all other terminal/* tests pass.

Old internal helpers (isPasteShortcut, isImeLanguageSwitch) are now
unreachable from resolve() but kept as private functions for
readability; a future commit may inline or remove them." -m "Ref: docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md §5.2"
```

---

## Task 5: 加 7 个新键的测试(TDD — 红灯)

**Files:**
- Modify: `app/src/test/java/com/example/sshterminal/terminal/KeyEventRoutingTest.kt`(在元测试之前追加)

- [ ] **Step 1: 写 10 个新测试**

在 `KeyEventRoutingTest.kt` 中,**`// Meta-test for the data-driven KEY_MAP table.` 注释行之前**追加以下 10 个测试:

```kotlin
    // -----------------------------------------------------------------------
    // New key bindings added in the 2026-06-29 vim/nano support design.
    // These were missing or broken in the previous routing table and are
    // the reason the whole refactor exists. See spec §3.2.
    // -----------------------------------------------------------------------

    @Test
    fun test_escapeAlone_writesEscByte() {
        val ev = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)

        val verdict = KeyMapper.resolve(KeyEvent.KEYCODE_ESCAPE, ev)

        assertEquals(
            "physical ESC must send 0x1B so vim can exit insert mode",
            KeyResolution.Send(byteArrayOf(0x1B.toByte())),
            verdict,
        )
    }

    @Test
    fun test_ctrlEscape_writesEscByte() {
        // Ctrl+ESC was already mapped in the old ctrlSequence() (it shared
        // a row with Ctrl+[), but it was undocumented. This test pins the
        // behavior now that it's in the data-driven table.
        val ev = keyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.META_CTRL_ON,
        )

        val verdict = KeyMapper.resolve(KeyEvent.KEYCODE_ESCAPE, ev)

        assertEquals(
            "Ctrl+ESC must produce 0x1B (same byte as Ctrl+[)",
            KeyResolution.Send(byteArrayOf(0x1B.toByte())),
            verdict,
        )
    }

    @Test
    fun test_escape_whileComposing_isPassedToIme() {
        // Mid-IME composition (e.g. user mid-pinyin) must defer ESC to the
        // IME so it can cancel the composition, not blast 0x1B to the
        // remote shell. Verified end-to-end through TerminalView.onKeyDown.
        val inputConnection = view.activeInputConnection()!!
        inputConnection.setComposingText("ni", 0)

        val ev = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)
        val handled = view.onKeyDown(KeyEvent.KEYCODE_ESCAPE, ev)

        assertFalse("ESC while composing must be passed to the IME", handled)
        assertEquals(
            "ESC must not write 0x1B to SSH while composing",
            0,
            endpoint.bytesWritten().size,
        )
    }

    @Test
    fun test_shiftTab_writesBackTabSequence() {
        val ev = keyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.META_SHIFT_ON,
        )

        val verdict = KeyMapper.resolve(KeyEvent.KEYCODE_TAB, ev)

        assertEquals(
            "Shift+Tab must produce ESC[Z (Back-Tab)",
            KeyResolution.Send("\u001B[Z".toByteArray(Charsets.UTF_8)),
            verdict,
        )
    }

    @Test
    fun test_insertKey_writesInsertSequence() {
        val ev = keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_INSERT)

        val verdict = KeyMapper.resolve(KeyEvent.KEYCODE_INSERT, ev)

        assertEquals(
            "KEYCODE_INSERT must produce ESC[2~ (Insert key sequence)",
            KeyResolution.Send("\u001B[2~".toByteArray(Charsets.UTF_8)),
            verdict,
        )
    }

    @Test
    fun test_ctrlCaret_writesRsByte() {
        val ev = keyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_CIRCUMFLEX,
            KeyEvent.META_CTRL_ON,
        )

        val verdict = KeyMapper.resolve(KeyEvent.KEYCODE_CIRCUMFLEX, ev)

        assertEquals(
            "Ctrl+^ must produce 0x1E (RS) — vim alt-file",
            KeyResolution.Send(byteArrayOf(0x1E.toByte())),
            verdict,
        )
    }

    @Test
    fun test_ctrlUnderscore_writesUsByte() {
        val ev = keyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_UNDERSCORE,
            KeyEvent.META_CTRL_ON,
        )

        val verdict = KeyMapper.resolve(KeyEvent.KEYCODE_UNDERSCORE, ev)

        assertEquals(
            "Ctrl+_ must produce 0x1F (US) — vim undo / nano go-to-line",
            KeyResolution.Send(byteArrayOf(0x1F.toByte())),
            verdict,
        )
    }

    @Test
    fun test_ctrlAt_writesNulByte() {
        val ev = keyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_AT,
            KeyEvent.META_CTRL_ON,
        )

        val verdict = KeyMapper.resolve(KeyEvent.KEYCODE_AT, ev)

        assertEquals(
            "Ctrl+@ must produce 0x00 (NUL) — bash set-mark / nano set mark",
            KeyResolution.Send(byteArrayOf(0x00.toByte())),
            verdict,
        )
    }

    @Test
    fun test_ctrlSlash_writesDelByte() {
        val ev = keyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_SLASH,
            KeyEvent.META_CTRL_ON,
        )

        val verdict = KeyMapper.resolve(KeyEvent.KEYCODE_SLASH, ev)

        assertEquals(
            "Ctrl+? must produce 0x7F (DEL) — alternative DEL byte",
            KeyResolution.Send(byteArrayOf(0x7F.toByte())),
            verdict,
        )
    }

    @Test
    fun test_newKeys_endToEnd_throughView_writeExpectedBytes() {
        // Integration-style: drive the same key events through the View
        // (not just KeyMapper) and assert the SSH channel sees the
        // expected bytes. This catches the "View layer is missing the
        // new key" class of bug — e.g. a future refactor that adds an
        // entry to KEY_MAP but forgets to add a parallel branch in
        // TerminalView.onKeyDown.
        val cases: List<Pair<KeyEvent, ByteArray>> = listOf(
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE) to byteArrayOf(0x1B.toByte()),
            keyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_TAB,
                KeyEvent.META_SHIFT_ON,
            ) to "\u001B[Z".toByteArray(Charsets.UTF_8),
            keyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_INSERT) to "\u001B[2~".toByteArray(Charsets.UTF_8),
            keyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_CIRCUMFLEX,
                KeyEvent.META_CTRL_ON,
            ) to byteArrayOf(0x1E.toByte()),
            keyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_UNDERSCORE,
                KeyEvent.META_CTRL_ON,
            ) to byteArrayOf(0x1F.toByte()),
            keyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_AT,
                KeyEvent.META_CTRL_ON,
            ) to byteArrayOf(0x00.toByte()),
            keyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_SLASH,
                KeyEvent.META_CTRL_ON,
            ) to byteArrayOf(0x7F.toByte()),
        )

        for ((ev, expectedBytes) in cases) {
            endpoint.bytesWritten() // discard any pre-existing writes
            val handled = view.onKeyDown(ev.keyCode, ev)
            assertTrue("keyCode=${ev.keyCode} meta=${ev.metaState} must be consumed", handled)
            val written = endpoint.bytesWritten()
            assertArrayEquals(
                "keyCode=${ev.keyCode} meta=${ev.metaState} wrote wrong bytes",
                expectedBytes,
                written,
            )
        }
    }
```

- [ ] **Step 2: 运行所有 KeyEventRoutingTest,看 10 个新 case 通过**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.KeyEventRoutingTest"
```

预期:42 个 case 全部 PASS(31 旧 + 1 元测试 + 10 新)。

如果有失败,**停下来**检查:
- 失败信息里 keyCode/meta — 对应 KEY_MAP entry 的 match 条件
- 顺序:新键的 entry 必须在 PRINTABLE 路径之前(否则走 `Ignore` 不会产生字节)

- [ ] **Step 3: 运行整个 terminal/ test 包**

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.*"
```

预期:全 PASS。

- [ ] **Step 4: 提交**

```bash
git add app/src/test/java/com/example/sshterminal/terminal/KeyEventRoutingTest.kt
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "test(terminal): pin 7 new vim/nano key bindings" -m "Adds end-to-end tests for ESC alone, Shift+Tab, KEYCODE_INSERT,
Ctrl+^/_/@/? — the 7 missing vim/nano key bindings called out in
the design. Includes an integration test that drives the events
through TerminalView.onKeyDown to catch any future regressions
in the View layer. All 42 KeyEventRoutingTest cases pass." -m "Ref: docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md §4.2"
```

---

## Task 6: 清理 + 最终验证

**Files:**
- Modify: `app/src/main/java/com/example/sshterminal/terminal/KeyMapper.kt`(更新类 kdoc,引用新表)

- [ ] **Step 1: 更新 `KeyMapper` 类顶部 kdoc**

把现有的 `KeyMapper` 类 kdoc(`/** Maps a [KeyEvent] to a [KeyResolution] ... */`)替换为:

```kotlin
/**
 * Maps a [KeyEvent] to a [KeyResolution] for the SSH terminal.
 *
 * Per `docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md`:
 *
 * Routing is data-driven: every behaviour lives as a row in the private
 * [KEY_MAP] list. [resolve] walks the list top-to-bottom and returns the
 * verdict from the first row whose `match` predicate fires. New keys are
 * added by appending an entry, not by editing a `when` block.
 *
 * Ordering of [KEY_MAP] is the contract — see the comment above the val
 * for the precedence list. Two invariants worth highlighting:
 *
 *  1. Ctrl+Shift+V (Paste) is the FIRST entry. It must beat the Ctrl+V
 *     printable-key short-circuit in [TerminalView.onKeyDown] so the user
 *     gets a paste, not a literal "V".
 *  2. IME language switch (Ctrl+Space / Shift+Space /
 *     KEYCODE_LANGUAGE_SWITCH) is the SECOND entry. It must NEVER reach
 *     the remote shell — that's the P0 bug from implementation_plan.md.
 *
 * Each entry carries structured per-program documentation (vim modes,
 * nano, bash readline) so a future maintainer can see "what does this
 * byte mean to the user?" without grepping markdown.
 *
 * Legacy [`toAnsiSequence`] wrapper is kept for older call sites and
 * collapses non-[KeyResolution.Send] verdicts to `null` — see its kdoc.
 */
object KeyMapper {
```

(把 `object KeyMapper {` 关键字前那段 `/** ... */` 整个替换;`object KeyMapper {` 关键字和它后面那行 `fun resolve(...)` 不动。)

- [ ] **Step 2: 编译 + 跑全套测试**

```bash
./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest
```

预期:
- `compileDebugKotlin` → `BUILD SUCCESSFUL`
- `testDebugUnitTest` → `BUILD SUCCESSFUL` + 100% pass

如果任何测试 fail,停下来 debug。`compileDebugKotlin` 失败的话查 kdoc 替换是不是破坏了缩进 / 括号。

- [ ] **Step 3: 检查 git 状态**

```bash
git status
```

预期:工作区 clean(没有未提交修改)。`docs/patches/` 下的文件应该仍然 untracked(它们是分支初始就存在的)。

- [ ] **Step 4: 提交 kdoc 更新**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/KeyMapper.kt
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "docs(terminal): update KeyMapper class kdoc to reference KEY_MAP" -m "Class-level kdoc now describes the data-driven routing table and
points readers to the design spec, instead of describing an
inline when-block that's no longer the source of truth." -m "Ref: docs/superpowers/specs/2026-06-29-vim-nano-keymapper-design.md"
```

---

## Self-Review

### 1. Spec coverage

| Spec section                       | Covered by task |
|-----------------------------------|-----------------|
| §2 Data structures (KeyMapDoc)    | Task 1          |
| §3.1 Routing order                | Task 3          |
| §3.2 New keys (7 entries)         | Task 3 (entries) + Task 5 (tests) |
| §3.3 Ctrl+Tab / Ctrl+Enter note   | Task 3 (note field on entries 7 & 8) |
| §3.4 ESC + IME interaction        | Task 5 (`test_escape_whileComposing_isPassedToIme`) |
| §4.1 Documentation per entry      | Task 3 (every entry has vim/nano/bash) |
| §4.2 Meta-test                    | Task 2 + Task 3 (Step 3) |
| §4.2 New test cases               | Task 5 (10 cases) |
| §5.1 File changes                 | All tasks       |
| §5.2 Migration steps              | Tasks 1→6       |
| §5.3 Risks (Ctrl+@ IME etc.)      | Acknowledged in spec; no task needed |
| §6 Acceptance criteria           | Verified in Task 6 + this self-review |

### 2. Placeholder scan

Searched the plan for: `TBD`, `TODO`, `FIXME`, `XXX`, `fill in`, `implement later`. None present. All code blocks contain complete, runnable content.

### 3. Type consistency

- `ProgramUsage(mode: String, effect: String)` — used consistently in all 19 entries
- `KeyMapEntry(description, match, verdict, vim, nano, bash, note)` — used consistently
- `KeyResolution.Send(byteArrayOf(0x1B.toByte()))` — used consistently for the new ESC test
- `KeyMapper.entriesForTest(): List<KeyMapEntry>` — defined in Task 3, used by meta-test in Task 2 and Task 3
- `KeyMapper.resolve(keyCode, event): KeyResolution` — public API preserved, body changed in Task 4
- `KeyMapper.toAnsiSequence(keyCode, event): ByteArray?` — public API preserved, body NOT changed

No mismatches.
