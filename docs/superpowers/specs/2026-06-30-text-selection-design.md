# Text Selection & Copy — Design Spec

**Date**: 2026-06-30
**Status**: Draft, pending user approval
**Scope**: Pad SSH terminal — enable flexible text selection from the terminal view and copy to the system clipboard.

## Problem

`com.termux.view.TerminalView` already exposes the building blocks for text selection (`startTextSelectionMode` / `stopTextSelectionMode` / `isSelectingText` / `updateFloatingToolbarVisibility`) and `com.termux.terminal.TerminalEmulator.getSelectedText(x1, y1, x2, y2)` for text extraction. The current `TerminalView` wrapper deliberately disabled long-press entry and left `TerminalOutput.onCopyTextToClipboard` as an empty stub. On a pad, the user has no native way to grab a compiler error message or a log line and paste it into a chat.

The paste side is already wired (Ctrl+Shift+V → `KeyResolution.Paste` → `pasteFromClipboard()`). This spec fills in the symmetric read side.

## Non-Goals

- No hardware-keyboard shortcut for copy (e.g. Ctrl+Shift+C). The user explicitly opted out. Existing Ctrl+Shift+V paste stays unchanged.
- No "select all screen" gesture beyond what Termux's ActionMode toolbar already provides (Select all).
- No custom Compose toolbar — Termux's `startTextSelectionMode(MotionEvent)` internally positions the floating toolbar via `updateFloatingToolbarVisibility`, matching Android's system-wide selection UX.
- No promise about which actions (Copy / Share / Select all / Cut) the floating toolbar surfaces. We don't add or remove toolbar items; whatever Termux's ActionMode shows is what users get. We only intercept the resulting `onCopyTextToClipboard` callback.
- No multi-clipboard history, no in-app clipboard picker, no share-sheet customization beyond the default Android share intent surfaced by the ActionMode toolbar.
- No change to `KeyMapper`, `TerminalInputConnection`, or the IME 5-method contract.

## Decisions

| Question | Decision |
|---|---|
| Entry point | Long-press → `termuxView.startTextSelectionMode(event)` |
| Toolbar | Termux built-in floating ActionMode toolbar (`updateFloatingToolbarVisibility`) |
| IME behavior | Hide soft keyboard on entering selection mode; rely on system focus restore on exit |
| Keyboard extension | None — selection driven by touch only |
| Implementation granularity | Extract `SelectionController`, testable in isolation |

## Architecture

### New file: `terminal/SelectionController.kt`

A single class with constructor-injected system services:

```kotlin
class SelectionController(
    private val view: View,
    private val clipboard: ClipboardManager,
    private val ime: InputMethodManager,
    private val toaster: (CharSequence) -> Unit = { msg ->
        Toast.makeText(view.context, msg, Toast.LENGTH_SHORT).show()
    },
) {
    /** True between enter() and exit(). Read by TerminalView for diagnostics. */
    var isActive: Boolean = false
        private set

    /**
     * Enter selection mode. Idempotent. If [event] is non-null (the long-press
     * path), the IME is hidden here; the [copyModeChanged] callback may invoke
     * enter() with a null event and the hide is skipped to avoid a double hide
     * (Android framework tolerates re-entrancy but skipping is cleaner).
     */
    fun enter(event: MotionEvent?) {
        if (isActive) return
        isActive = true
        if (event != null && view.windowToken != null) {
            runCatching { ime.hideSoftInputFromWindow(view.windowToken, 0) }
        }
    }

    /** Leave selection mode. Idempotent. Does not re-show the IME. */
    fun exit() {
        isActive = false
    }

    /**
     * Persist [text] to the system clipboard. Returns false (no-op) when the
     * text is null or empty, or when the system clipboard is unavailable.
     * Never throws — clipboard failures are logged via [AppLog] and swallowed.
     */
    fun copyToClipboard(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        if (clipboard == null) {
            AppLog.warn("SelectionController", "ClipboardManager unavailable; copy skipped")
            return false
        }
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("ssh-term", text))
            runCatching { toaster("已复制 ${text.length} 字符") }
                .onFailure { AppLog.warn("SelectionController", "toast failed", it) }
            true
        }.onFailure {
            AppLog.warn("SelectionController", "clipboard write failed", it)
        }.getOrDefault(false)
    }
}
```

### Modified file: `terminal/TerminalView.kt`

Three wiring changes, total ≈ 25 lines:

1. **New field**: `private val selectionController = SelectionController(this, context.getSystemService(...) as ClipboardManager, context.getSystemService(...) as InputMethodManager)`
2. **`termuxViewClient.onLongPress(event)`**: change `return false` to:
   ```kotlin
   override fun onLongPress(event: MotionEvent): Boolean {
       selectionController.enter(event)
       termuxView.startTextSelectionMode(event)
       return true
   }
   ```
3. **`termuxViewClient.copyModeChanged(copyMode)`**: change `{}` to:
   ```kotlin
   override fun copyModeChanged(copyMode: Boolean) {
       if (copyMode) selectionController.enter(event = null)
       else selectionController.exit()
   }
   ```
