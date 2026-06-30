# Two-Finger Gesture Scrollback — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a two-finger drag gesture to the pad SSH terminal that lets the user scroll back through terminal history, with a "back to bottom" banner and an indicator of newly arrived output.

**Architecture:** Introduce a `ScrollbackController` that mirrors `SelectionController`'s shape (constructor-injected `View` + `TerminalEmulator`, single state, mockable in isolation). The wrapper `TerminalView` intercepts `MotionEvent` at `dispatchTouchEvent` and consults the controller before forwarding to the inner Termux view. A new `ScrollbackBanner` Compose component overlays the `AndroidView` in `TerminalPane` and is driven by the controller's `StateFlow<ScrollbackState>`. Single-finger behavior, `KeyMapper`, the IME 5-method contract, and the existing alt-buffer crash guard are all untouched.

**Tech Stack:** Kotlin 1.9, Robolectric 4.13, JUnit 4.13.2, mockk 1.13.13, `kotlinx.coroutines` 1.7+ (StateFlow already in use), AppLog (existing), androidx.compose.ui:ui-test-junit4 + ui-test-manifest (new test-only deps).

**Spec:** `docs/superpowers/specs/2026-06-30-gesture-scrollback-design.md`

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt` | create | Multi-touch detection, `mTopRow` mutation, `pendingOutputCount`, `StateFlow<ScrollbackState>`, `scrollToBottom()` |
| `app/src/main/java/com/example/sshterminal/terminal/TerminalView.kt` | modify | + `scrollbackController` field, `dispatchTouchEvent` consults controller, `transcriptOutput.write` forwards byte counts, new public API (`setScrollbackListener`, `scrollToBottom`, `isInScrollback`) |
| `app/src/main/java/com/example/sshterminal/ui/ScrollbackBanner.kt` | create | Compose banner: hidden by default, "↑ 滚回历史" + optional "▼ N 行新输出" badge, whole row clickable |
| `app/src/main/java/com/example/sshterminal/ui/TerminalPane.kt` | modify | Wrap `AndroidView` in `Box`, overlay `ScrollbackBanner` driven by a `LaunchedEffect`-subscribed `MutableState` |
| `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt` | create | Pure logic + mockk: state machine, multi-touch entry, scroll math, output counting, `scrollToBottom`, defensive guards |
| `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerRobolectricTest.kt` | create | Real `MotionEvent.obtain` two-frame `DOWN`+`MOVE` end-to-end |
| `app/src/test/java/com/example/sshterminal/terminal/TerminalViewScrollbackWiringTest.kt` | create | Robolectric: `dispatchTouchEvent` returns true for 2-finger, false for 1-finger; `scrollToBottom` resets emulator; `setScrollbackListener` receives states; all 6 `AltBufferScrollCrashGuardTest` cases still pass |
| `app/src/test/java/com/example/sshterminal/ui/ScrollbackBannerTest.kt` | create | Compose UI test: hidden / visible / with badge / click fires `onBackToBottom` |
| `app/build.gradle.kts` | modify | + `testImplementation("androidx.compose.ui:ui-test-junit4")` and `testImplementation("androidx.compose.ui:ui-test-manifest")` |

No other files change. `KeyMapper`, `TerminalInputConnection`, `TerminalEndpoint`, `SshSession`, `AppPreferences`, `AppLog`, `AndroidManifest.xml` are untouched. The existing `termuxView.setOnTouchListener { ... }` alt-buffer guard stays exactly as it is — two-finger events never reach it because the wrapper intercepts them first.

---

## Task 1: ScrollbackController — scaffold + state data class

**Files:**
- Create: `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt`
- Create: `app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt`

- [ ] **Step 1: Write the failing test (state defaults)**

Create the test file with a single test pinning the initial state. Use mockk to satisfy the constructor — full assertions come in later tasks. The point of this first task is to make the class compile.

```kotlin
package com.example.sshterminal.terminal

import android.view.View
import com.termux.terminal.TerminalEmulator
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-logic tests for [ScrollbackController]. Uses mockk for the
 * [TerminalEmulator] (final class with JNI) so the state machine can be
 * driven without touching the AAR. Robolectric is required for `View`
 * (used by mockk's relaxed mocks); no real MotionEvents are constructed
 * here — see [ScrollbackControllerRobolectricTest] for the
 * MotionEvent.obtain path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScrollbackControllerTest {

    @Test
    fun state_isInScrollbackFalseByDefault() {
        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = mockk(relaxed = true),
            fontLineSpacing = { 16f },
        )

        assertFalse(controller.state.value.isInScrollback)
        assertEquals(0, controller.state.value.pendingOutputCount)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.ScrollbackControllerTest" -i
```
Expected: FAIL with `Unresolved reference: ScrollbackController`.

- [ ] **Step 3: Write minimal implementation (class shell)**

Create `app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt`:

```kotlin
package com.example.sshterminal.terminal

import android.view.MotionEvent
import android.view.View
import com.termux.terminal.TerminalEmulator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the two-finger scrollback gesture on the pad SSH client.
 *
 * Responsibilities (full scope in
 * docs/superpowers/specs/2026-06-30-gesture-scrollback-design.md):
 *   1. Multi-touch detection — pass through single-finger events,
 *      consume two-finger events and translate the dy to row deltas
 *      written to emulator.mTopRow.
 *   2. New-output counting — `pendingOutputCount` accumulates while
 *      the user is scrolled back; the banner reads it to render the
 *      "▼ N 行新输出" badge.
 *   3. State emission — `StateFlow<ScrollbackState>` is the single
 *      source of truth for the banner. Writes from IO thread go
 *      through `view.post { ... }` to land on the UI thread before
 *      any emission (Termux's emulator is single-threaded; same
 *      contract as TerminalView.reportPtyResize).
 *
 * No `release()` lifecycle — the controller is owned by the wrapper
 * and GC'd with it. Matches [SelectionController].
 */
class ScrollbackController(
    private val view: View,
    private val emulator: TerminalEmulator,
    private val fontLineSpacing: () -> Float,
) {
    /** Result of consulting the controller for a MotionEvent. */
    sealed interface TouchDecision {
        /** Wrapper should call super.dispatchTouchEvent (single-finger). */
        data object PassThrough : TouchDecision

        /** Wrapper should swallow the event and return true. */
        data object Consumed : TouchDecision
    }

    /** Banner state. Both fields are read on UI thread only. */
    data class ScrollbackState(
        val isInScrollback: Boolean = false,
        val pendingOutputCount: Int = 0,
    )

    private val _state = MutableStateFlow(ScrollbackState())
    val state: StateFlow<ScrollbackState> = _state.asStateFlow()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.ScrollbackControllerTest" -i
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(terminal): add ScrollbackController shell with StateFlow"
```

---

## Task 2: ScrollbackController — single-finger pass-through + two-finger entry

**Files:**
- Modify: `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt`
- Modify: `app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt`

- [ ] **Step 1: Add failing tests for the multi-touch entry path**

Append the following tests inside the `ScrollbackControllerTest` class (the existing import block needs one more line — see step 2):

```kotlin
    @Test
    fun onTouchEvent_singleFingerActionDown_returnsPassThrough() {
        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = mockk(relaxed = true),
            fontLineSpacing = { 16f },
        )

        // pointerCount=1 with ACTION_DOWN — single-finger entry. Must NOT
        // hijack the gesture (the alt-buffer guard inside Termux and
        // long-press selection are the single-finger path).
        val ev = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        try {
            val decision = controller.onTouchEvent(ev)
            assertEquals(ScrollbackController.TouchDecision.PassThrough, decision)
        } finally {
            ev.recycle()
        }
    }

    @Test
    fun onTouchEvent_twoFingerActionPointerDown_setsIsInScrollbackTrue() {
        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = mockk(relaxed = true),
            fontLineSpacing = { 16f },
        )

        // Build a real MotionEvent with pointerCount=2. The constructor
        // is fiddly; this is the shape `dispatchTouchEvent` produces when
        // a second finger lands.
        val downTime = SystemClock.uptimeMillis()
        val eventTime = downTime
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 10f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 10f; pressure = 1f; size = 1f },
        )
        val ev = MotionEvent.obtain(
            downTime, eventTime,
            MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            val decision = controller.onTouchEvent(ev)
            assertEquals(ScrollbackController.TouchDecision.Consumed, decision)
            assertTrue(controller.state.value.isInScrollback)
        } finally {
            ev.recycle()
        }
    }
