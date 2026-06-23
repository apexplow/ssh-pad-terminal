# Sprint 2 — 收尾(Claude 上次 80 轮用尽,这次只 fix 测试 + commit)

## ⚠️ 必读:上次哪里出错了

上一轮 Claude 干到了 `78d8576 feat(ssh): wire SSHJ 0.38.0 + BouncyCastle provider + auth + UI`,
但 80 turn agentic loop 用尽,**没跑测试 / 没 commit 后续 7 个改动文件 / 没删 PROMPT**。
最后挂在 `./gradlew testDebugUnitTest` —— 测试 31 分钟没出结果(很可能是某个测试在尝试连真 SSH,
**违反 prompt 第 4 条红线"不要连真 SSH"**)。

## 当前 git 状态(commit 后没 push)

```
HEAD: 78d8576 feat(ssh): wire SSHJ 0.38.0 + BouncyCastle provider + auth + UI
Modified but uncommitted:
  M app/src/main/java/com/example/sshterminal/ssh/ChannelTransport.kt
  M app/src/main/java/com/example/sshterminal/ssh/SshClient.kt
  M app/src/main/java/com/example/sshterminal/ssh/auth/PasswordAuthProvider.kt
  M app/src/main/java/com/example/sshterminal/ssh/auth/PublicKeyAuthProvider.kt
  M app/src/main/java/com/example/sshterminal/terminal/TerminalView.kt
  M app/src/main/java/com/example/sshterminal/ui/SshTermApp.kt
  M app/src/main/java/com/example/sshterminal/ui/TerminalPane.kt
Untracked:
  ?? app/src/test/java/com/example/sshterminal/ssh/SshConfigTest.kt
  ?? app/src/test/java/com/example/sshterminal/ssh/SshSessionWriteTest.kt
  ?? app/src/test/java/com/example/sshterminal/ssh/auth/PublicKeyAuthProviderTest.kt
  ?? PROMPT_SPRINT_2.md
```

## 你的任务(本次,极简 scope)

### Step 1(1 turn):读 3 个测试文件
- `app/src/test/java/com/example/sshterminal/ssh/SshConfigTest.kt`
- `app/src/test/java/com/example/sshterminal/ssh/SshSessionWriteTest.kt`
- `app/src/test/java/com/example/sshterminal/ssh/auth/PublicKeyAuthProviderTest.kt`

**判断 3 件事**:
1. 任何测试有没有调用 `SshClient.connect()` / `SshSession.openShell()` / 类似"会真连 SSH server"的代码? **有的话必须删掉或 mock**。
2. 任何测试有没有用 `Thread.sleep()` / `countDownLatch.await()` 超过 5 秒? **有的话改短**。
3. 任何测试有没有依赖 BouncyCastle 但没显式 `@Config(sdk = [33])`?

### Step 2(2-3 turn):修代码直到测试编译过
- **如果测试需要真 SSH → 改成 Robolectric mock**。
- 跑 `./gradlew :app:compileDebugUnitTestKotlin` 看是否编译过,**不跑测试本身**。
- 修编译错误,最多 5 turn。

### Step 3(1 turn):跑测试
```bash
cd /home/tao/code/ssh-pad-terminal
export SDKMAN_DIR="$HOME/.sdkman" && source "$HOME/.sdkman/bin/sdkman-init.sh"
export ANDROID_HOME=$HOME/.android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
export JAVA_HOME=$HOME/.sdkman/candidates/java/current
./gradlew :app:testDebugUnitTest --console=plain 2>&1
```

**这次不要用 `| tail`!直接看原始输出。** 输出可能很大,**让它缓冲**。
如果测试 5 分钟内没出结果 → 假设 hang,直接 kill gradle 进程(`pkill -9 -f "GradleDaemon"`),
接受测试已超时的事实,**进 Step 4**(不要继续轮询!).

### Step 4(2-3 turn):commit + assembleDebug
- 假设测试至少编译过(可能没全跑过),commit 7 个 M + 3 个 ??(测试文件)
- 用合理的 commit message 拆分(我建议 2-3 个 commit):
  - `feat(ssh): implement ChannelTransport + SshClient + auth providers`
  - `feat(ui): wire connect/disconnect + SIGWINCH`
  - `test(ssh): add Robolectric tests for SshConfig + SshSession + PublicKeyAuth`
  - `chore: drop transient Claude prompt file`
- 跑 `./gradlew :app:assembleDebug` 出 APK
- 不 push 不 merge

### Step 5(1 turn):报告
打印:
- git log --oneline (5-6 个新 commit)
- 测试 XML 摘要(pass/fail 数量,即使没跑全)
- APK 大小 + 路径
- 任何 [SPRINT_2_5_NOTE] / [SSH_ANDROID_PITFALL] 发现

## 硬约束(违反任一 = 任务失败)

- **不要写新功能** —— 只是修测试 + commit 已有改动
- **不要连真 SSH** —— 测试里任何 connect() 必须 mock
- **不要重写已有文件** —— 只在必要小改
- **不要 git push**
- **不要 git merge**
- **不要用 `| tail` / `TaskOutput block=true`** —— 这两个上次害你浪费 30+ turn
- **不要反复 poll 测试结果** —— 5 分钟没出就 kill,直接进 Step 4
- **PROMPT_SPRINT_2.md 必须删除并 commit**

## Turn 预算

- Step 1: 1 turn
- Step 2: ≤ 5 turn
- Step 3: 1 turn
- Step 4: ≤ 3 turn
- Step 5: 1 turn
- **总预算: 15 turn 内必须完成**

如果发现 5 turn 不够 fix 编译,直接 commit 当前能 commit 的 + 写 [DEFERRED] note,**别再纠结**。

## 环境

```bash
export SDKMAN_DIR="$HOME/.sdkman"
source "$HOME/.sdkman/bin/sdkman-init.sh"
export ANDROID_HOME=$HOME/.android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
export JAVA_HOME=$HOME/.sdkman/candidates/java/current
cd /home/tao/code/ssh-pad-terminal
```

## 开始

启动后**第一件事**:
1. `git log --oneline -5`(确认在 78d8576 之后)
2. 读 3 个测试文件全文
3. 按 Step 1-5 顺序执行
4. **预算意识**:每个 turn 都要问自己"这个 turn 是不是必要"

不要再浪费时间在 spec 解释上了 —— 这份 prompt 是完整的。