你是 OpenAI Codex,正在执行一个 Android Kotlin 项目的 Sprint 0 + Sprint 1 实现任务。仓库已经 git init 完毕。

# 必读(按顺序)
1. `./implementation_plan.md` — 完整技术设计,563 行,包含所有架构决策、模块划分、代码片段
2. `./test_plan.md` — 测试计划,Robolectric 单测 6 个用例必须全绿

# 任务范围 (Sprint 0 + Sprint 1,只做这两个)
## Sprint 0 — 基础设施(1天)
1. 建立 Android Studio Gradle 工程(Kotlin DSL,Kotlin 1.9+,AGP 8.x,minSdk 29,targetSdk 34)
2. 引入 `com.termux:terminal-emulator` 作为 Library module(从 Maven 拉或当成本地 module 都行,**不要**自研 ANSI 状态机)
3. 初始化 `TerminalView`(继承自 Termux 的 TerminalView,保留手势和选中)
4. 配置 JetBrainsMono 字体(Warp 风格深色主题,Material3)
5. 不要 SSH 真连,**Sprint 1 用 MockEchoSession 替代**

## Sprint 1 — IME 核心(1周,本次任务的真正重点)
严格按 `implementation_plan.md` 的"输入链路设计"章节实现:
1. `TerminalView.kt`:`onCheckIsTextEditor()`、`onCreateInputConnection()` 返回正确 EditorInfo(TYPE_CLASS_TEXT | NO_SUGGESTIONS | VARIATION_VISIBLE_PASSWORD,IME_ACTION_NONE | NO_FULLSCREEN | NO_EXTRACT_UI)
2. `TerminalInputConnection.kt`:完整 5 个方法 — `setComposingText` / `commitText` / `finishComposingText` / `deleteSurroundingText` / `setComposingRegion` / `setSelection`,以及 `sendKeyEvent`。每个方法的验收条件都在 implementation_plan.md 的"TerminalInputConnection 方法验收规格"小节里,**逐条满足**。
3. `KeyMapper.kt`:物理按键 → ANSI 转义序列映射(参照"KeyEvent 路由规则表")
4. `MockEchoSession.kt`:收到的字节原样回显到终端,作为 Sprint 1 的替代 SSH(满足 Sprint 1 第 5 条)
5. **Robolectric 单元测试**:`TerminalInputConnectionTest.kt`,覆盖 test_plan.md 第 1 节的全部 6 个用例(必须全绿)
6. 极简配置页 UI(Compose):主机/端口/用户名/密码或私钥
7. `AppPreferences.kt`:SharedPreferences 单主机存储
8. `KeyStoreManager.kt`:Android Keystore AES-256 私钥加密(实现见 implementation_plan.md "决策 3")
9. 私钥导入流程

# 硬约束(违反任一条都视为失败)
- **不要自研终端渲染/状态机** — 必须用 Termux terminal-emulator
- **不要修改 terminal-emulator 内部源码** — 黑盒使用
- **不要做 Sprint 2+ 的工作** — 不实现真 SSH 连接、SFTP、主机管理列表/分组、xterm 真机测试、Mosh
- **不要 commit Sprint 1 之外的代码** — Sprint 1 完成后停止,等用户确认
- **不要尝试运行 APK / 真机部署** — Sandbox 里跑不了,这步留给用户
- **不要引入未在 implementation_plan.md 中提到的库**(除非 Gradle/AGP 必要的)

# 验收红线(任一不过即视为未完成)
1. `./gradlew test` 全部绿(包括 6 个 Robolectric 用例)
2. `./gradlew assembleDebug` 成功生成 APK
3. `TerminalInputConnection.kt` 5 个方法的验收清单(在 implementation_plan.md 末尾的 Checklist)逐条对应实现
4. 没有自研 ANSI 状态机/ScreenBuffer
5. `git log` 至少包含:1) "init" 提交,2) Sprint 0 提交,3) Sprint 1 提交(分多个 commit 也行)

# 工作方式
- 用 `pty=true` 在 `/home/tao/ssh-term-QxuHWj` 下运行
- 每完成一个 Sprint,在该目录 commit 并 push 备注(本次没远端,只 commit)
- 遇到 ambiguity 或超出 scope 的决策,**用清晰的 [ASK_USER] 标记列出问题再继续**,不要自作主张
- 完成时在 stdout 打印:
  - Sprint 0/1 完成状态
  - `git log --oneline` 输出
  - `./gradlew test` 摘要
  - `./gradlew assembleDebug` 摘要
  - 任何 [ASK_USER] 项

# 环境变量(每次子 shell 必须先 source)
本机的 JDK / Android SDK 装好了但不在默认 PATH。在每个 shell 启动时执行:
```bash
export SDKMAN_DIR="$HOME/.sdkman"
source "$HOME/.sdkman/bin/sdkman-init.sh"        # 激活 JDK 25 (Temurin LTS)
export ANDROID_HOME=$HOME/.android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
export JAVA_HOME=$HOME/.sdkman/candidates/java/current
```
验证:`java -version` 应回 `openjdk 25`,`which sdkmanager` 应指向 `~/.android/sdk/cmdline-tools/latest/bin/sdkmanager`。

# GitHub 远端 —— 用户决定:本地完成,不 push
用户已确认:
- SSH 远端已配好
- 仓库**私有**(`private`)
- 本次任务**只 local commit**,不要 `git push`
- 远端 URL 用户后续自行提供

执行规则:
- **不要执行 `git push`**
- **不要执行 `git remote add`**(除非用户后续告知 URL)
- 每个 Sprint 完成后 `git log --oneline` 输出给用户即可

# 开始
现在就开始。先读 implementation_plan.md 全文,然后按 Sprint 0 → Sprint 1 顺序执行。
启动第一件事:执行上面的"环境变量"块 + 打印 `java -version` / `sdkmanager --version`,然后 [ASK_USER] GitHub 用户名。