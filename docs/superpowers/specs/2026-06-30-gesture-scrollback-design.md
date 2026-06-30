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
| Gesture | Two-finger swipe on the wrapper `TerminalView` (one swipe = one page) |
| Scroll granularity | **Page-by-page only.** Each gesture scrolls exactly one screenful (`emulator.mRows` lines). No incremental line scroll, no fling/inertia, no partial-page threshold — a swipe that exceeds a half-page threshold commits one full page up or down; a shorter swipe is a no-op. |
| Mechanism | Reuse `com.termux.view.TerminalView.doScroll(MotionEvent, Int)` via reflection. The inner view's existing scrollback path (branch 3 in `AltBufferScrollCrashGuardTest`'s root-cause kdoc) mutates its own `mTopRow` for us. We do NOT touch `emulator.mTopRow` (it doesn't exist on `TerminalEmulator`; the spec was originally based on a misreading of the AAR's javadoc). |
| Return to bottom | Banner with a tap target ("↑ 滚回历史" / "▼ N 行新输出"); tap calls `scrollToBottom()` which uses `doScroll` with a large positive delta. |
| New output during scrollback | Buffered in `pendingOutputCount`; banner shows count; tapping banner jumps to bottom |
| Buffer size | Termux's default `transcriptRows` (unchanged) |
| Auto-exit on reaching bottom | Yes — when `doScroll` brings the inner view's `mTopRow` back to 0, the controller reads it (via reflection) and clears `isInScrollback` |
| State holder | `kotlinx.coroutines.flow.MutableStateFlow<ScrollbackState>` (project convention) |
| Pending count store | `java.util.concurrent.atomic.AtomicInteger` (written from IO thread) |
| Compose banner location | `ScrollbackBanner` inside the same `Box` in `TerminalPane` that hosts the `AndroidView` |

## Architecture

### New file: `terminal/ScrollbackController.kt`

A single class, mirror-image of `SelectionController`:

```kotlin
class ScrollbackController(
    private val view: View,
    private val innerView: com.termux.view.TerminalView,
    private val emulator: TerminalEmulator,
    private val fontLineSpacing: () -> Float,
) {
    sealed interface TouchDecision {
        data object PassThrough : TouchDecision
        data object Consumed : TouchDecision
    }

    data class ScrollbackState(
        val isInScrollback: Boolean = false,
        val pendingOutputCount: Int = 0,
    )

    val state: StateFlow<ScrollbackState>

    fun onTouchEvent(ev: MotionEvent): TouchDecision
    fun onTranscriptWrite(byteCount: Int, columns: Int)
    fun scrollToBottom()
}
```

The controller holds:
- `isInScrollback: Boolean` (UI thread only)
- `pendingOutputCount: AtomicInteger` (IO thread writes, UI thread reads & emits)
- `gestureInitialY: Float?` and `gestureFinalY: Float?` (UI thread only, for page-scroll direction)
- `lastMoveEvent: MotionEvent?` (UI thread; needed to call doScroll on ACTION_UP since doScroll takes a MotionEvent arg)
- `doScrollMethod: Method` (cached on construction, looked up once via `getDeclaredMethod("doScroll", MotionEvent::class.java, Int::class.javaPrimitiveType)`)

`doScroll` on `com.termux.view.TerminalView` is package-private; the existing `AltBufferScrollCrashGuardTest` already uses reflection to invoke it for branch-2 NPE reproduction. Same pattern here, just for branch 3 (the safe one).

It does NOT hold the `MotionEvent` long-term — only the most recent MOVE event while a gesture is in flight.

### Changes to `terminal/TerminalView.kt`

