# MVP-006C.8 — Scheduled hours summary

## Base and scope

Branch: `mvp-006c8-scheduled-hours-summary`. Base: `2b7f999273ff6abce1751612b0431b2414e834a5` on `mvp-006c7-schedule-actual-lunch` (open PR #23). Origin was fetched and clean local main was fast-forward checked before selecting the unmerged C.7 dependency. No PRs were merged.

Only Schedule presentation and regression tests change. No Room/schema/migrations, punches, payroll, weekly planning engine, lunch records/alerts/snooze, Garmin forwarding, widgets, OCR/import, backup/restore, or Frozen Operations changes. Flexible per-day/extended lunch allocation remains deferred. No signed release build or backup restoration tests. Existing installation and data are preserved.

## Summary

Immediately below the selected week/navigation, scheduled paid hours use the strongest typography. The next line identifies employer-reported schedule hours or the number of rows with employer data when any calculation is an estimate. An empty week remains unknown for scheduled hours, not an invented employer zero.

The secondary personal projection, weekly lunch minutes per shift, target and projected shortfall/excess are visible without expansion. The progress bar explicitly describes projected paid progress toward the weekly target; it does not claim actual completion. Values above target retain the excess text while the bar caps at 100%.

The existing `schedulePlanning` adapter and `ClockOutPlan` supply all totals, sources, actual-punch precedence and week membership. Duration formatting retains existing floor/ceiling conventions. Nothing is calculated from display strings or written back to records. The existing fallback schedule estimate is labeled; it is never silently replaced by the personal projection. Saved-record coverage remains visible; existing planner issues remain in Planning Details.

Supporting actual/future breakdowns, gross estimate, source details and assumptions are in the default-collapsed Schedule Planning Details after the week’s rows. Add Shift is beside the title; the weekly lunch editor stays with the summary; screenshot import remains available after the rows. Redundant section headings/divider and the repeated shift-count line were removed; the completion summary retains the shift count. Selected dates, shift count, completion indicators, C.7 actual lunch/paid rows, edit/import actions and week navigation are preserved.

## Verification

`assembleDebug test lint assembleDebugAndroidTest` passed, excluding `BackupRoundTripTest` as requested. Unit tests: 867 per debug/release variant (1,734 executions), zero failures. Lint: zero errors, 16 existing warnings and one hint.

Existing tests cover empty/partial/completed weeks, active work, overnight schedules, missing/invalid employer metadata, independent adjacent plans and actual-punch precedence. Added tests cover multiple recorded lunch intervals plus unscheduled work, exact equality with the Dashboard's existing planner for identical current-week inputs, lunch changes preserving employer facts, and above-target projections.

UI coverage checks source labels, empty/estimated/reported totals, shortfall/excess/exact target, progress semantics, selected-week/count/value updates, default-collapsed details, typography hierarchy and text overflow at 320dp/200% font scale. Screenshot sample data is in memory; it is not production seed data.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Local rendered screenshots: `app/build/reports/scheduled-hours/normal.png` and `large-font.png`; `week.png` shows the selected week in the full Schedule screen.

## Physical-device acceptance

1. Install the debug APK over the current app without uninstalling or clearing storage. Confirm saved records and preferences remain.
2. Open Schedule. Confirm selected dates/count and scheduled hours are prominent; personal projected hours, planned lunch, target difference and progress are visible without opening details.
3. Navigate previous/current/future weeks. Compare totals with Dashboard/planner for matching inputs. Check the correct independent weekly lunch plan appears each time.
4. Check imported employer hours, mixed/missing metadata, and an empty week. Estimates must remain labeled and scheduled hours must not be replaced by personal projections.
5. Change a weekly lunch plan using the existing editor. Confirm the personal projection updates while employer schedule facts and actual punches remain unchanged.
6. Check completed shifts, recorded lunches, unscheduled work and overnight shifts. C.7 collapsed actual lunch/paid information and completion indicators must remain intact.
7. Expand Schedule Planning Details for supporting breakdowns; verify it starts collapsed. Confirm edit/delete/import and weekly navigation still work.
8. Check 200% fonts, a narrow device and TalkBack: text wraps, projected progress has an accessible label, and excess/shortfall meanings do not depend only on color. OEM rendering and spoken TalkBack remain physical-device checks.

## Changed files

- `ui/schedule/SchedulePlanningSummary.kt`: compact hierarchy, source labels, projected target progress and collapsed supporting details.
- `ui/schedule/ScheduleScreen.kt`: remove repeated heading/count and tighten header spacing.
- `SchedulePlanningTest.kt`: unchanged-planner equivalence and additional actual/unscheduled/above-target fixtures.
- `ScheduledHoursSummaryDeviceTest.kt`: source/target states, typography, progress accessibility and screenshots.
- `SchedulePlanningDeviceTest.kt`: selected-week/count/value checks and full-screen screenshot.
- `ScheduleCompletionDeviceTest.kt`, `ScheduleNavigationDeviceTest.kt`: target the shift's status text specifically rather than the new scheduled-hours heading.
- This acceptance document.

Final emulator suite: all 33 eligible tests passed, including completed-shift/C.7 lunch visibility, week navigation, schedule imports, notifications and widgets. Normal/200% summary rendering passed. The full 320dp Schedule screenshot shows two complete scheduled rows below the summary; the navigation test now asserts both remain displayed. Tests use in-memory samples or existing isolated fixtures, not production seed records.

The final focused visibility/navigation rerun passed (2 tests). A read-only before/after comparison confirmed all original Room table contents and saved user preferences were unchanged. Original font/theme/notification permission were restored and the emulator stopped. No restoration operation was performed.
