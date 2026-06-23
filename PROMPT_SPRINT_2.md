# Sprint 2 — 真 SSH 连接(SSHJ)

## 上下文
- 项目:09ssh(Android 平板 SSH 终端),本地仓库 `/home/tao/code/ssh-pad-terminal`
- 当前分支:`feature/sprint-2-real-ssh`(从 main 切出,HEAD `9e1eb80`)
- 完整 spec:`./implementation_plan.md`(必读)+ `./test_plan.md`
- 测试规划文档:`./commercialization_analysis.md`(可选,讲商业化)

## 当前代码状况
- `app/src/main/java/com/example/sshterminal/terminal/TerminalEndpoint.kt` — 5 行 `fun interface { fun write(bytes) }`,等着被 SSHJ 实现
- `app/src/main/java/com/example/sshterminal/terminal/MockEchoSession.kt` — Sprint 1 mock,Sprint 2 可以保留作 fallback 测试
- `app/src/main/java/com/example/sshterminal/data/prefs/AppPreferences.kt` — 已有 host/port/username/password/privateKeyName,**Sprint 1.5 已加密 password**
- `app/src/main/java/com/example/sshterminal/data/crypto/KeyStoreManager.kt` — 已 encrypt/decrypt
- 没有任何 SSHJ 依赖(spec 里说用 0.38+)
- 没有任何 `ssh/` 目录

## 任务清单

### 1. SSHJ 接入
- `app/build.gradle.kts` 加 `implementation("com.hierynomus:sshj:0.38.0")` + BouncyCastle
- BouncyCastle 必须注册为 Security provider(spec 明文要求)
- 解决 Android 上 SSHJ 的已知兼容问题(已知坑:BouncyCastle 在 Android 9+ 已自带,但 PKCS8 key loading 仍需 bcprov-jdk15on)

### 2. 数据模型
新建 `app/src/main/java/com/example/sshterminal/ssh/`:
- `SshClient.kt` — `connect(host, port, username, auth): SshSession`,withCatching 返回 Result
- `SshSession.kt` — 实现 `TerminalEndpoint`(write bytes)+ 加 read 协程(从 SSH 读 bytes → 喂给 Termux emulator)+ PTY 分配 + SIGWINCH
- `auth/Auth.kt` — sealed class `PasswordAuth(val password: String)` / `PublicKeyAuth(val privateKeyPath: String)`
- `auth/PasswordAuthProvider.kt` — 实现
- `auth/PublicKeyAuthProvider.kt` — 加载 PEM 文件,Ed25519/RSA 都要支持
- `SshConfig.kt` — 集中常量(默认 port 22、xterm-256color、TERM、连接超时)

### 3. PTY 分配
- `SshSession.openShell()` 时调 `session.allocateDefaultPTY()`(SSHJ API)
- terminal type = `xterm-256color`,dims = 80x24 初始,SIGWINCH 时 resize

### 4. 协程架构
按 spec §"终端数据流":
```
IO coroutine:
  while (connected) {
    bytes = sshChannel.readBytes()
    emulator.process(bytes)        // Termux terminal-emulator
    send RefreshSignal
  }

UI coroutine:
  while (true) {
    receive RefreshSignal
    postInvalidateOnAnimation()
  }
```
**注意**:不修改 terminal-emulator 黑盒(架构约束硬规则)

### 5. SIGWINCH
- `TerminalView` 大小变化时 → emit cols/rows 到 session.resizePTY(cols, rows)
- 用 `OnGlobalLayoutListener` 或 `View.OnLayoutChangeListener`

### 6. UI 接线
- `SshTermApp` 加 connect/disconnect 按钮
- 第一次点击 Connect → `SshClient.connect(...)`,成功后 `bindEndpoint(session)` + 启动 IO 协程
- Disconnect → 关闭 SSH、停协程、`bindEndpoint(MockEchoSession())` 退回
- **状态显示**:顶部一个 Text("Connected to user@host:port" / "Disconnected" / "Connecting..." / "Error: xxx")
- **错误处理**:Result.fold onError → snackbar + 退回 mock

