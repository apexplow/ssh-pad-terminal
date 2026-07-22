# 后台 SSH 断连排查实录（2026-07-11）

> **当前 keepalive 策略(2026-07-22 落地):** [`docs/ARCHITECTURE.md` §5](../ARCHITECTURE.md#5-ssh-keepalive-当前策略) — `HEARTBEAT` 单向 IGNORE 10s + TCP keepalive 25s 窗口 + FGS-driven nudge 3s + `SO_TIMEOUT_MS = 60_000` 兜底. 本文件是决策历史 ADR.

**现象：** 平板上通过 Tailscale 连 SSH，切到后台约 10–40 秒后断开，日志为  
`SocketException: Software caused connection abort`。  
**最终结论：** 不是单一 bug，而是「协议层误杀 + 反射路径失败 + OEM 冻结进程」叠在一起；真正让后台活下来的最后一刀是 **忽略电池优化**。

本文记录完整排查链，避免以后再走弯路。

---

## 1. 症状与约束

| 项 | 内容 |
|---|---|
| 环境 | Android 平板 + Tailscale (`100.x`) + 密码登录 |
| 复现 | 连接成功 → 切后台 → ~10–40 s → `Software caused connection abort` |
| 前台 | 持续输入时可以撑很久（后证实前台 nudge 节奏正常） |
| 约束 | 禁止连真实 sshd 写单元测试；只能靠 `AppLog` + 真机迭代 |

相关栈（简化）：

```
E/SshSession: readInto: SSHException
  Caused by: java.net.SocketException: Software caused connection abort
    at java.net.SocketInputStream.socketRead0
    at net.schmizz.sshj.transport.Reader.run
```

这是 **对端 / 中间路径 RST**（或本机 socket 已被关），不是 UI 主动 Disconnect。

---

## 2. 排查时间线（按发现顺序）

每一层「修好一点」都会露出下一层问题。真机 log 是唯一可靠的红绿灯。

### 阶段 A — 以为是「没有 TCP keepalive」

**假设：** sshj 的 SSH 层 keepalive 跑在用户态线程上，Doze 会暂停；应加内核 TCP keepalive（`TCP_KEEPIDLE/INTVL/CNT`）。

**做了什么：**

- `SO_KEEPALIVE=true`
- 反射 `setsockoptInt` 设 `10/5/3`（25 s 探测窗）
- SSH keepalive interval `30 → 10`

**真机结果：**

```
I/SshClient: TCP keepalive: SO_KEEPALIVE=true
W/SshClient: tightened-interval setsockoptInt failed
  NoSuchFieldException: No field fd in class Ljava/net/Socket
```

只打开了开关，**间隔仍是内核默认 2 小时** → 对短超时毫无帮助。

**教训：** ART 上 `fd` 不在 `java.net.Socket`，而在 `Socket.impl`（`SocketImpl`）里。

---

### 阶段 B — 修好取 fd，又撞上 setsockopt 反射

**修法：** `socketFileDescriptor()` 走 `Socket.impl → fd` 继承链。

**真机结果：**

```
NoSuchMethodException: android.app.ActivityThread$AndroidOs.setsockoptInt [...]
```

`Libcore.os` 运行时类型是 `AndroidOs`，`setsockoptInt` 声明在父类 `ForwardingOs`；对子类 `getMethod` 会失败。

**修法：** 优先 `android.system.Os.setsockoptInt` 静态方法；回退再解析 `ForwardingOs`。

**真机结果：** 终于出现：

```
I/SshClient: TCP keepalive: idle=10s intvl=5s cnt=3 (25s detection window)
```

但约 29 s 后仍断。

**教训：** TCP keepalive 保的是 **NAT / 半开检测**，sshd 的 `ClientAliveInterval` 看的是 **SSH 协议流量**。只开 TCP 不够。

---

### 阶段 C — FGS 发 SSH keepalive，但方式错了

**假设：** 在 `SshKeepAliveService` 里定期发 SSH 包。

**错法 1：** `sendGlobalRequest("keepalive@openssh.com", wantReply=true)`  
后台 Reader 可能来不及回包，nudge 自己卡住 / 失败。

**错法 2：** 日志出现 `nudge skipped (no live session)`——与 disconnect 同一毫秒，是拆连后的假信号，不是根因。

**修法：** FGS nudge 改为单向 `SSH_MSG_IGNORE`（与 Heartbeater 同路径）。

---

### 阶段 D — 最大的自伤：`KEEP_ALIVE` 在杀健康连接（BG-KA-04）

Sprint 3 曾把 sshj 从默认 `HEARTBEAT` 改成 `KEEP_ALIVE`（`KeepAliveRunner` + want-reply），为了「主动探测死连接」。

真机时间线高度吻合：

| 事件 | 时间 |
|---|---|
| `KeepAliveRunner started interval=10s` | T+0 |
| 理论自杀点 `10s × maxAliveCount(3)` | ~T+30 |
| 实际 abort | ~T+35 |

在 Tailscale / 后台路径上，**回复经常到不了客户端**；`KeepAliveRunner` 把「没收到回复」当成死连接，**主动拆掉其实还活着的会话**。

以前 interval=30 时要 ~90 s 才自杀，不容易察觉；改成 10 s 后变成约半分钟必断。

**修法：** 改回 `KeepAliveProvider.HEARTBEAT`（单向 IGNORE）。  
死连接检测改交给：

- 内核 TCP keepalive（25 s 窗）
- `SO_TIMEOUT`（60 s）
- FGS 持续 IGNORE（保协议活跃）

**教训：** 「能探测死连接」≠「在不可靠路径上必须用 want-reply」。对移动 / VPN，**误杀健康连接比漏检更糟**。

---

### 阶段 E — KeepAlive 线程根本没 start（BG-KA-02）

sshj 只在 `onConnect()` 时若 `keepAliveInterval > 0` 才 `start()` 线程。  
我们把 interval 设在 **auth 之后**，线程从未跑过。

**修法：** `applySshKeepAliveSettings()` 里显式 `keepAlive.start()`。  
log 应变为：`sshj Heartbeater started interval=10s`。

---

### 阶段 F — `Handler.postDelayed` 被推迟（BG-KA-05）

HEARTBEAT + IGNORE nudge 后：

```
nudge #1 ok  (T+0)
nudge #2 ok  (T+5)
nudge #3 ok  (T+10)
nudge #4 fail (T+25)   ← 中间空了 ~15 s
```

`Handler.postDelayed` 在后台被 OEM/Doze 推迟；15 s 无 TX → Tailscale / ClientAlive RST。

**修法：** 换成持 `PARTIAL_WAKE_LOCK` 的 `Thread.sleep` 循环；FGS 类型改 `specialUse`；间隔 5 s → 3 s。

---

### 阶段 G — sleep 也被冻 40 s：OEM 电池优化（BG-KA-06）

新 log：

```
nudge #1..#4  每 3 s 准时
（然后空白 ~40 s）
abort + Socket closed
```

对比：

| 会话 | 场景 | 结果 |
|---|---|---|
| 前台持续操作 | nudge 节奏稳定 | 可撑数分钟 |
| 切后台 | sleep 被冻 ~40 s | 必断 |

**FGS + WakeLock + specialUse 仍挡不住厂商「电池优化」对进程的冻结。**

**修法（最终生效）：**

1. 连接成功后请求 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
2. keepalive 线程改为 **非 daemon**
3. 通知渠道重要性 `LOW → DEFAULT`（新 channel id `ssh_session_v2`）
4. sleep  defer 时打明确告警 log

用户确认：**改了电池优化后后台不再断。**

---

## 3. 最终架构（三层 + 系统白名单）

```
┌─────────────────────────────────────────────────────────────┐
│  系统：忽略电池优化（必须）+ Tailscale 同样建议不优化          │
└─────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────┐
│  FGS (specialUse) + PARTIAL_WAKE_LOCK                        │
│    Thread.sleep 循环每 3 s 写 SSH_MSG_IGNORE（Doze 抗性 TX）   │
└─────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────┐
│  sshj Heartbeater（10 s，单向 IGNORE，显式 start）            │
└─────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────┐
│  内核 TCP keepalive 10/5/3 + SO_TIMEOUT 60 s（死连接兜底）    │
└─────────────────────────────────────────────────────────────┘
```

**不要**再把 `KeepAliveProvider.KEEP_ALIVE` 当默认——除非有证据证明对端稳定回复 `keepalive@openssh.com`，且你能接受「无回复即拆连」。

---

## 4. 关键代码位置

| 文件 | 作用 |
|---|---|
| `ssh/SshClient.kt` | TCP keepalive 反射、`Heartbeater` start、FGS nudge 回调（IGNORE） |
| `ssh/SshConfig.kt` | interval / FGS nudge 秒数 / SO_TIMEOUT |
| `ssh/SshKeepAliveService.kt` | FGS、WakeLock、sleep-loop、defer 诊断 |
| `ui/HanTermApp.kt` | 首次 Connected 时请求忽略电池优化 |
| `AndroidManifest.xml` | `WAKE_LOCK`、`FOREGROUND_SERVICE_SPECIAL_USE`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| `MainActivity` / Application | 通知渠道 `ssh_session_v2` IMPORTANCE_DEFAULT |

---

## 5. 真机验收清单

连接后 log **必须**类似：

```
I/SshClient: TCP keepalive: SO_KEEPALIVE=true
I/SshClient: TCP keepalive: idle=10s intvl=5s cnt=3 (25s detection window)
I/SshClient: sshj Heartbeater started interval=10s
I/SshKeepAliveService: foreground service started: ...
I/SshKeepAliveService: PARTIAL_WAKE_LOCK acquired
I/SshKeepAliveService: power[onStartCommand]: ignoringBatteryOpt=true ...
I/SshKeepAliveService: SSH keepalive sleep-loop started interval=3000ms
I/SshKeepAliveService: SSH keepalive nudge #1 ok
I/SshKeepAliveService: SSH keepalive nudge #2 ok
…（间隔稳定 ≈3 s，不要出现 10s+ 空洞）
```

红灯信号：

| log | 含义 |
|---|---|
| `No field fd` / `setsockoptInt failed` | TCP 间隔未生效 |
| `KeepAliveRunner started` | 又退回 want-reply，可能自杀 |
| `nudge gap=XXXms` / `sleep deferred` | OEM 仍在冻进程 |
| `ignoringBatteryOpt=false` | 用户未放行电池优化 |

手动：切后台 ≥5 分钟 → 回前台会话仍在。  
建议同步：系统设置里给 **本应用** 和 **Tailscale** 都设「不优化电池 / 允许后台」。

服务端可选核对：

```bash
sshd -T | grep -i clientalive
```

---

## 6. 经验总结

1. **分层假设，用真机 log 证伪。** 每一层「修好」都会露出下一层；没有 `AppLog` 序号（nudge `#N`）几乎无法发现 Handler/sleep 被 defer。
2. **Android 反射要按 ART 现实写。** `Socket.fd`、`AndroidOs.setsockoptInt` 都是「文档/旧博客正确、真机错误」的例子；优先公开 API（`android.system.Os`）。
3. **SSH keepalive ≠ TCP keepalive ≠ 进程不被冻。** 三者解决不同问题；混为一谈会空转几天。
4. **want-reply 的 dead-peer 探测在 VPN/后台是危险默认。** 误杀窗口 = `interval × maxAliveCount`；缩短 interval 会放大事故。
5. **sshj 在 `onConnect` 之后改 interval 必须手动 `start()`。**
6. **FGS + WakeLock 不能对抗厂商电池优化。** 这是产品级权限（用户手势），不是纯代码能兜住的。
7. **前台能撑、后台必断 → 先查进程冻结，再查协议。** 19:21（前台）vs 19:30（后台）对比一眼就能定方向。
8. **诊断 log 要可计量。** 「loop active」一次不够；要有序号 + 间隔，才能证明 defer。

---

## 7. 以后不要做的事

- 不要在没有真机证据时把 `HEARTBEAT` 改回 `KEEP_ALIVE`「为了更正确」。
- 不要假设 `Handler.postDelayed` 在 FGS 里准时。
- 不要假设 `Thread.sleep` + WakeLock 在国产 ROM 上准时。
- 不要只测前台空闲；验收必须包含 **真实切后台**。
- 不要把「nudge skipped (no live session)」出现在 disconnect 同一毫秒当成根因。

---

## 8. 相关编号（便于搜代码 / commit）

| ID | 含义 |
|---|---|
| BG-KA-01 | TCP keepalive 配置 |
| BG-KA-02 | Heartbeater / KeepAlive 显式 `start()` |
| BG-KA-03 | `PARTIAL_WAKE_LOCK` |
| BG-KA-04 | 放弃 `KEEP_ALIVE` 防自杀 |
| BG-KA-05 | sleep-loop 替代 `postDelayed`；`specialUse` FGS |
| BG-KA-06 | 忽略电池优化（最终生效条件） |

---

*记录日期：2026-07-11。验证：用户确认开启忽略电池优化后后台会话保持稳定。*
