# GEARS Behavioral Spec — ssh-pad-terminal

> Generated: 2026-06-25 (initial)
> Last status refresh: 2026-07-02 (Sprint 3 Modules 15–17 landed)
> Scope: Sprint 0 / 1 / 1.5 / 2 (terminal core + SSH transport) + Sprint 2.5 security (Modules 11–14) + Sprint 3 (Modules 15–17, all landed on `feat/alt-buffer-cursor-scroll`)
> Out of scope: multi-host list/groups/CRUD, SFTP, port forwarding, ProxyJump, Mosh — all still require an explicit ask per `CLAUDE.md`
> Source: 31 main Kotlin files + 31 test classes + `docs/REVIEW_2026-06-24.md` + `PROMPT_SPRINT_2_FIX.md`
> Verification status: **279 active unit tests** + 6 `@Ignore` (see test inventory — Sprint 2.5 Modules 11–14 landed 2026-06-29; Sprint 3 Modules 15–17 landed 2026-07-02 with 3 new test classes + 4 new cases added to `SshSessionWriteTest`).
>
> **Pattern reminder** (from `gears-spec-syntax` skill):
> ```
> [Given <static>] [While <state>] [When <trigger>] <actor> shall <behavior>.
> ```
> - **Given** = static config that does not change mid-execution
> - **While** = runtime state that may flip
> - **When** = the trigger event (at most one per spec)
> - **shall** = observable behavior
> - Errors are NOT a separate mode — they are a `<trigger>` paired with `shall <fallback>` or `shall not <bad>`.

---

## Table of Contents

