# Security Policy

HanTerm(`com.apexplow.hanterm`)是一个 Android 平板 SSH 客户端,围绕"让中文拼音 IME
在远程 SSH shell 里正常工作"这个差异化价值设计。本文件说明如何报告安全漏洞。

## Supported versions

| 版本 | 支持 |
|---|---|
| `main` 分支 HEAD | ✅ 接受安全报告 |
| 最近 4 个 release tag | ✅ 接受安全报告 |
| 更早的 tag / 任意 commit 之前的版本 | ❌ 请先升级 |

## Reporting a vulnerability

**不要在公开 Issue 里发安全漏洞细节**。PoC / 复现命令 / 堆栈 trace 一旦公开,
会变成 0-day 的使用说明书。

请通过 GitHub 的 [private vulnerability reporting](https://github.com/st6098770633/ssh-pad-terminal/security/advisories/new)
提交,流程是:

1. 点上面链接 → "New draft security advisory"
2. 标题 / 描述里给最小可复现信息(版本号 + 影响范围 + 严重程度判断)
3. maintainer 收到后会用 advisory 的私下评论渠道继续沟通,通常 7 天内首响
4. 修复合入后 advisory 公开,credit 致谢(默认)

如果 GitHub private advisory 通道不可用(账号权限 / 网络问题),可以走邮件 —
见仓库主页 `@st6098770633` 的 commit 历史里出现的邮箱。

## 已知架构决策(不视为漏洞)

下列设计选择已经在 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) / [`docs/COMPLIANCE_NOTES.md`](docs/COMPLIANCE_NOTES.md)
公开记录,**不是**安全漏洞,但请确认你理解威胁模型再上报:

- **Android Keystore 主密钥不要求用户生物认证**
  (`KeyStoreManager.setUserAuthenticationRequired(false)`)。
  理由与权衡详见 `docs/COMPLIANCE_NOTES.md` §4 "Keystore 威胁模型注释"。
- **`filesDir/app.log` 在 debug 构建保留 IME 输入文本的 fingerprint**
  — Release 构建受 `LogPolicy` 严格 Drop 任何 `LogClassification.Input` /
  `CredentialMetadata` / `ConnectionMetadata` 条目(Issue #13 + #54)。
- **`filesDir/crashes/` 保留最近 3 次崩溃栈** — 崩溃栈可能包含用户输入上下文,
  Issue #38 取 3 的折衷;清除按钮在 ConfigScreen 顶部。
- **`SharedPreferences` 明文存 host / port / username** — 这三个字段本身不是
  凭证(只是 routing 信息);密码与私钥都走 Keystore AES-256-GCM。
- **`adb backup` 被 `android:allowBackup="false"` 禁用** — 见
  `AndroidManifest.xml`;换设备需在 HanTerm 内重新配置。

## 严重程度分类

| 级别 | 含义 | 例子 |
|---|---|---|
| **Critical** | 远端代码执行 / 凭据明文外泄 / bypass host-key 验证 |  |
| **High** | 凭据明文落到 `filesDir/` 或 `app.log` |  |
| **Medium** | 拒绝服务 / 加密强度退化 |  |
| **Low** | 信息泄漏到 Logcat 但不影响磁盘文件 |  |

## 安全相关测试位置

- `app/src/test/java/com/taosun/hanterm/logging/` — LogPolicy 审计矩阵
- `app/src/test/java/com/taosun/hanterm/ssh/security/` — host-key 校验
- `app/src/test/java/com/taosun/hanterm/data/crypto/` — 凭据加密往返
- `app/src/test/java/com/taosun/hanterm/ui/ConfigScreenDebugLogGateTest.kt` —
  release / debug log 门控