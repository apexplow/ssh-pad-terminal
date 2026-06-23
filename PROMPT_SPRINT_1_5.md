# Sprint 1.5 — ConfigScreen 接入数据层 + 密码加密

## 上下文
项目:`09ssh`(Android 平板 SSH 终端),本地仓库 `/home/tao/code/ssh-pad-terminal`,
当前在 `feature/sprint-1.5-config-persistence` 分支(从 main 切出)。

Spec:`./implementation_plan.md`(563 行,必读) + `./test_plan.md`。
README 写明了 Sprint 1.5 待办:
- Compose `ConfigScreen` 接入 `AppPreferences`(主机/端口/用户名)
- 密码字段切到 `KeyStoreManager.encrypt()`
- 私钥导入流程串到 UI
- Robolectric 补 KeyEvent 路由表双链路去重用例(4-5 个)

## 现状(请先读这些文件)
- `app/src/main/java/com/example/sshterminal/ui/ConfigScreen.kt` — 70 行,`mutableStateOf` 写死,**完全没接**数据层,Import 按钮是 mock
- `app/src/main/java/com/example/sshterminal/data/prefs/AppPreferences.kt` — 73 行,API 完备,但 `password` 字段当前**明文**存 SharedPreferences
- `app/src/main/java/com/example/sshterminal/data/crypto/KeyStoreManager.kt` — 84 行,AES-256-GCM `encrypt()/decrypt()` API 已实现
- `app/src/main/java/com/example/sshterminal/ui/SshTermApp.kt` — 顶层 Composable,**没拿** `LocalContext.current`,所以连 `AppPreferences` 都初始化不了
- `app/src/test/java/.../TerminalInputConnectionTest.kt` — 6/6 绿,作为基线

## 任务清单(全部必做)

### 1. UI 接线(ConfigScreen → AppPreferences)
- `SshTermApp` 顶层:`val context = LocalContext.current` + `val prefs = remember(context) { AppPreferences(context) }`
- 用 `CompositionLocalProvider` 或函数参数把 `AppPreferences` 传给 `ConfigScreen`
- `ConfigScreen`:
  - 进入时从 `AppPreferences` 加载初值(host/port/username/password/privateKeyName)
  - 加一个 "Save" 按钮 + "Clear" 按钮
  - Save 时把所有字段写入 `AppPreferences`
  - password 字段走 `KeyStoreManager.encrypt()` → Base64 存,读取时 `decrypt()` 反向
  - Clear 按钮调 `AppPreferences.clear()`
- 不要改 `AppPreferences` 的现有字段名(host/port/username/password/privateKeyName)

### 2. 私钥导入流程
- 把 Import 按钮从 mock 换成真的:用 `ActivityResultContracts.OpenDocument()` 启 SAF
- 读取选中的 PEM 文件 → 写入 `context.filesDir/keys/<原文件名>.pem`(明文先存,后续 Sprint 用 Keystore 加密)
- 写完后 `AppPreferences.privateKeyName = "<filename>.pem"`
- 注意:`rememberLauncherForActivityResult` 需要在 Composable 里
- Manifest 已经申请权限了吗?如果没有,SAF 不需要权限,**不要**加 READ_EXTERNAL_STORAGE

### 3. 密码加密接入
**两种实现路径,选其一,说明选择**:
- **方案 A**:`AppPreferences.password` 字段类型从 `String` 改成 `ByteArray`(加密后的字节),内部自动加密/解密 —— 但这破坏现有 API
- **方案 B**(推荐):保留 `AppPreferences.password: String` API,**内部** 读写时自动经 Keystore —— 但意味着 Keystore key 是全局固定的,失去密文粒度
- **方案 C**(最干净):新增 `AppPreferences.setEncryptedPassword(ByteArray)` / `getEncryptedPassword(): ByteArray?` 方法,**ConfigScreen 显式**用 `KeyStoreManager.encrypt()` 包装,这样调用方明确知道自己在用 Keystore

**强烈建议走 C**。你要是选别的,在 commit message 里写明原因。

### 4. 新增 Robolectric 测试
- `app/src/test/java/com/example/sshterminal/data/prefs/AppPreferencesTest.kt` — 至少 4 个用例:
  - `test_saveAndLoadRoundTrip_hostPortUsername`
  - `test_clear_wipesAllFields`
  - `test_hasUsableCredentials_returnsTrueWhenPasswordSet`
  - `test_hasUsableCredentials_returnsTrueWhenPrivateKeySet`
  - `test_hasUsableCredentials_returnsFalseWhenBothBlank`
- 用 Robolectric 的 `ApplicationProvider.getApplicationContext<Context>()`
- **不要**测 KeyStore 加密往返(Robolectric 的 AndroidKeyStore 是 stub,行为不可靠,留给手工测)

### 5. (可选,但 spec 列了)KeyEvent 路由表 Robolectric 用例
- 加 4-5 个用例验证双链路去重:可打印字符 + Ctrl 时只走 onKeyDown 链路、退格 + 组合中不发 DEL 等等
- 实现可能比较 hacky(需要 mock TerminalView + KeyEvent),如果 30 分钟没搞定,**跳过,在 commit message 里写明 [DEFERRED]**
- 这部分本来 Claude 在 Sprint 1 漏掉了,你现在补

## 硬约束(违反任一 = PR 关)

- **不要 git push**(用户没要求)
- **不要 git merge**(用户会自己合并)
- **不要修改** `terminal-emulator` 黑盒
- **不要重写** `TerminalInputConnection` / `TerminalView` / `KeyMapper`(核心 IME 链路已绿)
- **不要删** Sprint 1 的 6 个 Robolectric 用例(它们必须继续绿)
- **不要引入** 未在 `implementation_plan.md` 中提到的库(除 SAF / Robolectric 已有的)
- **不要改** `AppPreferences` 的字段名 / `KeyStoreManager` 的 API 形状

## 验收红线

1. `./gradlew :app:testDebugUnitTest` **全绿**(原 6 + 至少 4 新增,共 10+)
2. `./gradlew :app:assembleDebug` 成功
3. `ConfigScreen.kt` **不再有任何 `mutableStateOf` 持有明文凭据**作为唯一存储(初值可暂存,Save 必须走 prefs)
4. `SshTermApp.kt` 显式拿 `LocalContext.current`
5. 至少 3 个新 git commit(建议:Sprint 1.5-prefs-test / Sprint 1.5-ui-wiring / Sprint 1.5-keyimport + tests)

## 环境

每个 shell 必 source:
```bash
export SDKMAN_DIR="$HOME/.sdkman"
source "$HOME/.sdkman/bin/sdkman-init.sh"
export ANDROID_HOME=$HOME/.android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
export JAVA_HOME=$HOME/.sdkman/candidates/java/current
```
Gradle wrapper 自带 JDK 17,build 走 wrapper 即可。

## 工作方式

- 启动后先 ls / git status 探查,然后读 ConfigScreen + AppPreferences + KeyStoreManager 全文
- 写代码、改代码、跑测试循环,每完成一段 commit 一次
- 完成后输出:
  - git log --oneline
  - ./gradlew :app:testDebugUnitTest 摘要
  - ./gradlew :app:assembleDebug 摘要
  - 任何 [DEFERRED] 项
  - 任何对 Sprint 2 的发现(用 [SPRINT_2_NOTE] 标记)

## 开始

启动第一件事:跑 git status + git log + 检查当前分支,然后按任务清单 1→5 顺序执行。
如果有任何歧义(尤其是方案 A/B/C 选哪个),在 commit message 里写明你的选择和理由,不要中途停下来问。