package com.apexplow.hanterm.terminal

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