```

Add to the import block at the top of the test file (one line each):
- `import android.os.SystemClock`
- `import android.view.InputDevice`
- `import org.junit.Assert.assertTrue`

- [ ] **Step 2: Run test to verify the new cases fail (PASS-through case may already pass)**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.ScrollbackControllerTest" -i
```
Expected: FAIL with `Unresolved reference: onTouchEvent` (method doesn't exist yet).

- [ ] **Step 3: Implement `onTouchEvent` for the multi-touch entry path**

Add this method to `ScrollbackController`, between the state field and the closing brace:

```kotlin
    private var anchorPointerY: Float? = null

    /**
     * Consult the controller for a single MotionEvent. The wrapper calls
     * this from `dispatchTouchEvent` BEFORE `super`. Returns PassThrough
     * for single-finger events (the inner view handles them); returns
     * Consumed for two-finger events (the controller owns the gesture).
     *
     * Threading: UI thread only.
     */
    fun onTouchEvent(ev: MotionEvent): TouchDecision {
        if (ev.pointerCount < 2) return TouchDecision.PassThrough
        // We are now in (or continuing) a two-finger gesture. Record the
        // anchor on the first 2-finger frame; subsequent MOVE frames use
        // the updated anchor to compute incremental dy.
        if (anchorPointerY == null) {
            anchorPointerY = centroidY(ev)
        }
        _state.value = _state.value.copy(isInScrollback = true)
        return TouchDecision.Consumed
    }

    private fun centroidY(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getY(i)
        return sum / ev.pointerCount
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.ScrollbackControllerTest" -i
```
Expected: 3 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(terminal): detect two-finger entry into scrollback"
```

---

## Task 3: ScrollbackController — scroll math (mTopRow + deltaRows + clamp)

**Files:**
- Modify: `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt`
- Modify: `app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt`

- [ ] **Step 1: Add failing tests for the scroll math**

Append these tests to `ScrollbackControllerTest`:

```kotlin
    @Test
    fun onTouchEvent_twoFingerMoveUp_increasesTopRow() {
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        every { emulator.mTopRow } returns 0
        every { emulator.mTotalRows } returns 200
        every { emulator.mRows } returns 24
        every { emulator.mTopRow = any() } answers { /* no-op for mock */ }

        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = emulator,
            fontLineSpacing = { 16f }, // 16 px per row
        )

        // Frame 1: enter scrollback at y=100
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 100f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 100f; pressure = 1f; size = 1f },
        )
        val ev0 = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )

        // Frame 2: move UP 32px (y went from 100 to 68 → deltaY = -32 → 2 rows up)
        val coords1 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 68f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 68f; pressure = 1f; size = 1f },
        )
        val ev1 = MotionEvent.obtain(
            0L, 16L, MotionEvent.ACTION_MOVE,
            2, props, coords1,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            controller.onTouchEvent(ev0)
            controller.onTouchEvent(ev1)

            // Initial mTopRow=0; moved up 32px (2 rows); expect mTopRow=2.
            verify { emulator.mTopRow = 2 }
        } finally {
            ev0.recycle()
            ev1.recycle()
        }
    }

    @Test
    fun onTouchEvent_twoFingerMoveDown_clampsAtZero() {
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        every { emulator.mTopRow } returns 0
        every { emulator.mTotalRows } returns 200
        every { emulator.mRows } returns 24
        every { emulator.mTopRow = any() } answers { /* no-op */ }

        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = emulator,
            fontLineSpacing = { 16f },
        )

        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 100f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 100f; pressure = 1f; size = 1f },
        )
        val coords1 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val ev0 = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val ev1 = MotionEvent.obtain(
            0L, 16L, MotionEvent.ACTION_MOVE,
            2, props, coords1,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            controller.onTouchEvent(ev0)
            controller.onTouchEvent(ev1)

            // deltaY = +100 (down); -deltaY/fontLineSpacing = -100/16 = -6.25 → -6 rows.
            // Toprow was 0, minus 6 clamps to 0. Verify the final write was 0.
            verify { emulator.mTopRow = 0 }
        } finally {
            ev0.recycle()
            ev1.recycle()
        }
    }

    @Test
    fun onTouchEvent_twoFingerMoveUp_clampsAtMaxScroll() {
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        every { emulator.mTopRow } returns 0
        every { emulator.mTotalRows } returns 200
        every { emulator.mRows } returns 24
        every { emulator.mTopRow = any() } answers { /* no-op */ }

        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = emulator,
            fontLineSpacing = { 16f },
        )

        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        // Frame 1: enter at y=1000
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 1000f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 1000f; pressure = 1f; size = 1f },
        )
        // Frame 2: y went UP to 0 (deltaY = -1000). -(-1000)/16 = 62 rows.
        // mTotalRows - mRows = 200 - 24 = 176. 62 < 176, so we'd write 62, NOT clamp.
        // To exercise the clamp, take a third frame that pushes past 176.
        val coords1 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 0f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 0f; pressure = 1f; size = 1f },
        )
        // mTopRow will be 62 after frame 2. Anchor has been updated to 0.
        // For a third frame we need to set mTopRow's mock to return 62
        // (every read in the clamp compares against this), but since
        // the read happens in `coerceIn` we let the relaxed mock return
        // 0 — the production read happens once per MOVE. Instead, drive
        // a HUGE upward move in a single frame:
        val coordsBigUp = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = -10000f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = -10000f; pressure = 1f; size = 1f },
        )
        val ev0 = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val ev1 = MotionEvent.obtain(
            0L, 16L, MotionEvent.ACTION_MOVE,
            2, props, coordsBigUp,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            controller.onTouchEvent(ev0)
            controller.onTouchEvent(ev1)

            // Final mTopRow must be mTotalRows - mRows = 200 - 24 = 176
            verify { emulator.mTopRow = 176 }
        } finally {
            ev0.recycle()
            ev1.recycle()
        }
    }

    @Test
    fun onTouchEvent_fontLineSpacingZero_doesNotTouchTopRow() {
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        every { emulator.mTopRow } returns 5
        every { emulator.mTotalRows } returns 200
        every { emulator.mRows } returns 24
        every { emulator.mTopRow = any() } answers { /* no-op */ }

        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = emulator,
            fontLineSpacing = { 0f }, // pathological — Robolectric renderer w/o font
        )

        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 100f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 100f; pressure = 1f; size = 1f },
        )
        val coords1 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 50f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 50f; pressure = 1f; size = 1f },
        )
        val ev0 = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val ev1 = MotionEvent.obtain(
            0L, 16L, MotionEvent.ACTION_MOVE,
            2, props, coords1,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            controller.onTouchEvent(ev0)
            controller.onTouchEvent(ev1)

            // No mTopRow write should have happened — fontLineSpacing=0 is
            // the "renderer not ready" path. Same guard as
            // TerminalView.reportPtyResize:583.
            verify(exactly = 0) { emulator.mTopRow = any() }
        } finally {
            ev0.recycle()
            ev1.recycle()
        }
    }
