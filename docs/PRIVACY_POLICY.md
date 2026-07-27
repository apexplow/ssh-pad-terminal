# HanTerm 隐私政策

> 本文档是 HanTerm 商店上架所用的隐私政策正文,适用于 Google Play 和国内应用市场。
> 在静态托管(GitHub Pages / Gist / 自有 CDN)发布为公开 URL,然后填入各商店 Console 的"隐私政策 URL"字段。
> 任何对本文件的实质修改都属于上架合规变更,需同步更新 `docs/COMPLIANCE_NOTES.md` 的 Data safety 表单答案。

**最后更新日期**:2026-07-24
**生效版本**:对应 HanTerm Android 应用 `versionName = 0.1.0` 起的所有版本。

---

## 1. 我们是谁

HanTerm(包名 `com.taosun.hanterm`)是一款面向 Android 平板的开源 SSH 客户端。
项目仓库: <https://github.com/st6098770633/ssh-pad-terminal>

如果您对本政策有疑问,可通过仓库 Issue 联系维护者。

---

## 2. 我们**不**做的事

为消除疑问,以下行为 HanTerm **绝不会**做:

- 不收集任何遥测(telemetry)、使用统计、设备指纹、广告 ID 或分析事件。
- 不集成任何第三方分析 SDK(无 Firebase Analytics、无 Crashlytics、无 Sentry、无 Bugly、无任何类似服务)。
- 不向任何远程服务器上传您的 SSH 会话内容、按键、屏幕输入或密码。
- 不读取、索引或外发您设备上的联系人、位置、相册、麦克风、相机等无关数据。
- 不展示广告。
- 不申请与 SSH 连接无关的任何运行时权限(无存储读写、无相机、无通讯录、无位置)。

SSH 客户端的网络活动**只有**用户主动点"连接"后才会发生,且全部走用户配置的 SSH 主机与端口。
没有"心跳上报"、没有"启动时 ping"、没有后台数据同步。

---

## 3. 我们**本地**存储了什么

HanTerm 把所有用户数据严格保存在应用沙箱内(`/data/data/com.taosun.hanterm/`),
应用**不申请 `MANAGE_EXTERNAL_STORAGE`、不读写公共目录**,其他应用无法直接访问。

