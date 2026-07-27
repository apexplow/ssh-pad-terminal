# Contributing to HanTerm

> **AI-agent 操作手册**: [`CLAUDE.md`](CLAUDE.md) — Hard constraints + Routing
> invariants + 测试规范,**所有贡献者(包括 AI agent)必须遵守**。本文只描述
> 仓库协作流程(如何跑测试 / 提 PR / 写 commit message);遇到架构/设计冲突
> 时 `CLAUDE.md` 与 `docs/ARCHITECTURE.md` 优先。
>
> **当前态参考**: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — 模块边界、
> keepalive 策略、连接生命周期、决策索引都在这里。
>
> **历史设计稿**: [`implementation_plan.md`](implementation_plan.md)(顶部有
> deprecation banner)— 新贡献者不应按其指导实现,只用作决策背景阅读。

---

## 1. 行为准则

- **友善但技术严格**。SSH 客户端的安全相关 bug(凭据泄漏、host-key 绕过、
  log classification 退化)会被当作 P0 处理,请直接 @maintainer。
- **不要塞进超出 Sprint 计划的能力**。Multi-host / SFTP / Mosh / 真彩终端 /
  port forwarding 都是 README §"路线图" 显式 deferred 的能力,
  `CLAUDE.md` Hard constraints 第 4 条再次重申 — 等 maintainer 显式 ask。
- **不要引入新依赖**。项目刻意保持小依赖面(SSHJ + BouncyCastle + Termux + Compose +
  Lifecycle);任何新 Gradle 依赖都需要先在 Issue 里讨论。

## 2. 提 PR 之前

```bash
# 1. 跑完整测试(命中 Gradle 缓存后 < 30s)
./gradlew :app:testDebugUnitTest

# 2. 出 debug APK(确认 R8 / 资源 shrinker 没把 SSHJ / BC / Termux 的反射类误删)
./gradlew :app:assembleDebug

# 3. 跑 release 构建(只在改了 proguard 相关代码时)
./gradlew :app:assembleRelease
```

CI(`.github/workflows/ci.yml`)会自动跑这三步并上传 HTML 测试报告。
PR 必须:

- 保持 `testDebugUnitTest` 全绿(0 failure / 0 error)。
- 不引入新的 `@Ignore` 除非有 Issue 链接解释为什么不能修。
- 不在 commit message / PR 描述中包含真实 host / 用户名 / 端口 / 凭据。

## 3. Commit message 规范

Conventional Commits:

| Prefix | 用例 |
|---|---|
| `feat(terminal): …` | IME 链路 / 渲染 |
| `feat(ssh): …` | SSHJ / keepalive / transport |
| `feat(ui): …` | Compose / HanTermApp / ViewModel |
| `feat(build): …` | Gradle / 依赖 / packaging |
| `fix(terminal): …` | IME / 渲染 bug |
| `fix(ssh): …` | 连接 / auth bug |
| `fix(logging): …` | AppLog / LogPolicy |
| `fix(crash): …` | CrashHandler / 异常恢复 |
| `test(terminal): …` / `test(ssh): …` | 单纯测试改动 |
| `refactor(terminal): …` / `refactor(ssh): …` | 重构,无行为变化 |
| `docs: …` | README / 注释 / 文档 |
| `ci: …` | GitHub Actions / Gradle wrapper |
| `chore(deps): …` / `chore: …` | 杂项(临时文件清理等) |

commit 末尾加 `Co-Authored-By: Claude <noreply@anthropic.com>` 当 AI agent 参与
编写时(`git commit` 会自动加)。

## 4. 必读约束(摘自 `CLAUDE.md` Hard constraints)

完整版见 `CLAUDE.md`,这里列最容易踩的几个:

1. **不要改 `com.termux:terminal-emulator` / `terminal-view` 内部**。
   这是 JitPack 黑盒,需要先开 Issue 讨论。
2. **不要自写 ANSI 状态机 / ScreenBuffer / 终端渲染器**。渲染走 Termux emulator。
3. **不要写需要真实 SSH server 的测试**。CLAUDE.md 明文禁止 — 所有 SSH 测试
   用 `FakeTransport` / mocks / Robolectric。
4. **`SshClient` 必须用 applicationContext**。`init {}` 守卫会抛
   `IllegalStateException`,不要绕过。
5. **`SshConfig.SO_TIMEOUT_MS` 单位是毫秒**。sshj 0.40 直接转发给
   `Socket.setSoTimeout`,**不要** 除以 1000(早期版本有 `/1000` bug)。
6. **BouncyCastle 必须显式注册**(`BouncyCastleBootstrap.ensureRegistered()`),
   不能依赖 Android 系统 BC。版本随 SSHJ 0.40 透传到 1.80.x。
7. **私有密钥文件走 SAF 导入,加密后存 `filesDir/keys/<name>.pem.enc`**。
   不要走 `cacheDir/` 或外部存储中转明文。
8. **不要把 host/port/user 写到 `AppLog.e(...)`** — `AppLog.e` 默认
   `LogClassification.Error` → File,会写进 `filesDir/app.log`。敏感字符串必须
   显式传 `classification = LogClassification.ConnectionMetadata`(Issue #13 + #54)。
9. **`LogClassification.Input` / `CredentialMetadata` / `ConnectionMetadata`**
   在 release 构建 Drop,在 debug 构建 Logcat-only。永远不要给这三类打
   `File` 或 `Error`。
10. **`isHandledTransportAbort` 的 "Software caused connection abort" 是 reader
    线程的正常 teardown 噪音**,不算崩溃。不要 patch 掉 `MainActivity.kt:isHandledTransportAbort`。

## 5. 提 PR 的流程

1. Fork → 新分支(`sprint/<scope>` 或 `fix/<scope>` 命名约定见 `CLAUDE.md` §"Git workflow")。
2. commit → push。
3. `gh pr create` — PR 描述里 link Issue 号,列出改动文件 + 关键测试。
4. CI 跑完所有 job 后请 maintainer review。Maintainer 负责 `gh pr merge` 与
   merge 后的分支清理。

## 6. 不在贡献范围

以下能力**不在当前仓库的贡献范围**,请勿主动实现:

- Multi-host 列表 / 主机分组 / 编辑 UI
- SFTP / port forwarding / ProxyJump(`zsed` / `sz` → Downloads 已 ship,跟 SFTP 无关)
- Mosh
- TrueColor 终端类型(目前 `xterm-256color`)
- xterm 鼠标协议
- OpenSSH 7.x / 8.x / 9.x 兼容矩阵、dropbear / busybox sshd 验证
- 横屏平板布局优化(Module 15 已交付基础双栏,但精细化是后续工作)
- KeystoreManager 在 Robolectric 下测试(目前只在真机测)
- i18n / a11y(已有 Issue #42,在做)

## 7. 反馈与沟通

- 仓库 Issue 是首要渠道。
- 紧急的安全问题请按 [`SECURITY.md`](SECURITY.md) 的流程,**不要**在公开
  Issue 里贴 PoC。