# HanTerm 上架合规备注 (Play Console 答案草稿 + 内部依据)

> 本文是 Issue #32 (P0 in #31 store-readiness plan) 的内部依据文档,**不是**面向最终用户的隐私政策。
> 面向用户的版本在 [`docs/PRIVACY_POLICY.md`](PRIVACY_POLICY.md),那里是公开发布的静态页 URL。
>
> 本文档按"商店 Console 会问什么 → 我们答什么 → 答案的依据"三段式组织,
> 维护者把各小节直接复制到对应 Console 字段即可,任何对 PR 答复的改动请同步回滚到 §1 (Data safety)
> 和 [`docs/PRIVACY_POLICY.md`](PRIVACY_POLICY.md) 的对应章节。

---

## 1. Play Console Data safety 表单答案

> 路径:Play Console → 应用内容 → Data safety。
> Google 强制要求 2022 年 7 月起所有新上架 / 更新应用填写此表。

### 1.1 "Does your app collect or share any of the required user data types?"

**答**:**是的**,但**仅在用户设备本地存储**,**不上传、不分享、不外发**(详见各字段)。
Google 的"shared"定义包括"传输给第三方开发者或 SDK",我们**没有任何此类行为**。

### 1.2 Data safety 表逐项答案

| 数据类别 | 是否收集 | 是否分享 | 是否本地存储 | 是否加密 | 用途 | 用户可否删除 |
|---|---|---|---|---|---|---|
| **Account info** — 用户名 | ✅ | ❌ | ✅ (`SharedPreferences`) | ❌(无凭证价值) | SSH 登录 | ✅ 重装即删 |
| **App activity** — SSH 会话交互 | ❌ | ❌ | ❌ | — | — | — |
| **App info** — 崩溃日志 | ✅(仅本地) | ❌ | ✅ (`filesDir/crash.log`) | ❌(无 PII) | 本地诊断 | ✅ 删除按钮 |
| **Audio / Video / Files / Photos** | ❌ | ❌ | ❌ | — | — | — |
| **Calendar / Contacts / Location** | ❌ | ❌ | ❌ | — | — | — |
| **Device info** — 设备型号、OS 版本 | ❌(无 SDK 收集) | ❌ | — | — | — | — |
| **Financial info / Health / Messages** | ❌ | ❌ | — | — | — | — |
| **Personal info** — 用户名(同 Account) | (见上) | — | — | — | — | — |
| **Web browsing** | ❌ | ❌ | — | — | — | — |
| **Credentials** — SSH 密码、私钥 | ✅ | ❌ | ✅ | ✅ AES-256-GCM (Android Keystore) | SSH 认证 | ✅ 重装即删 |

### 1.3 "Are all of the collected data encrypted in transit?"

**答**:**N/A**——HanTerm **不向任何远程端点传输用户数据**。
SSH 会话内容走用户配置的 SSH 服务器,这是用户主动的端到端连接,Google 在 Data safety
中明确豁免"用户发起的、由用户提供端点的网络通信"。我们**没有自己**的服务器。

### 1.4 "Do you provide a way for users to request that their data is deleted?"

**答**:**是**。HanTerm 没有远程数据副本,因此"删除"等于"清除本地"。
用户在系统设置 → 应用 → HanTerm → 存储 → 清除存储,即可一次性抹除全部 SSH 配置、
加密密码、加密私钥和日志。卸载也是同一效果。

### 1.5 依据文件

- [`docs/PRIVACY_POLICY.md`](PRIVACY_POLICY.md) §3 数据存储清单
- `app/src/main/java/com/taosun/hanterm/data/crypto/KeyStoreManager.kt` AES-256-GCM 实现
- `app/src/main/java/com/taosun/hanterm/data/crypto/EncryptedPrivateKeyStore.kt` 私钥 vault
- `app/src/main/java/com/taosun/hanterm/data/prefs/AppPreferences.kt` SharedPreferences
- `app/src/main/java/com/taosun/hanterm/logging/AppLog.kt` + `logging/LogPolicy.kt` 日志分类

---

## 2. FOREGROUND_SERVICE_SPECIAL_USE 用途说明

> Play Console 路径:应用内容 → 敏感权限和 API → Foreground service permissions → Special use。
> 同时填入"用途说明"(限 250 字符以内,推荐引用 BG-KA-06 等内部编号)。

### 2.1 Play Console 字段答案(English,实际填写)

> **Why does your app need a `FOREGROUND_SERVICE_SPECIAL_USE` permission?**
>
> HanTerm is an interactive SSH client. When the user moves the app to the background
> (multi-window, notification shade, screen off), the in-flight remote shell session must
> stay alive so that background commands (long `make`, `git fetch`, package downloads)
> complete instead of being killed by the OS. We declare `FOREGROUND_SERVICE_SPECIAL_USE`
> because the foreground service is **not** media playback, location tracking, or
> data sync in the categories Play already enumerates — it is a long-lived, user-initiated
> interactive session that the user explicitly started and can stop at any time.
>
> User-visible notification: a single line `user@host:port` (e.g. `alice@dev.local:22`)
> summarising the active session. Tapping the notification returns to the terminal.
> The service has zero outbound traffic of its own; all bytes flow over the user-configured
> SSH socket.