- New private field `private val scrollbackController = ScrollbackController(this, termuxView, emulator, fontLineSpacing = { termuxView.mRenderer?.getFontLineSpacing()?.toFloat() ?: 0f })`. Built **after** `emulator` and `termuxView` in the init order.
- `dispatchTouchEvent` is extended: before calling `super`, ask the controller. If it returns `Consumed`, return `true` and skip `super`. The existing `requestFocus()` on `ACTION_DOWN` stays.
- The existing `termuxView.setOnTouchListener { ... }` (alt-buffer crash guard) is unchanged. Two-finger events are intercepted at the wrapper and never reach the inner view, so the inner view's listener never sees them. (Note: the alt-buffer crash guard is **only** active in alt-buffer mode; in normal scrollback we let `doScroll` handle it.)
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
2. **Scrollback navigation**: translate pixel `dy` to row delta, mutate `emulator.mTopRow`, clamp to `[0, mTotalRows - mRows]` (Termux's `mTopRow` is the index of the topmost visible row in the transcript; valid range is `0..mTotalRows-mRows`)
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

### Entering scrollback (two-finger DOWN)
1. User places two fingers on the wrapper
2. `ACTION_POINTER_DOWN` arrives at `wrapper.dispatchTouchEvent`
3. `scrollbackController.onTouchEvent(ev)` sees `pointerCount >= 2`
4. Controller records two-finger centroid Y (`gestureInitialY`), sets `isInScrollback = true`, emits state
5. Banner appears ("↑ 滚回历史")
6. Controller returns `TouchDecision.Consumed`; wrapper returns `true` from `dispatchTouchEvent`; inner view never sees the event

### Scrolling (MOVE → UP)
1. Subsequent `ACTION_MOVE` (still ≥ 2 pointers) arrives at the controller
2. Controller stores the most recent MOVE event as `lastMoveEvent` and updates `gestureFinalY = centroidY(ev)`
3. Controller returns `TouchDecision.Consumed`
4. `ACTION_POINTER_UP` (one finger lifts, gesture ending) → still `Consumed` but no scroll action yet
5. `ACTION_UP` (last finger lifts):
   - Compute `dy = gestureFinalY - gestureInitialY` (single-shot; the inner view is NOT scrolling incrementally during the gesture)
   - Compute `threshold = fontLineSpacing() * emulator.mRows / 2` (half a page)
   - If `dy < -threshold` (user swiped UP more than half a page): call `doScrollMethod.invoke(innerView, lastMoveEvent, -emulator.mRows)` — page up
   - If `dy > +threshold`: call `doScrollMethod.invoke(innerView, lastMoveEvent, +emulator.mRows)` — page down
   - Otherwise: no-op (a tiny swipe is treated as a no-page-change gesture)
   - Reset `gestureInitialY` and `gestureFinalY` for the next gesture
6. After the doScroll call, read `innerView.mTopRow` (via the same reflection approach used to look up the method) — if it's `0`, set `isInScrollback = false` and emit. The user has paged back to the live view.

### New output during scrollback
1. SSH → `emulator.append(bytes)` → wrapped `transcriptOutput.write` called
2. `scrollbackController.onTranscriptWrite(len, columns)`: `pendingCount = max(1, len / columns); pendingOutputCount.addAndGet(pendingCount)`
3. `view.post { controller.refreshState() }` — pushes the new count to the banner on UI thread
4. Banner shows "▼ N 行新输出"
5. **We do NOT touch `mTopRow` and we do NOT call doScroll.** The user explicitly chose to be in scrollback; the inner view continues to render the scrolled-back content because the emulator's `mTopRow` (managed by the inner view, not us) hasn't changed.

### Exit scrollback (banner tap)
1. User taps the banner
2. `onBackToBottom()` → `view.scrollToBottom()` → `controller.scrollToBottom()`
3. Controller calls `doScrollMethod.invoke(innerView, lastMoveEvent ?: freshMoveEvent, +emulator.mTotalRows)` — a deliberately-oversized positive delta; `doScroll` clamps internally
4. Controller resets `isInScrollback = false`, `pendingOutputCount = 0`
5. `termuxView.postInvalidateOnAnimation()`
6. Emit state. Banner disappears.

### Pointer-count transitions
- 0 → 1 (`ACTION_DOWN`): not our gesture. `PassThrough`. The inner view's `setOnTouchListener` (alt-buffer guard) sees it as usual.
- 1 → 2 (`ACTION_POINTER_DOWN`): enter scrollback. Record initial centroid. `isInScrollback = true`.
- 2 → 1 (`ACTION_POINTER_UP`): stay in scrollback. Don't update centroid; the remaining finger is the canonical pointer, but we re-read centroid on every MOVE.
- 1 → 0 (`ACTION_UP` / `ACTION_CANCEL`): **commit the page scroll** (see step 5 in the "Scrolling" data flow above). This is where the work happens, not during MOVE.
- 0 → 3+ (rare): same as 0 → 2 — initial centroid is the first two pointers' average.

### Invariants (must hold)
- Single-finger behavior is byte-identical to the current wrapper.
- Double-finger events NEVER propagate to the inner `termuxView`.
- Alt-buffer crash guard (`isAltBufferScrollCrashPath`) is preserved — but note that in normal scrollback (the common case), the inner view's existing doScroll branch 3 is the path we exercise, and it's safe.
- `doScroll` is invoked only on UI thread.
- `pendingOutputCount` is the only field written from non-UI thread (IO).

## Error Handling

### Threading
- **UI thread**: MotionEvent dispatch, `doScroll` invocation, `postInvalidateOnAnimation`, Compose rendering.
- **IO thread** (`SshSession.readInto` on `Dispatchers.IO`): `pendingOutputCount.addAndGet(...)` only.
- `MutableStateFlow.value =` is thread-safe; we use `view.post { controller.refreshState() }` to bring the emit onto UI thread so Compose doesn't see cross-thread updates.
- `doScroll` is invoked only on UI thread. Termux's inner view was designed for UI-thread use (same assumption as the existing `selectionController` IME hide call).

### Defensive guards
- `emulator == null` or `innerView == null` → early return. The controller is constructed in `TerminalView` init after both are non-null.
- `fontLineSpacing <= 0` → threshold check degenerates to "any dy > 0" (since threshold = 0). This is the "renderer not ready" path; same guard as `reportPtyResize:583`. In practice the renderer is always ready by the time the user touches the screen.
- `pointerCount == 0` (rare `CANCEL` residue) → ignore.
- `pointerId` out of bounds → swallow the `MOVE`, log via `AppLog.warn` (no toast, no crash).
- First `ACTION_POINTER_DOWN` arriving with no prior centroid → use current centroid as initial.
- `lastMoveEvent == null` on ACTION_UP (e.g. user did POINTER_DOWN then immediately UP without a MOVE) → synthesize a fake ACTION_MOVE MotionEvent for doScroll to consume, or skip the scroll. We prefer to skip: a no-MOVE gesture is almost certainly a misclick.
- `doScroll` reflection throws (`InvocationTargetException`) → log via `AppLog.warn`, swallow. Single-finger path is unaffected.
- Alt-buffer mode (`isAltBufferScrollCrashPath == true`): do NOT call doScroll. The user is in vim/less/htop and the scrollback is owned by the remote. The controller's onTouchEvent still returns `Consumed` to prevent the wrapper from forwarding two-finger events to the inner view's GestureDetector (which would NPE). Effectively: in alt-buffer mode, two-finger gestures are silently swallowed.

### Lifecycle
- No `release()` method. Controller is owned by the wrapper, GC'd with it. Matches `SelectionController` (no `release` there either).
- Banner disappears automatically on `TerminalPane` recomposition; nothing to detach.
- `doScrollMethod` is a `Method` reference cached at construction. `Method` objects are not `Closeable`; no cleanup needed.

### User-visible errors
- Internal errors (e.g. `emulator` throws, doScroll reflection fails) are swallowed + `AppLog.warn`. The user-perceived worst case is "double-finger swipe did nothing". The single-finger path is unaffected.
- No toast, no crash log entry. The contract is "double-finger scroll must NEVER break single-finger input / SSH / IME".

### Edge cases
- Rotation: `OnLayoutChangeListener` already triggers `reportPtyResize`. `mTopRow` is clamped by the inner view's own logic; rotation does not push scrollback state out of bounds.
- Font size change: `setTextSize` triggers redraw. `mTopRow` unchanged. The threshold (which depends on lineSpacing) is re-evaluated on the next gesture.
- `pendingOutputCount` overflow: `coerceAtMost(9999)` in the banner; the underlying `AtomicInteger` is fine to hold real values.
- Banner tap while a drag is in progress: banner is on a separate Compose layer above the `AndroidView`. `clickable` only fires on a clean `UP`; an active two-finger drag is consuming events at the wrapper, so the banner sees a clean `UP` only after the user lifts both fingers. No race.
- User does POINTER_DOWN then immediately ACTION_UP without MOVE: `gestureFinalY == gestureInitialY`, `dy == 0`, neither threshold branch fires, no scroll. `gestureInitialY` is reset; `isInScrollback` stays true (so the banner doesn't flicker). This is acceptable — the user can swipe again or tap the banner.
- User does POINTER_DOWN then ACTION_POINTER_UP then ACTION_UP: same as the above — the gesture is committed on the final ACTION_UP, and the threshold check uses the centroid Y of the last MOVE (or POINTER_DOWN if no MOVE happened).

## Testing

### Test files

**`terminal/ScrollbackControllerTest.kt`** — pure logic + Robolectric. Uses a real `TerminalView` (which builds a real `TerminalEmulator` via its constructor) so we can read `termuxView.mTopRow` directly. The `innerView` argument to the controller is `view.termuxView`. Robolectric is required for `View`.

- Two-finger DOWN → `state.value.isInScrollback == true`
- Two-finger DOWN + UP with `dy < -threshold` (swipe up, half-page) → `doScroll` is called with `-emulator.mRows`; `termuxView.mTopRow` increases by `emulator.mRows`
- Two-finger DOWN + UP with `dy > +threshold` (swipe down) → `doScroll` called with `+emulator.mRows`; `mTopRow` decreases by `emulator.mRows` (clamped at 0)
- Two-finger DOWN + UP with `|dy| < threshold` (tiny swipe) → no `doScroll` call
- Page down brings `mTopRow` to 0 → `state.value.isInScrollback == false` (auto-exit)
- Single-finger DOWN → `PassThrough`
- Three-finger DOWN → same effect as two-finger
- 1 → 2 pointer transition (`ACTION_POINTER_DOWN` frame) → enters scrollback
- 2 → 1 pointer transition (`ACTION_POINTER_UP` frame) → stays in scrollback
- Alt-buffer mode (`isAlternateBufferActive && !isMouseTrackingActive`) → two-finger gestures are consumed (doScroll NOT called to avoid the NPE; the user is in a remote TUI and the scroll is owned by the remote)
- `onTranscriptWrite(80, 80)` → `pendingOutputCount == 1`
- `onTranscriptWrite(160, 80)` → `pendingOutputCount == 2`
- `onTranscriptWrite(40, 80)` → `pendingOutputCount == 1` (floor at 1)
- `scrollToBottom()` → `mTopRow` returns to 0, `pendingOutputCount == 0`, `!isInScrollback`
- `fontLineSpacing == 0` → threshold check still works (any dy triggers; production path won't hit this)

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
1. Connect, run `seq 1 1000`, double-finger swipe up → banner appears, content scrolls back one page
2. Double-finger swipe up again → scrolls back another page (one swipe = one page)
3. Double-finger swipe down → scrolls forward one page; when the inner view's mTopRow reaches 0, banner auto-dismisses
4. Stay in scrollback for a few seconds while `watch -n 1 'date'` runs remotely → banner shows "▼ 5 行新输出" (or similar)
5. Tap banner → jumps to bottom, badge clears
6. Long-press in scrollback → text selection still works
7. Open `vim` (alt buffer), double-finger drag → does NOT crash (alt-buffer guard preserved; the scroll is owned by the remote and our two-finger events are silently consumed)
8. Rotate device while in scrollback → banner position correct, `mTopRow` clamped
9. Change font size via volume key while in scrollback → banner correct, threshold re-evaluates on next gesture
10. Ctrl+Shift+V paste during scrollback → still pastes, no banner interference
11. Ctrl+Space / Shift+Space during scrollback → IME toggle still works
12. Tiny two-finger swipe (less than half a page) → no page change, no error
