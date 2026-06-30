# Two-Finger Gesture Scrollback — Design Spec

**Date**: 2026-06-30
**Status**: Draft, pending user approval
**Scope**: Pad SSH terminal — let the user browse terminal scrollback by swiping with two fingers on the terminal view, with a "back to bottom" banner and a "N new lines arrived" indicator.

## Problem

The current wrapper builds `TerminalEmulator` directly (skipping `TerminalSession`) and wires it into `com.termux.view.TerminalView` via `mEmulator` reflection. Touch-driven scrollback never responds in practice — the user has no way to inspect a long log line, an `npm install` transcript, or a `git log` that scrolled past the visible area without rerunning the command and `tee`ing the output.

This spec adds a deliberate, two-finger-driven scrollback path on the wrapper, leaving the existing single-finger / long-press / Ctrl+Space / Ctrl+Shift+V / IME pipeline untouched.

## Non-Goals

- No new keyboard shortcut for scrollback (e.g. Shift+PageUp). Touch gesture only.
- No change to `transcriptRows` — Termux's default is fine; the user explicitly opted to keep it.
- No change to `KeyMapper`, `TerminalInputConnection`, or the IME 5-method contract.
- No fix for the existing single-finger Termux scroll (whatever the root cause is, this spec layers an additional path on top of the wrapper, not on top of the inner view's gesture detector).
- No selection-of-text inside scrollback is designed in this spec. The Termux selection flow (long-press → ActionMode) is independent of `mTopRow` and continues to work; if it doesn't behave well over scrolled content, that's a follow-up.
- No scrollbar widget, no minimap, no history search. Just a single banner that toggles "viewing history / N new lines" and a "back to bottom" tap target.
- No config-screen setting for scrollback size. Termux's `transcriptRows` default stays.
- No `release()` lifecycle on the controller (matches `SelectionController`).

## Decisions

| Question | Decision |
|---|---|
| Gesture | Two-finger drag on the wrapper `TerminalView` |
| Return to bottom | Banner with a tap target ("↑ 滚回历史" / "▼ N 行新输出") |
| New output during scrollback | Buffered in `pendingOutputCount`; banner shows count; tapping banner jumps to bottom |
| Buffer size | Termux's default `transcriptRows` (unchanged) |
| Auto-exit on reaching bottom | Yes — `mTopRow == 0` triggers `exitScrollback()` |
| State holder | `kotlinx.coroutines.flow.MutableStateFlow<ScrollbackState>` (project convention) |
| Pending count store | `java.util.concurrent.atomic.AtomicInteger` (written from IO thread) |
| Compose banner location | `ScrollbackBanner` inside the same `Box` in `TerminalPane` that hosts the `AndroidView` |

## Architecture

### New file: `terminal/ScrollbackController.kt`

A single class, mirror-image of `SelectionController`:

```kotlin
class ScrollbackController(
    private val view: View,
    private val emulator: TerminalEmulator,
) {
    sealed interface TouchDecision {
        data object PassThrough : TouchDecision
        data object Consumed : TouchDecision
        data class Scrolled(val deltaRows: Int) : TouchDecision
    }

    data class ScrollbackState(
        val isInScrollback: Boolean = false,
        val pendingOutputCount: Int = 0,
    )

    val state: StateFlow<ScrollbackState>

    fun onTouchEvent(ev: MotionEvent): TouchDecision
    fun onTranscriptWrite(byteCount: Int, columns: Int)
    fun scrollToBottom()
    // internal: enterScrollback(), exitScrollback(), recordAnchor(ev), clearAnchor()
}
```

The controller holds:
- `isInScrollback: Boolean` (UI thread only)
- `pendingOutputCount: AtomicInteger` (IO thread writes, UI thread reads & emits)
- `anchorPointerY: Float?` and `anchorTopRow: Int` (UI thread only, for delta computation)

It does NOT hold the `MotionEvent` itself (events are short-lived and the framework owns them).

### Changes to `terminal/TerminalView.kt`

- New private field `private val scrollbackController = ScrollbackController(this, emulator)`. Built **after** `emulator` and `termuxView` in the init order (mirrors how `selectionController` is built before either of them — emulator must be constructed first because the controller's `emulator` parameter is non-null).
- `dispatchTouchEvent` is extended: before calling `super`, ask the controller. If it returns `Consumed` or `Scrolled`, return `true` and skip `super`. The existing `requestFocus()` on `ACTION_DOWN` stays.
- The existing `termuxView.setOnTouchListener { ... }` (alt-buffer crash guard) is unchanged. Two-finger events are intercepted at the wrapper and never reach the inner view, so the inner view's listener never sees them.
- New public API:
  - `fun setScrollbackListener(listener: ((ScrollbackState) -> Unit)?)` — wires `controller.state` to the Compose banner.
  - `fun scrollToBottom()` — delegates to `controller.scrollToBottom()` and calls `termuxView.postInvalidateOnAnimation()`.
  - `val isInScrollback: Boolean` — read-only, for tests and any future diagnostics.
- The `transcriptOutput.write` override is changed from a no-op (just `termuxView.postInvalidateOnAnimation()`) to: still call `postInvalidateOnAnimation()`, then if `isInScrollback`, call `scrollbackController.onTranscriptWrite(len, emulator.mColumns)` and `view.post { controller.refreshState() }` to push the new count to the banner. **The line count lives in the controller; the view is just a glue point.**

### New file: `ui/ScrollbackBanner.kt`

A pure Compose component:

```kotlin
@Composable
fun ScrollbackBanner(
    state: ScrollbackState,
    onBackToBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isInScrollback) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
            .clickable(onClick = onBackToBottom)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("↑ 滚回历史", style = MaterialTheme.typography.labelLarge)
        if (state.pendingOutputCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                "▼ ${state.pendingOutputCount.coerceAtMost(9999)} 行新输出",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
```

### Changes to `ui/TerminalPane.kt`

- The `Box` that hosts the `AndroidView` gets a `ScrollbackBanner` overlay. The banner state is sourced from a `remember`-ed lambda that subscribes to `view.setScrollbackListener(...)` via `LaunchedEffect` and exposes the latest state as a Compose `MutableState`.

## Components

```
terminal/
├── TerminalView.kt              # + scrollbackController field, dispatchTouchEvent branch,
│                                #   setScrollbackListener, scrollToBottom, isInScrollback;
│                                #   transcriptOutput.write accumulates pendingOutputCount
├── ScrollbackController.kt      # 新增 — multi-touch detection, mTopRow manipulation,
│                                #   state emission, pending output counting
ui/
├── TerminalPane.kt              # + ScrollbackBanner(state) 叠层
├── ScrollbackBanner.kt          # 新增 — Compose 横幅
```

`ScrollbackController` responsibilities:
1. **Multi-touch detection**: identify when `pointerCount >= 2` and the gesture has not been claimed by the inner view
2. **Scrollback navigation**: translate pixel `dy` to row delta, mutate `emulator.mTopRow`, clamp to `[0, mTotalRows]`
3. **State management**: maintain `isInScrollback` and `pendingOutputCount`; emit `StateFlow<ScrollbackState>`
4. **New output counting**: line estimate = `max(1, byteCount / columns)`

`ScrollbackBanner` responsibilities:
1. Hidden when `!isInScrollback`
2. Show "↑ 滚回历史" when `isInScrollback && pendingOutputCount == 0`
3. Show "↑ 滚回历史 · ▼ N 行新输出" when both are true
4. Whole banner is a clickable surface that calls `onBackToBottom`

`TerminalView` responsibilities (additive only):
1. Route two-finger gestures to `scrollbackController` before `super.dispatchTouchEvent`
2. Forward `transcriptOutput.write` byte counts to the controller
3. Expose `scrollToBottom()` and the state subscription

## Data Flow

### Entering scrollback (two-finger DOWN → MOVE)
1. User places two fingers on the wrapper
2. `ACTION_POINTER_DOWN` arrives at `wrapper.dispatchTouchEvent`
3. `scrollbackController.onTouchEvent(ev)` sees `pointerCount >= 2`
4. Controller records two-finger centroid Y, current `emulator.mTopRow`, sets `isInScrollback = true`, emits state
5. Banner appears ("↑ 滚回历史")

### Scrolling
1. Subsequent `ACTION_MOVE` (still ≥ 2 pointers) arrives at the controller
2. Compute `deltaY = currentCentroidY - anchorY` (UI thread, single field read, no synchronization needed)
3. `deltaRows = (deltaY / fontLineSpacing).toInt()`. Sign: `deltaY > 0` means fingers moved down → user wants to see older content → `mTopRow` should increase.
4. `emulator.mTopRow = (mTopRow + deltaRows).coerceIn(0, mTotalRows)`
5. Update `anchorY` to the new centroid so the next `MOVE` is incremental (not cumulative)
6. `termuxView.postInvalidateOnAnimation()`
7. If new `mTopRow == 0`: call `exitScrollback()` and emit. The user has "dragged to the bottom" — no need to tap the banner.

### New output during scrollback
1. SSH → `emulator.append(bytes)` → wrapped `transcriptOutput.write` called
2. `scrollbackController.onTranscriptWrite(len, columns)`: `pendingCount = max(1, len / columns); pendingOutputCount.addAndGet(pendingCount)`
3. `view.post { controller.refreshState() }` — pushes the new count to the banner on UI thread
4. Banner shows "▼ N 行新输出"
5. **We do NOT touch `mTopRow`.** The emulator's transcript grows upward; `mTopRow` stays where the user left it, so the user keeps reading the same lines.

### Exit scrollback (banner tap)
1. User taps the banner
2. `onBackToBottom()` → `view.scrollToBottom()` → `controller.scrollToBottom()`
3. `emulator.mTopRow = 0`; `pendingOutputCount.set(0)`; `isInScrollback = false`
4. `termuxView.postInvalidateOnAnimation()`
5. Emit state. Banner disappears.

### Pointer-count transitions
- 0 → 1 (`ACTION_DOWN`): not our gesture. `PassThrough`. The inner view's `setOnTouchListener` (alt-buffer guard) sees it as usual.
- 1 → 2 (`ACTION_POINTER_DOWN`): enter scrollback. Record anchor centroid.
- 2 → 1 (`ACTION_POINTER_UP`): stay in scrollback. The anchor is **not** updated; the remaining finger's position may move, which is fine (we re-read centroid on every MOVE).
- 1 → 0 (`ACTION_UP` / `ACTION_CANCEL`): stay in scrollback until banner tap or auto-exit on `mTopRow == 0`. This avoids premature exits when the user pauses between drags.
- 0 → 3+ (rare): same as 0 → 2.

### Invariants (must hold)
- Single-finger behavior is byte-identical to the current wrapper.
- Double-finger events NEVER propagate to the inner `termuxView`.
- Alt-buffer crash guard (`isAltBufferScrollCrashPath`) is preserved.
- `mTopRow` writes happen only on UI thread.
- `pendingOutputCount` is the only field written from non-UI thread (IO).

## Error Handling

### Threading
- **UI thread**: MotionEvent dispatch, `emulator.mTopRow` writes, `postInvalidateOnAnimation`, Compose rendering.
- **IO thread** (`SshSession.readInto` on `Dispatchers.IO`): `pendingOutputCount.addAndGet(...)` only.
- `MutableStateFlow.value =` is thread-safe; we use `view.post { controller.refreshState() }` to bring the emit onto UI thread so Compose doesn't see cross-thread updates.
- `emulator.mTopRow` writes are assumed single-threaded (same assumption `reportPtyResize` makes for `mColumns` / `mRows`). This is a project-wide contract — Termux's emulator was designed for single-threaded use.

### Defensive guards
- `emulator == null` → early return. The controller is constructed in `TerminalView` init after `emulator` is non-null.
- `fontLineSpacing <= 0` → skip the scroll conversion (same guard as `reportPtyResize:583`).
- `pointerCount == 0` (rare `CANCEL` residue) → ignore.
- `pointerId` out of bounds → swallow the `MOVE`, log via `AppLog.warn` (no toast, no crash).
- First `ACTION_POINTER_DOWN` arriving with no prior anchor → use current centroid as anchor (handles "user puts both fingers down in the same frame" gracefully).

### Lifecycle
- No `release()` method. Controller is owned by the wrapper, GC'd with it. Matches `SelectionController` (no `release` there either).
- Banner disappears automatically on `TerminalPane` recomposition; nothing to detach.

### User-visible errors
- Internal errors (e.g. `emulator` throws) are swallowed + `AppLog.warn`. The user-perceived worst case is "double-finger swipe did nothing". The single-finger path is unaffected.
- No toast, no crash log entry. The contract is "double-finger scroll must NEVER break single-finger input / SSH / IME".

### Edge cases
- Rotation: `OnLayoutChangeListener` already triggers `reportPtyResize`. `mTopRow` is clamped by the emulator; rotation cannot push scrollback state out of bounds.
- Font size change: `setTextSize` triggers redraw. `mTopRow` unchanged (row count is invariant). Banner stays.
- `pendingOutputCount` overflow: `coerceAtMost(9999)` in the banner; the underlying `AtomicInteger` is fine to hold real values.
- Banner tap while a drag is in progress: banner is on a separate Compose layer above the `AndroidView`. `clickable` only fires on a clean `UP`; an active two-finger drag is consuming events at the wrapper, so the banner sees a clean `UP` only after the user lifts both fingers. No race.

## Testing

### Test files

**`terminal/ScrollbackControllerTest.kt`** — pure logic, mockk (parallels `SelectionControllerTest.kt`)
- Two-finger DOWN → `state.value.isInScrollback == true`
- Two-finger MOVE → `emulator.mTopRow` increases monotonically
- Two-finger MOVE clamp: cannot exceed `mTotalRows`; cannot go below 0
- Drag back to `mTopRow == 0` → `state.value.isInScrollback == false` (auto-exit)
- Single-finger DOWN/MOVE → state unchanged; returns `PassThrough`
- Three-finger DOWN → same effect as two-finger
- 1 → 2 pointer transition (`ACTION_POINTER_DOWN` frame) → correctly enters scrollback from single-finger context
- 2 → 1 pointer transition (`ACTION_POINTER_UP` frame) → stays in scrollback
- 0 → 0 (`ACTION_UP` / `ACTION_CANCEL`) → stays in scrollback
- `onTranscriptWrite(80, 80)` → `pendingOutputCount == 1`
- `onTranscriptWrite(160, 80)` → `pendingOutputCount == 2`
- `onTranscriptWrite(40, 80)` → `pendingOutputCount == 1` (floor at 1)
- `scrollToBottom()` → `mTopRow == 0`, `pendingOutputCount == 0`, `!isInScrollback`
- `fontLineSpacing == 0` → scroll does not throw, `mTopRow` unchanged

**`terminal/ScrollbackControllerRobolectricTest.kt`** — Robolectric (parallels `SelectionControllerRobolectricTest.kt`)
- Real `MotionEvent.obtain` end-to-end with two-frame `DOWN` + `MOVE` sequence
- Anchor is the first frame's centroid; second frame MOVE uses relative delta, not absolute

**`terminal/TerminalViewScrollbackWiringTest.kt`** — Robolectric, mounted on a real `TerminalView`
- `dispatchTouchEvent` with two-finger `MOVE` returns `true` (consumed)
- `dispatchTouchEvent` with single-finger `MOVE` returns the same as `super` (pass-through, alt-buffer guard still works)
- `scrollToBottom()` resets emulator state
- `setScrollbackListener` receives state transitions
- Regression: all 6 `AltBufferScrollCrashGuardTest` cases pass unchanged

**`ui/ScrollbackBannerTest.kt`** — Compose UI test
- Requires new deps in `app/build.gradle.kts`: `testImplementation("androidx.compose.ui:ui-test-junit4")` and `testImplementation("androidx.compose.ui:ui-test-manifest")`
- Hidden when `ScrollbackState(isInScrollback=false, pendingOutputCount=0)`
- Shows "↑ 滚回历史" when `isInScrollback=true, pendingOutputCount=0`
- Shows both "↑ 滚回历史" and "▼ N 行新输出" when both are non-zero
- Caps display at 9999
- Click invokes `onBackToBottom` exactly once

### Regression coverage
- `AltBufferScrollCrashGuardTest` (6 cases) — unchanged
- `KeyEventRoutingTest` — unchanged (two-finger does not produce KeyEvent)
- `TerminalInputConnectionTest` — unchanged
- `TerminalViewLayoutTest` — unchanged
- `TerminalViewSelectionWiringTest` — unchanged (single-finger long-press path is independent)

### Manual test checklist (on device)
1. Connect, run `seq 1 1000`, double-finger swipe up → banner appears, content scrolls
2. Drag down past bottom → banner auto-dismisses
3. Stay in scrollback for a few seconds while `watch -n 1 'date'` runs remotely → banner shows "▼ 5 行新输出" (or similar)
4. Tap banner → jumps to bottom, badge clears
5. Long-press in scrollback → text selection still works
6. Open `vim` (alt buffer), double-finger drag → does NOT crash (alt-buffer guard preserved)
7. Rotate device while in scrollback → banner position correct, `mTopRow` clamped
8. Change font size via volume key while in scrollback → banner correct
9. Ctrl+Shift+V paste during scrollback → still pastes, no banner interference
10. Ctrl+Space / Shift+Space during scrollback → IME toggle still works
