# HanTerm — Icon Candidates

Product: **HanTerm** (`com.apexplow.hanterm`) — Android 平板原生 SSH 客户端。
核心差异化:让中文拼音 IME 在远程 SSH 会话里像本地输入一样工作。

Design language: 深色终端底 (`#0B0F14`→`#141B24`) + 终端绿 `#3FE07C` 主色 + 中文红 `#F2555A` 点缀。
主符号统一围绕 shell prompt `>_` 与汉字 `中`(呼应"中文 IME + 终端"这个卖点)。

> 生成方式:本机无 QM Icon Studio CLI,按 skill 规则输出**可编辑 SVG 源文件**,并用 `rsvg-convert` 渲染 PNG 预览。
> `contact-sheet` 里的圆角只是预览 mask;`option-*.svg` 源文件本身是严格正方形,圆角/各平台格式留到最终导出阶段。

## Candidates

| # | 方向 | 说明 |
|---|---|---|
| 01 | `>_` classic prompt | 最纯粹的终端提示符 + 光标块。安全、通用,32px 极清晰。 |
| 02 | `>` 中 | 绿色 prompt 直接"说中文"。**最能表达本项目差异化**。 |
| 03 | terminal window | 带红/黄/绿三点的窗口 + prompt。识别度高但 32px 偏碎。 |
| 04 | 中 + caret 括号 | 汉字为主角,终端括号包裹。CJK 属性最强。 |
| 05 | key + `>` | SSH 密钥环 + chevron,强调"安全 shell"。 |
| 06 | chip 中 | 圆角终端芯片,绿 prompt 喂给红色 中。 |
| 07 | minimal caret | 超粗 chevron + 红色闪烁光标条。极简、放大好看。 |
| 08 | padlock `>_` | 挂锁包住 `>_`,secure shell 直给。 |
| 09 | tablet | 横屏平板轮廓 + prompt,强调"平板"形态。 |
| 10 | `[ \| ]` bracket | 方括号 + 闪烁光标 monogram,抽象、品牌感强。 |

## 推荐(2–3 个方向)

1. **02 `> 中`** — 一眼说清"能在终端里打中文",与其它 SSH app 图标区分度最高。
2. **07 minimal caret** — 最耐缩放、最像"工具"气质,favicon/app 图标都稳。
3. **01 classic `>_`** — 最保守可靠的兜底,任何尺寸都不糊。

## 已选

**02 `> 中`** — 已导出到 Android `mipmap` + adaptive icon,并写入 `AndroidManifest` 的 `android:icon` / `android:roundIcon`。
源文件与预览见 `design/icon-options/selected/`。