| 数据 | 存储位置 | 加密 |
|---|---|---|
| 用户输入的 SSH 主机名、端口、用户名、字体大小 | `SharedPreferences` | 明文(无凭证敏感字段) |
| 用户输入的 SSH 密码 | `SharedPreferences` 中的 blob 字段 | **AES-256-GCM**,密钥由 Android Keystore 托管,硬件支持时落在 TEE / StrongBox |
| 导入的 SSH 私钥(PEM) | `filesDir/keys/<name>.pem.enc` | **AES-256-GCM**(同上) |
| SSH 服务器主机指纹(TOFU 信任库) | `filesDir/known_hosts` | 明文(只是 SHA-256 / SHA-512 指纹,不含私钥) |
| 应用运行日志 | `filesDir/app.log`(256 KB 滚动) | 明文,但**不包含会话内容或密码明文**——见下节 |
| 崩溃堆栈(若发生) | `filesDir/crashes/crash-<timestamp>.log`(Issue #38:按次分文件,保留最近 3 次) | 明文,**不外发**,仅用于本地诊断 |

### 3.1 关于日志

应用日志(`AppLog`)受一份内置的 `LogPolicy` 分类控制(详见 `docs/ARCHITECTURE.md`):

- **Drop**:在发布构建中直接丢弃(例如按键码、IME 输入、用户名@主机等 `LogClassification.Input` / `ConnectionMetadata` 类别)。
- **File**:落到 `filesDir/app.log`(仅本机,不上传)。
- **Logcat mirror**:仅 Debug 构建可见;Release 构建不会向 Logcat 写敏感内容。

崩溃日志(`CrashHandler`)走 `Thread.setDefaultUncaughtExceptionHandler`,把每次崩溃的时间戳文件
写到 `filesDir/crashes/`(命名 `crash-<yyyyMMdd-HHmmss-SSS>.log`,最多保留最近 3 次)。从 pre-#38 升级的旧版
用户,首次读取时会把遗留的 `filesDir/crash.log` 静默迁移到 `filesDir/crashes/`。
用户在主界面看到最近一次崩溃后,可以选择「复制到剪贴板」或「关闭」,**应用不自动上报到任何第三方**。

### 3.2 关于备份

`AndroidManifest.xml` 显式声明 `android:allowBackup="false"`,因此 ADB Backup / 云备份 / 设备迁移
**不会**把上述任何数据带走。换设备时,SSH 凭据和 TOFU 信任库需要在 HanTerm 内重新配置。

---

## 4. 加密与凭据保护

- **密码**:用户键入后立即用 Android Keystore 托管的 AES-256 密钥做 GCM 模式加密,12 字节 IV 与密文自包含存储。
  应用进程内从不长期持有解密后的明文密码(仅在认证握手瞬间短暂存在)。
- **私钥**:用户通过系统 SAF(Storage Access Framework)选择 PEM 文件后,应用立即读取 → 加密 → 写回 `filesDir/keys/`,
  内存中的明文副本在加密完成后被主动清零(`fill(0)`)。
  应用**不**通过 `cacheDir/` 或外部存储中转私钥字节。
- **Keystore 密钥**:AES 主密钥的访问**不**要求用户生物认证(`setUserAuthenticationRequired(false)`)。
  这是已记录的产品决策——见 `docs/COMPLIANCE_NOTES.md` §4 "Keystore 威胁模型注释"。
- **威胁模型边界**:上述措施可抵御其他普通 App 读取、Android 备份外泄。**不能**抵御已 root 的设备、
  连接了调试器的进程、或拿到锁屏密码的物理攻击者。

---

## 5. 网络活动

HanTerm 仅在用户主动连接时,通过 SSHJ 库(`com.hierynomus:sshj:0.40.0`)与用户配置的 SSH 服务器建立 TCP 连接,
使用标准 SSHv2 协议(端口可配,默认 22)。除此之外,**没有任何网络出站流量**——无 DNS 上报、无崩溃上报、
无遥测、无 OTA 检查。

应用使用以下网络相关权限,均为 SSH 连接所必需:

- `INTERNET` — TCP 套接字基础。
- `ACCESS_NETWORK_STATE` — 在打开套接字之前探测 WiFi/移动网络,用于把"没网"翻译成友好错误。

---

## 6. 前台服务与电池优化

为保证 SSH 会话在被切到后台后仍能保持连接(避免移动 NAT / Tailscale 静默断流),
HanTerm 在连接建立后会启动一个 `FOREGROUND_SERVICE_SPECIAL_USE` 类型的前台服务,
并请求用户授予 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 以应对部分 OEM 的 Doze 行为。
两类用途说明与正当理由的完整记录见 `docs/COMPLIANCE_NOTES.md` §2 与 §3。

通知显示一行 `user@host:port`,**不**包含会话内容或密码。

---

## 7. 第三方组件

HanTerm 使用了以下开源组件,均**仅**提供本地功能、不携带任何第三方数据收集行为:

| 组件 | 用途 | 许可 |
|---|---|---|
| SSHJ (`com.hierynomus:sshj`) | SSHv2 客户端实现 | Apache-2.0 |
| BouncyCastle (`org.bouncycastle:bcprov-jdk18on`) | SSH Ed25519 / RSA 算法的 JCE 实现 | MIT |
| Termux terminal-emulator / terminal-view | 终端渲染 | Apache-2.0 |

---

## 8. 儿童隐私

HanTerm 不面向 13 岁以下儿童,不收集任何年龄段用户的额外数据。

---

## 9. 政策的变更

若本政策发生实质变更,我们会在下一次商店提交时同步更新本文件,并在仓库 CHANGELOG / Release Notes 中记录。
版本日期见本文件顶部。

---

## 10. 联系我们

- 项目仓库: <https://github.com/st6098770633/ssh-pad-terminal/issues>
- 在主界面打开后,Crash / Debug 面板的"复制"按钮可以把诊断日志粘贴到任何本地工具,
  由用户自行决定是否附在 Issue 里提交——**应用不会自动上传**。