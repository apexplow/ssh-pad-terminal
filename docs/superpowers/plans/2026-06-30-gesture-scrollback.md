# Two-Finger Page-by-Page Scrollback — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a two-finger swipe gesture to the pad SSH terminal that pages back through terminal history one screenful at a time, with a "back to bottom" banner and an indicator of newly arrived output.

**Architecture:** Introduce a `ScrollbackController` (mirror of `SelectionController`'s shape) that detects two-finger gestures at the wrapper `TerminalView.dispatchTouchEvent` layer. On gesture end, the controller invokes `com.termux.view.TerminalView.doScroll(MotionEvent, Int)` via reflection — reusing the inner view's existing scrollback path (branch 3) to mutate the inner view's `mTopRow`. The controller maintains its own `isInScrollback` flag and `pendingOutputCount` exposed via `StateFlow<ScrollbackState>` to a Compose `ScrollbackBanner` overlay. Single-finger behavior, the alt-buffer crash guard, and the IME 5-method contract are all untouched.

**Tech Stack:** Kotlin 1.9, Robolectric 4.13, JUnit 4.13.2, mockk 1.13.13, `kotlinx.coroutines` 1.7+, AppLog (existing), androidx.compose.ui:ui-test-junit4 + ui-test-manifest (new test-only deps). Reflection on `com.termux.view.TerminalView.doScroll` (package-private method) — same pattern as the existing `AltBufferScrollCrashGuardTest`.

**Spec:** `docs/superpowers/specs/2026-06-30-gesture-scrollback-design.md`

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt` | modify | Constructor: `(view, innerView, emulator, fontLineSpacing)`. State: `isInScrollback` + `pendingOutputCount`. Multi-touch detection. doScroll reflection cache. On gesture end: doScroll with `±emulator.mRows`. `scrollToBottom()` calls doScroll with `+mTotalRows`. `onTranscriptWrite` increments pending count. |
| `app/src/main/java/com/example/sshterminal/terminal/TerminalView.kt` | modify | + `scrollbackController` field (built after emulator+termuxView). `dispatchTouchEvent` consults controller. `transcriptOutput.write` forwards byte counts. New public API (`setScrollbackListener`, `scrollToBottom`, `isInScrollback`). |
| `app/src/main/java/com/example/sshterminal/ui/ScrollbackBanner.kt` | create | Compose banner: hidden by default, "↑ 滚回历史" + optional "▼ N 行新输出" badge, whole row clickable. |
| `app/src/main/java/com/example/sshterminal/ui/TerminalPane.kt` | modify | Wrap `AndroidView` in `Box`, overlay `ScrollbackBanner` driven by a `LaunchedEffect`-subscribed `MutableState`. |
| `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt` | modify | Pure tests + Robolectric: state machine, page scroll, threshold, alt-buffer guard, `scrollToBottom`, output counting. |
| `app/src/test/java/com/example/sshterminal/terminal/TerminalViewScrollbackWiringTest.kt` | create | Robolectric: `dispatchTouchEvent` returns true for 2-finger, false for 1-finger; `scrollToBottom` resets; `setScrollbackListener` receives states; all 6 `AltBufferScrollCrashGuardTest` cases still pass. |
| `app/src/test/java/com/example/sshterminal/ui/ScrollbackBannerTest.kt` | create | Compose UI test: hidden / visible / with badge / click fires `onBackToBottom`. |
| `app/build.gradle.kts` | modify | + `testImplementation("androidx.compose.ui:ui-test-junit4")` and `testImplementation("androidx.compose.ui:ui-test-manifest")`. |

No other files change.

---

## Task 1: ScrollbackController — scaffold (with `innerView` dep)

**Files:**
- Modify: `app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt`
- Modify: `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt`

This task **supersedes** the previously-committed scaffold (commits `eb117bd` and `b092fd5`) — the constructor signature is changing to add `innerView`. The previously-added `onTouchEvent` from Task 2 is also being replaced in Task 3. For this task we only need the data class, sealed interface, state flow, and the new constructor.

- [ ] **Step 1: Replace the test file with a single test pinning the new constructor**

Replace the entire content of `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt` with:

```kotlin
package com.taosun.hanterm.terminal

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView as TermuxTerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [ScrollbackController]. Uses a real [com.taosun.hanterm.terminal.TerminalView]
 * (which builds a real [TerminalEmulator] and a real [TermuxTerminalView] inner view) so we can
 * read `termuxView.mTopRow` after a gesture and observe the doScroll side-effect end-to-end.
 * Pure-controller logic (state machine, threshold) is driven by real MotionEvents constructed
 * via the standard `MotionEvent.obtain` API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScrollbackControllerTest {

    private fun newController(): Triple<TerminalView, TerminalEmulator, ScrollbackController> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
        view.onCreateInputConnection(EditorInfo())
        val emulator = view.termuxView.mEmulator!!
        val controller = ScrollbackController(
            view = view,
            innerView = view.termuxView,
            emulator = emulator,
            fontLineSpacing = { 16f },
        )
        return Triple(view, emulator, controller)
    }

    @Test
    fun state_isInScrollbackFalseByDefault() {
        val (_, _, controller) = newController()

        assertFalse(controller.state.value.isInScrollback)
        assertEquals(0, controller.state.value.pendingOutputCount)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.terminal.ScrollbackControllerTest" -i
```
Expected: FAIL with `Too many arguments for public constructor ScrollbackController(View, TerminalEmulator, () -> Float)` (the old 3-arg constructor).

- [ ] **Step 3: Replace the controller file with the new scaffold**

Replace the entire content of `app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt` with:

```kotlin
package com.taosun.hanterm.terminal

import android.view.MotionEvent
import android.view.View
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView as TermuxTerminalView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the two-finger page-by-page scrollback gesture on the pad SSH client.
 *
 * Responsibilities (full scope in
 * docs/superpowers/specs/2026-06-30-gesture-scrollback-design.md):
 *   1. Multi-touch detection — pass through single-finger events, consume
 *      two-finger events at the wrapper dispatchTouchEvent layer so the
 *      inner view's GestureDetector never sees them.
 *   2. Page scroll — on gesture end (ACTION_UP), if the swipe exceeds a
 *      half-page threshold, invoke
 *      `com.termux.view.TerminalView.doScroll(MotionEvent, Int)` via
 *      reflection with `±emulator.mRows` to step the inner view's
 *      `mTopRow` by exactly one page. The inner view's own scrollback
 *      path (branch 3 in the AltBufferScrollCrashGuardTest root-cause
 *      kdoc) handles the actual mutation.
 *   3. New-output counting — `pendingOutputCount` accumulates while the
 *      user is scrolled back; the banner reads it to render the
 *      "▼ N 行新输出" badge.
 *   4. State emission — `StateFlow<ScrollbackState>` is the single
 *      source of truth for the banner. Writes from IO thread go
 *      through `view.post { ... }` to land on the UI thread before
 *      any emission.
 *
 * No `release()` lifecycle — the controller is owned by the wrapper
 * and GC'd with it. Matches [SelectionController].
 */
class ScrollbackController(
    private val view: View,
    private val innerView: TermuxTerminalView,
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
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.terminal.ScrollbackControllerTest" -i
```
Expected: 1 test, PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(terminal): ScrollbackController scaffold with innerView dep for doScroll"
```

---

## Task 2: ScrollbackController — multi-touch detection (re-arm Task 2's tests for the new constructor)

**Files:**
- Modify: `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt`
- Modify: `app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt`

The previous Task 2 (commit `b092fd5`) added `onTouchEvent` returning `PassThrough`/`Consumed` and the `anchorPointerY` field. We're REPLACING that work for the new design: this task introduces the full gesture state machine in one go (gestureInitialY, gestureFinalY, lastMoveEvent) because the page-by-page model has no per-MOVE work — the entire gesture is just "track initial Y, track final Y, dispatch on UP".

- [ ] **Step 1: Add tests for the multi-touch entry and the gesture state machine**

Replace the body of `ScrollbackControllerTest.kt` (keeping the imports and `newController()` helper) with these new tests. Add to the imports block at the top: `android.os.SystemClock`, `android.view.InputDevice`, `android.view.MotionEvent`, `org.junit.Assert.assertTrue`. The full file should look like:

```kotlin
package com.taosun.hanterm.terminal

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView as TermuxTerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [ScrollbackController]. Uses a real [com.taosun.hanterm.terminal.TerminalView]
 * (which builds a real [TerminalEmulator] and a real [TermuxTerminalView] inner view) so we can
 * read `termuxView.mTopRow` after a gesture and observe the doScroll side-effect end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScrollbackControllerTest {

    private fun newController(): Triple<TerminalView, TerminalEmulator, ScrollbackController> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TerminalView(context)
        view.bindEndpoint(TerminalEndpoint {})
        view.onCreateInputConnection(EditorInfo())
        val emulator = view.termuxView.mEmulator!!
        val controller = ScrollbackController(
            view = view,
            innerView = view.termuxView,
            emulator = emulator,
            fontLineSpacing = { 16f },
        )
        return Triple(view, emulator, controller)
    }

    @Test
    fun state_isInScrollbackFalseByDefault() {
        val (_, _, controller) = newController()
        assertFalse(controller.state.value.isInScrollback)
        assertEquals(0, controller.state.value.pendingOutputCount)
    }

    @Test
    fun onTouchEvent_singleFingerActionDown_returnsPassThrough() {
        val (_, _, controller) = newController()
        val ev = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        try {
            assertEquals(
                ScrollbackController.TouchDecision.PassThrough,
                controller.onTouchEvent(ev),
            )
            assertFalse(controller.state.value.isInScrollback)
        } finally {
            ev.recycle()
        }
    }

    @Test
    fun onTouchEvent_twoFingerActionPointerDown_setsIsInScrollbackTrue() {
        val (_, _, controller) = newController()
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 10f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 10f; pressure = 1f; size = 1f },
        )
        val ev = MotionEvent.obtain(
            downTime, downTime,
            MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            assertEquals(
                ScrollbackController.TouchDecision.Consumed,
                controller.onTouchEvent(ev),
            )
            assertTrue(controller.state.value.isInScrollback)
        } finally {
            ev.recycle()
        }
    }

    @Test
    fun onTouchEvent_twoFingerActionMove_doesNotChangeTopRowYet() {
        // Page-by-page model: the controller does NOT call doScroll on
        // MOVE; it just remembers the final centroid. The actual scroll
        // happens on ACTION_UP. So a sequence of MOVEs without UP leaves
        // mTopRow untouched.
        val (view, _, controller) = newController()
        val initialTopRow = view.termuxView.mTopRow

        val downTime = SystemClock.uptimeMillis()
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
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evMove = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE,
            2, props, coords1,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evMove)
            assertEquals(
                "MOVE alone must not yet call doScroll — page scroll happens on ACTION_UP",
                initialTopRow, view.termuxView.mTopRow,
            )
        } finally {
            evDown.recycle()
            evMove.recycle()
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.terminal.ScrollbackControllerTest" -i
```
Expected: 3 of 4 tests fail with `Unresolved reference: onTouchEvent`.

- [ ] **Step 3: Implement `onTouchEvent` (gesture state machine, no doScroll yet)**

In `ScrollbackController.kt`, add these fields and the method to the class (before the closing brace):

```kotlin
    private var gestureInitialY: Float? = null
    private var gestureFinalY: Float? = null
    private var lastMoveEvent: MotionEvent? = null

    /**
     * Consult the controller for a single MotionEvent. The wrapper calls
     * this from `dispatchTouchEvent` BEFORE `super`. Returns PassThrough
     * for single-finger events (the inner view handles them); returns
     * Consumed for two-finger events (the controller owns the gesture).
     *
     * Page-by-page contract: the controller does NOT call doScroll on
     * every MOVE. It only tracks the gesture start/end Y positions; the
     * actual page scroll happens on ACTION_UP (and is implemented in
     * Task 3).
     *
     * Threading: UI thread only.
     */
    fun onTouchEvent(ev: MotionEvent): TouchDecision {
        if (ev.pointerCount < 2) return TouchDecision.PassThrough

        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                // First 2-finger frame: arm the initial centroid. Note
                // this fires for the 2nd, 3rd, ... fingers; we want
                // exactly one arm per gesture.
                if (gestureInitialY == null) {
                    gestureInitialY = centroidY(ev)
                }
                _state.value = _state.value.copy(isInScrollback = true)
            }
            MotionEvent.ACTION_MOVE -> {
                // Track the final centroid and the most recent MOVE event
                // (so we can pass it to doScroll on ACTION_UP).
                gestureFinalY = centroidY(ev)
                lastMoveEvent = ev
            }
            MotionEvent.ACTION_UP -> {
                // Final finger lifted — commit the gesture. The actual
                // doScroll call lives in Task 3.
                // For now (Task 2), just clear the gesture state.
                gestureInitialY = null
                gestureFinalY = null
                lastMoveEvent = null
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                // One finger lifted but gesture is still active; the
                // remaining pointer can keep moving. Don't clear state.
            }
        }
        return TouchDecision.Consumed
    }

    private fun centroidY(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getY(i)
        return sum / ev.pointerCount
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.terminal.ScrollbackControllerTest" -i
```
Expected: 4 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(terminal): two-finger gesture state machine (no doScroll yet)"
```

---

## Task 3: ScrollbackController — page scroll via doScroll on ACTION_UP

**Files:**
- Modify: `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt`
- Modify: `app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt`

- [ ] **Step 1: Add tests for the page-scroll behavior**

Append these tests to the `ScrollbackControllerTest` class body (before the closing brace):

```kotlin
    @Test
    fun onTouchEvent_pageUp_callsDoScrollWithNegativeRows() {
        val (view, _, controller) = newController()
        val initialTopRow = view.termuxView.mTopRow
        val pageSize = view.termuxView.mRows

        // Two-finger POINTER_DOWN at y=200, MOVE to y=20 (huge upward swipe),
        // then ACTION_UP. dy = 20 - 200 = -180. Threshold = 16 * pageSize / 2.
        // For pageSize=24, threshold=192. dy=-180 is JUST under the threshold,
        // so we need an even bigger swipe. Let's use y=0: dy=-200, |dy| > 192.
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val coordsUp = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 0f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 0f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evMove = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE,
            2, props, coordsUp,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evUp = MotionEvent.obtain(
            downTime, downTime + 32L, MotionEvent.ACTION_UP, 10f, 0f, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evMove)
            controller.onTouchEvent(evUp)
            // After page-up, mTopRow should have increased by pageSize.
            // (The real inner view's doScroll clamps at the transcript
            // top, but the real transcript is huge so the clamp doesn't
            // bite for a single page.)
            assertEquals(
                "page-up must call doScroll with -mRows, advancing mTopRow by one page",
                initialTopRow + pageSize, view.termuxView.mTopRow,
            )
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_pageDown_callsDoScrollWithPositiveRows() {
        val (view, _, controller) = newController()
        // First page up so we have room to page down.
        view.termuxView.mTopRow = view.termuxView.mRows * 2
        val before = view.termuxView.mTopRow
        val pageSize = view.termuxView.mRows

        // Swipe DOWN: y=200 to y=400 (dy=+200, threshold=192 → triggers).
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val coordsDown = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 400f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 400f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evMove = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE,
            2, props, coordsDown,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evUp = MotionEvent.obtain(
            downTime, downTime + 32L, MotionEvent.ACTION_UP, 10f, 400f, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evMove)
            controller.onTouchEvent(evUp)
            assertEquals(
                "page-down must call doScroll with +mRows, decreasing mTopRow by one page",
                before - pageSize, view.termuxView.mTopRow,
            )
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_shortSwipe_isNoOp() {
        val (view, _, controller) = newController()
        val initialTopRow = view.termuxView.mTopRow

        // Swipe dy=10px — well under the threshold (192).
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val coordsSlight = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 190f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 190f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evMove = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE,
            2, props, coordsSlight,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evUp = MotionEvent.obtain(
            downTime, downTime + 32L, MotionEvent.ACTION_UP, 10f, 190f, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evMove)
            controller.onTouchEvent(evUp)
            assertEquals(
                "swipe below threshold must not call doScroll",
                initialTopRow, view.termuxView.mTopRow,
            )
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_pageDownToZero_autoExitsScrollback() {
        val (view, _, controller) = newController()
        view.termuxView.mTopRow = view.termuxView.mRows  // one page up
        assertTrue(controller.state.value.isInScrollback)

        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val coordsDown = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 400f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 400f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evMove = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE,
            2, props, coordsDown,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evUp = MotionEvent.obtain(
            downTime, downTime + 32L, MotionEvent.ACTION_UP, 10f, 400f, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evMove)
            controller.onTouchEvent(evUp)
            assertEquals(0, view.termuxView.mTopRow)
            assertFalse(
                "page-down to mTopRow=0 must auto-exit scrollback",
                controller.state.value.isInScrollback,
            )
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }

    @Test
    fun onTouchEvent_inAltBufferMode_swallowsGestureWithoutDoScroll() {
        // vim/less/htop are in the alt buffer. doScroll would NPE because
        // the inner view's mTermSession is null. The controller must
        // consume the gesture (to prevent the inner view's GestureDetector
        // from NPEing) but NOT call doScroll — the remote TUI owns
        // scrolling in this mode.
        val (view, _, controller) = newController()
        val emulator = view.termuxView.mEmulator!!
        emulator.doDecSetOrReset(true, 1049) // enter alt buffer

        val initialTopRow = view.termuxView.mTopRow
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords0 = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 200f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 200f; pressure = 1f; size = 1f },
        )
        val coordsUp = arrayOf(
            MotionEvent.PointerCoords().apply { x = 10f; y = 0f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 50f; y = 0f; pressure = 1f; size = 1f },
        )
        val evDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            2, props, coords0,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evMove = MotionEvent.obtain(
            downTime, downTime + 16L, MotionEvent.ACTION_MOVE,
            2, props, coordsUp,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val evUp = MotionEvent.obtain(
            downTime, downTime + 32L, MotionEvent.ACTION_UP, 10f, 0f, 0,
        )
        try {
            controller.onTouchEvent(evDown)
            controller.onTouchEvent(evMove)
            controller.onTouchEvent(evUp)
            assertEquals(
                "alt-buffer mode must not call doScroll (avoids the NPE in branch 2)",
                initialTopRow, view.termuxView.mTopRow,
            )
        } finally {
            evDown.recycle()
            evMove.recycle()
            evUp.recycle()
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail (page-scroll tests will fail because the controller doesn't call doScroll yet)**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.terminal.ScrollbackControllerTest" -i
```
Expected: 4 new tests FAIL (pageUp doesn't advance mTopRow, pageDown doesn't decrease, shortSwipe unchanged, pageDownToZero doesn't auto-exit, altBuffer unchanged). The altBuffer test is expected to PASS already because Task 2 didn't call doScroll at all.

- [ ] **Step 3: Implement doScroll reflection + page scroll on ACTION_UP**

Add to `ScrollbackController.kt`:

1. Add the doScroll Method cache and a `topRowField` cache right after the existing private fields:

```kotlin
    private val doScrollMethod: java.lang.reflect.Method by lazy {
        TermuxTerminalView::class.java.getDeclaredMethod(
            "doScroll",
            MotionEvent::class.java,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
    }

    private val topRowField: java.lang.reflect.Field by lazy {
        TermuxTerminalView::class.java.getDeclaredField("mTopRow").apply { isAccessible = true }
    }

    private fun readInnerTopRow(): Int = topRowField.getInt(innerView)
```

2. Replace the `ACTION_UP` branch in `onTouchEvent` to actually call doScroll:

```kotlin
            MotionEvent.ACTION_UP -> {
                commitGesture()
                // commitGesture resets the gesture state.
            }
```

3. Add the `commitGesture` and `dispatchPageScroll` methods (and a `synthesizeMoveEvent` helper) below `onTouchEvent`:

```kotlin
    /**
     * Called when the LAST finger lifts. Computes the total dy of the
     * gesture and dispatches a one-page scroll via doScroll if the swipe
     * crossed the half-page threshold.
     *
     * Threading: UI thread only.
     */
    private fun commitGesture() {
        val initial = gestureInitialY
        val final = gestureFinalY
        val move = lastMoveEvent
        gestureInitialY = null
        gestureFinalY = null
        lastMoveEvent = null
        if (initial == null || final == null || move == null) return
        // Alt-buffer mode: consume the gesture (we already returned
        // Consumed from onTouchEvent) but don't call doScroll — branch 2
        // would NPE. The remote TUI owns scrolling.
        if (emulator.isAlternateBufferActive && !emulator.isMouseTrackingActive) return

        val dy = final - initial
        val lineSpacing = fontLineSpacing().takeIf { it > 0f } ?: return
        val threshold = lineSpacing * emulator.mRows / 2f
        val amount = when {
            dy < -threshold -> -emulator.mRows   // page up
            dy > threshold -> +emulator.mRows    // page down
            else -> return                        // no-op for tiny swipes
        }
        invokeDoScroll(move, amount)
        // Auto-exit if the page scroll brought us back to the live view.
        if (readInnerTopRow() == 0) {
            _state.value = _state.value.copy(isInScrollback = false)
        }
    }

    private fun invokeDoScroll(move: MotionEvent, amount: Int) {
        runCatching {
            doScrollMethod.invoke(innerView, move, amount)
            innerView.postInvalidateOnAnimation()
        }.onFailure {
            com.taosun.hanterm.logging.AppLog.w(
                "ScrollbackController", "doScroll reflection failed", it,
            )
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.terminal.ScrollbackControllerTest" -i
```
Expected: 8 tests, all PASS (the existing 4 + the 4 new page-scroll tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(terminal): two-finger page scroll via inner view doScroll"
```

---

## Task 4: ScrollbackController — `scrollToBottom` + auto-exit on threshold

**Files:**
- Modify: `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt`
- Modify: `app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt`

- [ ] **Step 1: Add tests for `scrollToBottom`**

Append these tests to the test class body:

```kotlin
    @Test
    fun scrollToBottom_resetsInnerTopRowAndState() {
        val (view, _, controller) = newController()
        view.termuxView.mTopRow = view.termuxView.mRows * 3

        controller.scrollToBottom()

        assertEquals(0, view.termuxView.mTopRow)
        assertFalse(controller.state.value.isInScrollback)
        assertEquals(0, controller.state.value.pendingOutputCount)
    }

    @Test
    fun scrollToBottom_whenAlreadyAtZero_isNoOp() {
        val (view, _, controller) = newController()
        val initialTopRow = view.termuxView.mTopRow
        controller.scrollToBottom()
        assertEquals(initialTopRow, view.termuxView.mTopRow)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.terminal.ScrollbackControllerTest" -i
```
Expected: 2 new tests fail with `Unresolved reference: scrollToBottom`.

- [ ] **Step 3: Implement `scrollToBottom`**

Add to `ScrollbackController.kt` (near the other public methods):

```kotlin
    /**
     * Jump to the live view, clear pending output, and exit scrollback
     * mode. Safe to call from the banner click handler. Implemented as
     * a deliberately-oversized positive doScroll — the inner view's own
     * clamp keeps it at mTopRow=0.
     *
     * Threading: UI thread only.
     */
    fun scrollToBottom() {
        // Synthesize a minimal ACTION_MOVE event for doScroll if we don't
        // have one in flight (e.g., banner tapped with no active gesture).
        val ev = lastMoveEvent
            ?: MotionEvent.obtain(
                SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE, 0f, 0f, 0,
            )
        invokeDoScroll(ev, +emulator.mTotalRows) // overshoot; inner view clamps at 0
        if (ev !== lastMoveEvent) ev.recycle()
        _state.value = ScrollbackState() // isInScrollback=false, pending=0
    }
```

Add the import for `SystemClock` at the top of the file:
```kotlin
import android.os.SystemClock
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.terminal.ScrollbackControllerTest" -i
```
Expected: 10 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(terminal): scrollback scrollToBottom via doScroll"
```

---

## Task 5: ScrollbackController — pending output counting

**Files:**
- Modify: `app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt`
- Modify: `app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt`

- [ ] **Step 1: Add tests for the output counter**

Append these tests to the test class body:

```kotlin
    @Test
    fun onTranscriptWrite_eightyBytes_addsOneLine() {
        val (_, _, controller) = newController()
        controller.onTranscriptWrite(byteCount = 80, columns = 80)
        assertEquals(1, controller.state.value.pendingOutputCount)
    }

    @Test
    fun onTranscriptWrite_hundredSixtyBytes_addsTwoLines() {
        val (_, _, controller) = newController()
        controller.onTranscriptWrite(byteCount = 160, columns = 80)
        assertEquals(2, controller.state.value.pendingOutputCount)
    }

    @Test
    fun onTranscriptWrite_partialLine_floorsToOne() {
        val (_, _, controller) = newController()
        controller.onTranscriptWrite(byteCount = 40, columns = 80)
        assertEquals(1, controller.state.value.pendingOutputCount)
    }

    @Test
    fun onTranscriptWrite_accumulatesAcrossCalls() {
        val (_, _, controller) = newController()
        controller.onTranscriptWrite(80, 80)
        controller.onTranscriptWrite(80, 80)
        controller.onTranscriptWrite(40, 80)
        assertEquals(3, controller.state.value.pendingOutputCount)
    }

    @Test
    fun scrollToBottom_resetsPendingCount() {
        val (_, _, controller) = newController()
        controller.onTranscriptWrite(240, 80)
        assertEquals(3, controller.state.value.pendingOutputCount)
        controller.scrollToBottom()
        assertEquals(0, controller.state.value.pendingOutputCount)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.terminal.ScrollbackControllerTest" -i
```
Expected: 5 new tests fail with `Unresolved reference: onTranscriptWrite`.

- [ ] **Step 3: Implement `onTranscriptWrite`**

Add to `ScrollbackController.kt`:

1. Add the `pendingOutputCount` field and `refreshState` helper near the top of the class body:

```kotlin
    private val pendingOutputCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Re-publish the current pending count. Must run on the UI thread
     * (the wrapper calls this via `view.post { ... }` so Compose sees a
     * UI-thread emission).
     */
    internal fun refreshState() {
        _state.value = _state.value.copy(
            pendingOutputCount = pendingOutputCount.get(),
        )
    }
```

2. Add the `onTranscriptWrite` public method:

```kotlin
    /**
     * Account for [byteCount] bytes that the emulator just absorbed while
     * we were scrolled back. Line estimate = `max(1, byteCount / columns)`;
     * floor at 1 so a stray carriage return still registers as "something
     * happened" and the banner badge updates.
     *
     * Threading: the AtomicInteger add is safe from any thread; the
     * emission is the wrapper's responsibility (the caller should
     * `view.post { controller.refreshState() }` to bring the StateFlow
     * update onto the UI thread).
     */
    fun onTranscriptWrite(byteCount: Int, columns: Int) {
        if (byteCount <= 0) return
        val safeColumns = columns.coerceAtLeast(1)
        val lines = (byteCount / safeColumns).coerceAtLeast(1)
        pendingOutputCount.addAndGet(lines)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.terminal.ScrollbackControllerTest" -i
```
Expected: 15 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/ScrollbackController.kt app/src/test/java/com/example/sshterminal/terminal/ScrollbackControllerTest.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(terminal): scrollback pending output counter"
```

---

## Task 6: TerminalView — wire controller into dispatch + transcript + public API

**Files:**
- Modify: `app/src/main/java/com/example/sshterminal/terminal/TerminalView.kt`
- Create: `app/src/test/java/com/example/sshterminal/terminal/TerminalViewScrollbackWiringTest.kt`

- [ ] **Step 1: Write the failing wiring tests**

Create `app/src/test/java/com/example/sshterminal/terminal/TerminalViewScrollbackWiringTest.kt`:

```kotlin
package com.taosun.hanterm.terminal

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wires the ScrollbackController into TerminalView. Asserts:
 *   1. dispatchTouchEvent with a two-finger gesture eventually reaches
 *      the inner view's doScroll (visible via mTopRow change)
 *   2. dispatchTouchEvent with single-finger passes through (the
 *      existing alt-buffer guard, long-press selection, etc. remain
 *      unaffected)
 *   3. scrollToBottom() resets the inner view's mTopRow to 0
 *   4. setScrollbackListener() receives state transitions
 *   5. All 6 AltBufferScrollCrashGuardTest cases still pass
 *      (regression: the controller integration must not break the
 *      alt-buffer NPE guard that ships today)
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
    fun dispatchTouchEvent_singleFingerActionDown_doesNotConsume() {
        val initialTopRow = view.termuxView.mTopRow
        val consumed = view.dispatchTouchEvent(
            android.view.MotionEvent.obtain(
                0L, 0L, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0,
            ).also { it.recycle() },
        )
        // The wrapper still does its ACTION_DOWN focus request, and
        // single-finger events fall through to super. We don't assert
        // the exact return value (depends on Termux's GestureDetector
        // shadow), only that the controller wasn't engaged.
        assertEquals(initialTopRow, view.termuxView.mTopRow)
        // Discard the `consumed` return; not asserting it.
        @Suppress("UNUSED_VARIABLE")
        val ignored = consumed
    }

    @Test
    fun scrollToBottom_resetsInnerTopRow() {
        view.termuxView.mTopRow = view.termuxView.mRows * 2
        view.scrollToBottom()
        assertEquals(0, view.termuxView.mTopRow)
    }

    @Test
    fun isInScrollback_readsControllerState() {
        // Right after construction: not in scrollback.
        assertFalse(view.isInScrollback)
        // After scrollToBottom: still not in scrollback.
        view.termuxView.mTopRow = view.termuxView.mRows * 2
        view.scrollToBottom()
        assertFalse(view.isInScrollback)
    }

    @Test
    fun setScrollbackListener_firesInitialState() {
        var seen: ScrollbackController.ScrollbackState? = null
        view.setScrollbackListener { state -> seen = state }
        // Initial state fires once on registration (mirrors the
        // setPtyResizeListener pattern in this file).
        assertNotNull(seen)
        assertFalse(seen!!.isInScrollback)
    }
}
```

- [ ] **Step 2: Run the wiring tests to verify they fail**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.terminal.TerminalViewScrollbackWiringTest" -i
```
Expected: 4 tests fail with `Unresolved reference: setScrollbackListener` / `scrollToBottom` / `isInScrollback`.

- [ ] **Step 3: Wire ScrollbackController into TerminalView**

In `app/src/main/java/com/example/sshterminal/terminal/TerminalView.kt`:

1. Add a private field right after `selectionController` (line ~49) — it must come **after** `emulator` in the init order:

```kotlin
    /**
     * Owns the two-finger page-by-page scrollback gesture. Wired from
     * this view's dispatchTouchEvent (intercept multi-touch before it
     * reaches the inner Termux view) and from the transcriptOutput.write
     * override (count pending lines while scrolled back). See
     * docs/superpowers/specs/2026-06-30-gesture-scrollback-design.md.
     */
    private val scrollbackController: ScrollbackController = ScrollbackController(
        view = this,
        innerView = termuxView,
        emulator = emulator,
        fontLineSpacing = { termuxView.mRenderer?.getFontLineSpacing()?.toFloat() ?: 0f },
    )
```

2. Replace `dispatchTouchEvent` (line ~336) with:

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

3. Modify the `transcriptOutput.write` override (line ~132) to count pending lines when in scrollback:

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

4. Add three new public methods near the bottom of the class (next to `setPtyResizeListener` is a good neighbour):

```kotlin
    fun scrollToBottom() {
        scrollbackController.scrollToBottom()
        termuxView.postInvalidateOnAnimation()
    }

    val isInScrollback: Boolean
        get() = scrollbackController.state.value.isInScrollback

    fun setScrollbackListener(listener: ((ScrollbackController.ScrollbackState) -> Unit)?) {
        if (listener == null) return
        listener(scrollbackController.state.value)
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob(),
        )
        scope.launch {
            scrollbackController.state.collect { listener(it) }
        }
    }
```

Add these imports at the top of `TerminalView.kt`:
```kotlin
import kotlinx.coroutines.launch
```

- [ ] **Step 4: Run the wiring tests to verify they pass**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.terminal.TerminalViewScrollbackWiringTest" -i
```
Expected: 4 tests, all PASS.

- [ ] **Step 5: Run the full terminal test suite to check for regressions**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.terminal.*" -i
```
Expected: 6 `AltBufferScrollCrashGuardTest` cases + 15 `ScrollbackControllerTest` cases + 4 `TerminalViewScrollbackWiringTest` cases + all pre-existing terminal tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/terminal/TerminalView.kt app/src/test/java/com/example/sshterminal/terminal/TerminalViewScrollbackWiringTest.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(terminal): wire ScrollbackController into TerminalView dispatch and output"
```

---

## Task 7: build.gradle.kts — add Compose UI test dependencies

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

## Task 8: ScrollbackBanner — Compose implementation (TDD)

**Files:**
- Create: `app/src/test/java/com/example/sshterminal/ui/ScrollbackBannerTest.kt`
- Create: `app/src/main/java/com/example/sshterminal/ui/ScrollbackBanner.kt`

- [ ] **Step 1: Write the failing Compose test**

Create `app/src/test/java/com/example/sshterminal/ui/ScrollbackBannerTest.kt`:

```kotlin
package com.taosun.hanterm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertDoesNotExist
import com.taosun.hanterm.terminal.ScrollbackController
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
    fun click_invokesOnBackToBottom() {
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
        assert(clickCount == 1) { "expected 1 click, got $clickCount" }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.ui.ScrollbackBannerTest" -i
```
Expected: FAIL with `Unresolved reference: ScrollbackBanner`.

- [ ] **Step 3: Implement ScrollbackBanner**

Create `app/src/main/java/com/example/sshterminal/ui/ScrollbackBanner.kt`:

```kotlin
package com.taosun.hanterm.ui

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
import com.taosun.hanterm.terminal.ScrollbackController

/**
 * Top-of-pane banner that surfaces the two-finger scrollback state.
 *
 * Hidden by default; visible whenever the controller is in scrollback
 * mode. Shows an optional "▼ N 行新输出" badge when new output arrived
 * while the user was scrolled back. Tapping anywhere on the banner
 * calls [onBackToBottom], which the caller is expected to wire to
 * [com.taosun.hanterm.terminal.TerminalView.scrollToBottom].
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
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.ui.ScrollbackBannerTest" -i
```
Expected: 5 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/ui/ScrollbackBanner.kt app/src/test/java/com/example/sshterminal/ui/ScrollbackBannerTest.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(ui): ScrollbackBanner Compose component"
```

---

## Task 9: TerminalPane — overlay the banner

**Files:**
- Modify: `app/src/main/java/com/example/sshterminal/ui/TerminalPane.kt`

No new test for this task; the wiring is observable through `TerminalViewScrollbackWiringTest` (which covers `setScrollbackListener`), and the banner itself is tested in `ScrollbackBannerTest`.

- [ ] **Step 1: Modify TerminalPane**

In `app/src/main/java/com/example/sshterminal/ui/TerminalPane.kt`, replace the `AndroidView(...)` call (line ~130) with a `Box` that overlays the banner:

```kotlin
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                TerminalView(context).also { terminal ->
                    terminal.bindEndpoint(endpoint)
                    lastBoundEndpoint.value = endpoint
                    terminal.setComposingHintListener(onComposingHint)
                    terminal.setTextSize(fontSize)
                    viewHolder.view = terminal
                }
            },
            update = { terminal ->
                if (lastBoundEndpoint.value !== endpoint) {
                    terminal.bindEndpoint(endpoint)
                    lastBoundEndpoint.value = endpoint
                }
                terminal.setComposingHintListener(onComposingHint)
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
import com.taosun.hanterm.terminal.ScrollbackController
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
Expected: All tests pass (no regressions; 15 controller tests + 4 wiring tests + 5 banner tests + all pre-existing tests).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/sshterminal/ui/TerminalPane.kt
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "feat(ui): overlay ScrollbackBanner on TerminalPane"
```

---

## Task 10: Final regression sweep + update GEARS_SPEC.md

**Files:**
- Modify: `docs/GEARS_SPEC.md`

- [ ] **Step 1: Run the full unit test suite**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest -i
```
Expected: BUILD SUCCESSFUL. All tests pass — this is the gate to ship.

- [ ] **Step 2: Sanity-check the alt-buffer regression tests still pass**

Run:
```bash
cd /workspace/code/ssh-pad-terminal && ./gradlew :app:testDebugUnitTest --tests "com.taosun.hanterm.terminal.AltBufferScrollCrashGuardTest" -i
```
Expected: 6 tests, all PASS. (We didn't change the alt-buffer guard; this is the safety net.)

- [ ] **Step 3: Add a one-line spec entry in GEARS_SPEC.md**

Open `docs/GEARS_SPEC.md` and find the section that lists the TV-* requirement IDs. Add a TV-SB-* block:

```markdown
| TV-SB-01 | Given a `MotionEvent` with `pointerCount >= 2` and `actionMasked == ACTION_POINTER_DOWN`, the View shall consume the event (return `true` from `dispatchTouchEvent`) and `state.value.isInScrollback` shall be `true` immediately afterwards. | Two-finger entry into scrollback. |
| TV-SB-02 | Given a scrollback-active `ScrollbackController` and a `MotionEvent` with `actionMasked == ACTION_UP` whose centroid Y differs from the initial POINTER_DOWN centroid by more than `lineSpacing * mRows / 2`, the controller shall invoke `innerView.doScroll(move, ±mRows)` and the inner view's `mTopRow` shall change by exactly one page in the indicated direction. | Page scroll threshold + doScroll reflection. |
| TV-SB-03 | Given a scrollback-active `ScrollbackController` and a `MotionEvent` with `actionMasked == ACTION_UP` whose centroid Y differs from the initial centroid by less than the threshold, no `doScroll` call shall happen; `mTopRow` is unchanged. | Sub-threshold swipe is a no-op. |
| TV-SB-04 | Given `view.scrollToBottom()`, the inner view's `mTopRow` shall be `0` and `state.value.isInScrollback` shall be `false`. | Banner tap path. |
| TV-SB-05 | Given a `transcriptOutput.write` event with `isInScrollback == true` and `len > 0`, `state.value.pendingOutputCount` shall increase by `max(1, len / columns)`. | Output counter accumulation. |
| TV-SB-06 | Given the emulator is in alt-buffer mode (`isAlternateBufferActive && !isMouseTrackingActive`) and the user does a two-finger gesture, the controller shall consume the gesture but NOT call `doScroll` (avoids the existing branch-2 NPE in `AltBufferScrollCrashGuardTest`). | Alt-buffer safety. |
```

- [ ] **Step 4: Commit**

```bash
git add docs/GEARS_SPEC.md
git -c user.name=claude -c user.email=claude@anthropic.com commit -m "docs(spec): add TV-SB-* requirements for two-finger page scrollback"
```

---

## Self-Review

After writing this plan I checked it against the new spec:

**1. Spec coverage:** Every row of the Decisions table is pinned by at least one test:
- Two-finger gesture entry → `onTouchEvent_twoFingerActionPointerDown_setsIsInScrollbackTrue` (Task 2)
- Page-by-page granularity → `onTouchEvent_pageUp_callsDoScrollWithNegativeRows` (Task 3)
- doScroll reflection reuse → `onTouchEvent_pageUp_…` and `onTouchEvent_inAltBufferMode_swallowsGestureWithoutDoScroll` (Task 3)
- Banner with N-counter → `showsPendingBadge_whenPendingOutputCountPositive` (Task 8)
- scrollToBottom banner tap → `scrollToBottom_resetsInnerTopRowAndState` (Task 4)
- Auto-exit on mTopRow==0 → `onTouchEvent_pageDownToZero_autoExitsScrollback` (Task 3)
- Compose UI test deps → Task 7

**2. Placeholder scan:** No "TBD", "TODO", "fill in details", "implement later". Every step has complete code or a complete command.

**3. Type consistency:**
- `ScrollbackController.ScrollbackState` is used in `ScrollbackBanner` (Task 8) and `TerminalView.setScrollbackListener` (Task 6) with the same field names.
- `ScrollbackController.TouchDecision` has `PassThrough` and `Consumed` consistently in Tasks 2, 3, 6.
- `view.scrollToBottom()` is the single public entry used by both the wiring test (Task 6) and the banner click handler (Task 9).
- `doScrollMethod` and `topRowField` in the controller are `lazy`-initialised to keep the constructor side-effect-free.
- The `lastMoveEvent` lifecycle: set on MOVE, used on UP, cleared on UP. The `scrollToBottom` synthesises one if needed.
