# Sprint 3.5 真机验收清单 — SSHJ 0.40 升级回归

**目的**:Sprint 3.5 是纯"加固/依赖升级"sprint,没有新功能,但 CLAUDE.md 明确禁止用真 SSH 服务器写单元测试(`app/src/test` 全部走 `FakeTransport` / mock / Robolectric)。这份清单覆盖三类**单元测试原则上覆盖不到、必须上真机 + 真 sshd 才能验证**的回归面:

1. SSHJ 0.38 → 0.40 + BouncyCastle 透传升级到 1.80.2 之后,三条认证路径(密码 / RSA 私钥 / Ed25519 私钥)是否还能连得上、连得稳
2. 换了 BC 版本之后,对不同 SSH server 实现(标准 OpenSSH 的不同大版本、dropbear、busybox sshd)的兼容性有没有退化
3. Sprint 3.5 加固过程中在 `TerminalInputConnection` 发现并修复的 latch 重置 bug(见 `docs/GEARS_SPEC.md` TIC-DS-04),对应的 vim/nano 全键位真机验证要不要重跑一遍确认没有引入新问题

不属于本清单范围(见 `CLAUDE.md`"Out of scope"):known_hosts TOFU 交互 UI、SFTP、多主机列表、Mosh —— 这些不是 Sprint 3.5 改动面。

---

## 0. 前提

- [ ] 用 Sprint 3.5 分支(`chore/sprint-3.5-sshj-0.40-upgrade`)编出的 `app-debug.apk`(`./gradlew :app:assembleDebug`),装到平板:`adb install -r app/build/outputs/apk/debug/app-debug.apk`
- [ ] 运行前确认依赖确实是升级后的版本(不要测错分支):
  ```bash
  ./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -iE "sshj|bouncycastle"
  ```
  期望:`com.hierynomus:sshj:0.40.0`,`bcprov/bcpkix/bcutil-jdk18on` 均解析到 `1.80.2`(不是 `build.gradle.kts` 里声明的 `1.78.1` advisory 版本 —— 见 `CLAUDE.md`"Hard constraints"关于这个 pin 的说明)。
- [ ] 至少准备 2 台不同 sshd 的测试主机(见第 2 节矩阵),每台都各建一个密码账号 + 一对 RSA key + 一对 Ed25519 key,免得每次手测都要重新生成 key

---

## 1. 三种认证方式回归(SSHJ 0.40 + BC 1.80.2)

对**同一台**目标主机(建议先用标准较新版 OpenSSH,环境最干净),依次验证:

| # | 认证方式 | 步骤 | 通过标准 |
|---|---|---|---|
| 1.1 | 密码 | Config 页填 host/port/user + 密码 → Save → Connect | 3 秒内进终端,`whoami` 回显正确用户名 |
| 1.2 | 密码 · 错误密码 | 故意填错密码 → Connect | `SshErrorMessages.friendly()` 弹出单行英文错误(不是 sshj 异常类名堆栈),"Show logs"/"Copy logs" 可用,`app.log` 里不出现明文密码(Sprint 2.5 S3 的日志 gating 契约) |
| 1.3 | RSA 私钥(无密码保护) | SAF 导入 `id_rsa`(PEM,`-----BEGIN RSA PRIVATE KEY-----` 或新版 OpenSSH 格式均测一次)→ Connect | 进终端;这是 `PublicKeyAuthProviderTest` 的 RSA round-trip 在真实 sshj 握手下的端到端验证 |
| 1.4 | Ed25519 私钥(无密码保护) | SAF 导入 OpenSSH v1 格式的 Ed25519 私钥 → Connect | 进终端。**重点回归**:Sprint 3.5 把 `PublicKeyAuthProviderTest` 的测试 fixture 从 BC 的 PKCS#8 编码换成了 `OpenSSHPrivateKeyUtil.encodePrivateKey` 的真 OpenSSH v1 wire format,因为 SSHJ 0.40 的 `PKCS8KeyFile` 会硬拒绝 Ed25519 OID —— 单测已经证明"能装载"这个动作没坏,但**用真实 sshj 握手对一台真服务器认证成功**只有真机测试能证明 |
| 1.5 | Ed25519 私钥(带密码保护) | 生成一个有 passphrase 的 Ed25519 key,SAF 导入(走加密 slot,`EncryptedPrivateKeyStore`)→ Connect | 进终端。这条路径的单测(`PublicKeyAuthProviderEncryptedTest`)默认在 Robolectric 沙箱下 `assumeTrue` 跳过(AndroidKeyStore 不可用),**只有真机能跑到** |
| 1.6 | 私钥 · 文件被删除/损坏 | 导入私钥后手动去 `filesDir/keys/` 删掉对应文件,再次 Connect | 友好错误提示,不崩溃 |

