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

下列设计选择的理由与权衡已完整记录在 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) 和 [`docs/COMPLIANCE_NOTES.md`](docs/COMPLIANCE_NOTES.md) 中，
**不是**安全漏洞。请在报告安全问题时先确认你理解这些威胁模型：

- Keystore 用户认证 → COMPLIANCE_NOTES.md §4
- 日志敏感分类 → ARCHITECTURE.md §"Logging policy"
- 崩溃栈保留 → ARCHITECTURE.md §"测试矩阵"
- 主机/端口/用户名明文存储 → COMPLIANCE_NOTES.md §1.2
- 备份禁用 → COMPLIANCE_NOTES.md §3.2 / AndroidManifest.xml

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