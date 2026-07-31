# TODOS

Items deferred from /plan-eng-review on `feat/link-open-v0.2` design (2026-07-31).
Each item below is intentional scope-reduction — none blocks v0.1 ship.

---

## T-LARGE-1 — Width-breakpoint dialog form (AlertDialog on ≥840dp)

**What:** On `LocalConfiguration.current.screenWidthDp >= 840`, switch the link dialog
from `ModalBottomSheet` to centered `AlertDialog`. Below 840dp, keep the sheet.
**Why:** Material spec ambiguity (600dp vs 840dp). Plan §Design Decisions Log line 399
notes the implementer should verify.
**Pros:** Visual centering on very large tablets (iPad Pro 12.9 etc).
**Cons:** Doubles dialog component test matrix; needs Compose preview matrix.
**Context:** Currently set to 840dp as a guess; Material You guidance is 600dp. Real-device
validation in v0.1 will tell us which breakpoint feels right.
**Depends on:** v0.1 ship + at least 1 real tablet in field test.

## T-LARGE-2 — OSC 8 hyperlink protocol support

**What:** Enable Termux's `mEmulator.setHyperlinksEnabled(true)` (likely via reflection),
intercept hyperlink-tap events, route through `LinkOverlay`.
**Why:** ~10% of real shell output emits OSC 8 escapes (especially `gh`, modern `git`,
some `cargo` messages). Without OSC 8, the 90% of plain URLs are caught but the
hyperlink-tagged 10% rely on visual anchoring that bare-text detection misses.
**Pros:** Future-proof; matches iTerm2 hover-to-open; captures OSC 8 emitters.
**Cons:** JitPack reflection fragility (hard constraint in `CLAUDE.md`). Termux's
hyperlink impl is experimental and changes between versions.
**Context:** Plan §Approaches Considered C documents the v0.3+ candidate. Re-evaluate
when Termux exposes a public hyperlink listener (filed upstream).
**Depends on:** Termux upstream stable hyperlink API.

## T-LARGE-3 — Inline popup variant C (iTerm2 hover-tooltip style)

**What:** Replace ModalBottomSheet with anchored popup at the URL cell. Variant C
in design review (see `~/.gstack/projects/apexplow-ssh-pad-terminal/designs/link-open-20260731/`).
**Why:** Strongest visual anchor of all 3 forms. iTerm2 user muscle memory.
**Pros:** Best UX — URL cell + popup + action menu in one visual cluster.
**Cons:** Anchor algorithm doubles in complexity for tablet (URL center) vs phone
(URL right edge). Boundary clamping needed at top/bottom/edges.
**Context:** Design Decisions Log line 399 records as v0.3 candidate. Revisit when
v0.1 real-device usage data shows users want tighter visual coupling.
**Depends on:** Volume of user complaints about dialog "leaving" the URL.

## T-MEDIUM-1 — `cat largefile.log` Robolectric perf benchmark

**What:** Add `LinkOverlayPerformanceTest` with `@Test(timeout = 16)` measuring
`refresh()` against 80×24 + 10000-line transcript.
**Why:** Plan §Performance line 410 claims "16ms budget at 80×24" but the number
is unmeasured. Reviewer Concern F-9 (line 451 of plan) flags this.
**Pros:** Catches perf regression in CI before shipping.
**Cons:** Robolectric paint timing is flaky; may need custom harness or JVM-only
bench (no Android resources).
**Context:** Performance fix #1 (visible-window batch) reduces scan to ~2K chars.
Should easily fit in 16ms. Benchmark needed to confirm.
**Depends on:** LinkOverlay ships in v0.1; v0.2 add benchmark.

## T-MEDIUM-2 — Distinct `URL_DETECT` / `URL_OPEN` log classifiers

**What:** Lift URL-related log calls from shared `LogClassification.ConnectionMetadata`
into two new enum values: `URL_DETECT` (the URL was found in transcript) and `URL_OPEN`
(the URL was launched via Intent).
**Why:** URL of an external web page is not a "connection metadata" item — it's a
piece of user-targeted output that the user acted on. The classification reuse was a
v0.1 simplification; v0.2+ should treat URLs as their own classification.
**Pros:** More accurate log policy; future log audit can identify URL flows
distinctly from SSH connection metadata.
**Cons:** New enum values need `LogPolicy.kt` branches; update existing call sites
in `LinkDetector`, `LinkOverlay`, `LinkIntentLauncher`.
**Context:** Plan §Step 1 chose reuse-for-now; deferred to v0.2+ audit.
**Depends on:** Log audit identifying false positives.

## T-MEDIUM-3 — URL row-spanning regex

**What:** Detect URLs that wrap across row boundaries (long URLs in `cargo build`
output, `gh pr view` long titles). Current regex matches within a row only.
**Why:** Real shell output wraps URLs across lines. v0.1 misses these.
**Pros:** Correct detection of long URLs in TUI environments with 80-col terminal.
**Cons:** Regex complexity — needs cross-row state machine or two-pass scan.
**Context:** Plan §Step 3 explicitly defers "URL wraps across row boundary" to v0.2.
**Depends on:** Volume of user complaints about missed URLs in TUI output.

---

## How to use this file

- Add new items at the top with the next `T-<SIZE>-N` slot.
- `LARGE` = multi-week / multi-PR. `MEDIUM` = single-PR.
- Each item MUST include: What / Why / Pros / Cons / Context / Depends on.
- When picking up an item: create a sprint branch and link it in §Context.
- After merge: strike through the item (don't delete — leave the rationale).