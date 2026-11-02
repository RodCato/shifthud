# MVP-006D.6 — Daily navigation and weekly Frozen planning

Dependency: `codex/mvp-006d5-daily-tasks` at `8db74274b905f19c9ef7c37930216c3fef75c25a` (PR #38). This continues the existing Frozen DataStore and task identity mappings. No dependency is merged.

## Navigation and previews

Tasks starts at the device's local date. Previous/next arrows navigate calendar days across month/year boundaries. Today returns directly to the current checklist. The date controls and Week View are visible above the checklist; tools/history remain secondary.

Future dates are read-only. `frozenPlanningRows` combines saved records through today, any already-saved records for the target day, and missing target template definitions without writing them. It does not generate intervening days or assume those future routines will be unfinished. Source dates and notes remain visible; carryovers are labeled projected. Reactive task changes immediately update projections. When that day arrives, normal idempotent initialization writes only the missing stable template IDs.

Week View derives seven days using the existing Saturday–Friday `workWeekFor` helper. It never initializes the days being displayed. Each row shows the workflow focus/definition, recorded completed/outstanding counts or future-preview counts, and active/projected carryovers. Schedule data is the existing reactive repository list. Times and total scheduled span use actual saved shifts, including multiple shifts and overnight/DST duration. The span is explicitly labeled **before lunch**, not paid hours. Missing schedules say **No saved shift**, never assume a permanent day off. A day tap opens its checklist or read-only preview.

## Friday and KE freight

Friday's eight tasks are the user-provided inspection, expected-shipment review, priority backstock, freezer/equipment preparation, receiving verification, stocking, consolidation, and unfinished-freight handoff. The first task checks for unworked Thursday KE/vendor freight; it does not assert freight exists or create an official receiving record. Its reference distinguishes already-verified freight from unverified merchandise. Thursday and Saturday retain no invented routine.

A separate per-date Friday registration marker repairs a Friday visited by an older version when Friday had no template. It adds only missing stable Friday IDs and preserves manual tasks and notes. All tasks start incomplete.

Explicit equivalence maps Friday backstock to existing backstock work and Friday consolidation to Wednesday's consolidation definition. Receiving and freight tasks from separate deliveries remain distinct. No title-similarity matching is used.

## Freight continuity and associate resolution

Use existing **Edit task / notes** for freight origin/date, approximate remaining quantity and handoff details. There is no shipment inventory, mandatory pallet form, quantity default or case-count requirement. Original dates/IDs and notes persist across Saturday and other unscheduled days without copies.

**Resolved by another associate** sets an optional `resolvedByOtherAt` timestamp on the unfinished sources of the actionable row. It does not change the original task state to Completed or add a personal completion event. Active projections exclude resolved sources, while saved history displays the attribution and time. Its history checkbox is disabled to avoid treating that resolution as a personal completion. Dismissal remains available separately.

The new optional timestamp defaults to null for old JSON. No Room migration, payroll/timekeeping changes, backup implementation changes, notification changes or OCR changes are introduced. The November 1 archival boundary remains in force.

## Verification and acceptance

Unit coverage includes Friday registration repair, conditional KE inspection, independent task IDs, Friday-to-Sunday carryover persistence, notes/restart, associate attribution, pure future projections, arrival without duplicate IDs, Saturday–Friday/year boundaries, schedule spans and DST, and reactive source-state changes. Device tests exercise navigation, future/Week View persistence isolation, Today, and associate resolution. Execution results are reported in the PR; building a device test is not claimed as executing it.

Physical checks:
1. Tap Tasks; confirm today opens. Tap next, then Today, including a month/year boundary.
2. Open Week View; inspect all seven days, saved shift times, and read-only future rows. Tap a day.
3. On Friday, confirm eight tasks; inspect KE reference without entering a fictitious shipment.
4. Add a synthetic freight note; leave it unfinished through Saturday and verify its original date and note Sunday.
5. Resolve it by another associate and inspect attribution in saved history; verify no personal completion is recorded.
6. Check narrow screens, large fonts, TalkBack, midnight/time-zone changes and reopening the app.

No merge, physical-device deployment, signed release, app-data clearing or backup restoration testing is authorized by this implementation.

Executed verification: debug build and Android-test APK passed; 1,020 unit tests passed in each debug/release variant. Lint reported zero errors, 17 existing warnings and one hint. Thirteen emulator tests passed across Daily, Monday, Tuesday and Wednesday workflows, including future/Week View navigation and persistence isolation, Friday associate resolution, the seven-record fresh-start fixture, Endcap review/import regressions, and large-font carryover presentation. The initial obsolete Friday-undefined assertion was updated for the newly requested template; the complete unit suite then passed. Backup restoration tests were excluded. Manual TalkBack, device-specific layouts and actual-device overnight behavior remain acceptance checks.
