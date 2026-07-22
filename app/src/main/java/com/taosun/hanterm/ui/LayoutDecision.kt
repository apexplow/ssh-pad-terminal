package com.taosun.hanterm.ui

import android.content.res.Configuration

/**
 * Sprint 3 / Module 15 — landscape split layout decision.
 *
 * ## Why a pure function
 *
 * Per GEARS_SPEC.md SL-OR-04, the orientation decision MUST be a plain
 * Kotlin function taking primitive/enum arguments only — no `Context`,
 * no `@Composable` annotation. This lets the four-direction matrix
 * (PORTRAIT/LANDSCAPE × showTerminal true/false) be unit-tested under
 * plain JUnit, not Robolectric or Compose UI tests. The Compose caller
 * pulls `LocalConfiguration.current.orientation` and passes it in.
 *
 * ## Why a single function, not a Composable
 *
 * Putting the rule in a `@Composable` would force every test that wants
 * to assert the layout switch to spin up `createComposeRule()` — slow,
 * flaky, and testing the wrong thing (we care about the rule, not the
 * rendering). The rule itself is four lines of logic; the rendering is
 * a `Row` vs `Column` swap in [HanTermApp], exercised by manual tablet
 * testing per spec SL-TS-02.
 *
 * @param orientation one of [Configuration.ORIENTATION_PORTRAIT] /
 *   [Configuration.ORIENTATION_LANDSCAPE] (or 0 / undefined values from
 *   a freshly-created Activity — those fall through to the conservative
 *   "don't split" branch by way of the equality check).
 * @param showTerminal `true` when the user is already inside the
 *   fullscreen terminal surface; the split layout is a pre-connect
 *   affordance, so the fullscreen path always uses the default Box
 *   regardless of orientation (SL-OR-03).
 * @return `true` only when the device is in landscape AND the user is
 *   on the pre-connect screen. All other combinations return `false`.
 */
internal fun shouldUseSplitLayout(orientation: Int, showTerminal: Boolean): Boolean =
    orientation == Configuration.ORIENTATION_LANDSCAPE && !showTerminal