### 2.2 中文版(国内商店)

> HanTerm 是交互式 SSH 客户端。用户主动连接一台 SSH 服务器后,需要让"会话"在被切到后台、
> 拉下通知栏、息屏时继续存活,以便长时间任务(`make` / `git fetch` / 包下载)能跑完;
> 不然会被系统直接杀死。我们用 `FOREGROUND_SERVICE_SPECIAL_USE` 类型的前台服务来保活,
> 因为它不属于 Play 已枚举的"媒体播放 / 定位 / 数据同步"中的任何一类,而是用户主动发起、
> 可随时结束的长时间交互会话。通知里只显示一行 `user@host:port`(例如 `alice@dev.local:22`),
> 没有会话内容;服务自身**没有**任何出站流量,所有字节都走用户配置的 SSH 套接字。

### 2.3 Manifest 已有内容

`app/src/main/AndroidManifest.xml` 第 101–103 行:

```xml
<property
    android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
    android:value="Persistent interactive SSH session keepalive" />
```

这行 `<property>` 是 FGS `specialUse` 在 API 34+ 上的硬性要求(subtype 缺失 = 启动崩溃)。与 `minSdk` 解耦:
`specialUse` 这个常量本身和 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property 同时在 API 34 引入,所以一旦 `minSdk ≥ 34`,该 property 就
是启动 `SshKeepAliveService` 的必填项,不能省。Issue #40 的对比表里把这点列为"34 → 33 / 30"的真实阻断点。
文案"Persistent interactive SSH session keepalive"会进入系统日志,但**不会**进入 Play Console
——Play Console 需要的是上面 §2.1/§2.2 那段较长说明。

---

## 3. REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 正当理由

