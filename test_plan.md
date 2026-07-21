# 测试计划 (Test Plan)
针对 Android 平板 SSH 终端的中文输入链路。

## 1. Robolectric 自动化测试套件
**目标文件**: `app/src/test/java/com/taosun/hanterm/terminal/TerminalInputConnectionTest.kt`

测试场景清单（需全部断言通过）：
- `test_setComposingText_updatesStateButDoesNotWriteToSsh` (组合拼音不发包)
- `test_commitText_sendsUtf8BytesAndClearsComposing` (汉字上屏发UTF-8包)
- `test_commitText_emptyTextIsNoOp` (空字符防错)
- `test_deleteSurroundingText_whenComposing_doesNotSendDel` (组合中删除拼音)
- `test_deleteSurroundingText_whenIdle_sendsDelSequence` (非组合删除发送 0x7F)
- `test_finishComposingText_clearsStateButDoesNotWriteToSsh` (取消输入不发包)

## 2. 手工联调路径 (E2E)
**环境**: Android 平板 (API 29+) + 蓝牙/USB 实体键盘 + 搜狗或 Gboard 拼音输入法

1. **中文打字顺畅度**: 打开 `vim`，进入 Insert 模式，用拼音输入一段中文，确认期间没有任何拼音字母意外掉落在终端上，且最后选定的汉字正确上屏。
2. **打字中途取消**: 输入一段拼音，按下 `ESC` 取消选词。确认终端没有收到任何乱码或空格，并且 Vim 正确退出到了 Normal 模式（需确认 `ESC` 既退出了输入法组合状态，也发送到了远端）。
3. **退格键冲突**: 输入三个拼音字母，按一次退格（删除一个字母），再继续打字。确认不会因为退格导致输入法内部 buffer 与真实终端数据错位。
4. **控制键拦截**: 在未打字状态下按下 `Ctrl+C`、`Ctrl+D`、`Tab`。确认输入法没有弹窗，且终端进程正确收到控制信号。

## 3. SSH 兼容性验证
1. 使用 `ssh-keygen -t ed25519` 生成的私钥连接一台现代 Linux 服务器。
2. 断网模拟：在连接状态下关闭 Wi-Fi，认应用不会崩溃，而是优雅地提示断线并在日志中记录。

## 4. 文件下载传输验证（ZMODEM `sz` / trzsz `tsz`）

### 4.1 自动化覆盖边界
`ZmodemFilterTest`（6 个 case）与 `TrzszFilterTest`（7 个 case）使用 `InMemoryTransferSink` 验证协议状态机；`InboundTransferRouterTest` 验证互斥分流。这些测试**不覆盖**真实的 `MediaStoreDownloadSink`，因为后者依赖设备上的 `ContentResolver` / `MediaStore.Downloads` provider，无法在 JVM/Robolectric 沙箱中可靠复现。真实落盘行为需通过下面手动清单验证。

### 4.2 手动验证清单（非 tmux / ZMODEM）
1. 在**非 tmux** 远程 shell 执行 `sz <filename>`。
2. 确认终端没有乱码输出，文件被正确写入到平板的 `Downloads` 目录下。
3. 确认传输成功后 `MediaStore.Downloads.IS_PENDING` 被清除，文件在系统下载管理中可见。
4. 确认传输 abort（如中途取消或 CRC 失败）时，部分文件不会残留在 Downloads 中。
5. 确认 `Snackbar` 提示 `Saved to Downloads: …`（失败时为 `Transfer failed: …`，含原因）。

### 4.3 手动验证清单（tmux / trzsz）
1. 远程安装 `trzsz`，在 **tmux pane 内**执行 `tsz <filename>`。
2. 确认 Snackbar `Saved to Downloads: …`，系统 Downloads 可见同名文件。
3. 在非 tmux shell 再跑一次 `tsz`，确认同样成功。
4. 失败路径：中途 Disconnect，确认 Snackbar 含 `Transfer failed:` 且无 IS_PENDING 残留。