---

## 2. OpenSSH 兼容性矩阵

同一套认证方式(至少密码 + 一种私钥)在下表每一行都跑一次 1.1/1.3(或 1.4)。这条矩阵在 Sprint 2.5 路线图里就已经标记为待办(`README.md` "OpenSSH 7.x / 8.x / 9.x 兼容性矩阵"),Sprint 3.5 的 BC 版本跳动(1.78.1 → 1.80.2)让这条回归更有必要现在做一次,而不是无限期往后拖。

| Server 实现 | 版本举例 | 密码认证 | RSA 私钥 | Ed25519 私钥 | 备注 |
|---|---|---|---|---|---|
| OpenSSH | 9.x(Ubuntu 24.04 / Debian 12 默认) | [ ] | [ ] | [ ] | 基线,预期全绿 |
| OpenSSH | 8.x(Ubuntu 20.04 / CentOS 8 默认) | [ ] | [ ] | [ ] | |
| OpenSSH | 7.x(较老的嵌入式 / NAS 设备常见) | [ ] | [ ] | [ ] | 部分 7.x 默认 KEX/cipher 集合较窄,重点看是否卡在 KEX 阶段而非认证阶段 |
| dropbear | 任意较新版本(路由器 / OpenWrt 常见) | [ ] | [ ] | [ ] | dropbear 的 Ed25519 支持版本差异较大,先确认目标 dropbear 版本号支持 |
| busybox sshd | 任意 | [ ] | [ ] | [ ] | 功能通常最受限,预期只有密码或单一 key 类型可用,记录实际支持面即可,不强求全绿 |

记录方式:每格填 ✅ / ❌ / ⚠️(部分成功,备注说明),不要留空。任何 ❌ 都要在下面"发现的问题"里补一条,附上 `AppLog`(`filesDir/app.log`)相关片段(先确认没有敏感信息再贴)。

---

## 3. vim / nano 键位回归(含 TIC-DS-04 修复验证)

Sprint 2.5+ 的 vim/nano 全键位清单(`README.md` "手工联调(平板真机)"第 13 条)在本轮不重复抄写,直接执行那一条即可。本节只追加 Sprint 3.5 **新发现并修复**的一个场景,单元测试(`TerminalInputConnectionTest.test_deleteSurroundingText_afterCommit_onlyTheFirstDelIsSuppressed`)已经在 Robolectric 层面证明修复有效,这里补一次真机端到端确认:

- [ ] **多次拼音上屏后退格是否一直可用(TIC-DS-04 回归)**:用搜狗或 Gboard 输入一个中文词组并上屏(比如"你好"),**上屏后立刻退格删掉光标前一个字**,确认远端收到一次真实退格(字符被删)。**重复这个"上屏 → 退格"的循环至少 5 次**(修复前,`userInImeContext` latch 一旦被设为 `true` 就永远不会重置,理论上第一次上屏后的所有退格都会被吞掉,发不到远端 —— 这正是本轮加固在补测试时发现并修复的真实 bug)。
- [ ] 同一循环里穿插几次**纯 ASCII 输入 + 退格**(不经过 IME 组合),确认非 IME 路径的退格全程正常,不受前面拼音上屏影响。
- [ ] 长时间使用(连续切几个 app 再切回来,或者晾几分钟)后再测一次上屏 → 退格,确认不是"短时间内凑巧没触发"的假阴性。

---

## 4. 结果记录模板

验收人 / 日期 / 平板型号 / Android 版本:_____________________

| 章节 | 结果 | 备注 |
|---|---|---|
| 1. 三种认证方式回归 | [ ] 全绿 / [ ] 有问题(见下) | |
| 2. OpenSSH 兼容性矩阵 | [ ] 全绿 / [ ] 有问题(见下) | |
| 3. vim/nano + TIC-DS-04 回归 | [ ] 全绿 / [ ] 有问题(见下) | |

**发现的问题**(每条一个条目,附复现步骤 + 日志片段):

1. ...

**结论**:[ ] 可以合并 `chore/sprint-3.5-sshj-0.40-upgrade` / [ ] 需要先修复上面的问题