4. **`transcriptOutput.onCopyTextToClipboard(text)`**: change `{}` to:
   ```kotlin
   override fun onCopyTextToClipboard(text: String?) {
       // Always dismiss selection mode on the Copy action. The framework only
       // surfaces Copy on a non-empty selection, so the empty/null case is
       // theoretical — but if it does fire, dismissing is cleaner than letting
       // a stale toolbar linger. Clipboard failures (system service null, OEM
   // ROM throws) are surfaced via AppLog.warn, not via a stuck toolbar.
       selectionController.copyToClipboard(text)
       termuxView.stopTextSelectionMode()
   }
   ```

### Untouched

- `TerminalEndpoint` — selection does not write SSH bytes.
- `TerminalInputConnection` — IME 5-method contract and `userInImeContext` latch are stable.
- `KeyMapper` — no new `KeyResolution` variant.
- `pasteFromClipboard()` — Ctrl+Shift+V path remains the only paste entry.
- `AppPreferences` — no new field. (Disambiguation: the spec does NOT add a "selection hint shown" flag; the user did not ask for a discoverability banner.)

## Data Flow

### Entry A — Long-press

```
User finger long-presses terminal point (x, y)
  → com.termux.view.TerminalView.onTouchEvent
    → GestureDetector fires
      → termuxViewClient.onLongPress(event)               [TerminalView]
        ├─ selectionController.enter(event)              [SelectionController]
        │    · isActive = true
        │    · ime.hideSoftInputFromWindow(view.windowToken, 0)
        ├─ termuxView.startTextSelectionMode(event)
        │    · Termux enters selection mode, shows handles, shows ActionMode toolbar
        └─ return true  ← consumes the gesture
```

### Entry B — Drag handles (user adjusts range)

Pure Termux internals. `com.termux.view.TerminalView` updates its internal `mSelX1/Y1/X2/Y2` and re-runs `updateFloatingToolbarVisibility`. No controller involvement.

### Entry C — Tap Copy on ActionMode toolbar

```
User taps Copy
  → Android framework extracts selection
    → Termux calls TerminalOutput.onCopyTextToClipboard(text)   [transcriptOutput]
      ├─ selectionController.copyToClipboard(text)
      │    · null/empty  → return false; AppLog may warn; no Toast
      │    · success     → ClipboardManager.setPrimaryClip + toaster("已复制 N 字符")
      │    · clipboard==null → AppLog.warn; return false; no Toast
      └─ termuxView.stopTextSelectionMode()   ← ALWAYS called; clean teardown
```

### Entry D — User cancels (tap outside, X, app backgrounded)

```
Termux invokes termuxViewClient.copyModeChanged(false)
  → selectionController.exit()  · isActive = false
    · no showSoftInputFromWindow call — IME stays hidden until the user next
      taps TerminalView (focus never left, but the selection overlay captured
      touch). onCheckIsTextEditor() == true means the next tap re-shows IME
      via the system path; we do NOT proactively restore it here.
```

## IME Interaction

| Phase | IME state | Controller action |
|---|---|---|
| Idle, no IME | — | `isActive` false |
| User opens IME, starts pinyin | `isComposing = true` in `TerminalInputConnection` | unchanged |
| User long-presses while composing | Termux fires `onLongPress`; `hideSoftInputFromWindow` triggers IME to `finishComposingText` (per Android framework contract) → `TerminalInputConnection.finishComposingText()` runs the existing no-byte path | controller enters; IME hides; pinyin discarded without SSH pollution (existing `userInImeContext` latch is not invalidated because no DEL reaches SSH from this path) |
| Selection mode active | IME hidden | `copyModeChanged(true)` was the trigger; `enter(null)` keeps `isActive=true` and skips the hide (already hidden) |
| User taps Copy | `stopTextSelectionMode` releases IME capture | system focus restore re-shows IME on next user tap |
| User pastes from Ctrl+Shift+V mid-selection | IME hidden; key event arrives via `dispatchKeyEventPreIme` | Paste verdict wins; selection mode unaffected; user can resume adjusting range |

## Error Handling

| Scenario | Behavior |
|---|---|
| `ClipboardManager` system service returns null | `copyToClipboard` returns false, `AppLog.warn` entry, no Toast; `stopTextSelectionMode()` still called (clean teardown) |
| Selected text null or empty | `copyToClipboard` returns false, no Toast; `stopTextSelectionMode()` still called |
| `view.windowToken == null` at enter() | skip `hideSoftInputFromWindow`; set `isActive = true`; next `copyModeChanged(true)` will retry on a subsequent attach |
| Toast throws on vendor ROM | inner `runCatching` swallows; `AppLog.warn` entry; clipboard write still succeeds |
| Re-entrant `enter()` | early-return; idempotent |
| `stopTextSelectionMode` not paired with `startTextSelectionMode` (e.g. user backgrounds app) | Termux handles internally; `copyModeChanged(false)` fires later if applicable, controller exits cleanly |
| Hardware Ctrl+Shift+V during selection | existing `KeyResolution.Paste` path wins; clipboard text writes to SSH; selection mode unaffected |