### 7. 私钥读取
- 用 `KeyStoreManager.decrypt()` 不行(那是加密用的,私钥自己就是密钥)
- 直接读 `filesDir/keys/<privateKeyName>`(明文,从 Sprint 1.5 的 SAF 导入)
- SSHJ 的 `KeyPairUtils.loadKeyPair(...)` 加载 PEM

### 8. 测试(不能全跑真 SSH,但要能 mock)
- `SshConfigTest.kt` — 几个常量 + 默认值
- `PublicKeyAuthProviderTest.kt` — mock 一个 PEM 文件,断言能 load 成 KeyPair(用 BouncyCastle 的 Ed25519/RSA test vectors,**不要连真 SSH**)
- **新增契约测试**:`SshSessionWriteTest` —— mock SSHJ 的 `Channel` 子类,验证 write 出去的 bytes 是对的 + close 流程
- **不强求** Robolectric 跑真 SSH(本地 Android 模拟器跑 sshd 太重,留给 Sprint 2.5)

### 9. 验证
- `./gradlew :app:testDebugUnitTest` —— 全绿(原 29 + 新增 4-6)
- `./gradlew :app:assembleDebug` —— 出 APK
- **手工验证**(本机会话外做):
  ```
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb shell am start -n com.example.sshterminal/.MainActivity
  # 输入 host=192.168.x.x, port=22, username=..., password=...
  # 点 Connect → 应该看到 shell 提示符 → 输入 ls → 看到输出
  ```
  报告本地是否成功,**不要尝试**在本机启 sshd(Android 模拟器无 sshd)。

## 硬约束(违反任一 = PR 关)

- **不要改 terminal-emulator 内部**
- **不要改 TerminalInputConnection / TerminalView / KeyMapper**(Sprint 1.5 的 IME 链路已稳定)
- **不要做 SFTP / 主机列表 / Mosh**(Sprint 3+ 范围)
- **不要 push** —— 用户没要求
- **不要 merge** —— 用户自己合
- **不要重写 MockEchoSession** —— 保留作 fallback
- **不要新增 UI 库 / 导航库 / DI 框架** —— spec 没列
- **`/tmp/hermes-results` 等临时目录不要 commit**

## 验收红线

1. `./gradlew :app:testDebugUnitTest` **全绿**(原 29 + 至少 4 新增 = 33+)
2. `./gradlew :app:assembleDebug` 成功
3. **至少 2 个新 git commit**(建议:feat(ssh): SSHJ + auth / feat(ui): connect/disconnect wiring)
4. 没有任何"连真 SSH"的尝试留在代码里(只能 mock)
5. BouncyCastle provider 显式注册(不要靠 Android 自带)

## 工作方式

启动后先 ls / git status 探查,然后:
1. 读 implementation_plan.md §"SSHJ 在 Android 上的正确配置"(line ~496)和 §"模块划分与边界"
2. 读现有 TerminalEndpoint.kt + MockEchoSession.kt + AppPreferences.kt + KeyStoreManager.kt 理解现状
3. 按任务清单 1→9 顺序执行
4. 每完成一段 commit 一次,commit message 写清做了什么 + 为什么
5. 完成时输出:
   - git log --oneline
   - ./gradlew :app:testDebugUnitTest 摘要
   - ./gradlew :app:assembleDebug 摘要
   - 任何超出 scope 的发现(用 [SPRINT_2_5_NOTE] 标记)
   - 任何识别的 SSHJ Android 已知坑(用 [SSH_ANDROID_PITFALL] 标记)

## 环境

每个 shell 必 source:
```bash
export SDKMAN_DIR="$HOME/.sdkman"
source "$HOME/.sdkman/bin/sdkman-init.sh"
export ANDROID_HOME=$HOME/.android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
export JAVA_HOME=$HOME/.sdkman/candidates/java/current
```
Gradle wrapper 自带 JDK 17。

## 开始

启动第一件事:跑 git status + git log + ls app/src,然后按任务清单 1→9 顺序执行。
如果有任何歧义,在 commit message 里写明选择和理由,不要中途停下来问。