# HanTerm 上架应用市场 — 架构与代码优化清单

> 生成日期：2026-07-24  
> 分析基于代码库完整阅读（93 个 Kotlin 文件 + AndroidManifest + build.gradle）。  
> 分为「必须修复」「强烈建议」「可选/长期」三个优先级。

---

## 🔴 必须修复（阻塞上架）

### 1. `AppPreferences` 明文密码字段从未清除

**问题**：`AppPreferences.kt` 中保留了 `var password: String`，以明文形式写入 `SharedPreferences`。  
注释虽标注「legacy」，但该字段在 `clearConnectionFields()` 中仍被 `remove(KEY_PASSWORD)` 覆盖——意味着如果任何路径意外写入了该字段（例如测试、旧版迁移），明文密码会留在磁盘。

**修复**：在 `AppPreferences` 初始化时或每次 `getEncryptedPassword` 调用后，主动检测并清除 `KEY_PASSWORD`。长期应删除该字段，防止未来代码误用。

---

### 2. `KeyStoreManager` 未启用用户认证（Biometric/Device Lock）

**问题**：`KeyStoreManager.kt` 构建 `KeyGenParameterSpec` 时**没有**调用 `setUserAuthenticationRequired(true)`。  
这意味着即使设备解锁了 Secure Enclave，私钥加密材料可在无任何用户认证的情况下被访问。应用市场（尤其是企业应用、华为/小米）的安全审核会标记这一问题。

**修复**（最低线）：至少需要在代码和 PrivacyPolicy 中明确记录威胁模型决策：

```kotlin
// 明确写出决策，加注释说明为何不要求
.setUserAuthenticationRequired(false)
// 或针对密码存储槽，要求设备解锁后 30 秒内有效：
.setUserAuthenticationRequired(true)
.setUserAuthenticationValidityDurationSeconds(30)
```

---

### 3. 反射访问内部 API（`libcore`、`android.system.Os`）

**问题**：`SshClient.kt` 使用反射访问 `libcore.io.Libcore.os` 和 `libcore.io.ForwardingOs.setsockoptInt`，这些都是 `@hide` API。  
Google Play 的 DEX scanner 和华为 AppGallery 均会扫描 `Class.forName("libcore.*")` / `setAccessible(true)`，可能导致审核拒绝或上架后被要求整改。

**修复**：**保留 Path 1 的 `android.system.Os` 尝试路径，移除 Path 2 的 `libcore.*` 路径**，降低审核风险。完整修复是通过 JNI/NDK 调用 `setsockopt`。

---

### 4. 明文私钥在临时文件中曝露

**问题**：`PublicKeyAuthProvider.kt` 将解密后的私钥明文写入 `cacheDir/ssh-pad-key-tmp/` 的临时文件，然后用 `secureDeleteBestEffort` 清除。  
在进程被 OOM killer 终止时（`finally` 未执行），明文私钥文件会残留在 cacheDir。

**修复**：改用 `in-memory` 方式，通过 `loadKeyProvider` 的 Reader/InputStream 重载直接传递字节数组，避免落盘。sshj 的 `OpenSSHKeyV1KeyFile.init(Reader)` 支持此方式。

---

### 5. `HanTermAppViewModel` 不是标准 `ViewModel`

**问题**：`HanTermAppViewModel.kt` 是一个普通类，不继承 `androidx.lifecycle.ViewModel`。  
它持有 `Context` 引用，在 Compose 的 `remember` 里创建和管理生命周期。这会导致：
- 内存泄漏风险（如果 Context 是 Activity）
- 与 Compose Preview、动画测试不兼容
- 部分应用市场 SDK 兼容性要求 MVVM 标准实现

**修复**：迁移到 `ViewModel` + `ViewModelProvider.Factory`。`ConnectionRuntime` 可继续保持进程级单例，ViewModel 只持有 `Application` Context。

---

