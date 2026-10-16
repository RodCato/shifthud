# MVP-006C.9 — Daily scheduled hours

Base: `mvp-006c8-scheduled-hours-summary`, `383c3c0f8693999c84571b9d3ab55978bc067e81` (PR #24 remains open). Main was fast-forward checked; this PR depends on C.8 and does not merge the stack.

Collapsed Schedule rows show **projected paid** time and secondary planned lunch for future shifts. Linked completed/active sessions retain their recorded lunch presentation with an explicit **actual paid** label. Active work remains in progress; its remaining projection remains in the existing weekly summary. No daily table or saved completion state is introduced.

The presentation adapter calls the existing `clockOutPlan` with the full week's sessions and each schedule. It resolves the original full-week final-day lunch policy before each call, using an in-memory schedule copy only. No repository write occurs. It uses `futurePaid`, not the row call's aggregate actuals, which include other sessions. Weekly calculations are unchanged. For non-overlapping schedules, weekly worked time plus daily remaining projections reconciles exactly with weekly projected time. Completed row values are recorded actuals, not the zero remaining projection.

Overlapping schedules, missed punches and unlinked overlapping work show an unavailable/review state instead of presenting additive estimates that disagree with the planner. Unscheduled actual work contributes to the weekly total but never to a scheduled row. Existing actual rows can include linked records whose clock-in belongs to another week; the weekly engine still assigns actual work by clock-in week. These are different scopes, not grounds for changing punches or weekly calculations. Existing interval-union rules also prevent counting overlapping actual work twice in the weekly total. Displayed whole minutes can differ by rounding from a total of timestamp-precision durations.

No Room schema/migration, payroll, planner-engine, punch persistence, weekly preferences, backup/restore, OCR/import, notification, Garmin, widget or Frozen Operations changes. No flexible per-day lunch allocation. Only debug APKs are assembled; no signed release. No backup restoration tests are run.

## Physical acceptance

1. Install the debug APK over the existing app; never uninstall or clear data.
2. Open Schedule, navigate to a future Saturday–Friday week and check collapsed projected paid/lunch values against saved schedules.
3. For the five example shifts (7h, 8h, 9h, 9h, 9h), save 35m weekly lunch: rows show 6h25m, 7h25m, 8h25m, 8h25m, 8h25m; weekly personal projection is 39h5m and shortfall 55m. Employer metadata remains 37h.
4. Change to 60m: future rows update immediately; completed actuals and lunch punches remain unchanged. Cancel an edit and navigate adjacent weeks to check isolation.
5. Inspect completed, working, on-lunch and overnight rows. Expand/collapse and edit behavior remains available; checkboxes remain read-only.
6. Use 200% fonts, dark theme and TalkBack on a physical device. Confirm full combined date/time/paid/lunch descriptions, expansion action, scrolling and no clipping.
7. Restart the app and confirm saved preferences and actual records remain present.

## Changed files

- `app/src/androidTest/java/com/shifthud/ui/ScheduleActualsDeviceTest.kt`
- `app/src/androidTest/java/com/shifthud/ui/ScheduleCompletionDeviceTest.kt`
- `app/src/androidTest/java/com/shifthud/ui/ScheduleNavigationDeviceTest.kt`
- `app/src/androidTest/java/com/shifthud/ui/SchedulePlanningDeviceTest.kt`
- `app/src/androidTest/java/com/shifthud/ui/UiRefinementTest.kt`
- `app/src/main/java/com/shifthud/ui/schedule/ScheduleActuals.kt`
- `app/src/main/java/com/shifthud/ui/schedule/SchedulePlanning.kt`
- `app/src/main/java/com/shifthud/ui/schedule/ScheduleScreen.kt`
- `app/src/test/java/com/shifthud/ScheduleActualsTest.kt`
- `app/src/test/java/com/shifthud/ScheduleCompletionRepositoryTest.kt`
- `app/src/test/java/com/shifthud/SchedulePlanningTest.kt`
- `docs/DAILY-SCHEDULED-HOURS-006C9.md`

## Verification results

- `assembleDebug test lint assembleDebugAndroidTest`: passed using the existing test exclusion for `BackupRoundTripTest`; no backup restoration testing.
- 873 unit tests per debug/release variant (1,746 executions), zero failures/errors.
- All 34 eligible emulator tests passed across the full suite and targeted rerun: 31 passed in the final full run; three selectors needed to distinguish clickable rows from the weekly planned-lunch text, then all three passed on rerun. Production APK was unchanged for that selector correction.
- Narrow 320dp, 100%/200% font wrapping, dark screenshots, read-only checkbox/expansion semantics, week navigation, actual updates, and persisted-record reconstruction covered. Spoken physical TalkBack remains a manual check.
- Lint: zero errors, 16 pre-existing warnings and one hint.
- Installed with `adb install -r`; no uninstall or data clearing. Read-only before/after comparison confirmed every original Room table's rows and user preference bytes unchanged. Emulator notification permission, font scale and night mode restored; emulator stopped.
- `git diff --check` passed. Only presentation code, tests and this document changed.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Screenshots (local build artifacts): `app/build/reports/daily-hours/week.png`, `app/build/reports/daily-hours/projected-large.png`.
