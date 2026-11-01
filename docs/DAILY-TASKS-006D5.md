# MVP-006D.5 — Daily Tasks navigation and continuity

Base: `a7489d7` on `codex/mvp-006f1a-simple-door-counts`, the current integration branch containing the merged earnings PR #37. Existing D.1–D.4 and F.1/F.1A identifiers are retained. No dependency PR was merged by this work.

## Existing architecture and scope

Frozen work lives in the existing `FrozenTaskStore` DataStore (`frozen_tasks`, version-1 JSON), not Room. The existing Monday, Tuesday and Wednesday definitions, task IDs, notes, status events, OCR picking metadata, and truck-arrival controls are reused. Navigation uses the existing `selectTab` saved-stack mechanism.

Sunday now has its own seven-step template using the user's confirmed field notes, in their supplied order: FF Frozen promotional review, current ad-wall replenishment, regular backstock, freezer/equipment preparation, authorized receiving verification, mixed-pallet breakdown/home-shelf work, and consolidation/unfinished-freight notes. Each is independently checkable and starts incomplete. It does not reuse Wednesday's template or record a truck-arrival event.

Root cause: neither a Sunday definition nor Sunday dispatch existed. Previously visited Sundays could also be marked initialized with no template. A per-date Sunday registration marker repairs that case once, adding only missing stable Sunday IDs and preserving existing edits, completion and notes. The same repair supports an existing Sunday with manual tasks. There is no bulk historical rewrite; repair occurs when a Sunday is opened.

Thursday, Friday and Saturday remain undefined and show saved work/carryovers only. No additional responsibilities are invented.

## Daily behavior

Tasks is the fifth-tab-layout entry between Schedule and Quick Find. The existing Dashboard shortcut leads to it. Opening Tasks resolves the local date and atomically initializes only a missing daily workflow. Reopening preserves original task facts. Polling while the screen is resumed detects midnight/time-zone/date changes; only the selected date is initialized, never all intervening days.

Viewing does not call timekeeping, payroll, schedule, assignment or Count Route APIs. Automatic Tuesday/Wednesday templates use daily links; only explicit manual starts add assignment workflow aliases, reusing the same IDs. Wednesday initialization creates an empty arrival-event record for the existing truck UI, not an assignment or delivery-arrival event. Existing manual workflow controls remain under Workflow tools & history, along with date browsing, Endcap import, and Count Route. Browsing dates deliberately initializes their defined checklist without altering today's task facts.

## Carryovers and merging

- Unfinished older tasks are linked to the viewed date using their original IDs and source dates. No carryover task copies are made.
- Completed tasks are not linked to later days. Completion remains visible in the dates where the work was handled.
- Only known template IDs establish equivalence. Explicit mappings include Sunday/Monday/Tuesday backstock, Sunday/Monday ad-wall replenishment, and existing Monday/Wednesday inspection, ad-wall and Random Count definitions. Sunday receiving, preparation, promotional review, pallet work and consolidation remain distinct; consolidation is not equated with Monday's different Tuesday-handoff task. Custom titles and OCR names never cause merging.
- A merged row keeps every source record. Completing it updates the unfinished source records in one atomic edit; original notes and history stay attached to each source. Expanded rows expose source notes and source editing.
- “Remove from carryover” persists exclusion, including for automatically surfaced TODO tasks. Legacy explicit carryover-removal transitions remain recognized. Starting/reopening a source intentionally can re-enable it.
- A backward-compatible optional `carryoverExcluded` JSON Boolean defaults to false. A preferences set records visited dates, including empty undefined days. No Room schema migration, destructive migration, or parallel task store is introduced.

## Verification and acceptance checks

Tests cover seven independent Sunday tasks, empty/partially populated pre-registration Sunday repair, Sunday-to-Monday merging and Tuesday restart, automatic/idempotent opening, preserved completion and edits, Sunday manual carryovers through Monday completion and Tuesday restart, explicit identity merging, similar-title non-merging, multi-day gaps, dismissal persistence, historical/future isolation, existing workflow reuse, truck events, OCR metadata preservation, tab saved state, and midnight switching.

Physical checks:
1. Open Sunday November 1 and tap Tasks: seven ordered responsibilities should appear without setup; each checkbox changes only its task. Tap Tasks on other defined weekdays; they should load without Start/assignment prompts. Undefined weekdays clearly show carryovers/manual work only.
2. Complete a carryover Monday and reopen Tuesday; the original source must stay completed. A separate Tuesday routine remains a distinct occurrence.
3. Inspect source dates/notes on merged rows, switch tabs and return, then browse history and return to Today.
4. Check dark mode, large fonts, TalkBack, clock/time-zone changes, and reopening after reboot.
5. Confirm Endcap import, Count Route and manual workflow controls remain reachable through Workflow tools & history.

Backup restoration testing, clearing app data, signed release builds, and operational-procedure changes are outside this work.

Initial verification before Sunday registration: debug APK and Android-test APK assembled; 999 unit tests passed in each debug/release test variant. Lint reported zero errors, 17 warnings and one hint in unchanged files. Backup round-trip/restoration tests were explicitly excluded. The eligible emulator suite exercised 65 tests; 64 initially passed and one obsolete fixed-Monday navigation assertion was updated for the new Tasks entry. All 10 affected Daily/Monday/Tuesday/Wednesday device tests then passed, including the final build. Screenshots cover automatic Monday initialization and 320dp/200% font carryover rows. Manual TalkBack/device reboot checks remain.

A read-only emulator before/after comparison confirmed all nine original Room tables and all ten original Frozen tasks/facts were preserved. Only daily membership metadata was added to those tasks; existing reminder caches advanced their week key through normal startup behavior. No backup was restored and app data was never cleared.

Sunday follow-up verification: debug build and 1,004 unit tests per debug/release variant passed; lint has zero errors, 17 existing warnings and one hint. The Sunday emulator UI test passed with seven independently actionable tasks and persisted completion. The remaining device suite was interrupted by an emulator stall; the emulator was restarted without clearing data. Full follow-up emulator rerun remains pending system startup. No backup restoration was performed.