> Play Console 路径:应用内容 → 敏感权限和 API → Other permissions → `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
>
> 注:此项在某些商店(华为、小米、OPPO、vivo)是"明示弹窗"权限,需要在弹窗文案中说明用途。

### 3.1 弹窗文案(用户实际看到的)

> **HanTerm 想豁免电池优化**
>
> 原因:你在 `user@host` 上有一个 SSH 会话在后台运行。
> 部分厂商(华为、小米、OPPO、vivo 等)的电池优化会冻结前台服务的保活线程,
> 导致长任务中途断流。豁免后,HanTerm 可以在你切到其它 App 时保持会话在线。
>
> 你随时可以在系统设置 → 电池 → HanTerm → 取消豁免,或者直接断开 SSH 会话。

### 3.2 Play Console 字段答案(English)

> **Why does your app need the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission?**
>
> HanTerm runs a foreground service while the user has an active SSH session
> (see `FOREGROUND_SERVICE_SPECIAL_USE` rationale above). On a documented set of
> OEM ROMs (Huawei EMUI / Xiaomi MIUI / OPPO ColorOS / vivo Funtouch / Samsung One UI
> with "deep sleep"), aggressive battery savers freeze even foreground-service worker
> threads: in a 2026-07 device log (BG-KA-06), `Thread.sleep(40_000)` was deferred to
> 40 seconds of wall-clock after a Tailscale keepalive RST, which killed the session.
> We prompt the user once, on first successful connect, and never auto-prompt again.
> Disconnecting the session stops the foreground service and reverts the optimisation.

### 3.3 内部依据

- `docs/BACKGROUND_SSH_KEEPALIVE_POSTMORTEM_2026-07-11.md` "阶段 G — sleep 也被冻 40 s"
  (BG-KA-06 行 + 设备日志:Thread.sleep 推迟 ~40 s、Tailscale RST)。
- `docs/ARCHITECTURE.md` §5 SSH keepalive 当前策略(为何 keepalive 三道防线不够)。
- `app/src/main/AndroidManifest.xml` 第 40–46 行已有的 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  权限声明及 BG-KA-06 注释。

---

## 4. Keystore 用户认证威胁模型注释

> 不直接对应某条商店表单,但 Google 会在应用审核里对"使用 Keystore 但不要求生物认证"反复问。
> 写在这里,便于维护者在审核沟通时一键复制。

**决策**:`KeyStoreManager.getOrCreateKey()` 的 `KeyGenParameterSpec` **不**调用
`.setUserAuthenticationRequired(true)`。即 AES 主密钥的解密**不**需要用户生物认证。

**理由**(产品决策,见 Issue #39 关联,本仓库代码注释):

1. SSH 客户端的核心场景是**长时间会话**(数小时到数日)。每次解锁密钥都要求生物认证,
   会让"屏幕关闭 → 解锁手机 → 重新验证生物特征"成为强制操作,严重破坏体验。
2. SSH 凭据(密码 / 私钥)与设备锁屏密码是不同信任域;锁屏密码已能防"捡到手机的人"
   进主界面,但无法区分"用户主动在 SSH 里做了危险动作"。把 SSH 凭据绑定到生物特征
   并不能额外阻止"用户本人在 SSH 里跑 `rm -rf`",只会拖累正常流程。
3. 我们的实际威胁是"其他普通 App / 备份外泄 / ADB pull `/data/data`"。这些通道被
   Android Keystore 的硬件绑定 + `allowBackup="false"` + 应用沙箱联合堵死,**不需要**生物认证。
4. 物理拿到解锁手机的攻击者,Android 已经把 Keystore 主密钥在锁屏后随 `setUnlockedDeviceRequired`
   或 OEM 等价物限制;我们没有再叠加的必要。
5. 已知不足:已 root 设备、调试器附加、内核级攻击者**不在本威胁模型内**。这类用户应该自己评估。

**审核沟通模板**:
> We do not gate `KeyStoreManager`'s AES-256 key behind biometric or device-credential
> authentication. SSH sessions can run for many hours, and a per-use biometric prompt
> would interrupt normal use without meaningfully raising the bar: an attacker who
> already holds the unlocked phone is past the threat boundary. Our actual threats
> (other apps reading files, ADB backup exfiltration) are covered by the Keystore's
> hardware binding plus `allowBackup="false"` and the Android sandbox — none of which
> require user authentication. We document this trade-off in
> `docs/COMPLIANCE_NOTES.md` §4 so reviewers can audit it without code spelunking.

---

## 5. 加密 / 出口合规问卷答案

> Play Console 路径:应用内容 → 隐私与安全 → Encryption & data safety → Export compliance。
> 多数应用勾"No, this app does not use encryption"——但 SSH 客户端显然要选 Yes,以下逐项展开。

### 5.1 各家商店的统一答案

- **Does the app use encryption?** — **YES**。
- **Encryption purpose?** — SSHv2 协议 + 用户凭据的本地 AES-256-GCM 静态加密。
- **Encryption library/algorithm?** — BouncyCastle 1.78+ (`AES/GCM/NoPadding`, `Ed25519`, `RSA` via sshj);
  Android Keystore (`AndroidKeyStore` provider) 托管 AES 主密钥。
- **Key length?** — AES-256 / RSA-2048+(由 sshj 协商) / Ed25519-256。
- **Is the app's primary purpose cryptography?** — **NO**(HanTerm 是 SSH 客户端,密码学只是功能子集)。
- **Does the app use any cryptographic functionality NOT described in the
  mass-market cryptography exemption (e.g. SUA / 5D002)?** — **NO**——AES / SHA-2 / Ed25519
  / RSA 都属于 mass-market exemption 的非受控类,无需单独登记。
- **Is the app available outside the US / sanctioned jurisdictions?** — Yes(全球分发);
  但密码学仅限 mass-market exemption,不触发 EAR Category 5 Part 2 的受控类别。

### 5.2 引用条目

- `com.hierynomus:sshj:0.40.0` — Apache-2.0
- `org.bouncycastle:bcprov-jdk18on:1.78.1`(实际解析到 1.80.2,见 `app/build.gradle.kts` Sprint 3.5 注释) — MIT
- Android Keystore 平台 API——不引入新的加密原语,只是平台提供的 JCA 接口。

---

## 6. 国内商店专项备注

> 各国内商店(华为 AppGallery、小米/Vivo/OPPO/联想应用商店、腾讯应用宝等)表单字段不完全一致,
> 但以下事实复用度极高,逐家微调即可。

| 字段 | 答案 |
|---|---|
| **应用类别** | 工具 / 开发者工具 |
| **是否需要联网** | 是,**仅**用户主动连接时(SSH) |
| **是否收集个人信息** | 是,**仅本地**,详见 §1.2 |
| **是否提供账号体系** | 否 |
| **是否集成第三方 SDK / 统计** | 否 |
| **是否含广告** | 否 |
| **是否含支付 / 付费功能** | 否(首发版本) |
| **是否使用前置摄像头 / 麦克风** | 否 |
| **权限申请清单** | INTERNET / ACCESS_NETWORK_STATE / FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS / WAKE_LOCK / REQUEST_IGNORE_BATTERY_OPTIMIZATIONS |
| **隐私政策 URL** | 填入 [`docs/PRIVACY_POLICY.md`](PRIVACY_POLICY.md) 静态托管后的公开 URL |
| **开发者承诺** | 见 `HanTerm` 是开源项目(给出仓库链接),无后门、无远程数据收集 |

---

## 7. 与源文档关系

| 文档 | 角色 |
|---|---|
| [`docs/PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | **公开**隐私政策,填入各商店"隐私政策 URL"字段 |
| [`docs/COMPLIANCE_NOTES.md`](COMPLIANCE_NOTES.md) | **内部**合规备注,本文件 |
| [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) | 实现决策索引,被隐私政策引用 |
| [`docs/BACKGROUND_SSH_KEEPALIVE_POSTMORTEM_2026-07-11.md`](BACKGROUND_SSH_KEEPALIVE_POSTMORTEM_2026-07-11.md) | BG-KA-06 设备日志,battery opt 章节的实证依据 |

---

## 8. 变更日志

- **2026-07-24**:初版,对应 Issue #32 关闭。涵盖 §1 Data safety、§2 FGS specialUse、
  §3 battery opt、§4 Keystore 威胁模型、§5 出口合规、§6 国内商店备注。