1. [Module 1: IME → SSH byte pipeline (`terminal/`)](#module-1-ime--ssh-byte-pipeline-terminal)
2. [Module 2: Physical-key routing (`terminal/KeyMapper` + `TerminalView.onKeyDown`)](#module-2-physical-key-routing-terminalkeymapper--terminalviewonkeydown)
3. [Module 3: Termux emulator wrapper (`terminal/TerminalView`)](#module-3-termux-emulator-wrapper-terminalterminalview)
4. [Module 4: SSH transport (`ssh/SshSession` + `SshTransport` + `ChannelTransport`)](#module-4-ssh-transport-sshsshsession--sshtransport--channeltransport)
5. [Module 5: SSH connect lifecycle (`ssh/SshClient`)](#module-5-ssh-connect-lifecycle-sshsshclient)
6. [Module 6: Error translation (`ssh/SshErrorMessages`)](#module-6-error-translation-sshssherrormessages)
7. [Module 7: Credential storage (`data/crypto/KeyStoreManager` + `data/prefs/AppPreferences`)](#module-7-credential-storage-datacryptokeystoremanager--dataprefsapppreferences)
8. [Module 8: Auth providers (`ssh/auth/`)](#module-8-auth-providers-sshauth)
9. [Module 9: Active session survival across Activity recreation (`ssh/ActiveSshSessionStore`)](#module-9-active-session-survival-across-activity-recreation-sshactivesshsessionstore)
10. [Module 10: Crash capture (`CrashHandler`)](#module-10-crash-capture-crashhandler)
11. [Module 11: Security — Host fingerprint (Sprint 2.5, S1)](#module-11-security--host-fingerprint-sprint-25-s1)
12. [Module 12: Security — Private key at rest (Sprint 2.5, S2)](#module-12-security--private-key-at-rest-sprint-25-s2)
13. [Module 13: Security — Debug log gating (Sprint 2.5, S3)](#module-13-security--debug-log-gating-sprint-25-s3)
14. [Module 14: Security — Auth diagnostic gating (Sprint 2.5, S4)](#module-14-security--auth-diagnostic-gating-sprint-25-s4)
15. [Module 15: Landscape split layout (Sprint 3, S1)](#module-15-landscape-split-layout-sprint-3-s1)
16. [Module 16: Command snippets (Sprint 3, S2)](#module-16-command-snippets-sprint-3-s2)
17. [Module 17: Session close-reason disambiguation (Sprint 3, S3)](#module-17-session-close-reason-disambiguation-sprint-3-s3)
18. [Cross-cutting invariants (regressions to guard)](#cross-cutting-invariants-regressions-to-guard)
19. [GEARS → GWT test translation table](#gears--gwt-test-translation-table)
20. [Spec coverage matrix](#spec-coverage-matrix)

---

## Module 1: IME → SSH byte pipeline (`terminal/`)

This module is the **Sprint 1.5 P0 contract** — every spec below is regression-critical per `docs/REVIEW_2026-06-24.md` §3.10–3.11 and `test_plan.md` §1. The `userInImeContext` latch is what closes the Gboard `setComposingText("") → deleteSurroundingText(1, 0)` race that would otherwise leak DEL bytes to the remote shell during a pinyin cancel.

### 1.1 `TerminalInputConnection.setComposingText`

| ID | Spec | Why it matters |
|---|---|---|
| TIC-SC-01 | When the IME invokes `setComposingText` with non-empty text, `TerminalInputConnection` shall set `isComposing = true`, latch `userInImeContext = true`, surface the text as a hint via `TerminalComposingView.showComposingHint`, and shall not write any bytes to `TerminalEndpoint`. | Pin `test_setComposingText_updatesStateButDoesNotWriteToSsh`; prevents "pinyin letters leak to remote" bug. |
| TIC-SC-02 | When the IME invokes `setComposingText` with empty text, `TerminalInputConnection` shall clear `isComposing` to `false` and shall not reset `userInImeContext`. | The latch must survive `setComposingText("")` so the next `deleteSurroundingText` (the Gboard race) is still swallowed — this is the P0 fix. |

### 1.2 `TerminalInputConnection.commitText`

| ID | Spec |
|---|---|
| TIC-CT-01 | When the IME invokes `commitText` with non-empty text, `TerminalInputConnection` shall set `userInImeContext = true`, clear `isComposing = false`, hide the composing hint, and write `text.toString().toByteArray(Charsets.UTF_8)` to `TerminalEndpoint`. |
| TIC-CT-02 | When the IME invokes `commitText` with empty text, `TerminalInputConnection` shall not write any bytes to `TerminalEndpoint`. | Empty commit is a no-op (`test_commitText_emptyTextIsNoOp`). |
| TIC-CT-03 | When `commitText` is invoked, `TerminalInputConnection` shall set `userInImeContext = true` regardless of the text's content. | Latch must also latch on commit, so a backspace *right after* committing a candidate still routes through the IME. |

### 1.3 `TerminalInputConnection.finishComposingText`

| ID | Spec |
|---|---|
| TIC-FC-01 | When the IME invokes `finishComposingText`, `TerminalInputConnection` shall set `userInImeContext = true`, clear `isComposing = false`, hide the composing hint, and shall not write any bytes to `TerminalEndpoint`. | Mirrors `test_finishComposingText_clearsStateButDoesNotWriteToSsh`; same latch rationale as commit. |

### 1.4 `TerminalInputConnection.deleteSurroundingText`  *(P0 Gboard race fix)*

| ID | Spec |
|---|---|
| TIC-DS-01 | While `userInImeContext == true`, when the IME invokes `deleteSurroundingText(before, after)`, `TerminalInputConnection` shall invoke the super-class implementation and shall not write any DEL bytes to `TerminalEndpoint`. | Closes the Gboard race; covered by `test_deleteSurroundingText_whenComposing_doesNotSendDel`. |
| TIC-DS-02 | While `userInImeContext == false` and `beforeLength > 0`, when the IME invokes `deleteSurroundingText(beforeLength, 0)`, `TerminalInputConnection` shall write `beforeLength` bytes of value `0x7F` to `TerminalEndpoint` and shall return `true`. | Idle DEL → remote; covered by `test_deleteSurroundingText_whenIdle_sendsDelSequence`. |
| TIC-DS-03 | While `userInImeContext == false` and `beforeLength <= 0`, when the IME invokes `deleteSurroundingText`, `TerminalInputConnection` shall delegate to the super-class implementation and shall not write any DEL bytes. | Cursor-only deletes are a no-op for the wire. |
| TIC-DS-04 | When `deleteSurroundingText` writes DEL bytes to the endpoint (TIC-DS-02 path), `TerminalInputConnection` shall reset `userInImeContext` to `false` so the next truly-idle backspace also reaches SSH. | Without this reset, every backspace forever would be swallowed after the first IME interaction — explicit in the kdoc. |

### 1.5 `TerminalInputConnection.sendKeyEvent`

| ID | Spec |
|---|---|
| TIC-SK-01 | When the action is not `ACTION_DOWN`, `TerminalInputConnection.sendKeyEvent` shall return `true` (consume the event) and shall not write any bytes. | UP / repeat / etc. are absorbed. |
| TIC-SK-02 | While `isComposing == true`, when `ACTION_DOWN` arrives, `TerminalInputConnection.sendKeyEvent` shall return `true` and shall not write any bytes. | IME owns the letter pipeline mid-composition. |
| TIC-SK-03 | While `isComposing == false`, when `ACTION_DOWN` arrives and `KeyMapper.resolve` returns `KeyResolution.Send(bytes)`, `TerminalInputConnection.sendKeyEvent` shall write `bytes` to `TerminalEndpoint` and shall return `true`. |
| TIC-SK-04 | While `isComposing == false`, when `ACTION_DOWN` arrives and `KeyMapper.resolve` returns a non-`Send` verdict (`Swallow` / `Ignore` / `Paste`), `TerminalInputConnection.sendKeyEvent` shall not write any bytes and shall return `false`. | Pastes are surfaced by the View layer, not the InputConnection. |

### 1.6 `TerminalEndpoint` contract

| ID | Spec |
|---|---|
| TE-01 | Given a call to `TerminalEndpoint.write(bytes)`, the endpoint shall accept the bytes in the order received and shall not block the caller. | The interface is `fun interface TerminalEndpoint { fun write(bytes: ByteArray) }`; the SAM contract is "fire and forget, in-order". |
| TE-02 | Given an empty `bytes` array, `TerminalEndpoint.write` shall be a no-op. | `SshSession.write` enforces this for the SSH case; `MockEchoSession` should match. |

---

## Module 2: Physical-key routing (`terminal/KeyMapper` + `TerminalView.onKeyDown`)

Per `docs/REVIEW_2026-06-24.md` §3.10 — these rules are the **load-bearing non-negotiable routing invariants**; touching them is a PR-closing offense per `README.md`.

### 2.1 Routing verdicts (`KeyMapper.KeyResolution`)

| ID | Spec |
|---|---|
| KM-VRD-01 | Given a `KeyEvent`, `KeyMapper.resolve` shall return exactly one of four verdicts: `Send(ByteArray)`, `Swallow`, `Ignore`, or `Paste`. |
| KM-VRD-02 | When the verdict is `Send(bytes)`, the `bytes` payload shall be the exact SSH-protocol byte sequence to forward to the remote (UTF-8 for multi-byte, ASCII control bytes for Ctrl-chords, ANSI escape sequences for arrows/F-keys). |
| KM-VRD-03 | When the verdict is `Swallow`, the View shall return `true` from `onKeyDown` so the system stops dispatching the event to the IME. |
| KM-VRD-04 | When the verdict is `Paste`, the View shall read the system clipboard and write its UTF-8 text contents to the `TerminalEndpoint`. | Desktop-style paste from a hardware keyboard. |
| KM-VRD-05 | When the verdict is `Ignore`, the View shall return `false` from `onKeyDown` so the event continues to the InputConnection / IME. |

### 2.2 Paste shortcut (Ctrl+Shift+V)

| ID | Spec |
|---|---|
| KM-PS-01 | Given `keyCode == KEYCODE_V` and `event.isCtrlPressed && event.isShiftPressed`, `KeyMapper.resolve` shall return `KeyResolution.Paste`. | Wins over the Ctrl+V byte path because checked first. |
| KM-PS-02 | Given `keyCode == KEYCODE_V` and `event.isCtrlPressed && !event.isShiftPressed`, `KeyMapper.resolve` shall return `KeyResolution.Ignore`. | Ctrl+V alone must fall through to the printable-key path so the IME can emit a literal "V" — defended by `test_ctrlV_alone_doesNotResolveToPaste`. |
| KM-PS-03 | Given `keyCode == KEYCODE_V` and `!event.isCtrlPressed`, `KeyMapper.resolve` shall return `KeyResolution.Ignore`. | Shift+V alone produces a literal "V". |

### 2.3 IME-internal shortcuts (must never reach SSH)

| ID | Spec |
|---|---|
| KM-IS-01 | Given `event.keyCode == KEYCODE_LANGUAGE_SWITCH`, `KeyMapper.resolve` shall return `KeyResolution.Swallow`. | Spec P0 — IME language-switch key must not leak to SSH. |
| KM-IS-02 | Given `event.keyCode == KEYCODE_SPACE` and `event.isCtrlPressed`, `KeyMapper.resolve` shall return `KeyResolution.Swallow`. |
| KM-IS-03 | Given `event.keyCode == KEYCODE_SPACE` and `event.isShiftPressed`, `KeyMapper.resolve` shall return `KeyResolution.Swallow`. |

### 2.4 Ctrl-letter mapping (xterm convention)

| ID | Spec |
|---|---|
| KM-CTL-01 | Given `event.isCtrlPressed` and a `keyCode` in `KEYCODE_A..KEYCODE_Z`, `KeyMapper.resolve` shall return `KeyResolution.Send(byteArrayOf(<byte>))` where `<byte>` maps `A→0x01, B→0x02, …, Z→0x1A` (excluding V, see KM-PS-02). | ✅ pinned by `KeyEventRoutingTest` (Ctrl+A through Ctrl+Z rows, added in `819c6bf test(terminal): pin Ctrl+ A-Z + \ + ] byte routing` + `9d1830d feat(terminal): expand ctrlSequence mapping for tmux / readline shortcuts`). |
| KM-CTL-02 | Given `event.isCtrlPressed` and `keyCode == KEYCODE_BACKSLASH`, `KeyMapper.resolve` shall return `KeyResolution.Send(byteArrayOf(0x1C))`. | ✅ pinned by `KeyEventRoutingTest.ctrlBackslash_producesFs` (0x1C). |
| KM-CTL-03 | Given `event.isCtrlPressed` and `keyCode == KEYCODE_RIGHT_BRACKET`, `KeyMapper.resolve` shall return `KeyResolution.Send(byteArrayOf(0x1D))`. | ✅ pinned by `KeyEventRoutingTest.ctrlRightBracket_producesGs` (0x1D). |
| KM-CTL-04 | Given `event.isCtrlPressed` and `keyCode == KEYCODE_ESCAPE`, `KeyMapper.resolve` shall return `KeyResolution.Send(byteArrayOf(0x1B))`. | 🟡 pinned for the View path; explicit `KeyMapper`-level test not yet added (KM-CTL-04 under-tested per coverage matrix). |
| KM-CTL-05 | Given `event.isCtrlPressed` and `keyCode == KEYCODE_V`, `KeyMapper.resolve` shall return `KeyResolution.Ignore` (so Ctrl+V falls through to the printable path). | ✅ pinned by `KeyEventRoutingTest.ctrlV_alone_doesNotResolveToPaste`. |
| KM-CTL-06 | Given `event.isCtrlPressed` and `keyCode == KEYCODE_SPACE`, `KeyMapper.resolve` shall return `KeyResolution.Swallow` (handled upstream as IME language switch). | ✅ pinned by `KeyEventRoutingTest.ctrlSpace_swallowed`. |
| KM-CTL-07 | Given `event.isCtrlPressed` and any other `keyCode` not in the above list, `KeyMapper.resolve` shall fall through to the per-`keyCode` ANSI mapping or `Ignore` if no mapping exists. |

### 2.5 Alt-letter mapping (xterm ESC prefix)

| ID | Spec |
|---|---|
| KM-ALT-01 | Given `event.isAltPressed && event.unicodeChar > 0`, `KeyMapper.resolve` shall return `KeyResolution.Send(byteArrayOf(0x1B) + event.unicodeChar.toChar().toString().toByteArray(Charsets.UTF_8))`. |

### 2.6 Per-keyCode ANSI mapping (no modifier)

| ID | Spec |
|---|---|
| KM-KC-01 | When `KeyMapper.resolve` reaches the per-`keyCode` switch and `keyCode == KEYCODE_DEL`, it shall return `KeyResolution.Send(byteArrayOf(0x7F))`. |
| KM-KC-02 | When `keyCode == KEYCODE_ENTER`, it shall return `KeyResolution.Send("\r".toByteArray(Charsets.UTF_8))`. |
| KM-KC-03 | When `keyCode == KEYCODE_TAB`, it shall return `KeyResolution.Send("\t".toByteArray(Charsets.UTF_8))`. |
| KM-KC-04..15 | When `keyCode` is one of `DPAD_UP/DOWN/LEFT/RIGHT`, `MOVE_HOME/END`, `PAGE_UP/DOWN`, `FORWARD_DEL`, `F1..F12`, it shall return `KeyResolution.Send(<ANSI sequence as documented in source>)`. |
| KM-KC-16 | When `keyCode` is none of the above, `KeyMapper.resolve` shall return `KeyResolution.Ignore`. |

### 2.7 `TerminalView.dispatchKeyEventPreIme` (Pre-Ime hook)

| ID | Spec |
|---|---|
| TV-PRE-01 | When `dispatchKeyEventPreIme` is called and `KeyMapper.resolve` returns `KeyResolution.Paste`, the View shall read the clipboard and write its UTF-8 contents to `endpoint`, and shall consume both `ACTION_DOWN` and `ACTION_UP` (return `true` for both). | Fixes "Gboard swallows Ctrl+Shift+V before onKeyDown runs" — see `TerminalView.kt:439-450` kdoc. |
| TV-PRE-02 | When `KeyMapper.resolve` returns any verdict other than `Paste`, the View shall delegate to `super.dispatchKeyEventPreIme` and consume nothing. |

### 2.8 `TerminalView.onKeyDown` (the dual-link dedup logic)

| ID | Spec |
|---|---|
| TV-OKD-01 | While `inputConnection.isComposing() == true`, when `onKeyDown` is invoked and `KeyMapper.resolve` returns `KeyResolution.Swallow`, the View shall return `true` and shall not write any bytes. | Mid-composition Ctrl+Space must not leak to SSH. |
| TV-OKD-02 | While `inputConnection.isComposing() == true`, when `onKeyDown` is invoked and `KeyMapper.resolve` returns `KeyResolution.Paste`, the View shall read the clipboard and write its UTF-8 contents to `endpoint`, and shall return `true`. | Paste still works mid-composition. |
| TV-OKD-03 | While `inputConnection.isComposing() == true`, when `onKeyDown` is invoked and `keyCode` is `KEYCODE_DEL` or `KEYCODE_ENTER`, the View shall return `false` and shall not write any bytes. | Let the IME handle DEL/ENTER; do not double-consume. |
| TV-OKD-04 | While `inputConnection.isComposing() == true`, when `onKeyDown` is invoked with any other key, the View shall return `false` and shall not write any bytes. | IME owns the letter pipeline. |
| TV-OKD-05 | While `inputConnection.isComposing() == false`, when `onKeyDown` is invoked with a printing key and no Ctrl/Alt modifier, the View shall return `false` and shall not write any bytes. | Printable chars → InputConnection (no double-write). |
| TV-OKD-06 | While `inputConnection.isComposing() == false`, when `onKeyDown` is invoked and `KeyMapper.resolve` returns `KeyResolution.Send(bytes)`, the View shall write `bytes` to `endpoint` and shall return `true`. |
| TV-OKD-07 | While `inputConnection.isComposing() == false`, when `onKeyDown` is invoked and `KeyMapper.resolve` returns `KeyResolution.Paste`, the View shall read the clipboard and write its UTF-8 contents to `endpoint`, and shall return `true`. |
| TV-OKD-08 | While `inputConnection.isComposing() == false`, when `onKeyDown` is invoked and `KeyMapper.resolve` returns `KeyResolution.Swallow`, the View shall return `true` and shall not write any bytes. |
| TV-OKD-09 | While `inputConnection.isComposing() == false`, when `onKeyDown` is invoked and `KeyMapper.resolve` returns `KeyResolution.Ignore`, the View shall return `false` and shall not write any bytes. |

---

## Module 3: Termux emulator wrapper (`terminal/TerminalView`)

Two Sprint 2 regression fixes live here:

1. The `isAltBufferScrollCrashPath` guard — the previous version crashed the process when the user scrolled inside a remote TUI on the alternate screen (vim, less, htop, fzf). Fix documented in `TerminalView.kt` kdoc and pinned by `AltBufferScrollCrashGuardTest` (6 tests).
2. The `onLayout` re-measure (`TerminalView.kt:223-232` + kdoc at `195-222`) — without it, the inner Termux view is locked into its intrinsic 80×24 size on first measure pass, leaving a ~1/4-screen block on tablets. Pinned by `TerminalViewLayoutTest` (2 tests: TV-LY-01, TV-LY-02). Fix landed in `a0a34a1 fix(terminal): re-measure inner Termux view in onLayout to fill wrapper`.

### 3.1 Emulator wiring

| ID | Spec |
|---|---|
| TV-EM-01 | When the `TerminalView` is constructed, the constructor shall set the Termux `TerminalView.setTextSize(DEFAULT_TEXT_SIZE)` to initialise `mRenderer` (so `onDraw` does not NPE). |
| TV-EM-02 | When the `TerminalView` is constructed, the constructor shall set the Termux `TerminalView.setTerminalViewClient(<stub>)` to a stub that returns `false` from `onScale` and requests focus on single-tap. |
| TV-EM-03 | When the `TerminalView` is constructed, the constructor shall set `termuxView.mEmulator = <the emulator instance>` to wire our custom `TerminalEmulator` (bypassing `TerminalSession` to avoid the JNI local-shell fork). |
| TV-EM-04 | When the `TerminalView` is constructed, the constructor shall install a `termuxView.mRenderer`-independent `OnLayoutChangeListener` that calls `reportPtyResize(width, height)`. |

### 3.2 Layout correction (regression: 1/4-screen block on tablets)

| ID | Spec |
|---|---|
| TV-LY-01 | When `onLayout(changed, l, t, r, b)` returns and the inner `termuxView.width != width` or `termuxView.height != height`, the View shall re-measure `termuxView` with `MeasureSpec.EXACTLY` and re-layout it to `(0, 0, width, height)`. | Prevents the inner view from being locked into its intrinsic 80×24 size on first measure pass. ✅ pinned by `TerminalViewLayoutTest.test_onLayout_divergentInnerSize_remeasuresInner`. |
| TV-LY-02 | When `onLayout(changed, l, t, r, b)` returns and the inner `termuxView` already matches the wrapper, the View shall be a no-op. | Idempotent — does not disturb rotation / font-size change re-layouts. ✅ pinned by `TerminalViewLayoutTest.test_onLayout_matchingSize_isNoOp`. |

### 3.3 PTY resize signalling

| ID | Spec |
|---|---|
| TV-PTY-01 | When `termuxView`'s layout dimensions change, the View shall compute `(cols, rows)` from the new pixel dimensions and invoke the `ptyResizeListener` with the new `(cols, rows, widthPx, heightPx)`. |
| TV-PTY-02 | When `ptyResizeListener` is registered, the View shall invoke it once immediately with the current `termuxView.width, termuxView.height` so a freshly-bound session gets the current size rather than waiting for the next layout pass. |
| TV-PTY-03 | When the layout dimensions change to the same `(cols, rows)` as the last reported, the View shall not invoke the `ptyResizeListener`. | Prevents SIGWINCH spam from `OnGlobalLayoutListener` firing for unrelated reasons (keyboard insets, IME show/hide). |

### 3.4 Font size (regression: held volume key → "connection disconnected" overlay)

| ID | Spec |
|---|---|
| TV-FS-01 | Given a `setTextSize(size)` call where `size == currentTextSize`, the View shall be a no-op. | Prevents the held-volume-key / Compose-recomposition flood that caused the SIGWINCH-induced channel close on dropbear / busybox. |
| TV-FS-02 | Given a `setTextSize(size)` call where `size != currentTextSize`, the View shall call `termuxView.setTextSize(size)`, update `currentTextSize = size`, and re-run `reportPtyResize`. |

### 3.5 Alt-buffer scroll crash guard (Sprint 2 regression fix)

| ID | Spec |
|---|---|
| TV-AB-01 | Given a `MotionEvent` with `actionMasked == ACTION_MOVE`, when `emulator.isAlternateBufferActive && !emulator.isMouseTrackingActive` is true, the View shall return `true` (consume the event) and shall not propagate the gesture to the Termux inner view. | Prevents the NPE on `mTermSession.getEmulator()` inside Termux's `doScroll → handleKeyCode` alt-buffer branch. |
| TV-AB-02 | Given a `MotionEvent` with `actionMasked == ACTION_MOVE` and the predicate in TV-AB-01 is false, the View shall return `false` and let the inner view's `GestureDetector` handle the gesture. | Normal scrollback is safe. |
| TV-AB-03 | Given a `MotionEvent` with `actionMasked == ACTION_UP` or `ACTION_CANCEL`, the View shall return `false`. | Don't consume the up-pair; let the inner view's `GestureDetector` reset `mScrollRemainder` and fire mouse-up. |
| TV-AB-04 | Given a `MotionEvent` with `actionMasked == ACTION_SCROLL` (from a mouse wheel / trackpad), when the alt-buffer-crash predicate is true, the View shall return `true` and shall not propagate to the inner view. | Compose's `pointerInteropFilter` only covers touch events; generic motion from a Bluetooth mouse lands here. |
| TV-AB-05 | Given a `MotionEvent` with `actionMasked == ACTION_SCROLL` and the alt-buffer-crash predicate is false, the View shall delegate to `super.dispatchGenericMotionEvent`. |

### 3.5b Two-finger page scrollback (added 2026-06-30)

| ID | Spec |
|---|---|
| TV-SB-01 | Given a `MotionEvent` with `pointerCount >= 2` and `actionMasked == ACTION_POINTER_DOWN`, the View shall consume the event (return `true` from `dispatchTouchEvent`) and `state.value.isInScrollback` shall be `true` immediately afterwards. | Two-finger entry into scrollback. |
| TV-SB-02 | Given a scrollback-active `ScrollbackController` and a `MotionEvent` with `actionMasked == ACTION_UP` whose centroid Y differs from the initial POINTER_DOWN centroid by more than `lineSpacing * mRows / 2`, the controller shall invoke `innerView.doScroll(move, ±mRows)` and the inner view's `mTopRow` shall change by exactly one page in the indicated direction. | Page scroll threshold + doScroll reflection. |
| TV-SB-03 | Given a scrollback-active `ScrollbackController` and a `MotionEvent` with `actionMasked == ACTION_UP` whose centroid Y differs from the initial centroid by less than the threshold, no `doScroll` call shall happen; `mTopRow` is unchanged. | Sub-threshold swipe is a no-op. |
| TV-SB-04 | Given `view.scrollToBottom()`, the inner view's `mTopRow` shall be `0` and `state.value.isInScrollback` shall be `false`. | Banner tap path. |
| TV-SB-05 | Given a `transcriptOutput.write` event with `isInScrollback == true` and `len > 0`, `state.value.pendingOutputCount` shall increase by `max(1, len / columns)`. | Output counter accumulation. |
| TV-SB-06 | Given the emulator is in alt-buffer mode (`isAlternateBufferActive && !isMouseTrackingActive`) and the user does a two-finger gesture, the controller shall consume the gesture but NOT call `doScroll` (avoids the existing branch-2 NPE in `AltBufferScrollCrashGuardTest`). | Alt-buffer safety. |

### 3.6 IME editor config

| ID | Spec |
|---|---|
| TV-IME-01 | When `onCreateInputConnection(outAttrs)` is called, the View shall set `outAttrs.inputType` to `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_NORMAL or TYPE_TEXT_FLAG_MULTI_LINE` and shall **not** set `TYPE_TEXT_FLAG_NO_SUGGESTIONS`. | Per `implementation_plan.md` §"输入链路设计": `NO_SUGGESTIONS` was previously set and suppressed IME composing entirely, breaking Chinese input. |
| TV-IME-02 | When `onCreateInputConnection(outAttrs)` is called, the View shall set `outAttrs.imeOptions` to `IME_ACTION_NONE or IME_FLAG_NO_FULLSCREEN or IME_FLAG_NO_EXTRACT_UI`. |
| TV-IME-03 | When `onCreateInputConnection(outAttrs)` is called, the View shall return a new `TerminalInputConnection(this, endpoint)` and cache it in `inputConnection`. |

### 3.7 IME service on input

| ID | Spec |
|---|---|
| TV-IN-01 | When a `MotionEvent` with `action == ACTION_DOWN` is dispatched, the View shall call `requestFocus()` and shall delegate to `super.dispatchTouchEvent`. | Ensures the IME candidate window attaches. |

---

## Module 4: SSH transport (`ssh/SshSession` + `SshTransport` + `ChannelTransport`)

### 4.1 `SshSession.write`

| ID | Spec |
|---|---|
| SS-WR-01 | Given `bytes.isEmpty()`, `SshSession.write` shall return immediately and shall not enqueue any work. |
| SS-WR-02 | Given `closed.get() == true`, `SshSession.write` shall return immediately and shall not enqueue any work. |
| SS-WR-03 | Given non-empty `bytes` and `closed.get() == false`, `SshSession.write` shall enqueue a copy of `bytes` on `writeExecutor` and return immediately. | The caller (UI / IME thread) does not touch the socket. |
| SS-WR-04 | When the queued write task starts executing and `closed.get() == true`, the task shall be a no-op. | Race protection: close() can flip between enqueue and execution. |
| SS-WR-05 | When the queued write task starts executing and `closed.get() == false`, the task shall call `transport.write(payload)` exactly once. | `payload` is a copy so the original array can be safely mutated / GC'd by the caller. |

### 4.2 `SshSession.readInto`  *(P0: cancellation ≠ close)*

| ID | Spec |
|---|---|
| SS-RI-01 | Given the underlying transport returns `null` from `readBytes()`, `SshSession.readInto` shall break the loop, return `Result.success(Unit)`, and shall call `close()` in the `finally` block. | EOF: clean disconnect. |
| SS-RI-02 | Given the coroutine is cancelled mid-read, `SshSession.readInto` shall rethrow the `CancellationException` unwrapped, shall not wrap it in `Result`, and shall **not** call `close()` in the `finally` block. | The session is a longer-lived resource than any one read loop; cancellation is a "stop reading" signal, not a "kill session" signal. |
| SS-RI-03 | Given `transport.readBytes()` throws a `SocketException`, `SshSession.readInto` shall return `Result.failure(SshException(SshErrorMessages.friendly(e), e))` and shall call `close()`. |
| SS-RI-04 | Given `transport.readBytes()` throws a `SocketTimeoutException`, `SshSession.readInto` shall return `Result.failure(SshException(SshErrorMessages.friendly(e), e))` and shall call `close()`. | `SocketTimeoutException` extends `InterruptedIOException`, not `SocketException` — must be caught explicitly. |
| SS-RI-05 | Given `transport.readBytes()` throws a `SSHException` (sshj), `SshSession.readInto` shall return `Result.failure(SshException(SshErrorMessages.friendly(e), e))` and shall call `close()`. |
| SS-RI-06 | Given the read loop succeeds and `sink` throws, `SshSession.readInto` shall propagate the exception and shall call `close()`. | The session is unusable if the UI sink is broken. |
| SS-RI-07 | When the loop is iterating, the blocking `transport.readBytes()` call shall run on `Dispatchers.IO` and the `sink(bytes)` call shall run on the caller's coroutine context. | Caller's context is typically `Dispatchers.Main` for the emulator write. |
| SS-RI-08 | When `transport.readBytes()` returns a non-null `bytes` array, `SshSession.readInto` shall invoke `sink(bytes)` and shall not return until the sink returns. | Sequential in-order delivery. |

### 4.3 `SshSession.resizePty`

| ID | Spec |
|---|---|
| SS-RP-01 | Given `closed.get() == true`, `SshSession.resizePty` shall return immediately. |
| SS-RP-02 | Given `closed.get() == false`, `SshSession.resizePty` shall enqueue a task on `writeExecutor` that calls `transport.resizePty(cols, rows, widthPx, heightPx)` exactly once. |

### 4.4 `SshSession.close`  *(idempotent)*

| ID | Spec |
|---|---|
| SS-CL-01 | Given `closed.get() == false`, `SshSession.close` shall atomically set `closed` to `true`, enqueue a task that calls `transport.close()` and then `onClose()`, and shall call `writeExecutor.shutdown()`. |
| SS-CL-02 | Given `closed.get() == true`, `SshSession.close` shall return immediately and shall not enqueue any work. | The `onClose` hook tears down the parent `SshClient` — firing it twice would null out a still-in-use client. |

### 4.5 `ChannelTransport.write`  *(P0: SSHJ `Channel.outputStream` is buffered)*

| ID | Spec |
|---|---|
| CT-WR-01 | Given any `bytes` array, `ChannelTransport.write` shall call `channel.outputStream.write(bytes)` followed by `channel.outputStream.flush()`. | Without the flush, keystrokes pile up until the next 1 KiB boundary. |
| CT-WR-02 | Given an empty `bytes` array, `ChannelTransport.write` shall still call `flush()`. | Belt-and-braces: ensures any prior buffered bytes are pushed. |

### 4.6 `ChannelTransport.readBytes`

| ID | Spec |
|---|---|
| CT-RB-01 | Given the underlying `input.read(buf)` returns a positive `n <= buf.size`, `ChannelTransport.readBytes` shall return a `ByteArray` of length `n` containing the first `n` bytes. |
| CT-RB-02 | Given the underlying `input.read(buf)` returns `0` or negative, `ChannelTransport.readBytes` shall return `null`. | EOF / error → null. |

### 4.7 `ChannelTransport.resizePty`

| ID | Spec |
|---|---|
| CT-RP-01 | Given the channel is a `Session.Shell`, `ChannelTransport.resizePty` shall call `shell.changeWindowDimensions(cols, rows, widthPx, heightPx)` wrapped in `runCatching`. |
| CT-RP-02 | Given the channel is NOT a `Session.Shell`, `ChannelTransport.resizePty` shall be a no-op. | Command / Subsystem don't support window-change. |

### 4.8 `ChannelTransport.close`

| ID | Spec |
|---|---|
| CT-CL-01 | Given any channel, `ChannelTransport.close` shall call `IOUtils.closeQuietly(channel as java.io.Closeable)`. | Both closeQuietly overloads match; explicit cast disambiguates. |

---

## Module 5: SSH connect lifecycle (`ssh/SshClient`)

### 5.1 Constructor + context invariant

| ID | Spec |
|---|---|
| SC-CT-01 | Given a non-application `Context` (e.g. an Activity), `SshClient` construction shall throw `IllegalStateException("SshClient requires applicationContext; got <class>")`. | Prevents Activity leak across configuration changes. |
| SC-CT-02 | Given the application `Context`, `SshClient` shall store `context.applicationContext` and shall not retain the original. |

### 5.2 `SshClient.connect`  *(P0: SSH-level keepalive + foreground service)*

| ID | Spec |
|---|---|
| SC-CN-01 | Given valid (host, port, username, auth), `SshClient.connect` shall run the SSHJ setup on `Dispatchers.IO`, allocate a PTY with `ECHO=1, ECHOE=1, ICANON=1, ONLCR=1`, open a shell channel, and return `Result.success(SshSession(...))`. |
| SC-CN-02 | Given any failure during the SSHJ setup, `SshClient.connect` shall return `Result.failure(SshException(SshErrorMessages.friendly(t), t))` where `t` is the original throwable, and shall log the full stack trace at `Log.e` level. |
| SC-CN-03 | Given a `CancellationException`, `SshClient.connect` shall rethrow it unwrapped, not wrapped in `Result.failure`. | Structured concurrency: a wrapped cancellation would be misinterpreted as a connect failure. |
| SC-CN-04 | Given a successful connect, `SshClient.connect` shall call `SshKeepAliveService.start(context, "$username@$host:$port")` wrapped in `runCatching`; a service-start failure shall be logged at `Log.e` and shall not unwind the successful connect. |
| SC-CN-05 | Given a successful connect, `SshClient.connect` shall set `client.connection.keepAlive.keepAliveInterval = 30` to send SSH keepalive requests every 30 s. | Without keepalive, a half-open connection (mobile NAT, captive portal, silent server close) blocks the read loop for hours. |
| SC-CN-06 | Given an `Auth.PasswordAuth`, `SshClient.connect` shall route to `PasswordAuthProvider`. |
| SC-CN-07 | Given an `Auth.PublicKeyAuth`, `SshClient.connect` shall route to `PublicKeyAuthProvider`. |
| SC-CN-08 | Given a partial connect failure after the `SSHClient` is constructed, `SshClient.connect` shall close the partial `SSHClient` before rethrowing so its socket does not leak. |
| SC-CN-09 | `SshClient.buildSshjConfig()` shall return a `Config` with `keepAliveProvider == KeepAliveProvider.KEEP_ALIVE`, and `connect` shall set `maxAliveCount = SshConfig.SSH_KEEPALIVE_MAX_ALIVE_COUNT` on the resulting `KeepAliveRunner`. | 2026-07-02 code review finding: sshj's `DefaultConfig` default is `KeepAliveProvider.HEARTBEAT`, whose `Heartbeater` only *writes* `SSH_MSG_IGNORE` and never waits for a reply — it keeps NAT mappings warm but can never by itself detect a dead peer. `KEEP_ALIVE` (`KeepAliveRunner`) actively probes and disconnects after `maxAliveCount` misses. Tested by `SshClientKeepAliveTest.buildSshjConfig_optsIntoActiveDeadPeerDetection` (no real socket needed — `buildSshjConfig()` is a pure function). |

### 5.3 `SshClient.disconnect`  *(P0: order matters)*

| ID | Spec |
|---|---|
| SC-DC-01 | `SshClient.disconnect` shall call `SshKeepAliveService.stop(context)` (wrapped in `runCatching`) **before** closing the sshj client. | `Context.stopService` is async; stopping first avoids the race where the sshj close completes and a reconnect re-promotes a service we meant to retire. |
| SC-DC-02 | `SshClient.disconnect` shall close the sshj client (wrapped in `runCatching`, failure logged via `AppLog.e`) and shall clear the internal `sshRef` atomically via `getAndSet(null)` beforehand, so the client reference used for close/log is captured exactly once. |
| SC-DC-03 | `SshClient.disconnect` shall be idempotent AND safe to call concurrently from multiple threads: only the caller that wins the `sshRef.getAndSet(null)` race runs any teardown; every other (concurrent or later) call is a true no-op. | Safe to call from Disconnect button + `SshSession`'s `writeExecutor` thread (via the `onClose` hook, itself reached from `readInto`'s `finally`) + the UI's `onSessionClosed` error handler on the main thread — these are real, not hypothetical, concurrent callers. 2026-07-02 code review found this was previously a plain non-atomic `var`, i.e. an unguarded data race; fixed with `AtomicReference<SSHClient?>`. Tested by `SshClientKeepAliveTest` (`disconnect_isIdempotent_secondAndThirdCallsAreNoOps`, `disconnect_concurrentCallers_closeTheUnderlyingClientExactlyOnce`, `disconnect_swallowsButDoesNotCrashOn_closeFailure`). |

---

## Module 6: Error translation (`ssh/SshErrorMessages`)

`internal object` — exposed only to `ssh/`. The UI layer renders the returned string verbatim into a status label. **P0: must walk the cause chain**, otherwise every sshj error falls through to the generic "Connection failed" string (per `SshErrorMessages.kt:25-30` kdoc).

### 6.1 `friendly(throwable)`

| ID | Spec |
|---|---|
| SE-FR-01 | Given a throwable whose `getCause()` chain (walking until `null` or a cycle, with a `seen` set guard) ends in a `SocketTimeoutException` whose stack trace contains `net.schmizz.sshj.transport.TransportImpl.receiveServerIdent`, `friendly` shall return `"Server didn't respond with an SSH banner. The address is reachable but may not be running SSH on this port."`. |
| SE-FR-02 | Given a throwable whose `getCause()` chain ends in a `SocketTimeoutException` with no `receiveServerIdent` frame, `friendly` shall return `"Connection timed out. Check your network and the server's address."`. |
| SE-FR-03 | Given a throwable whose `getCause()` chain ends in an `UnknownHostException`, `friendly` shall return `"Server not found. Check the hostname in Settings."`. |
| SE-FR-04 | Given a throwable whose `getCause()` chain ends in a `ConnectException`, `friendly` shall return `"Connection refused. Check the port and that the SSH service is running."`. |
| SE-FR-05 | Given a throwable whose `getCause()` chain ends in a `NoRouteToHostException`, `friendly` shall return `"Host unreachable. Check your network connection."`. |
| SE-FR-06 | Given a throwable whose `getCause()` chain ends in a `PortUnreachableException`, `friendly` shall return `"Server is not reachable on this port."`. |
| SE-FR-07 | Given a throwable whose `getCause()` chain ends in an `SSHException`, `friendly` shall return `"SSH handshake failed. The server may not support SSH on this port."`. |
| SE-FR-08 | Given a throwable whose `getCause()` chain ends in an `IOException`, `friendly` shall return `"Connection lost. The server may have closed the connection."`. |
| SE-FR-09 | Given any other throwable, `friendly` shall return `"Connection failed: <root.message>:<root.javaClass.simpleName>"`. |
| SE-FR-10 | Given a cause chain with a cycle (`cause === this`) or repeated instances, `friendly` shall return at the cycle boundary rather than hanging. |

### 6.2 `isSshBannerRead` (stack-frame disambiguation)

| ID | Spec |
|---|---|
| SE-IS-01 | Given a `SocketTimeoutException`, `isSshBannerRead` shall return `true` iff the throwable's `stackTrace` contains a frame with `className == "net.schmizz.sshj.transport.TransportImpl"` and `methodName == "receiveServerIdent"`. |
| SE-IS-02 | Given a `SocketTimeoutException` whose stack trace does not contain that frame, `isSshBannerRead` shall return `false`. |

### 6.3 SshConfig — SSH tuning constants

| ID | Spec |
|---|---|
| SCFG-01 | `SshConfig.CONNECT_TIMEOUT_MS` shall be `15` seconds (15,000 ms). | Short enough that a wrong port doesn't feel frozen. |
| SCFG-02 | `SshConfig.SO_TIMEOUT_MS` shall be `60` seconds (60,000 ms). | **CRITICAL**: sshj's `setTimeout` is forwarded straight to `Socket.setSoTimeout` which is in **milliseconds** — a previous `/1000` bug capped banner reads at 60 ms. |
| SCFG-03 | `SshConfig.SSH_KEEPALIVE_INTERVAL_SECONDS` shall be `30`. | Catches mobile NAT timeouts (60-120 s) without spamming. |
| SCFG-04 | `SshConfig.DEFAULT_TERM_TYPE` shall be `"xterm-256color"`. |
| SCFG-05 | `SshConfig.DEFAULT_PTY_COLS` shall be `80` and `SshConfig.DEFAULT_PTY_ROWS` shall be `24` for the initial allocation before the first `TerminalView` layout. |
| SCFG-06 | `SshConfig.DEFAULT_PORT` shall be `22`. |

> **Known issue** (from `docs/REVIEW_2026-06-24.md` §3.4): `SshConfig.READ_TIMEOUT_MS` and `KEX_TIMEOUT_MS` are defined but **not actually wired** to anything in `SshSession.readInto` or `SshClient.connect`. They are dead constants. Either consume them or delete them.

---

## Module 7: Credential storage (`data/crypto/KeyStoreManager` + `data/prefs/AppPreferences`)

### 7.1 `KeyStoreManager.getOrCreateKey`

| ID | Spec |
|---|---|
| KSM-KEY-01 | Given the keystore does not contain the alias `ssh_key_encryption_key`, `getOrCreateKey` shall generate a 256-bit AES key with `PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, and store it under the alias. |
| KSM-KEY-02 | Given the keystore already contains the alias, `getOrCreateKey` shall return the existing `SecretKey` without regenerating. |
| KSM-KEY-03 | The returned `SecretKey` shall be backed by `AndroidKeyStore` (hardware-backed when the device supports it). |

### 7.2 `KeyStoreManager.encrypt`

| ID | Spec |
|---|---|
| KSM-EN-01 | Given a `plaintext: ByteArray`, `encrypt` shall return `IV || ciphertext` where `IV` is the 12-byte AES-GCM IV (from `cipher.iv`) and `ciphertext` is the AES-GCM-encrypted bytes (including the 128-bit auth tag). |
| KSM-EN-02 | Given the cipher's IV size is not 12 bytes, `encrypt` shall throw `IllegalStateException("unexpected IV size: <size>")`. |
| KSM-EN-03 | Given the same key and the same plaintext, two `encrypt` calls shall return different payloads (because the IV is randomly generated per call). |

### 7.3 `KeyStoreManager.decrypt`

| ID | Spec |
|---|---|
| KSM-DE-01 | Given a `payload` of length `<= 12`, `decrypt` shall throw `IllegalArgumentException("payload too short to contain an IV")`. |
| KSM-DE-02 | Given a `payload` of length `> 12`, `decrypt` shall return the AES-GCM-decrypted bytes, throwing on any authentication failure. |
| KSM-DE-03 | The decryption key shall be the same `SecretKey` from `getOrCreateKey()`. |

### 7.4 `KeyStoreManager.deleteKey`

| ID | Spec |
|---|---|
| KSM-DL-01 | Given the alias exists, `deleteKey` shall remove it from the keystore. |
| KSM-DL-02 | Given the alias does not exist, `deleteKey` shall be a no-op. |
| KSM-DL-03 | After `deleteKey`, all previously-encrypted payloads shall fail to decrypt (auth tag mismatch). | Confirms the key is genuinely gone, not just hidden. |

### 7.5 `AppPreferences` — host / port / username

| ID | Spec |
|---|---|
| AP-HOST-01 | Given `prefs` has no `KEY_HOST`, the `host` getter shall return the empty string. |
| AP-HOST-02 | Given the user sets `host` to a non-empty string, the `host` getter shall return that string on the next call. |
| AP-PORT-01 | Given `prefs` has no `KEY_PORT`, the `port` getter shall return `22` (`SshConfig.DEFAULT_PORT`). |
| AP-USER-01 | Given `prefs` has no `KEY_USERNAME`, the `username` getter shall return the empty string. |

### 7.6 `AppPreferences` — font size (clamped read)

| ID | Spec |
|---|---|
| AP-FS-01 | Given a stored font size `< MIN_FONT_SIZE` (8), the `fontSize` getter shall return `MIN_FONT_SIZE` (8). |
| AP-FS-02 | Given a stored font size `> MAX_FONT_SIZE` (32), the `fontSize` getter shall return `MAX_FONT_SIZE` (32). |
| AP-FS-03 | Given a stored font size in `[8, 32]`, the `fontSize` getter shall return it unchanged. |
| AP-FS-04 | The `fontSize` setter shall write the value unclamped — the caller (currently `MainActivity`) is responsible for stepping into range. |

### 7.7 `AppPreferences` — Plan C encrypted password slot

| ID | Spec |
|---|---|
| AP-EP-01 | Given `setEncryptedPassword(ciphertext)` is called with a non-empty array, `getEncryptedPassword()` shall return a `ByteArray` whose `Base64.decode` equals `ciphertext`. |
| AP-EP-02 | Given `setEncryptedPassword(byteArrayOf())` is called (the "empty password" sentinel), `getEncryptedPassword()` shall return `null` so the caller doesn't have to handle the empty-IV-decryption crash. |
| AP-EP-03 | Given no `KEY_ENCRYPTED_PASSWORD` has ever been set, `getEncryptedPassword()` shall return `null`. |

### 7.8 `AppPreferences.hasUsableCredentials`  *(P0: must use Plan C slot, not legacy)*

| ID | Spec |
|---|---|
| AP-HUC-01 | Given a non-blank host, non-blank username, and a non-null `getEncryptedPassword()`, `hasUsableCredentials` shall return `true`. |
| AP-HUC-02 | Given a non-blank host, non-blank username, and a non-blank `privateKeyName`, `hasUsableCredentials` shall return `true`. |
| AP-HUC-03 | Given a blank host OR blank username, `hasUsableCredentials` shall return `false` regardless of credentials. |
| AP-HUC-04 | `hasUsableCredentials` shall consult the `getEncryptedPassword()` (Plan C) slot, not the legacy `password` (plain-text) slot. | Sprint 1.5 `ConfigScreen` always writes via `setEncryptedPassword`, so the plain-text slot is always empty in production; checking it would always return false. |

### 7.9 `AppPreferences.clear`

| ID | Spec |
|---|---|
| AP-CL-01 | Given a call to `clear()`, all keys (`host`, `port`, `username`, `password`, `privateKeyName`, `encrypted_password`, `fontSize`) shall be removed from the underlying `SharedPreferences`. |
| AP-CL-02 | `clear()` shall **not** delete the Keystore key. | The Keystore key is a separate resource; "forget this device" must use `KeyStoreManager.deleteKey()` explicitly. |

---

## Module 8: Auth providers (`ssh/auth/`)

### 8.1 `Auth` sealed class (compile-time exhaustiveness)

| ID | Spec |
|---|---|
| AUTH-SL-01 | `Auth` is a sealed class with two variants: `PasswordAuth(val password: String)` and `PublicKeyAuth(val privateKeyPath: String)`. | Adding a new auth type forces a compile error at every `when (auth)` site. |
| AUTH-SL-02 | The `username` shall not be carried by `Auth` — it flows through `SshClient.connect` as a separate parameter. | The same `Auth` instance can be retried against a different user. |

### 8.2 `PasswordAuthProvider`

| ID | Spec |
|---|---|
| PAP-AU-01 | Given an `Auth.PasswordAuth`, `PasswordAuthProvider.authenticate` shall call `client.authPassword(username, auth.password)`. |
| PAP-AU-02 | Given an `Auth.PublicKeyAuth` passed to `PasswordAuthProvider.authenticate`, the call shall throw `IllegalArgumentException("PasswordAuthProvider requires Auth.PasswordAuth, got <class>")`. | Compile-time check is bypassed at the `SshAuthProvider` boundary; runtime check is the safety net. |
| PAP-AU-03 | Given a successful auth call, `PasswordAuthProvider.authenticate` shall log a single `Log.i` line containing the username, password length, and a SHA-256 first-16-hex of the password (no plaintext). |

### 8.3 `PublicKeyAuthProvider`  *(P0: BouncyCastle registration required)*

| ID | Spec |
|---|---|
| PKP-AU-01 | Given an `Auth.PublicKeyAuth`, `PublicKeyAuthProvider.authenticate` shall call `loadKeyProvider(auth.privateKeyPath)` and then `client.authPublickey(username, keyProvider)`. |
| PKP-AU-02 | Given a `PKCS8` key format, `loadKeyProvider` shall construct a `PKCS8KeyFile` and call `init(keyFile)`. |
| PKP-AU-03 | Given an `OpenSSH` (classic) key format (`BEGIN RSA/EC/DSA PRIVATE KEY`), `loadKeyProvider` shall construct an `OpenSSHKeyFile`. |
| PKP-AU-04 | Given an `OpenSSHv1` key format (OpenSSH 6.5+, `BEGIN OPENSSH PRIVATE KEY`), `loadKeyProvider` shall construct an `OpenSSHKeyV1KeyFile` from `com.hierynomus.sshj.*`. | The `v1` envelope is only known to `hierynomus.sshj`, not the system BouncyCastle. |
| PKP-AU-05 | Given a `PuTTY` key format (`.ppk`), `loadKeyProvider` shall construct a `PuTTYKeyFile`. |
| PKP-AU-06 | Given a key format of `Unknown`, `loadKeyProvider` shall throw `IllegalStateException("Unknown / unsupported key format for <path>")`. |
| PKP-AU-07 | Given a `privateKeyPath` that does not point to a regular file, `loadKeyProvider` shall throw `IllegalArgumentException("private key file not found: <path>")`. |
| PKP-AU-08 | Given a passphrase-protected (encrypted) key file, `PublicKeyAuthProvider` shall fail with an SSHJ exception. | v1.0 explicitly does not support encrypted keys (no `PasswordFinder` wiring). |
| PKP-AU-09 | The `BouncyCastleBootstrap.ensureRegistered()` call (made by `SshClient.connect` before invoking the auth provider) shall make the system `KeyFactory.getInstance("Ed25519")` return a non-null result. | The system BouncyCastle on API 29 is 1.62, which lacks Ed25519 — we must register a modern BC explicitly. |

---

## Module 9: Active session survival across Activity recreation (`ssh/ActiveSshSessionStore`)

Per `ActiveSshSessionStore.kt:30-37` kdoc: SSH sessions can outlive the Activity (foreground service keeps the process alive, multi-window mode recreates MainActivity, etc.). A process-scoped singleton is the right scope — not `rememberSaveable` (channels aren't `Parcelable`), not ViewModel (dies with the process).

| ID | Spec |
|---|---|
| ASS-ST-01 | Given a call to `set(session)`, the `ActiveSshSessionStore` shall atomically replace the previously stored session with `session` (or null if no previous session existed). |
| ASS-ST-02 | Given a call to `get()` after `set(session)`, the store shall return the same `session` instance. |
| ASS-ST-03 | Given a call to `get()` with no previous `set`, the store shall return `null`. |
| ASS-ST-04 | Given a call to `clear()`, the store shall atomically set the stored session to `null`. |
| ASS-ST-05 | `set`, `get`, and `clear` shall be thread-safe and visible across threads (backed by `AtomicReference`). |
| ASS-ST-06 | `clear` shall be idempotent: a second call shall be a no-op. | Safe to call from BackHandler, Disconnect button, and `onSessionClosed` error handler without coordinating. |

---

## Module 10: Crash capture (`CrashHandler`)

Per `MainActivity.kt:21-32` and `docs/REVIEW_2026-06-24.md` §3.10. The handler is the "user can read the stack trace on next launch" fallback so non-adb users can debug.

| ID | Spec |
|---|---|
| CH-IN-01 | Given a call to `CrashHandler.install(context)`, the handler shall install a `Thread.UncaughtExceptionHandler` that writes the timestamp + thread name + full stack trace to `filesDir/crash.log` (overwriting any previous content) and then delegates to the previous default handler. | Only the most recent crash is kept. |
| CH-IN-02 | `CrashHandler.install` shall be idempotent — a second call shall be a no-op or re-install the same handler without recursion. |

| ID | Spec |
|---|---|
| CH-RC-01 | Given a non-existent `crash.log` file, `readLastCrash` shall return `null`. |
| CH-RC-02 | Given an empty `crash.log` file, `readLastCrash` shall return `null`. |
| CH-RC-03 | Given a non-empty `crash.log` file, `readLastCrash` shall return the full file contents as a String. |

| ID | Spec |
|---|---|
| CH-CL-01 | Given a call to `clearLastCrash`, the `crash.log` file shall be deleted if it exists; a missing file shall not raise. |

| ID | Spec |
|---|---|
| CH-AB-01 | Given a thread whose `name` starts with `"Reader"` and whose throwable's root cause is a `SocketException` OR is an `SSHException` with message containing `"abort"` (case-insensitive), the handler shall NOT write to `crash.log` but shall still delegate to the previous handler. | Suppresses the sshj-internal "Software caused connection abort" log spam — already surfaced by `SshSession.readInto` as a clean `Result.failure` (per `MainActivity.kt:55-72` kdoc). |
| CH-AB-02 | Given a thread whose `name` does not start with `"Reader"`, the handler shall write the crash to `crash.log` regardless of the throwable type. | Real crashes from other threads must be captured. |
| CH-AB-03 | Given a `crash.log` write failure (e.g. disk full), the handler shall swallow the write error and still delegate to the previous handler. | Last-ditch: don't make a write failure worse by adding a second uncaught exception. |

| ID | Spec |
|---|---|
| APP-01 | Given a call to `SshTermApplication.onCreate`, the application shall install the `CrashHandler`, initialise the process-scoped `AppLog` sink, and create the foreground-service notification channel — all before any Activity code runs. | Catches early-init crashes (manifest inflation, theme resolution). |
| APP-02 | Given a `NotificationManagerCompat.createNotificationChannel` IPC failure, the application shall log the error at `Log.e` and shall not crash the process. | Graceful degradation on OEM-quirk IPC failures. |

---

## Module 11: Security — Host fingerprint (Sprint 2.5, S1)

> **Status (2026-06-29)**: ✅ **Implemented.** `KnownHostsStore.kt`, `HostFingerprint.kt`, `KnownHostsVerifier.kt` in `ssh/security/`; `SshClient.kt` wires TOFU per-connect. KHV-UX-01..02 via `SshConnectResult.enrollmentNotice` + Snackbar.

**Severity**: 🔴 **HIGH** — `SshClient` currently uses `PromiscuousVerifier()` (default), accepting any host fingerprint. MITM risk is unbounded on first-connect.

**Design intent (from `docs/REVIEW_2026-06-24.md` §4 S1 + `implementation_plan.md` §"验证计划")**: Sprint 2.5 introduces a **TOFU (Trust On First Use)** store. On first connect, record the host's public-key fingerprint into `filesDir/known_hosts`. On subsequent connects, verify the presented fingerprint matches the recorded one; on mismatch, refuse the connection with a user-readable error.

**NOT in scope** (per `CLAUDE.md`): full `known_hosts` format parsing, key rotation UX, host-key pinning with separate trust per algorithm. TOFU only.

### 11.1 `KnownHostsStore` — persistence

| ID | Spec |
|---|---|
| KHS-ST-01 | Given the store has never been written, `get(host, port)` shall return `null`. | No prior trust = first-use path. |
| KHS-ST-02 | Given the store contains a row for `(host, port)`, `get(host, port)` shall return the stored `HostFingerprint(keyType, fingerprintBase64)`. |
| KHS-ST-03 | Given a successful TOFU enrollment, `put(host, port, fingerprint)` shall atomically overwrite any existing row for `(host, port)`. | Idempotent re-enroll is allowed (e.g. user explicitly resets). |
| KHS-ST-04 | When `put` writes, the store shall fsync (or its platform equivalent) before returning, so a power loss between `put` and the SSH handshake does not leave the store empty. | TOFU is the security boundary; partial writes are worse than no record. |
| KHS-ST-05 | The store file path shall be `filesDir/known_hosts`, and the store shall not be included in `android:allowBackup` (already covered by the manifest-wide `allowBackup="false"`). |
| KHS-ST-06 | Given a malformed row in the store (e.g. truncated file, bad Base64), `get` shall return `null` and shall not throw. | Defensive: corrupt store is equivalent to no record. |

### 11.2 `HostFingerprint` — value type

| ID | Spec |
|---|---|
| HF-VT-01 | `HostFingerprint` shall be an immutable data class with two fields: `keyType: String` (one of `"ssh-rsa"`, `"ecdsa-sha2-nistp256"`, `"ssh-ed25519"`, etc.) and `fingerprintBase64: String` (the SHA-256 of the host's public key, Base64-encoded, matching the `ssh-keygen -lf` output format). |
| HF-VT-02 | Two `HostFingerprint` instances shall be equal iff both `keyType` and `fingerprintBase64` are equal (case-sensitive on `keyType`, case-sensitive on `fingerprintBase64` per RFC 4648). |

### 11.3 `KnownHostsVerifier` — SSH-layer integration

| ID | Spec |
|---|---|
| KHV-VF-01 | `KnownHostsVerifier` shall implement `net.schmizz.sshj.transport.verification.HostKeyVerifier`. |
| KHV-VF-02 | Given the host-port pair has no record in `KnownHostsStore`, when sshj presents a host key, the verifier shall record `(host, port, HostFingerprint(keyType, base64))` into the store and shall return `true` (accept the key). | First-use enrolls trust. |
| KHV-VF-03 | Given the host-port pair has a record in `KnownHostsStore`, when sshj presents a host key whose `(keyType, fingerprintBase64)` matches the record, the verifier shall return `true`. |
| KHV-VF-04 | Given the host-port pair has a record in `KnownHostsStore`, when sshj presents a host key whose `(keyType, fingerprintBase64)` does **not** match the record, the verifier shall return `false` and shall not modify the store. | Mismatch = possible MITM, refuse. |
| KHV-VF-05 | Given the host-port pair has a record but the stored `keyType` differs from the presented `keyType` (e.g. server rotated from RSA to Ed25519), the verifier shall return `false` unless the new key is explicitly re-enrolled via a separate `KHS-ST-03` call. | Key-type change = same trust level as fingerprint change. |
| KHV-VF-06 | When the verifier returns `false`, sshj's transport layer shall surface the rejection as an `SSHException` whose cause chain reaches a known-hosts-mismatch leaf. | The error is then translated by `SshErrorMessages` (Module 6). |

### 11.4 `SshClient` wiring (replaces `PromiscuousVerifier` default)

| ID | Spec |
|---|---|
| SC-KHV-01 | `SshClient.connect` shall pass a `KnownHostsVerifier` instance to `SSHClient.addHostKeyVerifier`, not a `PromiscuousVerifier`. | Closes S1: no more silent "accept any host" default. |
| SC-KHV-02 | Given the `KnownHostsStore` is unavailable (e.g. disk full on first write), the connect call shall return `Result.failure(SshException("Cannot initialize host-key store", cause))` and shall not open a TCP connection. | Fail-closed, not fail-open. |
| SC-KHV-03 | When a `KHV-VF-04` mismatch occurs, the connect call shall return `Result.failure(SshException("Host key for <host>:<port> has changed since first connection. Possible man-in-the-middle. If you trust the new key, reset the host entry in Settings.", cause))`. | User-readable; the recovery path is a manual "forget this host" action, not an auto-accept. |
| SC-KHV-04 | The connection attempt shall be aborted before `authenticate(...)` is called when KHV-VF-04 fires. | We must not waste a password / private-key attempt on a host whose identity is unverified. |

### 11.5 "Forget this host" UI affordance

| ID | Spec |
|---|---|
| SC-FH-01 | Given the user invokes "Forget host" in `ConfigScreen` for a saved `(host, port)`, the UI shall call `KnownHostsStore.delete(host, port)` and shall re-allow the next connect to enroll a new fingerprint. |
| SC-FH-02 | `KnownHostsStore.delete(host, port)` shall remove the row and shall fsync the store. |
| SC-FH-03 | `KnownHostsStore.delete` shall be idempotent: a second call for the same `(host, port)` shall be a no-op. |

### 11.6 First-use UX

| ID | Spec |
|---|---|
| KHV-UX-01 | Given the user connects to a host for the first time, the connect call shall succeed and the UI shall display a one-line notice: `"New host <host>:<port> enrolled. Future connections will verify this key."`. | TOFU = silent enroll + visible notice (so the user knows it happened). |
| KHV-UX-02 | The notice shall be shown for exactly the next connect that triggered the enroll; it shall not be persisted across launches. | Not a notification, not a snackbar history item. |

---

## Module 12: Security — Private key at rest (Sprint 2.5, S2)

> **Status (2026-06-29)**: ✅ **Implemented.** `EncryptedPrivateKeyStore.kt` + `PublicKeyAuthProvider` temp-file auth path + legacy `.pem` migration.

**Severity**: 🔴 **HIGH** — `Auth.PublicKeyAuth.privateKeyPath` currently points to a **plaintext** PEM file under `filesDir/keys/`. The Android sandbox protects against other ordinary apps, but `adb backup` (mitigated by `allowBackup="false"`) and rooted devices leave the key exposed. (`PublicKeyAuthProvider.kt:38-44` kdoc explicitly acknowledges this is a Sprint 1.5 simplification.)

**Design intent**: encrypt the PEM file with the existing `KeyStoreManager` AES-256-GCM key, store the ciphertext under `filesDir/keys/`, keep the same flow (SAF import → name in prefs → resolve path on auth). The cleartext PEM exists only briefly in memory during auth.

**NOT in scope**: passphrase-protected keys (already explicitly out of scope per `PublicKeyAuthProvider.kt:78-79` kdoc), key rotation, multi-key support.

### 12.1 Storage format

| ID | Spec |
|---|---|
| PKR-FMT-01 | The encrypted private-key file shall be stored at `filesDir/keys/<safeName>.pem.enc` where `<safeName>` is the sanitized file name (matching the existing `ConfigScreen` import flow). |
| PKR-FMT-02 | The file contents shall be the raw `KeyStoreManager.encrypt(plaintextPEM)` payload — i.e. `IV (12 bytes) || ciphertext || GCM tag`. | Self-contained, identical to the password slot. |
| PKR-FMT-03 | The `AppPreferences.privateKeyName` field shall continue to store the safeName; the `.pem.enc` suffix is derived at resolve time, not stored in prefs. | Keeps the prefs schema unchanged. |
| PKR-FMT-04 | When a plaintext `.pem` file exists in `filesDir/keys/` from a prior install, the first successful auth shall trigger a one-time migration (re-encrypt → write `.pem.enc` → delete `.pem`). | Closes the migration window for upgraders. |

### 12.2 `EncryptedPrivateKeyStore`

| ID | Spec |
|---|---|
| EPKS-IM-01 | Given a `safeName`, the importer shall read the source PEM, encrypt via `KeyStoreManager.encrypt(plaintext)`, write to `filesDir/keys/<safeName>.pem.enc`, and store `safeName` into `AppPreferences.privateKeyName`. | Replaces the current plaintext write. |
| EPKS-IM-02 | After a successful import, the source PEM (if it was inside `filesDir/keys/`) shall be securely deleted: the file shall be overwritten with random bytes then deleted, so a forensic recovery cannot recover the cleartext. |
| EPKS-IM-03 | `EPKS-IM-02`'s secure delete shall be best-effort on flash storage (kernel may have copied blocks to wear-level pools) — the importer shall still delete the file, but shall not retry on `IOException` mid-overwrite. | Documented limitation; defense in depth, not a guarantee. |
| EPKS-IM-04 | The plaintext PEM byte array shall be zeroed (`plaintext.fill(0)`) immediately after encryption completes, regardless of success. | Best-effort scrub of the in-memory copy. |

### 12.3 `PublicKeyAuthProvider` resolution

| ID | Spec |
|---|---|
| PKP-RES-01 | Given an `Auth.PublicKeyAuth(privateKeyPath)` where the file ends in `.pem.enc`, the provider shall read the file, decrypt via `KeyStoreManager.decrypt(payload)`, write the cleartext to a temp file under `cacheDir/ssh-pad-key-tmp/` (mode 0600, owner = app UID), pass the temp file to SSHJ's `KeyProvider`, and securely delete the temp file after `authPublickey` returns. | SSHJ only accepts `File`-based key providers; the cleartext must touch disk for the duration of the auth call. |
| PKP-RES-02 | The temp file shall be created with `File.createTempFile(..., suffix = ".pem")` and shall be deleted in a `finally` block, including the throw path. |
| PKP-RES-03 | The temp file's parent directory (`cacheDir/ssh-pad-key-tmp/`) shall be created with `mkdirs()` and shall have permissions 0700. | Limits the window where the cleartext is on disk. |
| PKP-RES-04 | The cleartext byte array shall be zeroed (`cleartext.fill(0)`) after the temp file is written, before SSHJ consumes the temp file. | Minimize the in-memory lifetime. |
| PKP-RES-05 | When the Keystore key is unavailable (e.g. Keystore was wiped), `PublicKeyAuthProvider.authenticate` shall return a `Result.failure(SshException("Cannot decrypt private key: device keystore is unavailable. Re-import the key after unlocking the device once.", cause))`. | Fail-closed with a user-recoverable hint. |
| PKP-RES-06 | Given a legacy plaintext `.pem` file exists (pre-Sprint-2.5 install) and a `.pem.enc` does not, the provider shall auto-migrate per PKR-FMT-04 on first auth and shall proceed. |

### 12.4 `AppPreferences` schema (no change, but documented for clarity)

| ID | Spec |
|---|---|
| AP-PKN-01 | `AppPreferences.privateKeyName` shall continue to store the `safeName` only; the on-disk path is derived. |
| AP-PKN-02 | When `privateKeyName` is non-blank AND neither `<safeName>.pem.enc` nor `<safeName>.pem` exists in `filesDir/keys/`, `hasUsableCredentials` shall return `false`. | Defends against a stale prefs entry pointing at a deleted key file. |
| AP-PKN-03 | When `privateKeyName` is non-blank AND the `.pem.enc` file exists, `hasUsableCredentials` shall return `true` (no need to decrypt to check presence). | Cheap check; decryption happens at auth time. |

### 12.5 Threat-model caveats (documented, not auto-fixed)

| ID | Spec |
|---|---|
| PKR-TM-01 | This module does NOT defend against a process-memory dump attack while auth is in progress (the cleartext is decrypted in RAM for the duration of the SSHJ auth call). | Documented limitation; consistent with the existing threat model in `KeyStoreManager.kt:18-21` kdoc. |
| PKR-TM-02 | This module does NOT defend against a rooted device, a debugger attached to the process, or a `SecureWorld`-compromised Keystore. | Out of scope. |
| PKR-TM-03 | This module does NOT encrypt keys in transit between the keystore and the in-process cipher — the JCA / Conscrypt stack does that. | Out of scope. |

---

## Module 13: Security — Debug log gating (Sprint 2.5, S3)

> **Status (2026-06-29)**: ✅ **Implemented.** `buildConfig = true`, `appendDebugLog` / `passwordFingerprint` gating, legacy `debug.log` cleanup (BC-COMPAT).

**Severity**: 🟡 **MEDIUM** — `ConfigScreen.appendDebugLog` writes `host`, `port`, `username`, and a `password` fingerprint to `filesDir/debug.log`. The file is app-private but `adb pull /data/data/com.example.sshterminal/files/debug.log` works on any device with USB debugging enabled, leaking the user's host roster and account list to anyone with physical access during a debug install.

**Root cause** (`docs/REVIEW_2026-06-24.md` §4 S3): `app/build.gradle.kts` does not enable `buildConfig = true`, so `BuildConfig.DEBUG` is not generated, and `ConfigScreen` cannot gate the call.

**Fix prerequisite**: enable `buildConfig = true` in `app/build.gradle.kts` (per §3.21 of the review). This unblocks both Module 13 and Module 14.

### 13.1 Build config enablement

| ID | Spec |
|---|---|
| BC-EN-01 | `app/build.gradle.kts` shall set `buildFeatures { buildConfig = true }` for the `debug` build type. | Generates `BuildConfig.DEBUG`. |
| BC-EN-02 | `app/build.gradle.kts` shall set `applicationIdSuffix = ".debug"` for the `debug` build type. | Allows debug + release to coexist on the same device. |
| BC-EN-03 | The release build type shall not set `applicationIdSuffix`. | Release stays at the production applicationId. |

### 13.2 `ConfigScreen.appendDebugLog`

| ID | Spec |
|---|---|
| CS-DL-01 | Given `BuildConfig.DEBUG == true`, when `ConfigScreen.appendDebugLog(context, message)` is called, the function shall append `message` to `filesDir/debug.log`. | Debug builds keep the current behavior. |
| CS-DL-02 | Given `BuildConfig.DEBUG == false`, when `ConfigScreen.appendDebugLog(context, message)` is called, the function shall return immediately and shall not touch any file. | Release builds must not write host/port/username. |
| CS-DL-03 | Given any call to `appendDebugLog` (debug or release), the function shall not write the `password` field — neither the plaintext nor any derived fingerprint. | S3 + S4 are addressed at the same call site; the helper no longer accepts password-derived content. |
| CS-DL-04 | Given a `message` argument, the function shall log the message to Logcat at `Log.d("ConfigScreen", message)` and to `app.log` (the existing `AppLog` sink) regardless of `BuildConfig.DEBUG`. | A user-mutable signal remains visible in the standard logs even when the file sink is disabled. |

### 13.3 `ConfigScreen.passwordFingerprint` (related deprecation)

| ID | Spec |
|---|---|
| CS-PF-01 | `passwordFingerprint(password)` shall return the empty string when `BuildConfig.DEBUG == false`. | Keep the function for call-site compat; stop producing sensitive content in release. |
| CS-PF-02 | Given `BuildConfig.DEBUG == true`, `passwordFingerprint(password)` shall return the existing SHA-256 first-16-hex (current behavior, for ad-hoc lab testing). |

### 13.4 Backwards compatibility

| ID | Spec |
|---|---|
| BC-COMPAT-01 | When a user upgrades from a pre-Sprint-2.5 install to Sprint 2.5 release, any pre-existing `filesDir/debug.log` shall be deleted on first launch. | One-shot cleanup. |
| BC-COMPAT-02 | The deletion in BC-COMPAT-01 shall run only once per install (tracked via a `SharedPreferences` flag `debug_log_migrated_v2_5`). |

---

## Module 14: Security — Auth diagnostic gating (Sprint 2.5, S4)

> **Status (2026-06-29)**: ✅ **Implemented.** `PasswordAuthProvider` + `PublicKeyAuthProvider` diagnostic logging gated by `BuildConfig.DEBUG`.

**Severity**: 🟡 **MEDIUM** — `PasswordAuthProvider.authenticate` calls `Log.i(TAG, "password auth: user=$username length=${auth.password.length} sha256=${sha256Hex(auth.password)} firstByte=...")` (PAP-AU-03 in Module 8). The SHA-256 first-16-hex is short enough to be brute-forced against a dictionary of common passwords (well-known attack against truncated hashes).

**Fix**: gate the entire `Log.i` call behind `BuildConfig.DEBUG`. The release build emits nothing for the auth path beyond the standard sshj internal logs (which the user cannot read anyway).

### 14.1 `PasswordAuthProvider` log gate

| ID | Spec |
|---|---|
| PAP-LG-01 | Given `BuildConfig.DEBUG == true`, when `PasswordAuthProvider.authenticate` is called, the function shall emit the existing `Log.i` line (user, length, SHA-256 first-16-hex, first byte). |
| PAP-LG-02 | Given `BuildConfig.DEBUG == false`, when `PasswordAuthProvider.authenticate` is called, the function shall not emit any `Log.*` call AND shall not call `sha256Hex` (zero CPU cost when disabled). |
| PAP-LG-03 | `PasswordAuthProvider.authenticate` shall not write to `AppLog` (the file sink) for any auth event, regardless of build type. | Belt-and-braces — `AppLog` is not the right sink for auth events. |

### 14.2 `PublicKeyAuthProvider` log gate (parallel hygiene)

| ID | Spec |
|---|---|
| PKP-LG-01 | `PublicKeyAuthProvider.authenticate` shall not log the `privateKeyPath` in release builds. | The path itself is not sensitive (sandboxed), but the existence of a key on a user profile is metadata worth not leaking via `adb logcat`. |
| PKP-LG-02 | `PublicKeyAuthProvider.loadKeyProvider(path)` shall log the key format (e.g. `OpenSSHv1`) at `Log.d` when `BuildConfig.DEBUG == true` and shall be silent otherwise. | Format is not sensitive and useful for diagnosis. |

### 14.3 `SshErrorMessages` translation (orthogonal — keep as-is)

| ID | Spec |
|---|---|
| S4-NOTE-01 | `SshErrorMessages.friendly` strings (the user-facing connect-failure messages) shall remain unconditional — they are user-visible in the status label, not in `adb logcat` or a file sink. | The `SshErrorMessages` strings do not contain the password or its hash. |

### 14.4 Threat-model rationale (for the PR description)

| ID | Spec |
|---|---|
| S4-TM-01 | The threat model for `Log.i` in release builds is `adb logcat` access during a USB-debugging session. `BuildConfig.DEBUG` is the standard signal for "this is a developer-visible build" and aligns with the rest of the Android toolchain. |
| S4-TM-02 | A user who wants to capture auth diagnostics on a production build shall do so via a manual `tcpdump` / `sshd -ddd` on the remote, not via a client-side log. | Defends the design choice. |

---

## Module 15: Landscape split layout (Sprint 3, S1)

> **Status (2026-07-02)**: ✅ **Implemented.** `ui/LayoutDecision.kt` (pure `shouldUseSplitLayout(orientation, showTerminal)`); `SshTermApp.kt` `!showTerminal` branch now branches on landscape → `Row`, portrait → original `Column` BYTE-FOR-BYTE unchanged (SL-LY-02); `LayoutDecisionTest` pins SL-OR-01..03 / SL-TS-01 (4 cases). Compose `Row`/`Column` swap SL-LY-01/03..05 exercised by manual tablet checklist per SL-TS-02.

**Sprint 3 task, independent of Modules 16–17** — touches only the Compose layout branch in `ui/SshTermApp.kt`; shares no code path with the other two Sprint 3 tasks.

**Problem**: the pre-connect screen (`ui/SshTermApp.kt:441-508`, the `!showTerminal` branch) stacks the Connect/Disconnect row, `ConfigScreen` form, error log panel, and `TerminalPane` preview into a single vertical `Column`. On a tablet in landscape this `Column` is wide but the content is still stacked top-to-bottom, so most of the horizontal space goes unused and the effective display density is low (per README §路线图 "平板横屏布局优化").

**Design intent**: when the device is in landscape **and** the user hasn't entered the fullscreen terminal yet, switch the pre-connect screen from a `Column` to a `Row` — `ConfigScreen` on the left, the `TerminalPane` preview + error log panel on the right. Portrait behavior and the fullscreen terminal (`showTerminal == true`) path are untouched.

**NOT in scope**: a full phone/tablet breakpoint matrix, a user-draggable split ratio, any change to the `showTerminal == true` fullscreen path, any change to `AndroidManifest.xml`'s `configChanges` handling (rotation already survives Activity recreation).

### 15.1 Orientation decision (pure function)

| ID | Spec |
|---|---|
| SL-OR-01 | Given `orientation == Configuration.ORIENTATION_LANDSCAPE` and `showTerminal == false`, `shouldUseSplitLayout(orientation, showTerminal)` shall return `true`. |
| SL-OR-02 | Given `orientation == Configuration.ORIENTATION_PORTRAIT` (any `showTerminal` value), `shouldUseSplitLayout` shall return `false`. |
| SL-OR-03 | Given `showTerminal == true` (any orientation), `shouldUseSplitLayout` shall return `false`. | The fullscreen terminal path is orientation-agnostic; it already fills the screen via `Box(fillMaxSize)`. |
| SL-OR-04 | `shouldUseSplitLayout` shall be a plain Kotlin function taking primitive/enum arguments only (no `Context`, no Compose `@Composable` annotation), so it is unit-testable without Robolectric. |

### 15.2 Layout composition

| ID | Spec |
|---|---|
| SL-LY-01 | Given `shouldUseSplitLayout(...) == true`, `SshTermApp`'s pre-connect screen shall render a `Row` with `ConfigScreen` (plus the Connect/Disconnect controls) in the leading pane and `TerminalPane` (plus the error log panel, when `connectionState is ConnectionState.Error`) in the trailing pane. |
| SL-LY-02 | Given `shouldUseSplitLayout(...) == false`, `SshTermApp`'s pre-connect screen shall render the existing vertical `Column` (Connect/Disconnect row → `ConfigScreen` → error log panel → `TerminalPane` preview), byte-for-byte the same composition as today. | Regression guard: portrait users must see zero behavior change. |
| SL-LY-03 | The fullscreen terminal branch (`showTerminal == true`, the `Box(fillMaxSize)` subtree at `SshTermApp.kt:298-439`) shall not be modified by this module. |
| SL-LY-04 | In the split (`Row`) layout, `ConfigScreen`'s existing `verticalScroll` wrapper shall remain in place so the form remains fully reachable in the narrower leading pane. |
| SL-LY-05 | Rotating the device while `showTerminal == false` shall cause the next recomposition to re-evaluate `shouldUseSplitLayout` and swap `Row`/`Column` accordingly, without losing `ConfigScreen`'s in-flight `ConnectionDraft` state (already held in `remember`/`rememberSaveable` one level up in `SshTermApp`). |

### 15.3 Testing

| ID | Spec |
|---|---|
| SL-TS-01 | `shouldUseSplitLayout` shall have a pure-JUnit test enumerating all 4 combinations of `{PORTRAIT, LANDSCAPE} × {showTerminal=true, false}`. |
| SL-TS-02 | The `Row`/`Column` Compose rendering itself is exercised only by the manual device checklist (§Testing, tablet landscape rotation) — consistent with the project's existing precedent of deferring Compose-layout UI assertions to real-device testing (see `ScrollbackBanner`'s "延后到真机手测" note). |

---

## Module 16: Command snippets (Sprint 3, S2)

> **Status (2026-07-02)**: ✅ **Implemented.** `data/prefs/SnippetStore.kt` (SharedPreferences + `org.json`, no new libs); `ui/SnippetPanel.kt` (Material3 `ModalBottomSheet` + LazyColumn + Add/Edit/Delete form); `ui/SnippetPayload.kt` (`buildSnippetPayload(command, appendNewline)` pure helper, `appendNewline=true` → single CR `0x0D` matching KM-KC-02). `SshTermApp.kt` fullscreen `showTerminal` path adds TopEnd IconButton to open the panel; pre-connect path untouched. `SnippetStoreTest` (10 cases) pins SNP-ST-01..06; `SnippetPayloadTest` (4 cases) pins SNP-SEND-01..02 + SNP-TS-02; SNP-UI-01..04 / SNP-TS-03 exercised by manual device checklist.

**Sprint 3 task, independent of Modules 15/17** — introduces new files (`data/prefs/SnippetStore.kt`, `ui/SnippetPanel.kt`) plus one new entry-point hook in `ui/SshTermApp.kt`; shares no code path with the layout or close-reason tasks.

**Problem**: users repeatedly retype the same commands (`ll`, `tmux attach`, `systemctl status foo`, …) on a soft/hardware keyboard that is already the app's weak point for Latin-script typing speed vs. a desktop. (Originally captured in README §路线图 "命令 Snippet（常用命令收藏）" as a Sprint 3 candidate; now landed.)

**Design intent**: a small, global (not per-host, v1) list of saved commands the user can tap to send into the active `TerminalEndpoint`, with an optional trailing carriage return, plus add/edit/delete.

**NOT in scope**: command parameterization/templating (e.g. `{host}` substitution), per-host snippet scoping, cloud sync, import/export, keyboard shortcuts for snippets.

### 16.1 Data model

| ID | Spec |
|---|---|
| SNP-DM-01 | `CommandSnippet` shall be an immutable data class: `id: String, label: String, command: String, appendNewline: Boolean = true`. |
| SNP-DM-02 | `id` shall be generated once at creation time (e.g. `UUID.randomUUID().toString()`) and shall never change for the lifetime of the snippet, so edits don't reorder or duplicate entries. |

### 16.2 `SnippetStore` persistence

| ID | Spec |
|---|---|
| SNP-ST-01 | Given no snippets have ever been saved, `SnippetStore.getAll()` shall return an empty list. |
| SNP-ST-02 | Given a call to `SnippetStore.add(snippet)`, `getAll()` shall subsequently include that snippet, appended after all existing entries (insertion order preserved). |
| SNP-ST-03 | Given a call to `SnippetStore.update(snippet)` for an `id` that already exists, `getAll()` shall reflect the updated fields at the same list position; the entry order shall not change. |
| SNP-ST-04 | Given a call to `SnippetStore.delete(id)`, `getAll()` shall no longer contain an entry with that `id`; `delete` for a non-existent `id` shall be a no-op (no throw). |
| SNP-ST-05 | The store shall serialize the list as a single JSON array using the platform `org.json` classes (no new Gradle dependency, per `CLAUDE.md`'s "no libraries not listed in `implementation_plan.md`" constraint) into one `SharedPreferences` string field. |
| SNP-ST-06 | Given a corrupted/unparseable stored JSON string, `getAll()` shall return an empty list rather than throwing. | Same defensive posture as `KnownHostsStore.get` (KHS-ST-06) — a corrupt store is equivalent to no data, not a crash. |

### 16.3 Sending a snippet

| ID | Spec |
|---|---|
| SNP-SEND-01 | Given the user taps a snippet with `appendNewline == false`, the panel shall call `endpoint.write(command.toByteArray(Charsets.UTF_8))` and shall not append any extra bytes. |
| SNP-SEND-02 | Given the user taps a snippet with `appendNewline == true`, the panel shall call `endpoint.write(command.toByteArray(Charsets.UTF_8) + "\r".toByteArray(Charsets.UTF_8))`. | `\r` matches the existing `KEYCODE_ENTER` mapping (KM-KC-02), not `\n`. |
| SNP-SEND-03 | Sending a snippet shall use whatever `TerminalEndpoint` is currently bound (the same reference `KeyMapper`/paste writes to) — it shall not require an active `SshSession` and shall degrade to `MockEchoSession` exactly like every other input path when disconnected. |

### 16.4 UI affordance

| ID | Spec |
|---|---|
| SNP-UI-01 | The fullscreen terminal screen shall expose an entry-point control (icon button) that opens `SnippetPanel`; tapping outside the panel or a dedicated close control shall dismiss it without sending anything. |
| SNP-UI-02 | `SnippetPanel` shall list all snippets from `SnippetStore.getAll()` and shall re-read the list every time the panel is opened (no stale in-memory cache across add/edit/delete). |
| SNP-UI-03 | Adding a new snippet via the panel's form shall call `SnippetStore.add(...)` and the list shall reflect the new entry without requiring the panel to be closed and reopened. |
| SNP-UI-04 | Editing or deleting an existing snippet shall call `SnippetStore.update(...)` / `SnippetStore.delete(...)` respectively and the visible list shall update in the same recomposition pass. |

### 16.5 Testing

| ID | Spec |
|---|---|
| SNP-TS-01 | `SnippetStoreTest` (Robolectric, for `SharedPreferences`) shall cover SNP-ST-01..06. |
| SNP-TS-02 | A pure-JUnit test for a small helper `buildSnippetPayload(command: String, appendNewline: Boolean): ByteArray` shall cover SNP-SEND-01/02 without needing a `TerminalEndpoint` fake. |
| SNP-TS-03 | `SnippetPanel`'s Compose rendering is exercised by the manual device checklist only, consistent with the project's existing Compose-UI testing precedent. |

---

## Module 17: Session close-reason disambiguation (Sprint 3, S3)

> **Status (2026-07-02)**: ✅ **Implemented — race root-caused and closed.** `ssh/SessionCloseReason.kt` sealed class (`UserInitiated` / `RemoteEof` / `TransportError(message)` / `SinkError(message)`); `SshSession.lastCloseReason: @Volatile` field; `SshSession.close(userInitiated: Boolean = false)` synchronously writes `UserInitiated` **before** enqueueing async `transport.close()` (SCR-CL-01); single enforcement point `setCloseReasonUnlessUserInitiated()` gates every `readInto` exit branch so future maintainers cannot regress SCR-CL-02 by adding a new catch; `SshClient.disconnect(userInitiated: Boolean = false)` plumbs the signal via `onClose`; `TerminalPane.finally` now checks `session.lastCloseReason !is SessionCloseReason.UserInitiated` (SCR-TP-01) so user-initiated disconnects do not pop the "Connection Closed" overlay. `SshTermApp.kt` 3 user-initiated paths (BackHandler double-press, BackHandler snackbar action, pre-connect Disconnect button) capture live session reference → `session.close(userInitiated = true)` synchronously → fall back to `sshClient.disconnect()` if `activeSession == null` (SCR-UI-02). `SshSessionWriteTest` adds 4 `scr_ts_*` cases: SCR-TS-01 race verification, SCR-TS-02 clean EOF→`RemoteEof`, SCR-TS-02 `SocketException`→`TransportError` (with `SshErrorMessages.friendly` message pinned), SCR-TS-02 default `close()` does not set `UserInitiated` (SCR-CL-03). Drive-by fix: `FakeTransport.enqueueEof()` no longer NPEs on `LinkedBlockingQueue.put(null)` — switched to a singleton `ByteArray(0)` sentinel.

**Sprint 3 task, independent of Modules 15/16** — touches only `ssh/SshSession.kt`, `ssh/SshClient.kt` (disconnect call sites), and `ui/TerminalPane.kt`'s `finally` block; shares no code path with the layout or snippet tasks.

**Problem (root-caused, not just a naming gap)**: README §路线图 lists "`SshSession` 暴露真实错误事件（目前 readInto 失败的"连接断了"和 Disconnect 按钮的"用户主动断"在 UI 难区分）". Reading `ui/SshTermApp.kt`'s Disconnect button / back-handler paths against `ui/TerminalPane.kt`'s `LaunchedEffect` finds a genuine race, not just an ambiguous message:

1. The Disconnect button sets `activeSession = null` **then** calls `sshClient.disconnect()`, which synchronously closes the underlying sshj socket.
2. `activeSession = null` changes the key of `TerminalPane`'s `LaunchedEffect(sshSession, viewHolder.view)`, which schedules cancellation of the old `readInto`-driving coroutine — but Compose cancellation of the previous effect happens on the **next recomposition**, not synchronously.
3. If the socket close (step 1) reaches the still-running `readInto` loop's blocking read before the coroutine is actually cancelled (step 2), `transport.readBytes()` throws a `SocketException`. `readInto`'s `catch` block handles it as a normal (non-cancelled) failure, and the `finally` block's `if (isActive)` check (per `TerminalPane.kt:130-135`) still sees `isActive == true` (the coroutine hasn't been marked cancelled yet) — so it calls `onSessionClosed(...)`, which sets `ConnectionState.Error(...)` and pops the "Connection Closed" red overlay even though the user just clicked Disconnect.

**Design intent**: give `SshSession` an explicit, synchronously-set close reason that wins any race against the async socket teardown, so `TerminalPane` can reliably tell "user asked for this" apart from "the transport actually failed".

**NOT in scope**: changing the public `onSessionClosed: (reason: String) -> Unit` callback signature exposed to `ui/SshTermApp.kt` (the new sealed type stays internal to `ssh/` + `TerminalPane`, minimizing the UI-layer diff); finer-grained categories like connection-quality heuristics.

### 17.1 `SessionCloseReason` — value type

| ID | Spec |
|---|---|
| SCR-RS-01 | `SessionCloseReason` shall be a sealed class/interface with variants `UserInitiated`, `RemoteEof`, `TransportError(message: String)`, `SinkError(message: String)`. |
| SCR-RS-02 | Given `readInto` exits via clean EOF (`transport.readBytes() == null`), `SshSession.lastCloseReason` shall be set to `RemoteEof`, unless it is already `UserInitiated` (see SCR-CL-02). |
| SCR-RS-03 | Given `readInto` exits via `SocketException`, `SocketTimeoutException`, or `SSHException`, `SshSession.lastCloseReason` shall be set to `TransportError(SshErrorMessages.friendly(e))`, unless it is already `UserInitiated`. |
| SCR-RS-04 | Given `readInto`'s `sink` callback throws, `SshSession.lastCloseReason` shall be set to `SinkError(e.message ?: e.javaClass.simpleName)`, unless it is already `UserInitiated`. |

### 17.2 `SshSession.close` — synchronous reason capture

| ID | Spec |
|---|---|
| SCR-CL-01 | `SshSession` shall expose `fun close(userInitiated: Boolean = false)`. When called with `userInitiated = true`, it shall write `lastCloseReason = SessionCloseReason.UserInitiated` as the **first statement**, before enqueueing the (asynchronous) `transport.close()` work on `writeExecutor`. | Closes the race window described in the Problem section — the flag is visible to any concurrently-running `readInto` catch block before the socket is actually torn down. |
| SCR-CL-02 | Once `lastCloseReason` has been set to `UserInitiated`, no subsequent write from `readInto`'s exception/EOF handling (SCR-RS-02..04) shall overwrite it. | Core invariant of the race fix: "user asked first" always wins, regardless of which thread's write lands second. |
| SCR-CL-03 | `close()` (no-arg, existing call sites) shall behave exactly as `close(userInitiated = false)` — i.e. `lastCloseReason` is only set to `UserInitiated` when a caller explicitly opts in. |
| SCR-CL-04 | `close`'s existing idempotency (`SS-CL-02`: a second call is a no-op) is unchanged; if the first call already set a reason, a second call (with any `userInitiated` value) shall not change `lastCloseReason`. |

### 17.3 `TerminalPane` consumption

| ID | Spec |
|---|---|
| SCR-TP-01 | `TerminalPane`'s `finally` block shall call `onSessionClosed(...)` only when `isActive == true` **and** `session.lastCloseReason !is SessionCloseReason.UserInitiated`. | Replaces the current single `if (isActive)` check with a check that is race-proof against the scenario in the Problem section. |
| SCR-TP-02 | Given `session.lastCloseReason is SessionCloseReason.UserInitiated`, `TerminalPane` shall not call `onSessionClosed`, regardless of what `failureReason` would otherwise have been computed. |
| SCR-TP-03 | Given `session.lastCloseReason` is `RemoteEof`, `TransportError`, or `SinkError`, the string passed to `onSessionClosed` shall be unchanged from today's behavior (`failureReason ?: "Connection closed by remote"`) — this module changes *whether* the callback fires, not its message formatting. |

### 17.4 UI call-site wiring

| ID | Spec |
|---|---|
| SCR-UI-01 | The Disconnect button handler and the back-handler's double-press-to-disconnect handler in `ui/SshTermApp.kt` shall call `activeSession?.close(userInitiated = true)` instead of unconditionally calling `sshClient.disconnect()` directly. | `SshSession.onClose` already cascades to `SshClient.disconnect()` (per `SS-CL-01`), so the teardown path is unchanged — only the explicit "this was the user" signal is new. |
| SCR-UI-02 | Given `activeSession == null` at the time Disconnect is invoked (defensive edge case — e.g. a stale button state), the handler shall fall back to calling `sshClient.disconnect()` directly, matching today's behavior. |
| SCR-UI-03 | The `onFailure` branch of `handleConnectOutcome` (a failed *connect* attempt, not a live session) shall not be touched by this module — it never had a live `SshSession` to mark. |

### 17.5 Testing

| ID | Spec |
|---|---|
| SCR-TS-01 | `SshSessionWriteTest` shall add a case: call `session.close(userInitiated = true)`, then simulate a `SocketException` arriving from a concurrent `readInto` loop (via `FakeTransport`) — assert `lastCloseReason` is still `UserInitiated`. |
| SCR-TS-02 | `SshSessionWriteTest` shall add cases asserting `readInto`'s EOF / `SocketException` paths set `RemoteEof` / `TransportError` respectively when no prior `UserInitiated` close occurred. |
| SCR-TS-03 | `TerminalPane`'s branch on `lastCloseReason` is exercised by the manual device checklist (tap Disconnect mid-transfer; kill network mid-session) — Compose `LaunchedEffect` coroutine timing is not practical to assert under Robolectric per the project's existing `@Ignore`'d `readInto` timing cases (see `CLAUDE.md` "4 `@Ignore`'d cases in `SshSessionWriteTest`"). |

---

## Cross-cutting invariants (regressions to guard)

These cut across modules and are the highest-value tests to add next. Each is sourced from a documented bug fix in the implementation history.

| ID | Spec | Source |
|---|---|---|
| XI-01 | Given a `MotionEvent` whose `actionMasked == ACTION_DOWN` arrives on the `TerminalView`, the IME `InputMethodManager` shall receive the `View.onCreateInputConnection` callback the next time the user invokes the IME. | `TV-IN-01` is the trigger; ensures IME attaches on first tap. |
| XI-02 | Given the user holds a volume key, `setTextSize` shall be invoked at most once per *distinct* size value per second (idempotency guard in `TV-FS-01`). | Fix for the held-volume-key-induced channel close. |
| XI-03 | Given a `SshSession` is created and `close` is called twice, the second call shall be a no-op (the underlying transport is closed exactly once). | `SS-CL-02` is the spec. |
| XI-04 | Given the foreground-service `SshKeepAliveService` is running, the process shall be promoted to the "perceptible" priority bucket and the OS shall not kill the process for at least 30 minutes of background time. | Behavior of `Service.startForeground` on API 29+; tested manually. |
| XI-05 | Given the user's first IME interaction is a `setComposingText("")` followed by `deleteSurroundingText(1, 0)` (Gboard cancel pattern), no DEL byte shall be written to `TerminalEndpoint`. | `TIC-DS-01` + `TIC-SC-02` combination. The P0 race fix. |
| XI-06 | Given the user pastes from the clipboard with Ctrl+Shift+V while a pinyin composition is active, the clipboard contents shall be written to `TerminalEndpoint` and the IME shall not receive the keystrokes. | `TV-PRE-01` + `TV-OKD-02` combination. |
| XI-07 | Given a `SocketException` on the sshj `Reader` thread, the `crash.log` file shall remain empty (or contain only prior non-suppressed crashes) — the user shall not see a misleading "Last crash" overlay. | `CH-AB-01`. |
| XI-08 | Given `MainActivity` is recreated (config change we don't handle in the manifest, multi-window mode), the `ActiveSshSessionStore.get()` shall return the same live `SshSession` instance as before recreation. | `ASS-ST-02`. |
| XI-09 | Given `SshConfig.SO_TIMEOUT_MS = 60_000`, when the SSH banner read exceeds 60 s, `SshSession.readInto` shall return `Result.failure(SshException(SshErrorMessages.friendly(e), e))` with the banner-read error message (not the generic "check your network" message). | `SCFG-02` + `SE-FR-01` + `SS-RI-04`. |
| XI-10 | Given the user upgrades from a pre-Sprint-2.5 install, on the first launch of a release build, the pre-existing `filesDir/debug.log` shall be deleted. | `BC-COMPAT-01` closes the S3 leak path for upgraders. |
| XI-11 | Given `BuildConfig.DEBUG == false`, when the user attempts a connect, no call to `Log.*` shall originate from `PasswordAuthProvider.authenticate` and no entry shall be appended to `filesDir/debug.log`. | `PAP-LG-02` + `CS-DL-02`. The combined S3 + S4 invariant. |
| XI-12 | Given the user connects to a host for the first time, when sshj presents the host key, the `KnownHostsStore` shall contain a new row for `(host, port)` after the connect call returns, and the connect shall succeed. | `KHV-VF-02` + `KHS-ST-03` — the TOFU contract. |
| XI-13 | Given the user connects to a host that was previously enrolled, when sshj presents a host key whose fingerprint differs from the stored record, the connect call shall return `Result.failure(...)` with the user-readable MITM-warning message, and the `authenticate` step shall not be reached. | `KHV-VF-04` + `SC-KHV-03` + `SC-KHV-04` — the S1 fail-closed path. |
| XI-14 | Given a private key was imported under the encrypted-pem scheme, when the user attempts a public-key auth, the cleartext PEM shall be present on disk only under `cacheDir/ssh-pad-key-tmp/` (mode 0600, owner = app UID) for the duration of the SSHJ auth call, and the temp file shall not exist after the call returns (success or throw). | `PKP-RES-01` + `PKP-RES-02` — the S2 minimal-exposure invariant. |

---

## GEARS → GWT test translation table

Per `gears-spec-syntax` skill: GIVEN = `Given` + `While`, WHEN = `When`, THEN = `shall`. Each GEARS spec collapses to one GWT test. The table below shows the mapping for the most-tested specs — use as a template for new tests.

| GEARS ID | GIVEN | WHEN | THEN |
|---|---|---|---|
| `TIC-SC-01` | `connection` is constructed with a `MockEchoSession` endpoint | `connection.setComposingText("ni", 0)` | `connection.isComposing() == true`, `view.lastHint == "ni"`, `endpoint.bytesWritten().isEmpty()` |
| `TIC-CT-01` | `connection` is constructed, `setComposingText("ni", 0)` was called | `connection.commitText("你", 0)` | `connection.isComposing() == false`, `String(endpoint.bytesWritten(), UTF_8) == "你"` |
| `TIC-DS-01` | `userInImeContext` is latched true (via `setComposingText`) | `connection.deleteSurroundingText(1, 0)` | `endpoint.bytesWritten().isEmpty()` (no DEL to SSH) |
| `TIC-DS-02` | `userInImeContext` is false (idle) | `connection.deleteSurroundingText(3, 0)` | `endpoint.bytesWritten() == byteArrayOf(0x7F, 0x7F, 0x7F)` |
| `TIC-DS-04` | a previous `deleteSurroundingText` was sent to SSH | `connection.deleteSurroundingText(1, 0)` (after the first one) | the latch has reset; the second call also writes 0x7F |
| `KM-PS-01` | a `KeyEvent` with `keyCode == KEYCODE_V`, `isCtrlPressed`, `isShiftPressed` | `KeyMapper.resolve(keyCode, event)` | returns `KeyResolution.Paste` |
| `KM-PS-02` | a `KeyEvent` with `keyCode == KEYCODE_V`, `isCtrlPressed`, `!isShiftPressed` | `KeyMapper.resolve(keyCode, event)` | returns `KeyResolution.Ignore` |
| `KM-CTL-01` | a `KeyEvent` with `isCtrlPressed` and `keyCode == KEYCODE_C` | `KeyMapper.resolve(keyCode, event)` | returns `KeyResolution.Send(byteArrayOf(0x03))` |
| `KM-IS-01` | a `KeyEvent` with `keyCode == KEYCODE_LANGUAGE_SWITCH` | `KeyMapper.resolve(keyCode, event)` | returns `KeyResolution.Swallow` |
| `TV-OKD-06` | the `TerminalView` is bound to a `MockEchoSession`, no IME composing | `view.onKeyDown(KEYCODE_C, ev-with-CTRL)` | `endpoint.bytesWritten() == byteArrayOf(0x03)`, returns `true` |
| `TV-AB-01` | the emulator reports `isAlternateBufferActive == true` and `isMouseTrackingActive == false` | `view.onTouchEvent(ACTION_MOVE)` | returns `true`, no crash |
| `TV-FS-01` | the current font size is 16 | `view.setTextSize(16)` | `currentTextSize` stays 16, no `reportPtyResize` invocation |
| `SS-WR-03` | a non-closed `SshSession` with a `FakeTransport` | `session.write(byteArrayOf(0x03))` | `transport` receives `byteArrayOf(0x03)`, caller did not block |
| `SS-RI-02` | a coroutine driving `session.readInto { ... }` is cancelled | the coroutine is cancelled | `CancellationException` is rethrown unwrapped, `close()` is NOT called |
| `SS-RI-04` | `transport.readBytes()` throws `SocketTimeoutException` (banner read scenario) | `session.readInto { ... }` | returns `Result.failure(SshException("Server didn't respond with an SSH banner...", e))` |
| `SC-CN-05` | a successful `SshClient.connect` | (the connect call) | `client.connection.keepAlive.keepAliveInterval == 30` |
| `SC-CN-09` | `SshClient.buildSshjConfig()` (no socket needed — pure function) | inspect the returned `Config` | `config.keepAliveProvider == KeepAliveProvider.KEEP_ALIVE` (not sshj's `HEARTBEAT` default) |
| `SC-DC-01` | a live `SshClient` with an active session | `client.disconnect()` | `SshKeepAliveService` is stopped **before** the sshj client is closed |
| `SC-DC-03` | an `SSHClient` mock injected into `sshRef`, two threads calling `disconnect()` concurrently | both threads call `disconnect()` at once | `close()` is invoked exactly once; `sshRef` ends `null`; neither call throws |
| `SE-FR-01` | a `SocketTimeoutException` whose stack contains `TransportImpl.receiveServerIdent` | `SshErrorMessages.friendly(e)` | returns the banner-read message |
| `SE-FR-10` | a throwable whose cause chain is `a → b → a` | `SshErrorMessages.friendly(a)` | returns at the cycle boundary without hanging |
| `KSM-EN-01` | a plaintext `byteArrayOf(0x41, 0x42)` | `KeyStoreManager.encrypt(...)` | returns a 14-byte payload (12 IV + 2 ciphertext, tag included) |
| `KSM-DE-02` | a valid payload from `encrypt` | `KeyStoreManager.decrypt(payload)` | returns the original plaintext |
| `KSM-DE-01` | a 5-byte payload | `KeyStoreManager.decrypt(payload)` | throws `IllegalArgumentException("payload too short to contain an IV")` |
| `AP-EP-02` | `setEncryptedPassword(byteArrayOf())` was called | `getEncryptedPassword()` | returns `null` |
| `AP-HUC-01` | `host = "srv"`, `username = "u"`, `setEncryptedPassword(<valid>)` was called | `hasUsableCredentials()` | returns `true` |
| `AP-HUC-04` | `host = "srv"`, `username = "u"`, `password = "plain"` was set on the legacy slot, `setEncryptedPassword` was NEVER called | `hasUsableCredentials()` | returns `false` (uses the Plan C slot, not the legacy) |
| `ASS-ST-02` | `set(session)` was called | `get()` on a different thread | returns the same `session` instance |
| `CH-AB-01` | a `Thread("Reader")` throws `SSHException("Software caused connection abort", cause: SocketException)` | the uncaught handler runs | `crash.log` is not modified, previous handler is delegated to |
| `KHV-VF-02` | the `KnownHostsStore` has no record for `(host, port)`, sshj presents a host key | the verifier is called | the store contains a new row for `(host, port)` with the presented key's fingerprint, the verifier returns `true` |
| `KHV-VF-04` | the `KnownHostsStore` contains `(host, port) → (keyTypeA, fpA)`, sshj presents a key with `(keyTypeA, fpB ≠ fpA)` | the verifier is called | the store is unchanged, the verifier returns `false`, the connect returns `Result.failure` with the MITM warning |
| `PKP-RES-01` | `filesDir/keys/<safeName>.pem.enc` exists (encrypted under `KeyStoreManager`), auth is invoked | `PublicKeyAuthProvider.authenticate` runs | a temp file under `cacheDir/ssh-pad-key-tmp/` exists for the duration of the call, is gone after, and the auth call either succeeds (with a valid key) or fails cleanly |
| `PKP-RES-05` | the Keystore alias is missing (e.g. `KeyStoreManager.deleteKey()` was called) and a `.pem.enc` exists | `PublicKeyAuthProvider.authenticate` runs | the call returns a `Result.failure` with a user-readable "device keystore is unavailable" message; the auth flow does not progress past this point |
| `CS-DL-02` | a release build is running (no `BuildConfig.DEBUG` flag) | `ConfigScreen.appendDebugLog(context, "host=$host port=$port")` is called | `filesDir/debug.log` is not modified, no bytes are written |
| `PAP-LG-02` | a release build is running | `PasswordAuthProvider.authenticate` is called | `Log.*` is not called, `sha256Hex` is not invoked, the auth still proceeds normally |
| `BC-COMPAT-01` | a pre-Sprint-2.5 `filesDir/debug.log` exists | the upgraded app's first launch completes | `filesDir/debug.log` no longer exists |

---

## Spec coverage matrix

| Module | Specs | Test files (current) | Coverage |
|---|---|---|---|
| Module 1: IME pipeline | 14 (TIC-*) + 2 (TE-*) | `TerminalInputConnectionTest.kt` (11 tests) | ✅ Core flow covered; `TIC-DS-04` (latch reset) needs explicit test |
| Module 2: Routing | 30+ (KM-*, TV-*) | `KeyEventRoutingTest.kt` (31 tests) | ✅ Ctrl A–Z + `\` + `]` + Ctrl+Space + Ctrl+Shift+V all pinned (after `819c6bf` + `9d1830d`); `KM-CTL-04` (Ctrl+ESC) still under-tested |
| Module 3: View | 15+ (TV-EM-*, TV-PTY-*, TV-AB-*, TV-FS-*) | `TerminalViewLayoutTest.kt` (2) + `AltBufferScrollCrashGuardTest.kt` (6) | ✅ Alt-buffer guard (TV-AB-01..05) and layout re-measure (TV-LY-01..02) pinned; `TV-FS-01` (font-size idempotency) still needs explicit test |
| Module 4: SshSession / Transport | 18 (SS-*, CT-*) + 14 (SCR-* M17) | `SshSessionWriteTest.kt` (16 tests, **6 `@Ignore`**) | 🟡 `readInto` failure-path coverage is the bulk of the `@Ignore`s (`CLAUDE.md` "don't blindly delete"); `write` paths fully covered; Sprint 3 M17 `SessionCloseReason` race-fix pinned via `scr_ts_*` cases |
| Module 5: SshClient | 11 (SC-*) | `SshClientHostKeyWiringTest.kt` (8 tests) | 🟡 Connect integration manual; SC-KHV pinned |
| Module 6: ErrorMessages | 13 (SE-*, SCFG-*) | `SshErrorMessagesTest.kt` (17) + `SshConfigTest.kt` (6) | ✅ |
| Module 7: KeyStoreManager + Prefs | 18 (KSM-*, AP-*) | `AppPreferencesTest.kt` (13 tests) | 🟡 Keystore round-trip needs device; AP-PKN-02..03 pinned |
| Module 8: Auth | 11 (AUTH-*, PAP-*, PKP-*) | `PublicKeyAuthProviderTest.kt` (5, **2 `@Ignore`**) + encrypted/log-gate tests | 🟡 Ed25519 `@Ignore`; Keystore `@Assume` skips |
| Module 9: ActiveSessionStore | 6 (ASS-*) | `ActiveSshSessionStoreTest.kt` (4) | ✅ |
| Module 10: CrashHandler | 11 (CH-*, APP-*) | (no direct unit test) | 🟡 Manual only |
| Module 11: Host fingerprint (S2.5, S1) | 21 | `KnownHostsStoreTest.kt` (11) + `KnownHostsVerifierTest.kt` (10) + wiring (8) | ✅ |
| Module 12: Private key at rest (S2.5, S2) | 13 | `EncryptedPrivateKeyStoreTest.kt` (8) + `PublicKeyAuthProviderEncryptedTest.kt` (5) | 🟡 Keystore `@Assume` skips |
| Module 13: Debug log gating (S2.5, S3) | 9 | `ConfigScreenDebugLogGateTest.kt` (6) + `LegacyDebugLogCleanupTest.kt` (3) | ✅ |
| Module 14: Auth diagnostic gating (S2.5, S4) | 8 | `PasswordAuthProviderLogGateTest.kt` (3) + `PublicKeyAuthProviderLogGateTest.kt` (2) | ✅ |
| Module 15: Landscape split layout (Sprint 3, S1) | 9 (SL-*) | `LayoutDecisionTest.kt` (4 tests, pins SL-OR-01..03 + SL-TS-01) | ✅ Spec rule pinned; Compose `Row`/`Column` swap SL-LY-01/03..05 manual per SL-TS-02 |
| Module 16: Command snippets (Sprint 3, S2) | 13 (SNP-*) | `SnippetStoreTest.kt` (10 tests, pins SNP-ST-01..06) + `SnippetPayloadTest.kt` (4 tests, pins SNP-SEND-01..02 + SNP-TS-02) | ✅ Spec rules pinned; `SnippetPanel` Compose UI manual per SNP-TS-03 |
| Module 17: Session close-reason disambiguation (Sprint 3, S3) | 14 (SCR-*) | `SshSessionWriteTest.kt` (+4 `scr_ts_*` cases pinning SCR-TS-01 race + SCR-TS-02 `RemoteEof` / `TransportError` / `close()` no-`UserInitiated`) | ✅ Spec rules pinned; `TerminalPane` `LaunchedEffect` timing manual per SCR-TS-03 |
| Cross-cutting | 14 (XI-*) | (integration) | ❌ Gap |
| **Total** | **~316 GEARS specs** | **161/178 green** (6 `@Ignore` + 11 `@Assume`) | |

### Test inventory by file (2026-07-02 snapshot)

| Test file | `@Test` | `@Ignore` | Notes |
|---|---:|---:|---|
| `terminal/KeyEventRoutingTest.kt` | 42 | 0 | Was 8 → 31 (Ctrl A–Z / `\` / `]` / ESC / F-key rows in `819c6bf` / `9d1830d`) → 42 (Sprint 2.5+ vim/nano: 7 new keys + ESC-while-composing + end-to-end + meta-test + Ctrl+ESC, in `bac49f4` / `c6ad356` / `4f04a9e`) |
| `terminal/TerminalInputConnectionTest.kt` | 18 | 0 | TIC-SC/CT/DS/SK/FC + latch reset + Sprint 2.5+ harness updates |
| `terminal/TerminalViewLayoutTest.kt` | 2 | 0 | Pins TV-LY-01/02 |
| `terminal/TerminalViewScrollbackWiringTest.kt` | 7 | 0 | Pins TV-SB wrapper wiring |
| `terminal/TerminalViewSelectionWiringTest.kt` | 7 | 0 | Sprint 2.5+ long-press selection wiring |
| `terminal/TerminalViewSelectionActionModeTest.kt` | 7 | 0 | Sprint 2.5+ ActionMode integration |
| `terminal/TerminalViewTranscriptOutputTest.kt` | 4 | 0 | Sprint 2.5+ transcript output plumbing |
| `terminal/ScrollbackControllerTest.kt` | 25 | 0 | TV-SB-01..06 + state-machine + alt-buffer guard + pointer edges |
| `terminal/SelectionControllerTest.kt` | 11 | 0 | Sprint 2.5+ long-press clipboard copy controller |
| `terminal/SelectionControllerRobolectricTest.kt` | 3 | 0 | Robolectric harness for above |
| `terminal/AltBufferScrollCrashGuardTest.kt` | 9 | 0 | Pins TV-AB-01..05 + upstream-NPE regression reproducer |
| `ssh/SshSessionWriteTest.kt` | 16 | 6 | Was 12 + 4 `@Ignore`; Sprint 3 M17 added 4 `scr_ts_*` cases (race + EOF→`RemoteEof` + `SocketException`→`TransportError` + default `close()` no `UserInitiated`). `readInto` cancellation/`SocketTimeoutException` paths still on the `@Ignore` list (CLAUDE.md "don't blindly delete") |
| `ssh/SshErrorMessagesTest.kt` | 17 | 0 | Cause-chain + banner-read disambiguation |
| `ssh/SshConfigTest.kt` | 6 | 0 | SCFG-01..06 |
| `ssh/ActiveSshSessionStoreTest.kt` | 4 | 0 | ASS-ST-* |
| `ssh/SshClientHostKeyWiringTest.kt` | 8 | 0 | Sprint 2.5 S1 — SC-KHV-01..04 + KnownHostsVerifier wiring |
| `ssh/security/KnownHostsVerifierTest.kt` | 10 | 0 | Sprint 2.5 S1 — verifier trust/mismatch/unknown |
| `ssh/security/KnownHostsStoreTest.kt` | 11 | 0 | Sprint 2.5 S1 — store CR + corruption |
| `ssh/auth/PublicKeyAuthProviderTest.kt` | 3 | 2 | Ed25519 + OpenSSHv1 fixture still TODO |
| `ssh/auth/PublicKeyAuthProviderEncryptedTest.kt` | 5 | 0 | Sprint 2.5 S2 release-only encrypted PEM path |
| `ssh/auth/PublicKeyAuthProviderLogGateTest.kt` | 2 | 0 | Sprint 2.5 S3 — no sensitive bytes to log |
| `ssh/auth/PasswordAuthProviderLogGateTest.kt` | 3 | 0 | Sprint 2.5 S3 — no password hash leak |
| `data/prefs/AppPreferencesTest.kt` | 13 | 0 | Was 8 → 13; covers AP-HOST/PORT/USER/FS/EP/HUC/CL/PKN |
| `data/prefs/SnippetStoreTest.kt` | 10 | 0 | **Sprint 3 M16** — pin SNP-ST-01..06 |
| `data/crypto/EncryptedPrivateKeyStoreTest.kt` | 8 | 0 | Sprint 2.5 S2 |
| `logging/AppLogTest.kt` | 13 | 0 | Rotation + concurrent writes + Logcat mirror + S3 gating |
| `ui/ConnectionDraftTest.kt` | 2 | 0 | `ConfigScreen` form draft wiring |
| `ui/ConfigScreenDebugLogGateTest.kt` | 6 | 0 | Sprint 2.5 S3 |
| `ui/LayoutDecisionTest.kt` | 4 | 0 | **Sprint 3 M15** — pin SL-OR-01..03 + SL-TS-01 (2×2 truth table) |
| `ui/LegacyDebugLogCleanupTest.kt` | 3 | 0 | Sprint 2.5 S3 BC-COMPAT |
| `ui/SnippetPayloadTest.kt` | 4 | 0 | **Sprint 3 M16** — pin SNP-SEND-01..02 + SNP-TS-02 |
| **Total** | **285** | **6** | **279 active** |

### Known spec gaps to fill

From `docs/REVIEW_2026-06-24.md` §6.2, **updated 2026-07-02** with the actual `@Ignore` breakdown (was the 2026-06-26 Sprint 2.5 capture; Sprint 3 added 4 `scr_ts_*` cases and 1 more `@Ignore` for the same `runBlocking`-timing flake):

1. **`SshSession.readInto` failure paths** — **6 `@Ignore` in `SshSessionWriteTest`** (was 5 in the 2026-06-26 capture; Sprint 3 M17 grew it by 1). Still must adopt `runTest` + `StandardTestDispatcher` to replace `runBlocking + delay`.
2. **Ed25519 loading** — **2 `@Ignore` in `PublicKeyAuthProviderTest`** — needs a fixture; `bcpkix-jdk18on:1.78.1` is already in `testImplementation` per `CLAUDE.md` §"Test conventions", so the helpers exist; just needs the test body.
3. **End-to-end `SshClient.connect`** — needs a TestContainers sshd. (Out of CI scope per `CLAUDE.md`; manual lab testing only.)
4. **KeyStoreManager tests** — Robolectric's AndroidKeyStore is a stub; needs instrumented test on a real device. Explicitly out of unit-test scope per `CLAUDE.md` §"Out of scope".
5. **`ConfigScreen` Compose UI tests** — needs `composeTestRule` setup. Currently wired: `ConnectionDraftTest` (pure JUnit on the form-draft data layer), `LayoutDecisionTest` (Sprint 3 M15 pin on the pure function), `SnippetPayloadTest` (Sprint 3 M16 pin on the pure helper), `ConfigScreenDebugLogGateTest` + `LegacyDebugLogCleanupTest` (Sprint 2.5 S3 logs). Still-needed Compose tests: `SnippetPanel` modal sheet (defer to manual per SNP-TS-03) + the layout `Row`/`Column` swap (defer to manual per SL-TS-02).
6. **`runConnect` / `resolveAuth`** — need ViewModel extraction (Sprint 4+ prerequisite) to be testable.
7. **Cross-cutting (XI-01 to XI-14)** — integration / manual matrix.
8. **`TIC-DS-04` (latch reset)** + **`TV-FS-01` (font-size idempotency)`** — small, single-test gaps; each is a one-test addition. `KM-CTL-04` (Ctrl+ESC) was closed by the Sprint 2.5+ vim/nano KeyMapper refactor (`KeyEventRoutingTest.test_ctrlEscape_writesEscByte`).

### Sprint 2.5 implementation status — **landed 2026-06-29**

All four security debts in `docs/REVIEW_2026-06-24.md` §4 were implemented in Sprint 2.5 (2026-06-29 landing); see Modules 11–14 status banners (✅ Implemented) above, the coverage-matrix rows for those modules (✅ pinned), and the Sprint 2.5 landing entry in the revision history. Sprint 3 (Modules 15–17) landed 2026-07-02 on `feat/alt-buffer-cursor-scroll` — see the Sprint 3 implementation status section above for details.

### Spec-level security issues (specs authored as Modules 11–14)

All four security debts flagged in `docs/REVIEW_2026-06-24.md` §4 have a full GEARS sub-spec (Modules 11-14). The table below maps each risk to its module and the highest-priority spec inside.

| Risk | Module | Highest-priority spec | Why it's the priority |
|---|---|---|---|
| **S1 🔴** PromiscuousVerifier default → MITM | [Module 11](#module-11-security--host-fingerprint-sprint-25-s1) | `KHV-VF-04` + `SC-KHV-04` (fail-closed on mismatch, no auth spent) | Closes the silent "accept any host" default; fail-closed path. |
| **S2 🔴** Private keys in plaintext under `filesDir/keys/` | [Module 12](#module-12-security--private-key-at-rest-sprint-25-s2) | `PKP-RES-01` (encrypted file → temp file for SSHJ → securely deleted) | Minimizes cleartext lifetime on disk; matches the password-slot pattern. |
| **S3 🟡** `debug.log` leaks host/port/username via `adb pull` | [Module 13](#module-13-security--debug-log-gating-sprint-25-s3) | `CS-DL-02` + `BC-COMPAT-01` (no file write in release; one-shot cleanup for upgraders) | Closes the leak in release + the upgrade window in one spec pair. |
| **S4 🟡** `PasswordAuthProvider` logs truncated SHA-256 of password | [Module 14](#module-14-security--auth-diagnostic-gating-sprint-25-s4) | `PAP-LG-02` (no `Log.*` in release, `sha256Hex` not even called) | Closes the brute-forceable hash leak with zero CPU cost. |

**Unblocking prerequisite** (Modules 13 + 14): `app/build.gradle.kts` must set `buildFeatures { buildConfig = true }` (BC-EN-01). This is a one-line Gradle change that the reviewer already flagged as missing (§3.21 of `docs/REVIEW_2026-06-24.md`); verified still missing 2026-06-26.

**Implementation ordering recommendation** (Sprint 2.5):
1. **BC-EN-01** — unblock Modules 13 + 14.
2. **Module 11 (S1)** — biggest blast radius; do first so all subsequent dev is on a TOFU'd connection.
3. **Module 13 + 14 (S3 + S4)** — one PR each, small surface.
4. **Module 12 (S2)** — most invasive (key migration, temp-file lifecycle, fsync on the keystore), needs the most QA on real devices with multiple key formats.

---

## Sprint 3 implementation status (2026-07-02)

Modules 15–17 are **all implemented** on `feat/alt-buffer-cursor-scroll` (ahead of `origin/feat/alt-buffer-cursor-scroll` by 4 commits, awaiting push). Verified by directory scan: `ui/LayoutDecision.kt`, `data/prefs/SnippetStore.kt`, `ui/SnippetPanel.kt`, `ui/SnippetPayload.kt`, `ssh/SessionCloseReason.kt` all present; `LayoutDecisionTest` (4 cases), `SnippetStoreTest` (10 cases), `SnippetPayloadTest` (4 cases) all green; `SshSessionWriteTest` grew from 12 active + 4 `@Ignore` to 16 active + 6 `@Ignore` with 4 new `scr_ts_*` cases pinning SCR-TS-01..02.

Unlike Sprint 2.5's S1–S4 (which had a strict ordering recommendation because Modules 13/14 were blocked on a shared Gradle prerequisite and Module 11 had the biggest blast radius), **the three Sprint 3 tasks were mutually independent** and were deliberately scoped that way so they could be picked up in any order, in parallel, by different engineers/agents:

| Task | Module | Files touched | Status |
|---|---|---|---|
| 平板横屏布局优化 | [Module 15](#module-15-landscape-split-layout-sprint-3-s1) | new `ui/LayoutDecision.kt`, `ui/SshTermApp.kt` (Compose layout branch only) | ✅ landed (`a877470`) |
| 命令 Snippet | [Module 16](#module-16-command-snippets-sprint-3-s2) | new `data/prefs/SnippetStore.kt`, new `ui/SnippetPanel.kt`, new `ui/SnippetPayload.kt`, one entry-point hook in `ui/SshTermApp.kt` | ✅ landed (`b7ed0d8`) |
| SshSession 关闭原因区分 | [Module 17](#module-17-session-close-reason-disambiguation-sprint-3-s3) | new `ssh/SessionCloseReason.kt`, `ssh/SshSession.kt`, `ssh/SshClient.kt` (disconnect signature), `ui/SshTermApp.kt` (3 user-initiated paths), `ui/TerminalPane.kt` (finally block) | ✅ landed (`749cb9e`) |

The three tasks' touched-file sets were pairwise disjoint (Module 15 only edits a Compose branch, Module 16 is net-new files plus one hook, Module 17 only edits `ssh/` + one `finally` block) — none required another to land first, and none shared a test file.

**Next candidate** (Sprint 4+, requires explicit ask per `CLAUDE.md`): multi-host list / groups / CRUD UI; SFTP; port forwarding; ProxyJump; Mosh (most complex — defer to last).

**Not included in this Sprint 3 pass** (per explicit scope decision, still valid candidates for a future sprint):
- 多主机列表 + 分组 + 新增/编辑/删除 — deferred, not specced here; requires an explicit ask per `CLAUDE.md`'s "Sprint 3+ ... is out of scope for any change unless explicitly requested."
- `known_hosts TOFU` — **not a Sprint 3 item**; already fully implemented and specced as [Module 11](#module-11-security--host-fingerprint-sprint-25-s1) in Sprint 2.5 S1. The old README roadmap line referencing it under "Sprint 3" was stale and has been corrected.

---

## Revision history

| Date | Author | Change |
|---|---|---|
| 2026-06-25 | Hermes (GEARS skill) | Initial generation from Sprint 2 / 2.5 source + `docs/REVIEW_2026-06-24.md` + `PROMPT_SPRINT_2_FIX.md` |
| 2026-06-25 | Hermes (GEARS skill) | +Modules 11–14 (Sprint 2.5 security: S1 host fingerprint, S2 private key at rest, S3 debug log gating, S4 auth diagnostic gating). 51 new specs; +5 cross-cutting invariants (XI-10..14). Total: ~280 specs. |
| 2026-06-29 | Sprint 2.5 landing | Modules 11–14 implemented; test inventory 178 total (161 active). |
| 2026-06-26 | Status refresh | (1) Header corrected: actual = 113/120 unit tests green (7 `@Ignore`) across **12** test classes, not 20/20 across 6 — the original numbers were the Sprint 2 review's stale snapshot. (2) Module 3 §3.2 (TV-LY-01/02) marked ✅ after `a0a34a1 fix(terminal): re-measure inner Termux view in onLayout to fill wrapper` + `c181d15 test(terminal): pin onLayout re-measure for 1/4-screen regression`. (3) Module 2 §2.4 KM-CTL-01/02/03 marked ✅ after `819c6bf test(terminal): pin Ctrl+ A-Z + \ + ] byte routing` + `9d1830d feat(terminal): expand ctrlSequence mapping for tmux / readline shortcuts` + `1e71ddb docs: extend Ctrl+ routing table for full ASCII control set`. (4) Coverage matrix updated to actual per-file `@Test` / `@Ignore` counts; `SshSessionWriteTest` now 12 + 5 `@Ignore` (was 7 + 3); `KeyEventRoutingTest` now 31 (was 8). (5) Modules 11–14 given explicit ⚠️ "no code yet" status headers with verified-by-grep assertions. (6) Test-inventory + Sprint-2.5-status tables added. |
| 2026-07-02 | Sprint 3 planning | +Modules 15–17 (Sprint 3 task specs: S1 landscape split layout, S2 command snippets, S3 session close-reason disambiguation — the last one root-caused from a real Disconnect-vs-socket-close race, not just a naming gap). 36 new specs (9 SL-* + 13 SNP-* + 14 SCR-*). Header scope line updated (Sprint 3 is no longer blanket "out of scope" — only multi-host/SFTP/port-forward/Mosh remain excluded). New "Sprint 3 implementation status" section documents that the three tasks are mutually independent (disjoint touched-file sets) and can be parallelized. `README.md`'s stale "known_hosts TOFU" Sprint-3 roadmap line corrected (it was completed in Sprint 2.5 S1 / Module 11). Total: ~316 specs. |
| 2026-07-02 | Sprint 3 landing | All three Sprint 3 modules landed on `feat/alt-buffer-cursor-scroll`: M15 in `a877470 feat(ui): split pre-connect screen into two-column Row on tablet landscape` (`ui/LayoutDecision.kt` + `LayoutDecisionTest`), M16 in `b7ed0d8 feat(data,ui): command snippets — SnippetStore + SnippetPanel + entry button` (`data/prefs/SnippetStore.kt` + `ui/SnippetPanel.kt` + `ui/SnippetPayload.kt` + 2 new test classes), M17 in `749cb9e fix(ssh): disambiguate SshSession close reason vs. user-initiated disconnect` (new `ssh/SessionCloseReason.kt` + 4 new `scr_ts_*` cases on `SshSessionWriteTest`; closes the race root-caused in the §Problem section by writing `lastCloseReason = UserInitiated` synchronously before the async socket teardown enqueues, with `setCloseReasonUnlessUserInitiated()` as the single enforcement point for SCR-CL-02; `FakeTransport.enqueueEof()` NPE drive-by fixed with a `ByteArray(0)` sentinel). Status banners in M15/M16/M17 flipped from 📋 Planned to ✅ Implemented with file/test references. Module 15/16/17 rows in the coverage matrix updated to ✅ with their pinning test classes. Test inventory expanded: 12 → 31 test classes (added `LayoutDecisionTest` 4, `SnippetStoreTest` 10, `SnippetPayloadTest` 4), `SshSessionWriteTest` 12 + 4 `@Ignore` → 16 + 6 `@Ignore`. Header verification line updated: 161/178 → 279 active unit tests. Sprint 3 implementation status section rewritten to record landed status with commit SHAs. README "当前状态" table gained a Sprint 3 row mirroring the existing Sprint 2.5+ row format; 路线图 Sprint 3 sub-bullet items flipped to `[x]`; new 决策 §15 "SshSession 关闭原因区分:同步写入的 lastCloseReason @Volatile" added; 文档 section's `docs/GEARS_SPEC.md` description corrected from "尚未实现" to "全部已实现". |