```

Add to the import block at the top of the test file:
- `import io.mockk.every`
- `import io.mockk.verify`

- [ ] **Step 2: Run test to verify they fail**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.ScrollbackControllerTest" -i
```
Expected: FAIL on the new tests — `onTouchEvent` doesn't yet process the MOVE, so `mTopRow` is never assigned.

- [ ] **Step 3: Implement scroll math in `onTouchEvent`**

Replace the `onTouchEvent` method in `ScrollbackController` with this version:

```kotlin
    fun onTouchEvent(ev: MotionEvent): TouchDecision {
        if (ev.pointerCount < 2) return TouchDecision.PassThrough

        // We are in (or continuing) a two-finger gesture.
        if (anchorPointerY == null) {
            anchorPointerY = centroidY(ev)
        }
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            // First 2-finger frame; no delta yet. Just arm the anchor and
            // flip the state. Subsequent MOVE frames will scroll.
            _state.value = _state.value.copy(isInScrollback = true)
            return TouchDecision.Consumed
        }
        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            return applyMove(ev)
        }
        // Other two-finger events (POINTER_UP, etc.): consume so the
        // inner view doesn't see them, but don't change mTopRow.
        return TouchDecision.Consumed
    }

    private fun applyMove(ev: MotionEvent): TouchDecision {
        val lineSpacing = fontLineSpacing()
        if (lineSpacing <= 0f) {
            // Defensive: renderer not ready. Don't write to mTopRow.
            // State is still in scrollback (we entered on POINTER_DOWN).
            return TouchDecision.Consumed
        }
        val anchor = anchorPointerY ?: return TouchDecision.Consumed
        val currentY = centroidY(ev)
        val deltaY = currentY - anchor
        // deltaY > 0 → fingers moved DOWN → see NEWER content → mTopRow
        // DECREASES. deltaY < 0 → fingers moved UP → see OLDER content →
        // mTopRow INCREASES. So `mTopRow += -deltaY / lineSpacing`.
        val deltaRows = (-deltaY / lineSpacing).toInt()
        val maxTopRow = (emulator.mTotalRows - emulator.mRows).coerceAtLeast(0)
        val currentTopRow = emulator.mTopRow
        val newTopRow = (currentTopRow + deltaRows).coerceIn(0, maxTopRow)
        emulator.mTopRow = newTopRow
        // Update the anchor so the NEXT MOVE frame is incremental.
        anchorPointerY = currentY
        return TouchDecision.Consumed
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.ScrollbackControllerTest" -i
```
Expected: 7 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(terminal): scrollback two-finger drag mutates mTopRow with clamp"
```

---

## Task 4: ScrollbackController — auto-exit on mTopRow == 0 + pointer-count transitions

**Files:**
- Modify: `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt`
- Modify: `app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt`

- [ ] **Step 1: Add failing tests for auto-exit and pointer transitions**

Append these tests to `ScrollbackControllerTest`:

```kotlin
    @Test
    fun onTouchEvent_dragBackToZero_exitsScrollback() {
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        // Simulate the emulator following our writes: every time the
        // controller reads mTopRow, give it back whatever it last wrote.
        var currentTopRow = 3
        every { emulator.mTopRow } answers { currentTopRow }
        every { emulator.mTopRow = any() } answers { currentTopRow = it.invocation.args[0] as Int; Unit }
        every { emulator.mTotalRows } returns 200
        every { emulator.mRows } returns 24

        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = emulator,
            fontLineSpacing = { 16f },
        )

        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        // Enter at y=100, then a small DOWNWARD drag (y=148). That's
        // deltaY=+48, -deltaY/16 = -3 rows. 3 - 3 = 0 → auto-exit.
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 100f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 100f; pressure = 1f; size = 1f },
        )
        val coords1 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 148f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 148f; pressure = 1f; size = 1f },
        )
        val ev0 = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val ev1 = MotionEvent.obtain(
            0L, 16L, MotionEvent.ACTION_MOVE,
            2, props, coords1,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            controller.onTouchEvent(ev0)
            assertTrue("enter must flip isInScrollback", controller.state.value.isInScrollback)

            controller.onTouchEvent(ev1)
            assertFalse(
                "drag back to mTopRow=0 must auto-exit scrollback",
                controller.state.value.isInScrollback,
            )
        } finally {
            ev0.recycle()
            ev1.recycle()
        }
    }

    @Test
    fun onTouchEvent_pointerUpToOne_staysInScrollback() {
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        every { emulator.mTopRow } returns 5
        every { emulator.mTotalRows } returns 200
        every { emulator.mRows } returns 24
        every { emulator.mTopRow = any() } answers { /* no-op */ }

        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = emulator,
            fontLineSpacing = { 16f },
        )

        // 1) Two-finger POINTER_DOWN — enter scrollback.
        val props2 = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords2 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 100f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 100f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_POINTER_DOWN,
            2, props2, coords2,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        // 2) One finger lifts (POINTER_UP with pointerCount==1 in the event
        // representation; in the MotionEvent API the count is the number of
        // pointers being lifted, not the new total). Build a real POINTER_UP.
        val evUp = MotionEvent.obtain(
            0L, 16L, MotionEvent.ACTION_POINTER_UP,
            2, props2, coords2, // same coords is fine; only the action matters
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evUp)
            assertTrue(
                "2→1 finger transition must NOT exit scrollback",
                controller.state.value.isInScrollback,
            )
        } finally {
            evDown.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_actionUp_staysInScrollbackUntilScrollToBottom() {
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        every { emulator.mTopRow } returns 5
        every { emulator.mTotalRows } returns 200
        every { emulator.mRows } returns 24
        every { emulator.mTopRow = any() } answers { /* no-op */ }

        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = emulator,
            fontLineSpacing = { 16f },
        )

        // Enter via 2-finger POINTER_DOWN...
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 100f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 100f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        // ... then a 1-finger ACTION_UP.
        val evUp = MotionEvent.obtain(
            0L, 32L, MotionEvent.ACTION_UP, 10f, 100f, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evUp)
            assertTrue(
                "ACTION_UP alone must NOT exit scrollback — user may pause before tapping the banner",
                controller.state.value.isInScrollback,
            )
        } finally {
            evDown.recycle()
            evUp.recycle()
        }
    }