## 🟡 强烈建议（影响稳定性 / 审核通过率）

### 6. `AppLog.SimpleDateFormat` 非线程安全

**问题**：`AppLog.kt` 初始化了一个 `SimpleDateFormat` 实例并共享使用，注释中也承认它是线程不安全的。并发写入（IO 线程 + UI 线程同时触发 SSH 错误）可能导致时间戳格式化出错甚至崩溃。

**修复**：改用 `java.time.DateTimeFormatter`（线程安全），或在 `writeLine` 内部局部创建 `SimpleDateFormat`。

---

### 7. `FontSizeController` 使用全局可变状态

**问题**：`FontSizeController.kt` 是进程级单例，`state` 和 `Channel` 都是静态的。`MainActivity.onKeyDown` 直接写 `FontSizeController.state.value`（绕过 ViewModel），在多 Activity 场景（如小窗、分屏）下会造成状态不一致。

**修复**：将 `FontSizeController` 的状态移入 ViewModel，通过标准的 Compose 状态提升传递到 `MainActivity`。

---

### 8. 主机名和用户名以明文存储在 SharedPreferences

**问题**：`AppPreferences.kt` 使用标准 `SharedPreferences`（`MODE_PRIVATE`）存储主机名、用户名。虽然密码 blob 由 KeyStore 保护，但主机名和用户名以明文存储，在 root 设备上可被读取。

**建议**：迁移到 [`EncryptedSharedPreferences`](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences)（Jetpack Security），或至少对 `host`、`username` 字段做字段级加密。

---

### 9. `minSdk = 36` 极大限制受众

**问题**：当前 `minSdk = 36`（Android 16）。截至 2026 年，Android 16 的市场份额约为 5-15%，SSH 工具的目标用户（开发者、运维）通常使用落后 1-2 个大版本的设备。

**背景**：minSdk 升级到 36 的原因是为了使用 `SO_KEEPALIVE` via `StandardSocketOptions`（API 33+）和简化 BouncyCastle 兼容代码（Issue #19）。

**建议**：评估降至 `minSdk = 30`（Android 11，约 90% 覆盖率）。TCP keepalive 可通过 NDK setsockopt 在低版本实现；BouncyCastle 已经是显式注册，无需系统 provider。

---

### 10. `CrashHandler` 只保存最后一次崩溃

**问题**：`CrashHandler.kt` 每次崩溃都覆写同一个 `crash.log`，丢失历史崩溃记录，对间歇性 bug 调试不友好。

**建议**：保留最近 3 次崩溃（轮转文件名），或上架后接入 Firebase Crashlytics。

---

### 11. 缺少隐私政策 / 用户数据处理声明

**问题**：Google Play 和国内应用市场均要求 SSH 类工具（涉及「网络连接」「持久化凭证」）提供隐私政策链接，并声明：
- 收集的数据：主机名、用户名（本地存储，不上传）
- 加密方式：AES-256-GCM via Android Keystore
- 不收集用户输入内容（SSH 会话内容不上传）

**修复**：准备隐私政策页面（可以是 GitHub Pages 静态页），并在 Store listing 中填写。

---

### 12. 缺少 ProGuard/R8 混淆规则

**问题**：`app/build.gradle.kts` 的 `release` build type 没有启用 `minifyEnabled = true`，也没有 `proguard-rules.pro`。Release APK 完整保留了类名、方法名，可被轻易逆向。

**修复**：

```kotlin
// app/build.gradle.kts
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

并创建 `app/proguard-rules.pro` 包含 SSHJ/BouncyCastle 的 keep 规则。

---

### 13. 应用签名配置缺失

**问题**：`build.gradle.kts` 没有 `signingConfigs` 配置，目前只能生成 debug 签名的 APK。上架需要用正式 keystore 签名。

**修复**：

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("KEYSTORE_PATH") ?: "release.keystore")
        storePassword = System.getenv("KEYSTORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS")
        keyPassword = System.getenv("KEY_PASSWORD")
    }
}
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
    }
}
```