## Testing Strategy

| Test class | Type | Coverage |
|---|---|---|
| `SelectionControllerTest` | pure JUnit | `enter` toggles `isActive`; idempotent re-entry; `exit` from active state; `copyToClipboard` empty/null no-op; ClipboardManager-null graceful fallback; `toaster` invoked exactly once on success; `toaster` not invoked on empty input |
| `SelectionControllerRobolectricTest` | Robolectric | real `hideSoftInputFromWindow` actually fires (assert via `ime.isAcceptingText` or shadow); real Toast surface; ClipboardManager round-trip readable from a second `ClipboardManager` lookup |
| `TerminalViewSelectionWiringTest` | Robolectric | `termuxViewClient.onLongPress(event)` invokes `selectionController.enter(event)` AND `termuxView.startTextSelectionMode(event)`; `copyModeChanged(false)` invokes `selectionController.exit()`; `transcriptOutput.onCopyTextToClipboard("foo")` invokes `selectionController.copyToClipboard("foo")` AND `termuxView.stopTextSelectionMode()`; `onCopyTextToClipboard("")` STILL calls `stopTextSelectionMode` (always-dismiss contract) |
| `TerminalInputConnectionTest` (existing) | unchanged | regression — verify selection mode does not perturb the `userInImeContext` latch reset contract |
| `KeyEventRoutingTest` (existing) | unchanged | regression — verify Ctrl+Shift+V Paste verdict still fires while selection mode is active |

### Manual E2E (out-of-band)

1. Long-press on terminal → handles appear, ActionMode toolbar shows
2. Drag handles → selection range updates visually
3. Tap Copy → clipboard has the text, Toast fires, selection dismisses
4. With clipboard populated, tap an Android text field elsewhere → paste works (smoke)
5. Mid-pinyin long-press → IME cancels, no stray bytes reach SSH
6. Ctrl+Shift+V during selection → clipboard text writes to SSH, selection unaffected
7. Background app mid-selection → re-foreground; selection either clears (Termux default) or remains; controller exits via `copyModeChanged(false)` either way

## Files Touched

| File | Change |
|---|---|
| `app/src/main/java/com/example/sshterminal/terminal/SelectionController.kt` | new file, ~50 lines |
| `app/src/main/java/com/example/sshterminal/terminal/TerminalView.kt` | 3 wiring sites, ≈ 25 lines net |
| `app/src/test/java/com/example/sshterminal/terminal/SelectionControllerTest.kt` | new file, pure JUnit |
| `app/src/test/java/com/example/sshterminal/terminal/SelectionControllerRobolectricTest.kt` | new file, Robolectric |
| `app/src/test/java/com/example/sshterminal/terminal/TerminalViewSelectionWiringTest.kt` | new file, Robolectric |

No changes to `KeyMapper`, `TerminalInputConnection`, `TerminalEndpoint`, `KeyResolution`, `SshSession`, `AppPreferences`, `AppLog`, `build.gradle.kts`, or `AndroidManifest.xml`.

## Risks

- **Termux black-box contracts.** `startTextSelectionMode(event)` / `stopTextSelectionMode()` / `copyModeChanged(boolean)` / `onCopyTextToClipboard(text)` are public API on `com.termux.view.TerminalView` / `TerminalViewClient` / `TerminalOutput` respectively. Robolectric tests cover wiring; the real behavioral correctness of those four APIs is verified by manual E2E on a device (no real-Android-device CI in this repo).
- **ActionMode toolbar visibility.** The toolbar relies on `termuxView.updateFloatingToolbarVisibility(event)` being called by Termux during selection drag. If Termux does not call it, the toolbar is invisible — selection still works, Copy is still reachable via long-press → "Select text" menu. Out of spec to investigate further without a reproduction.
- **`mSelX1/Y1` in termux grid coordinates.** Termux's coordinate model uses absolute (col, row) where col is the visible cell column and row is absolute transcript row (scrolled). `getSelectedText(x1,y1,x2,y2)` accepts these directly. No coordinate math in our wrapper.
- **`view.windowToken` race on rapid background.** Possible to call `enter(event)` after the view detached. The `runCatching { ime.hide... }` guards this, and `windowToken == null` check is a second guard.

## Acceptance Criteria

- [ ] Long-press on terminal enters selection mode (Termux handles toolbar + handles)
- [ ] IME hides immediately on long-press
- [ ] Copy writes the selected text to system clipboard with `label = "ssh-term"`
- [ ] Toast `已复制 N 字符` appears on successful copy
- [ ] Empty / null selection does NOT clear selection mode (user re-picks)
- [ ] `copyModeChanged(false)` resets `isActive` regardless of which path entered selection mode
- [ ] Ctrl+Shift+V paste works mid-selection
- [ ] No regressions in `TerminalInputConnectionTest` or `KeyEventRoutingTest`
- [ ] `AppLog.warn` entries present for clipboard-null and toast-throws paths (verifiable via existing `AppLogTest` patterns)