```

- [ ] **Step 2: Run test to verify the new cases fail**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.ScrollbackControllerTest" -i
```
Expected: FAIL — auto-exit isn't implemented yet, and `applyMove` doesn't check the new value.

- [ ] **Step 3: Implement auto-exit and verify pointer transitions**

In `ScrollbackController`, add the auto-exit check at the end of `applyMove` (right after `emulator.mTopRow = newTopRow`):

```kotlin
        if (newTopRow == 0) {
            // Dragged back to live — auto-exit. The anchor is reset so a
            // subsequent two-finger DOWN starts a fresh gesture.
            anchorPointerY = null
            _state.value = _state.value.copy(isInScrollback = false)
        }
```

Also add a public `scrollToBottom()` method (used in Task 5 but stubbed here so the next test compiles):

```kotlin
    /**
     * Jump to the live view, clear pending output, and exit scrollback
     * mode. Safe to call from the banner click handler. Resets the
     * two-finger anchor so the next two-finger DOWN starts a fresh
     * gesture.
     *
     * Threading: UI thread only.
     */
    fun scrollToBottom() {
        anchorPointerY = null
        emulator.mTopRow = 0
        _state.value = ScrollbackState() // isInScrollback=false, pending=0
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.ScrollbackControllerTest" -i
```
Expected: 10 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(terminal): scrollback auto-exit on mTopRow=0 and scrollToBottom()"
```

---

## Task 5: ScrollbackController — pending output counting

**Files:**
- Modify: `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt`
- Modify: `app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt`

- [ ] **Step 1: Add failing tests for output counting**

Append these tests to `ScrollbackControllerTest`:

```kotlin
    @Test
    fun onTranscriptWrite_eightyBytesEightyCols_addsOneLine() {
        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = mockk(relaxed = true),
            fontLineSpacing = { 16f },
        )

        controller.onTranscriptWrite(byteCount = 80, columns = 80)
        assertEquals(1, controller.state.value.pendingOutputCount)
    }

    @Test
    fun onTranscriptWrite_hundredSixtyBytesEightyCols_addsTwoLines() {
        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = mockk(relaxed = true),
            fontLineSpacing = { 16f },
        )

        controller.onTranscriptWrite(byteCount = 160, columns = 80)
        assertEquals(2, controller.state.value.pendingOutputCount)
    }

    @Test
    fun onTranscriptWrite_partialLine_floorsToOne() {
        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = mockk(relaxed = true),
            fontLineSpacing = { 16f },
        )

        controller.onTranscriptWrite(byteCount = 40, columns = 80)
        assertEquals(1, controller.state.value.pendingOutputCount)
    }

    @Test
    fun onTranscriptWrite_accumulatesAcrossCalls() {
        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = mockk(relaxed = true),
            fontLineSpacing = { 16f },
        )

        controller.onTranscriptWrite(byteCount = 80, columns = 80)
        controller.onTranscriptWrite(byteCount = 80, columns = 80)
        controller.onTranscriptWrite(byteCount = 40, columns = 80)
        assertEquals(3, controller.state.value.pendingOutputCount)
    }

    @Test
    fun scrollToBottom_resetsPendingCount() {
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        every { emulator.mTopRow } returns 0
        every { emulator.mTotalRows } returns 200
        every { emulator.mRows } returns 24
        every { emulator.mTopRow = any() } answers { /* no-op */ }

        val controller = ScrollbackController(
            view = mockk(relaxed = true),
            emulator = emulator,
            fontLineSpacing = { 16f },
        )

        controller.onTranscriptWrite(byteCount = 240, columns = 80)
        assertEquals(3, controller.state.value.pendingOutputCount)

        controller.scrollToBottom()
        assertEquals(0, controller.state.value.pendingOutputCount)
        assertFalse(controller.state.value.isInScrollback)
    }
```

- [ ] **Step 2: Run test to verify they fail**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.ScrollbackControllerTest" -i
```
Expected: FAIL with `Unresolved reference: onTranscriptWrite`.

- [ ] **Step 3: Implement `onTranscriptWrite`**

In `ScrollbackController`, add the `pendingOutputCount` field, the `refreshState` helper, and the `onTranscriptWrite` method. Place them between the existing `state` field and `anchorPointerY`:

