# MVP-006D.5 — Daily Tasks navigation and continuity

Base: `a7489d7` on `codex/mvp-006f1a-simple-door-counts`, the current integration branch containing the merged earnings PR #37. Existing D.1–D.4 and F.1/F.1A identifiers are retained. No dependency PR was merged by this work.

## Existing architecture and scope

Frozen work lives in the existing `FrozenTaskStore` DataStore (`frozen_tasks`, version-1 JSON), not Room. The existing Monday, Tuesday and Wednesday definitions, task IDs, notes, status events, OCR picking metadata, and truck-arrival controls are reused. Navigation uses the existing `selectTab` saved-stack mechanism.

Sunday now has its own seven-step template using the user's confirmed field notes, in their supplied order: FF Frozen promotional review, current ad-wall replenishment, regular backstock, freezer/equipment preparation, authorized receiving verification, mixed-pallet breakdown/home-shelf work, and consolidation/unfinished-freight notes. Each is independently checkable and starts incomplete. It does not reuse Wednesday's template or record a truck-arrival event.

Root cause: neither a Sunday definition nor Sunday dispatch existed. Previously visited Sundays could also be marked initialized with no template. A per-date template registration marker repairs configured weekdays once, adding only missing stable template IDs and preserving existing edits, completion and notes. The same repair supports an existing Sunday with manual tasks. There is no bulk historical rewrite; repair occurs when a Sunday is opened.

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

## November 1 fresh-start repair

The missing Sunday definition/dispatch was fixed in `8f6485f`. The remaining defect was unrestricted historical carryover eligibility: every unfinished October record could be linked into November. Daily loading now performs one atomic, persistent reset (`carryovers_archived_before_2026_11_01`) in the existing Frozen DataStore. Unfinished tasks dated before November 1 receive a backward-compatible `carryoverArchived` flag. Their original status, IDs, source dates, descriptions, notes and event history are unchanged; completed historical records are not altered. No Room migration is needed.

November 1 is the active carryover boundary. Both derived daily rows and legacy future workflow views exclude earlier records, so later historical browsing or status edits cannot reactivate October work in November. Past workflows remain inspectable and label archived records. Newly initialized historical templates are archived for carryover purposes as well. The bulk reset does not run again after its marker is written.

Template registration now repairs every configured weekday once, including an older instance containing only manual tasks/carryovers. Missing stable IDs are added; existing IDs and their edits are never replaced. Thursday–Saturday remain undefined. No task completion is inferred from the reported delivery observations.

The daily UI omits an empty Carryovers section. Expanded pending rows offer **Dismiss task**, retaining source facts while excluding the unresolved lineage from active lists. A matched Sunday/Monday row dismisses its unfinished sources together. History remains available through Workflow tools & history.

Verification: debug build and 1,009 unit tests in each debug/release variant passed. Tests cover seven synthetic historical carryovers from October 19/21, completed-history preservation, repeat launch/restart, late historical initialization, all configured weekdays, manual-only instance repair, dismissal persistence and existing workflow regressions. Emulator/lint final results are recorded in the PR.

Physical acceptance: install the debug build without clearing app data; tap Tasks on Sunday and verify the seven responsibilities and saved notes. October items should be absent from active totals but labeled archived in saved history. Complete or dismiss a task, reopen, and check Monday's responsibilities plus only unresolved November sources. Check TalkBack and large fonts on the actual device. No merge, physical-device deployment, signed release, app-data clearing or backup restoration testing is performed.
