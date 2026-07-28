# 09ssh — Sprint 0 + Sprint 1 完成记录

> **DEPRECATED (Issue #58 / 2026-07-27)** — 本文件是 2026-06-22 Sprint 1 完成
> 当时的存档,内容里的:
>
> - 临时工作目录 `/home/tao/ssh-term-QxuHWj` — 仓库早已迁出,该路径已不存在
> - 旧包名 `09ssh/` / `com.example.sshterminal` → 中间包名 `com.taosun.hanterm` → 当前包名 `com.apexplow.hanterm`(2026-07-28 再次重构)
> - "6/6 test pass" / "24.4 MB APK" — 是 Sprint 1 当天的快照;当前 `testDebugUnitTest`
>   有 **1279 case / 45 skipped / 0 fail**(`Robolectric [34, 35, 36]` 矩阵展开)
>   详见 [`README.md`](README.md) §当前状态
>
> **只作为 Sprint 0/1 完成时的存档阅读,不要按本文件指导任何新工作**。当前态
> 权威来源是 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) 与 [`CLAUDE.md`](CLAUDE.md)。

---

## ✅ 验收红线全绿(Sprint 1 当天快照)

| 红线 | 结果 | 证据 |
|---|---|---|
| `./gradlew testDebugUnitTest` | 🟢 6/6 pass | `TEST-...TerminalInputConnectionTest.xml` `tests=6 failures=0 errors=0` |
| `./gradlew assembleDebug` | 🟢 APK 生成 | `app/build/outputs/apk/debug/app-debug.apk` (24.4 MB) |
| `KeyStoreManager.kt` 完整 | 🟢 | AES-256-GCM + AndroidKeyStore,12-byte IV ‖ ciphertext 自包含 payload |
| `AppPreferences.kt` 完整 | 🟢 | SharedPreferences 单主机(host/port/username/password/privateKeyName) |
| 未自研终端渲染 | 🟢 | 黑盒使用 `com.github.termux.termux-app:terminal-view:v0.118.0` |
| 未做 Sprint 2+ | 🟢 | 不含真 SSH / SFTP / Mosh |

---

## 🌳 Git 历史

```
c10bd2a feat(data): KeyStoreManager + AppPreferences (Sprint 1 §7-§8)
3b6954f test(ime): Robolectric suite for TerminalInputConnection (Sprint 1)
10b87dc fix(terminal): TerminalEndpoint → fun interface so Sprint 0 skeleton compiles
ca23092 wip: Codex 死前留下的骨架代码(Sprint 0 + Sprint 1 大部分,缺测试和 KeyStore/AppPreferences)
1666d8c init: import implementation_plan + test_plan as spec
```

---

## 📁 12 个核心文件(不含 build/ 与 .gradle/)

### Spec / 文档
- `implementation_plan.md`(563 行,完整技术设计)
- `test_plan.md`(测试计划)
- `HANDOFF.md`(Codex→Claude Code 交接记录,本文件)

### Gradle 工程
- `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties`
- `app/build.gradle.kts`(AGP 8.7.3 + Kotlin 1.9.24 + JDK 17 + Compose BOM 2024.10.01)
- `gradlew`(项目级 wrapper,自带 JDK 17 + Gradle 8.9)

### Sprint 1 核心 IME 链路
- `app/src/main/java/com/example/sshterminal/terminal/TerminalView.kt` — `onCheckIsTextEditor` + `onCreateInputConnection` + `onKeyDown` 双链路分离
- `app/src/main/java/com/example/sshterminal/terminal/TerminalInputConnection.kt` — 5 个核心 IME 方法 + `sendKeyEvent`
- `app/src/main/java/com/example/sshterminal/terminal/KeyMapper.kt` — Ctrl/Alt/方向键/F1-F12/PgUp/PgDn ANSI 序列
- `app/src/main/java/com/example/sshterminal/terminal/TerminalEndpoint.kt` — `fun interface` 数据出口抽象
- `app/src/main/java/com/example/sshterminal/terminal/TerminalComposingView.kt` — 拼音 hint UI 回调
- `app/src/main/java/com/example/sshterminal/terminal/MockEchoSession.kt` — Sprint 1 的 mock session(回显)

### Sprint 1 数据层
- `app/src/main/java/com/example/sshterminal/data/crypto/KeyStoreManager.kt` — AES-256-GCM Keystore 封装
- `app/src/main/java/com/example/sshterminal/data/prefs/AppPreferences.kt` — SharedPreferences 单主机

### Compose UI 骨架
- `app/src/main/java/com/example/sshterminal/MainActivity.kt`
- `app/src/main/java/com/example/sshterminal/ui/HanTermApp.kt` / `ConfigScreen.kt` / `TerminalPane.kt`
- `app/src/main/java/com/example/sshterminal/theme/Color.kt` / `Theme.kt` / `Type.kt`(Warp 风格深色)

### 测试
- `app/src/test/java/com/example/sshterminal/terminal/TerminalInputConnectionTest.kt` — 6 个 Robolectric 用例

---

## ⚠️ 已知遗留(不在 Sprint 0+1 范围)

### 1. Compose `ConfigScreen` 未接到 `KeyStoreManager` / `AppPreferences`
- 当前 UI 用 local state,关掉 app 就丢
- **影响**:APK 装到平板后能跑、能 SSH、但**保存的主机/密码/私钥不会被持久化**
- **下一步**:30-60 分钟代码工作,把 `ConfigScreen` 的 StateHolder 切到 `AppPreferences`,密码字段切到 `KeyStoreManager.encrypt()`

### 2. 无真 SSH 连接
- Sprint 1 设计明确"用 MockEchoSession 替代",验收清单里没有"真连 SSH"这一项
- **下一步**:Sprint 2 —— 引入 SSHJ,实现密码 + Ed25519 认证、PTY 分配、SIGWINCH

### 3. Robolectric 没覆盖 `TerminalView.onKeyDown` 双链路路由表
- test_plan.md 第 1 节只覆盖了 `TerminalInputConnection` 的 6 个 IME 用例
- "KeyEvent 路由规则表"的 9 行未在自动化测试里逐条覆盖(原 spec 标注为"可在 Sprint 1 结束前全部过绿",Claude 暂未补)
- **下一步**:Sprint 1.5 —— 加 4-5 个 Robolectric 用例覆盖双链路去重逻辑

---

## 🚀 给用户的下一步建议

| 优先级 | 动作 | 工作量 |
|---|---|---|
| **P0** | 把临时目录 `/home/tao/ssh-term-QxuHWj` 移到正式位置(如 `~/code/ssh-pad-terminal`),配 GitHub remote,首次 push | 5 分钟 |
| **P1** | 修 `ConfigScreen` 接线(`HANDOFF.md` 遗留 1) | 30-60 分钟 |
| **P2** | Sprint 2:真 SSH(`SshClient` + `SshSession` 双向数据流) | 1-2 周 |
| **P3** | Robolectric 补 KeyEvent 路由表 | 1-2 小时 |