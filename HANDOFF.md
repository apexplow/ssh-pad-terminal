# Handoff — Codex 中途挂了,Claude Code 接手

> **DEPRECATED (Issue #58 / 2026-07-27)** — 本文件是 2026-06-22 Sprint 1 启动
> 当天的 Codex → Claude Code 工作交接记录,内容涉及**已不存在**的临时工作目录
> (`/home/tao/ssh-term-QxuHWj`)与**旧包名** `09ssh/`。仓库早已迁出该目录,
> 包名现在是 `com.taosun.hanterm`。**只作为决策历史阅读,不要按本文件指导
> 任何新工作**。当前实现请看 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
> 与 [`CLAUDE.md`](CLAUDE.md)。

## 背景
- 项目:Android 平板 SSH 终端(`09ssh/`)
- 用户:**孙涛**,Feishu「孙涛pad ssh项目」群
- 工作目录:`/home/tao/ssh-term-QxuHWj`(临时,git 已 init)
- Spec 在仓库内:`./implementation_plan.md`(563 行)+ `./test_plan.md`

## Codex 已完成(本仓库 HEAD ~2 commit 之后)
1. ✅ Gradle 工程:Gradle 8.9 + JDK 17 + AGP 8.7.3 + Kotlin 1.9.24 + JitPack 源
2. ✅ 极简 Compose 配置页骨架(`ui/ConfigScreen.kt` / `HanTermApp.kt` / `TerminalPane.kt`)
3. ✅ Theme(Color/Theme/Type.kt)
4. ✅ 核心 IME 链路代码:
   - `terminal/TerminalView.kt` (76 行) — `onCheckIsTextEditor` / `onCreateInputConnection` / `onKeyDown` 双链路分离
   - `terminal/TerminalInputConnection.kt` (63 行) — 5 个核心方法 + `sendKeyEvent`
   - `terminal/KeyMapper.kt` (55 行) — Ctrl/Alt/方向键/F1-F12 ANSI
   - `terminal/TerminalComposingView.kt` + `terminal/TerminalEndpoint.kt` + `MockEchoSession.kt`(辅助)
5. ❌ **没写测试** —— Robolectric 6 个用例零个落地,验收红线 1 不过
6. ❌ **没写 KeyStoreManager / AppPreferences** — Sprint 1 第 7、8、9 项缺
7. ❌ **没跑 ./gradlew test / assembleDebug** — Codex 死前正在写 settings.gradle.kts
8. ❌ **0 个新 commit** — 死时来不及 commit

## Claude Code 的任务

### 范围(只做这些)
1. **补齐 Robolectric 单元测试**:`app/src/test/java/com/example/sshterminal/terminal/TerminalInputConnectionTest.kt`,覆盖 test_plan.md 第 1 节的全部 6 个用例
2. **补齐 `KeyStoreManager.kt`**(对照 implementation_plan.md "决策 3" 的代码片段,AES-256 GCM)
3. **补齐 `AppPreferences.kt`**(SharedPreferences 单主机存储)
4. **代码 review + 修 Codex 留下的潜在 bug**(待 Claude 自己识别)
5. **跑 `./gradlew test`** —— 必须全绿
6. **跑 `./gradlew assembleDebug`** —— 必须成功
7. **git commit** —— 分合理的 commit,不带 push(用户没远端 URL)

### 硬约束(违反任一即视为失败)
- **不要重写已有代码** —— TerminalInputConnection / TerminalView / KeyMapper 这三个文件骨架基本对,在它的基础上修;只在必要小改,不要替换整个文件
- **不要修改 terminal-emulator 内部** —— 黑盒使用
- **不要做 Sprint 2+ 的工作** —— 不实现真 SSH、SFTP、主机列表、Mosh
- **不要 git push** —— 用户没提供远端
- **不要装额外工具**(Gradle wrapper 自带,Android SDK 装好)

### 验收红线
1. `./gradlew test` 全绿(6 个 Robolectric 用例必须跑通)
2. `./gradlew assembleDebug` 成功
3. `KeyStoreManager.kt` 和 `AppPreferences.kt` 存在且实现完整
4. 至少有 3 个新 git commit(Sprint 0 收尾 / Sprint 1 IME 测试 / Sprint 1 KeyStore+Prefs)

### 环境
本机 JDK 25 / Android SDK platform-34 / build-tools 34.0.0 / adb 都装好,在 `~/.sdkman/candidates/java/current` 和 `~/.android/sdk`,**但非交互 shell 默认不 source**。每个 shell 调用前必须:
```bash
export SDKMAN_DIR="$HOME/.sdkman"
source "$HOME/.sdkman/bin/sdkman-init.sh"
export ANDROID_HOME=$HOME/.android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
export JAVA_HOME=$HOME/.sdkman/candidates/java/current
```
不过 Gradle wrapper 自带 JDK 17(`./gradle/jdks/jdk-17.0.11+9/`),build 用 wrapper 的 JDK 就够。

### 完成时打印
- Sprint 0/1 收尾状态
- `git log --oneline`
- `./gradlew test` 摘要(pass/fail 数)
- `./gradlew assembleDebug` 摘要
- 任何识别到的 Codex 留下的 bug 及修复
- 任何超出 scope 的发现(用 [NOTED] 标记)