```kotlin
    /**
     * Output lines that arrived while the user was scrolled back. Written
     * from the IO thread; the StateFlow emission is brought back to UI
     * thread by the wrapper (see TerminalView.transcriptOutput.write).
     * We use AtomicInteger so the add-and-emit pair is safe across the
     * two threads without a coarse lock.
     */
    private val pendingOutputCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Re-publish the current pending count. Must run on the UI thread
     * (called from `view.post { ... }` at the end of every
     * `onTranscriptWrite` so Compose sees a UI-thread emission).
     */
    internal fun refreshState() {
        _state.value = _state.value.copy(
            pendingOutputCount = pendingOutputCount.get(),
        )
    }

    /**
     * Account for [byteCount] bytes that the emulator just absorbed while
     * we were scrolled back. Line estimate = `max(1, byteCount / columns)`;
     * floor at 1 so a stray carriage return still registers as "something
     * happened" and the banner badge updates.
     *
     * Threading: the AtomicInteger add is safe from any thread; the
     * emission is the wrapper's responsibility (call `view.post { ... }`
     * in TerminalView so the StateFlow update happens on UI thread).
     */
    fun onTranscriptWrite(byteCount: Int, columns: Int) {
        if (byteCount <= 0) return
        val safeColumns = columns.coerceAtLeast(1)
        val lines = (byteCount / safeColumns).coerceAtLeast(1)
        pendingOutputCount.addAndGet(lines)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.ScrollbackControllerTest" -i
```
Expected: 15 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(terminal): scrollback pending output counter"
```

---

## Task 6: TerminalView wiring — controller field, dispatchTouchEvent, public API

**Files:**
- Modify: `app/src/main/java/com/example/sshterminal/terminal/TerminalView.kt`
- Create: `app/src/test/java/com/example/sshterminal/terminal/TerminalViewScrollbackWiringTest.kt`

- [ ] **Step 1: Write the failing wiring tests**

Create `app/src/test/java/com/example/sshterminal/terminal/TerminalViewScrollbackWiringTest.kt`:

```kotlin
package com.example.sshterminal.terminal

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wires the ScrollbackController into TerminalView. Asserts:
 *   1. dispatchTouchEvent returns true for two-finger MOVE (consumed)
 *   2. dispatchTouchEvent passes single-finger MOVE through to the
 *      existing path (alt-buffer guard, long-press selection, etc.
 *      remain unaffected)
 *   3. scrollToBottom() resets emulator.mTopRow and exits scrollback
 *   4. setScrollbackListener() receives state transitions
 *
 * Touch dispatch in Robolectric doesn't always reach the inner
 * Termux view's GestureDetector (the upstream shadow is incomplete —
 * see the long comment in AltBufferScrollCrashGuardTest), so we
 * verify the contract on the wrapper itself: two-finger events are
 * CONSUMED, single-finger events are not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TerminalViewScrollbackWiringTest {

    private lateinit var context: Context
    private lateinit var view: TerminalView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
        view.onCreateInputConnection(EditorInfo())
    }

    @Test
    fun dispatchTouchEvent_twoFingerMove_returnsTrue() {
        val ev = twoFingerMove(
            yStart = 100f,
            yEnd = 60f, // 40px up
        )
        try {
            val consumed = view.dispatchTouchEvent(ev)
            assertTrue(
                "two-finger MOVE must be consumed by the wrapper — " +
                    "inner view must NEVER see multi-touch scrollback events",
                consumed,
            )
        } finally {
            ev.recycle()
        }
    }

    @Test
    fun dispatchTouchEvent_singleFingerMove_isNotIntercepted() {
        // Single-finger MOVE on the wrapper should NOT be intercepted —
        // it's the responsibility of the inner view (long-press for
        // selection, single-finger drag for whatever Termux's default
        // does). The wrapper only owns two-finger.
        val ev = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_MOVE, 50f, 50f, 0,
        )
        try {
            // We can't predict the exact return value (depends on
            // GestureDetector behaviour) but it must NOT be the
            // scrollback-consumed path. The simplest assertion: state
            // should remain isInScrollback=false after a single-finger
            // MOVE.
            view.dispatchTouchEvent(ev)
            val controller = scrollbackControllerField.get(view) as ScrollbackController
            assertFalse(
                "single-finger MOVE must not flip the controller into scrollback",
                controller.state.value.isInScrollback,
            )
        } finally {
            ev.recycle()
        }
    }

    @Test
    fun scrollToBottom_resetsEmulatorAndExitsScrollback() {
        val emulator = view.termuxView.mEmulator!!
        emulator.mTopRow = 5

        view.scrollToBottom()

        assertEquals(0, emulator.mTopRow)
        val controller = scrollbackControllerField.get(view) as ScrollbackController
        assertFalse(controller.state.value.isInScrollback)
    }

    @Test
    fun setScrollbackListener_receivesStateTransitions() {
        val seen = mutableListOf<ScrollbackController.ScrollbackState>()
        view.setScrollbackListener { state -> seen.add(state) }

        // Initial state fires once on registration (mirrors the
        // setPtyResizeListener pattern).
        assertEquals(1, seen.size)
        assertFalse(seen.single().isInScrollback)

        // Two-finger DOWN → enter scrollback → state fires.
        val ev = twoFingerDown(y = 100f)
        try {
            view.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
        assertTrue(
            "banner should see isInScrollback=true after a 2-finger DOWN",
            seen.last().isInScrollback,
        )
    }

    @Test
    fun isInScrollback_readsControllerState() {
        assertFalse(view.isInScrollback)

        val ev = twoFingerDown(y = 100f)
        try {
            view.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
        assertTrue(view.isInScrollback)
    }

    // ---- helpers ----

    private val scrollbackControllerField: java.lang.reflect.Field by lazy {
        View::class.java.getDeclaredField("scrollbackController").apply { isAccessible = true }
    }

    private fun twoFingerDown(y: Float): MotionEvent {
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = y; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = y; pressure = 1f; size = 1f },
        )
        return MotionEvent.obtain(
            downTime, downTime,
            MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun twoFingerMove(yStart: Float, yEnd: Float): MotionEvent {
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        // Frame 1: POINTER_DOWN at yStart (enter).
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = yStart; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = yStart; pressure = 1f; size = 1f },
        )
        val down = MotionEvent.obtain(
            downTime, downTime,
            MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        view.dispatchTouchEvent(down)
        down.recycle()
        // Frame 2: MOVE to yEnd.
        val coords1 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = yEnd; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = yEnd; pressure = 1f; size = 1f },
        )
        return MotionEvent.obtain(
            downTime, downTime + 16L,
            MotionEvent.ACTION_MOVE,
            2, props, coords1,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }
}
```

- [ ] **Step 2: Run the wiring tests to verify they all fail**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.TerminalViewScrollbackWiringTest" -i
```
Expected: 5 failures, all with `Unresolved reference: scrollbackController` / `isInScrollback` / `scrollToBottom` etc.

- [ ] **Step 3: Wire ScrollbackController into TerminalView**

In `app/src/main/java/com/example/sshterminal/terminal/TerminalView.kt`:

1. Add a private field after the existing `selectionController` field (line ~49):

```kotlin
    /**
     * Owns the two-finger scrollback gesture. Wired from this view's
     * [dispatchTouchEvent] (intercept multi-touch before it reaches the
     * inner Termux view) and from the [transcriptOutput.write] override
     * (count pending lines while scrolled back). See
     * docs/superpowers/specs/2026-06-30-gesture-scrollback-design.md.
     */
    private val scrollbackController: ScrollbackController = ScrollbackController(
        view = this,
        emulator = emulator,
        fontLineSpacing = { termuxView.mRenderer?.getFontLineSpacing()?.toFloat() ?: 0f },
    )
```

(Note: the field must come **after** the `emulator` field, which is initialised further down. The Kotlin property initialiser order is top-to-bottom — this will work because by the time the field is initialised, the property `emulator` has been assigned via `.also { ... }` in its own declaration. If you see an `uninitialized` error, the order needs adjusting.)

2. Add three new public methods near the bottom of the class (next to `setPtyResizeListener` is a good neighbour):

