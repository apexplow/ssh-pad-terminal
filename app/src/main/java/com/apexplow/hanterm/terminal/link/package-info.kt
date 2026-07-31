/**
 * Sprint 4 Link-Open — long-press a URL cell in the terminal transcript to
 * open it in the system browser.
 *
 * Module boundary (mirrors `terminal/zmodem/` and `terminal/trzsz/` patterns):
 *   - [LinkDetector]    pure function — regex over a string, no Android imports
 *   - [LinkOverlay]     screen-buffer scan + span map (Main-thread invariant)
 *   - [LinkOverlayView] transparent FrameLayout child that paints underlines
 *   - [LinkGesture]     long-press recognizer (own sealed LinkDecision)
 *   - [LinkIntentLauncher] pure helper — URL re-validate + Intent dispatch
 *   - [LinkDialog]      Compose Material3 sheet with 3 actions
 *
 * Threading: every mutation of [LinkOverlay.spans] happens on the Main
 * thread, inside `dispatchTouchEvent ACTION_DOWN` after a `mTopRow` diff.
 * IO-thread writes from `BufferedPtyBridge.Endpoint.write` set a
 * `@Volatile lastWriteUptimeMs` flag (OV #4) so the next Main refresh can
 * avoid reading emulator state inside a torn window.
 *
 * Logging: URL-related `AppLog.*` calls reuse `LogClassification.ConnectionMetadata`
 * (same classification `SshClient.kt:293` uses for host/port). Release-mode
 * behavior is `Drop` from file sink — consistent with the existing redaction
 * policy. See `docs/GEARS_SPEC.md` for `LogPolicy` classification table.
 *
 * Out of scope (deferred, see `docs/TODOS.md` T-LARGE-2/3):
 *   - OSC 8 hyperlink protocol (Termux upstream listener is experimental)
 *   - Inline popup variant C (anchored to URL cell, not bottom-sheet)
 *   - URL row-spanning regex (current regex matches within a row only)
 */
package com.apexplow.hanterm.terminal.link