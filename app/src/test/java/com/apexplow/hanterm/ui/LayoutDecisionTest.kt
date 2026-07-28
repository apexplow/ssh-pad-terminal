package com.apexplow.hanterm.ui

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JUnit coverage for [shouldUseSplitLayout] — Sprint 3 / Module 15 /
 * SL-TS-01.
 *
 * Why a dedicated test class: the rule itself is four lines of logic, but
 * it's the entire gate between the portrait single-column layout and the
 * landscape two-pane layout. Off-by-one or sign-flip here is the kind of
 * bug that's invisible until a tester rotates the tablet — keeping the
 * 2x2 truth table pinned keeps the layout decision honest.
 *
 * The fullscreen Compose rendering itself is intentionally NOT exercised
 * here — per SL-TS-02 + the project's existing precedent
 * (`ScrollbackBanner`'s "延后到真机手测" note), Compose-layout UI assertions
 * are deferred to the manual device checklist.
 */
class LayoutDecisionTest {

    @Test
    fun sl_ts_01_portrait_showTerminalFalse_returnsFalse() {
        assertFalse(
            "Portrait + pre-connect must NOT split — the single Column layout is what users see today",
            shouldUseSplitLayout(Configuration.ORIENTATION_PORTRAIT, showTerminal = false),
        )
    }

    @Test
    fun sl_ts_01_portrait_showTerminalTrue_returnsFalse() {
        assertFalse(
            "Portrait + fullscreen terminal must NOT split — the fullscreen Box(fillMaxSize) owns the surface",
            shouldUseSplitLayout(Configuration.ORIENTATION_PORTRAIT, showTerminal = true),
        )
    }

    @Test
    fun sl_ts_01_landscape_showTerminalFalse_returnsTrue() {
        assertTrue(
            "Landscape + pre-connect IS the split-layout target — ConfigScreen on the left, TerminalPane preview on the right",
            shouldUseSplitLayout(Configuration.ORIENTATION_LANDSCAPE, showTerminal = false),
        )
    }

    @Test
    fun sl_ts_01_landscape_showTerminalTrue_returnsFalse() {
        assertFalse(
            "Landscape + fullscreen terminal must NOT split — the fullscreen path is orientation-agnostic and already fills the screen",
            shouldUseSplitLayout(Configuration.ORIENTATION_LANDSCAPE, showTerminal = true),
        )
    }
}