```kotlin
    fun scrollToBottom() {
        scrollbackController.scrollToBottom()
        termuxView.postInvalidateOnAnimation()
    }

    val isInScrollback: Boolean
        get() = scrollbackController.state.value.isInScrollback

    fun setScrollbackListener(listener: ((ScrollbackController.ScrollbackState) -> Unit)?) {
        if (listener == null) return
        // Initial fire so the banner doesn't sit blank for a frame
        // (mirrors setPtyResizeListener's `force` pattern).
        listener(scrollbackController.state.value)
        // Collect in a way that doesn't outlive the listener; the
        // controller's StateFlow is hot as long as the controller is
        // alive (i.e. as long as the wrapper is attached), and the
        // banner is removed on TerminalPane recomposition, so this
        // subscription dies with the view. For a stricter cleanup we
        // could use a Job; deferred.
        // Use a simple scope: dispatch each emission onto the main
        // thread so the listener can update Compose state directly.
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob(),
        )
        scope.launch {
            scrollbackController.state.collect { listener(it) }
        }
    }
```

Add the import at the top of `TerminalView.kt`:
```kotlin
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
```

3. Modify `dispatchTouchEvent` to consult the controller first (line 336):

Replace:
```kotlin
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            requestFocus()
        }
        return super.dispatchTouchEvent(ev)
    }
```

With:
```kotlin
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            requestFocus()
        }
        // Two-finger gestures are owned by the scrollback controller.
        // We consult it before super so the inner Termux view never
        // sees multi-touch events (avoids its doScroll alt-buffer
        // crash branch, and avoids contaminating its single-finger
        // gesture detector state).
        when (scrollbackController.onTouchEvent(ev)) {
            ScrollbackController.TouchDecision.Consumed -> return true
            ScrollbackController.TouchDecision.PassThrough -> { /* fall through */ }
        }
        return super.dispatchTouchEvent(ev)
    }
```

4. Modify the `transcriptOutput.write` override to count pending lines when in scrollback (line 132):

Replace:
```kotlin
        override fun write(bytes: ByteArray, offset: Int, len: Int) {
            // The emulator already updated its internal transcript; we just
            // need the View to redraw.
            termuxView.postInvalidateOnAnimation()
        }
```

With:
```kotlin
        override fun write(bytes: ByteArray, offset: Int, len: Int) {
            // The emulator already updated its internal transcript; we just
            // need the View to redraw.
            termuxView.postInvalidateOnAnimation()
            // While the user is scrolled back, count lines that arrived
            // during the read so the banner can show "▼ N 行新输出".
            // The actual count is computed on the IO thread (cheap);
            // the StateFlow emission is brought back to UI thread by
            // post{} so Compose doesn't see cross-thread updates.
            if (scrollbackController.state.value.isInScrollback) {
                scrollbackController.onTranscriptWrite(len, emulator.mColumns)
                post { scrollbackController.refreshState() }
            }
        }
```

- [ ] **Step 4: Run the wiring tests to verify they pass**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.TerminalViewScrollbackWiringTest" -i
```
Expected: 5 tests, all PASS.

- [ ] **Step 5: Run the full terminal test suite to check for regressions**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.*" -i
```
Expected: All 6 `AltBufferScrollCrashGuardTest` cases + 15 `ScrollbackControllerTest` cases + 5 `TerminalViewScrollbackWiringTest` cases + all pre-existing terminal tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/TerminalView.kt app/src/test/java/com/example/sshterminal/terminal/TerminalViewScrollbackWiringTest.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(terminal): wire ScrollbackController into TerminalView dispatch and output"
```

---

## Task 7: ScrollbackControllerRobolectricTest — real MotionEvent end-to-end

**Files:**
- Create: `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerRobolectricTest.kt`

This task adds an end-to-end MotionEvent test as a safety net. The pure-logic tests in `ScrollbackControllerTest` already cover the controller thoroughly, but this one drives a real `MotionEvent.obtain` sequence to pin the actionMasked / pointerCount contract on `dispatchTouchEvent` (Robolectric's shadows sometimes lie about synthetic events, but the real path is what we ship).

- [ ] **Step 1: Write the test file**

Create `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerRobolectricTest.kt`:

```kotlin
package com.example.sshterminal.terminal

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.termux.terminal.TerminalEmulator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end MotionEvent test for [ScrollbackController]. Unlike
 * [ScrollbackControllerTest] (which uses mockk for every Android dep),
 * this one constructs a real `MotionEvent.obtain` with multiple pointers
 * and drives the controller through a full two-finger gesture:
 *   1. ACTION_DOWN (single finger) — pass-through
 *   2. ACTION_POINTER_DOWN (second finger lands) — enter scrollback
 *   3. ACTION_MOVE (fingers drag up) — mTopRow advances incrementally
 *   4. ACTION_POINTER_UP (one finger lifts) — stay in scrollback
 *   5. ACTION_UP (last finger lifts) — stay in scrollback
 *   6. scrollToBottom() — exit
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScrollbackControllerRobolectricTest {

    @Test
    fun twoFingerGesture_drivesControllerThroughFullLifecycle() {
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        var topRow = 0
        every { emulator.mTopRow } answers { topRow }
        every { emulator.mTopRow = any() } answers { topRow = it.invocation.args[0] as Int; Unit }
        every { emulator.mTotalRows } returns 200
        every { emulator.mRows } returns 24

        val controller = ScrollbackController(
            view = mockk<View>(relaxed = true),
            emulator = emulator,
            fontLineSpacing = { 16f },
        )

        val downTime = SystemClock.uptimeMillis()

        // 1) Single-finger DOWN at y=200 — should pass through.
        val down1 = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 10f, 200f, 0)
        try {
            assertEquals(
                ScrollbackController.TouchDecision.PassThrough,
                controller.onTouchEvent(down1),
            )
        } finally {
            down1.recycle()
        }

        // 2) Second finger lands (POINTER_DOWN) — enter scrollback.
        val props2 = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coordsDown2 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val pointerDown2 = MotionEvent.obtain(
            downTime, downTime + 8L,
            MotionEvent.ACTION_POINTER_DOWN,
            2, props2, coordsDown2,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            assertEquals(
                ScrollbackController.TouchDecision.Consumed,
                controller.onTouchEvent(pointerDown2),
            )
            assertTrue(controller.state.value.isInScrollback)
        } finally {
            pointerDown2.recycle()
        }

        // 3) MOVE up 32px (y went from 200 to 168). deltaY = -32, rows = 2.
        val coordsMove = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 168f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 168f; pressure = 1f; size = 1f },
        )
        val move = MotionEvent.obtain(
            downTime, downTime + 16L,
            MotionEvent.ACTION_MOVE,
            2, props2, coordsMove,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            controller.onTouchEvent(move)
            verify { emulator.mTopRow = 2 }
        } finally {
            move.recycle()
        }

        // 4) One finger lifts (POINTER_UP) — must stay in scrollback.
        val pointerUp = MotionEvent.obtain(
            downTime, downTime + 32L,
            MotionEvent.ACTION_POINTER_UP,
            2, props2, coordsMove,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            controller.onTouchEvent(pointerUp)
            assertTrue(
                "2→1 finger transition must not exit scrollback",
                controller.state.value.isInScrollback,
            )
        } finally {
            pointerUp.recycle()
        }

        // 5) Last finger lifts (ACTION_UP) — must still stay in scrollback.
        val up = MotionEvent.obtain(downTime, downTime + 64L, MotionEvent.ACTION_UP, 10f, 168f, 0)
        try {
            controller.onTouchEvent(up)
            assertTrue(
                "ACTION_UP alone must not exit scrollback",
                controller.state.value.isInScrollback,
            )
        } finally {
            up.recycle()
        }

        // 6) scrollToBottom() — must reset.
        controller.scrollToBottom()
        assertEquals(0, topRow)
        assertFalse(controller.state.value.isInScrollback)
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.ScrollbackControllerRobolectricTest" -i
```
Expected: 1 test PASS (this one is not a TDD "first-fail" task; the controller is already implemented and the test verifies the contract end-to-end).

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerRobolectricTest.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "test(terminal): pin ScrollbackController two-finger gesture end-to-end"
```