---

## 🟢 可选 / 长期优化

### 14. 依赖注入框架（Hilt）

当前 `HanTermApplication.kt` 手工管理进程级单例，`synchronized(lock)` + `cachedXxx` 模式随着功能增长会越来越难维护。引入 Hilt 可以标准化依赖图，便于 instrumented test 注入 mock。

### 15. 多主机 / 主机列表 UI

当前 `AppPreferences` 是单主机设计（Sprint 1 范围）。Sprint 3 应实现多主机存储（Room 或 EncryptedSharedPreferences + JSON），这是 SSH 客户端上架后用户最强烈的功能需求。

### 16. 国际化（i18n）

`strings.xml` 只有 3 个通知字符串，其余 UI 文案全部内嵌在 Compose 树中。应将所有用户可见字符串提取到 `strings.xml`，并添加简体中文翻译（`values-zh/`）。

### 17. 无障碍（Accessibility）

需要检查：
- 所有按钮/图标有 `contentDescription`
- 颜色对比度符合 WCAG AA（4.5:1）
- 支持系统字体缩放

Google Play 的 Accessibility Checklist 现在是审核的一部分。

### 18. 依赖版本升级

```
Compose BOM: 2024.10.01 → 已落后约 2 个大版本，建议升级
AGP: 8.7.3 → 建议跟进 8.x 最新稳定版
Kotlin: 1.9.24 → 建议升级到 2.0.x（Compose Multiplatform 兼容）
```

---

## 优先级汇总

| # | 问题 | 优先级 | 工作量 |
|---|------|--------|--------|
| 1 | 明文密码字段残留 | 🔴 必须 | XS |
| 2 | KeyStore 未启用用户认证 | 🔴 必须 | S |
| 3 | `libcore.*` 反射（审核风险） | 🔴 必须 | M |
| 4 | 私钥明文落盘临时文件 | 🔴 必须 | M |
| 5 | ViewModel 未用标准 Lifecycle | 🔴 必须 | L |
| 6 | SimpleDateFormat 线程不安全 | 🟡 建议 | XS |
| 7 | FontSizeController 全局可变状态 | 🟡 建议 | S |
| 8 | SharedPreferences 主机/用户名明文 | 🟡 建议 | M |
| 9 | minSdk = 36 受众过窄 | 🟡 建议 | L |
| 10 | CrashHandler 单文件覆写 | 🟡 建议 | XS |
| 11 | 缺少隐私政策声明 | 🟡 建议 | S |
| 12 | 缺少 ProGuard/R8 规则 | 🟡 建议 | S |
| 13 | 应用签名配置缺失 | 🟡 建议 | S |
| 14 | 依赖注入（Hilt） | 🟢 可选 | XL |
| 15 | 多主机支持 | 🟢 可选 | XL |
| 16 | 国际化（i18n） | 🟢 可选 | L |
| 17 | 无障碍支持 | 🟢 可选 | M |
| 18 | 依赖版本升级 | 🟢 可选 | S |

---

## 架构整体评价

**优点：**
- 关注点分离清晰：`ssh/`、`terminal/`、`data/`、`ui/` 层次明确
- `ConnectionRuntime` 的 7 步 teardown 顺序设计严谨，并发安全
- `AppLog` 的 `LogClassification` 敏感数据分级策略超出同类开源项目
- 测试覆盖率高（93 个源文件对应约 40 个测试文件）
- 后台保活（FGS + WakeLock + TCP keepalive 三层）设计务实

**需要改进：**
- ViewModel 没有遵循 Android 架构组件规范
- 安全层（KeyStore 配置）还停留在「能用」而非「达到应用商店安全标准」
- 反射调用内部 API 是 Play 审核的定时炸弹
- minSdk = 36 在上架初期会显著限制下载量