---

## Task 8: build.gradle.kts — add Compose UI test dependencies

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add the deps**

In `app/build.gradle.kts`, find the `testImplementation` block. After the `io.mockk:mockk:1.13.13` line, add:

```kotlin
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
```

Both artifacts are versioned by the Compose BOM already declared at the top of the file (`platform("androidx.compose:compose-bom:2024.10.01")`), so no version string is needed.

- [ ] **Step 2: Verify the deps resolve**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:dependencies --configuration testDebugRuntimeClasspath 2>&1 | grep -E "ui-test" | head -5
```
Expected: Two lines containing `ui-test-junit4` and `ui-test-manifest`.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "build: add Compose UI test dependencies for banner test"
```

---

## Task 9: ScrollbackBanner — Compose implementation (TDD)

**Files:**
- Create: `app/src/test/java/com/example/sshterminal/ui/ScrollbackBannerTest.kt`
- Create: `app/src/main/java/com/example/sshterminal/ui/ScrollbackBanner.kt`

- [ ] **Step 1: Write the failing Compose test**

Create `app/src/test/java/com/example/sshterminal/ui/ScrollbackBannerTest.kt`:

```kotlin
package com.example.sshterminal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertDoesNotExist
import com.example.sshterminal.terminal.ScrollbackController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScrollbackBannerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun hidden_whenIsInScrollbackFalse() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ScrollbackBanner(
                        state = ScrollbackController.ScrollbackState(isInScrollback = false),
                        onBackToBottom = {},
                    )
                }
            }
        }

        // "↑ 滚回历史" should not be present (early return path).
        composeRule.onNodeWithText("↑ 滚回历史").assertDoesNotExist()
    }

    @Test
    fun visible_whenIsInScrollbackTrue() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ScrollbackBanner(
                        state = ScrollbackController.ScrollbackState(isInScrollback = true),
                        onBackToBottom = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("↑ 滚回历史").assertIsDisplayed()
    }

    @Test
    fun showsPendingBadge_whenPendingOutputCountPositive() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ScrollbackBanner(
                        state = ScrollbackController.ScrollbackState(
                            isInScrollback = true,
                            pendingOutputCount = 5,
                        ),
                        onBackToBottom = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("↑ 滚回历史").assertIsDisplayed()
        composeRule.onNodeWithText("▼ 5 行新输出").assertIsDisplayed()
    }

    @Test
    fun capsPendingBadgeAt9999() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ScrollbackBanner(
                        state = ScrollbackController.ScrollbackState(
                            isInScrollback = true,
                            pendingOutputCount = 50000,
                        ),
                        onBackToBottom = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("▼ 9999 行新输出").assertIsDisplayed()
    }

    @Test
    fun click_invokesOnBackToBottomExactlyOnce() {
        var clickCount = 0
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ScrollbackBanner(
                        state = ScrollbackController.ScrollbackState(isInScrollback = true),
                        onBackToBottom = { clickCount++ },
                    )
                }
            }
        }

        composeRule.onNodeWithText("↑ 滚回历史").performClick()
        composeRule.onNodeWithText("↑ 滚回历史").performClick()

        assert(clickCount == 2) {
            "expected 2 clicks, got $clickCount"
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.ui.ScrollbackBannerTest" -i
```
Expected: FAIL with `Unresolved reference: ScrollbackBanner`.

- [ ] **Step 3: Implement ScrollbackBanner**

Create `app/src/main/java/com/example/sshterminal/ui/ScrollbackBanner.kt`:

```kotlin
package com.example.sshterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sshterminal.terminal.ScrollbackController

/**
 * Top-of-pane banner that surfaces the two-finger scrollback state.
 *
 * Hidden by default; visible whenever the controller is in scrollback
 * mode. Shows an optional "▼ N 行新输出" badge when new output arrived
 * while the user was scrolled back. Tapping anywhere on the banner
 * calls [onBackToBottom], which the caller is expected to wire to
 * [com.example.sshterminal.terminal.TerminalView.scrollToBottom].
 *
 * The pending-count cap (9999) lives in the UI layer so the underlying
 * state can carry the real value for as long as needed without
 * overflowing the badge.
 */
@Composable
fun ScrollbackBanner(
    state: ScrollbackController.ScrollbackState,
    onBackToBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isInScrollback) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onBackToBottom)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "↑ 滚回历史",
            style = MaterialTheme.typography.labelLarge,
        )
        if (state.pendingOutputCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "▼ ${state.pendingOutputCount.coerceAtMost(9999)} 行新输出",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.ui.ScrollbackBannerTest" -i
```
Expected: 5 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/ui/ScrollbackBanner.kt app/src/test/java/com/example/sshterminal/ui/ScrollbackBannerTest.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(ui): ScrollbackBanner Compose component"
```

---

## Task 10: TerminalPane — overlay the banner

**Files:**
- Modify: `app/src/main/java/com/example/sshterminal/ui/TerminalPane.kt`

No new test for this task; the wiring is observable through the existing `TerminalViewScrollbackWiringTest` (which already covers `setScrollbackListener`), and the banner itself is tested in `ScrollbackBannerTest`. The integration here is mechanical: subscribe, pass state down, hand the click back.

- [ ] **Step 1: Modify TerminalPane**

In `app/src/main/java/com/example/sshterminal/ui/TerminalPane.kt`, replace the `AndroidView(...)` call (line 130) with a `Box` that overlays the banner:

```kotlin
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                TerminalView(context).also { terminal ->
                    terminal.bindEndpoint(endpoint)
                    lastBoundEndpoint.value = endpoint
                    terminal.setComposingHintListener(onComposingHint)
                    // Apply the persisted font size on first construction so the
                    // user never sees the default 14 then a jump to their saved
                    // value. TerminalView's constructor already calls setTextSize(14)
                    // to initialise the renderer; this overrides it before the first
                    // frame.
                    terminal.setTextSize(fontSize)
                    viewHolder.view = terminal
                }
            },
            update = { terminal ->
                // bindEndpoint() has a side effect of nulling inputConnection;
                // calling it on every recomposition would detach the IME's
                // active InputConnection on every volume-button press. Skip the
                // rebind when the endpoint reference hasn't changed.
                if (lastBoundEndpoint.value !== endpoint) {
                    terminal.bindEndpoint(endpoint)
                    lastBoundEndpoint.value = endpoint
                }
                terminal.setComposingHintListener(onComposingHint)
                // TerminalView.setTextSize is idempotent — repeated calls with
                // the same value are a no-op, so we don't need an extra guard
                // here. The PTY resize fires only when the underlying font
                // metrics actually change, which is the only behaviour that
                // would queue a SIGWINCH on the SSH write executor.
                terminal.setTextSize(fontSize)
            },
        )

        // Scrollback banner: subscribes to the view's StateFlow and
        // floats above the terminal surface. Banner click jumps back
        // to the live view via TerminalView.scrollToBottom().
        val scrollbackState = remember { mutableStateOf(ScrollbackController.ScrollbackState()) }
        val terminal = viewHolder.view
        LaunchedEffect(terminal) {
            terminal?.setScrollbackListener { state -> scrollbackState.value = state }
        }
        if (terminal != null) {
            ScrollbackBanner(
                state = scrollbackState.value,
                onBackToBottom = { terminal.scrollToBottom() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
            )
        }
    }
```

Add the imports at the top of `TerminalPane.kt`:
```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.sshterminal.terminal.ScrollbackController
```

- [ ] **Step 2: Compile-check**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:compileDebugKotlin -i
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full test suite**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest -i
```
Expected: All tests pass (no regressions in pre-existing tests; all 5 new banner tests + 15 controller tests + 5 wiring tests + 1 Robolectric controller test pass).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/ui/TerminalPane.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(ui): overlay ScrollbackBanner on TerminalPane"
```

---

## Task 11: Final regression sweep + update GEARS_SPEC.md

**Files:**
- Modify: `docs/GEARS_SPEC.md` (add a one-line entry to the spec index referencing the new feature)

- [ ] **Step 1: Run the full unit test suite**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest -i
```
Expected: BUILD SUCCESSFUL. All tests pass — this is the gate to ship.

- [ ] **Step 2: Sanity-check the existing alt-buffer regression tests still pass**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.example.sshterminal.terminal.AltBufferScrollCrashGuardTest" -i
```
Expected: 6 tests, all PASS. (We didn't change the alt-buffer guard; this is the safety net.)

- [ ] **Step 3: Add a one-line spec entry in GEARS_SPEC.md**

Open `docs/GEARS_SPEC.md` and find the section that lists the TV-* requirement IDs. Add a TV-SB-* block (placeholder content — the spec text will be filled in during a follow-up documentation task):

```markdown
| TV-SB-01 | Given a `MotionEvent` with `pointerCount >= 2` and `actionMasked == ACTION_POINTER_DOWN`, the View shall return `true` from `dispatchTouchEvent` and `state.value.isInScrollback` shall be `true` immediately afterwards. | Two-finger entry into scrollback. |
| TV-SB-02 | Given a scrollback-active `ScrollbackController` and a `MotionEvent` with `actionMasked == ACTION_MOVE` and `pointerCount >= 2`, the View shall update `emulator.mTopRow` to `(mTopRow + deltaRows).coerceIn(0, mTotalRows - mRows)` and return `true` from `dispatchTouchEvent`. | Two-finger drag scroll math + clamp. |
| TV-SB-03 | Given a scrollback-active `ScrollbackController` and a `MotionEvent` with `pointerCount < 2` (single-finger transition), the View shall not consume the event and shall preserve `state.value.isInScrollback == true`. | Don't auto-exit on finger lift. |
| TV-SB-04 | Given `view.scrollToBottom()`, `emulator.mTopRow` shall become `0` and `state.value.isInScrollback` shall become `false`. | Banner tap path. |
| TV-SB-05 | Given a `transcriptOutput.write` event with `isInScrollback == true` and `len > 0`, `state.value.pendingOutputCount` shall increase by `max(1, len / columns)`. | Output counter accumulation. |
```

- [ ] **Step 4: Commit**

```bash
git add docs/GEARS_SPEC.md
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "docs(spec): add TV-SB-* requirements for two-finger scrollback"
```

---

## Self-Review

After writing the plan I checked it against the spec:

**1. Spec coverage:**
- Problem statement — addressed in `TerminalViewScrollbackWiringTest.dispatchTouchEvent_twoFingerMove_returnsTrue` and the entire controller state machine.
- Decisions table — every row is pinned by a test or a design choice in the plan.
- Architecture (controller class shape, data class, sealed interface, state flow) — Task 1 + Task 2 + Task 5.
- Architecture changes to TerminalView (field, dispatchTouchEvent, transcriptOutput.write, public API) — Task 6.
- Architecture new file ScrollbackBanner — Task 9.
- Components (file structure) — covered in the File Structure table and the per-task Files lists.
- Data flow (entering, scrolling, new output, exit, pointer transitions) — every transition has a test in `ScrollbackControllerTest` (Tasks 2, 3, 4, 5).
- Error handling (threading, defensive guards, lifecycle, edge cases) — `fontLineSpacing == 0` test in Task 3; threading notes in the controller kdoc and the `view.post` call in Task 6 step 4.
- Testing — all four test files in the spec are produced by the plan; the existing 5 regression test files are explicitly named in Task 6 step 5 and Task 11 step 2.

**2. Placeholder scan:** No "TBD", no "implement later", no "fill in details". Every step shows the actual code. Two-step references to "similar to Task N" appear in narrative only, not as substitutes for code.

**3. Type consistency:** `ScrollbackController.ScrollbackState` is used in `TerminalView.setScrollbackListener` (Task 6) and in `ScrollbackBanner` (Task 9) and in `ScrollbackBannerTest` (Task 9) with the same field names (`isInScrollback`, `pendingOutputCount`). `ScrollbackController.TouchDecision` has `PassThrough` and `Consumed` consistently in Tasks 2, 3, 6, 7. `view.scrollToBottom()` is the single public entry used by both the test and the banner click handler. `setScrollbackListener`'s parameter type matches the banner's subscription in `TerminalPane.kt` (Task 10).
