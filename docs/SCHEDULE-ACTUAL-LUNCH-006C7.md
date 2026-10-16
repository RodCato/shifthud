# MVP-006C.7 — Schedule actual lunch visibility

## Base and scope

Branch: `mvp-006c7-schedule-actual-lunch`. Base: `9949935731dfe64c5ac2248202b1d049bf35954b`, the head of open C.6 PR #22 (`mvp-006c6-smart-widget`). Local main was fast-forward checked after fetching origin; the unmerged C.6 dependency remains the correct integration base. No PRs were merged.

This is derived Schedule presentation only. No schema/migration, punch persistence, payroll calculations, weekly projection, planning logic, notification/snooze/Garmin, widget, screenshot import, or backup/restore implementation changes. No signed release build. No uninstall, data clear, or backup restoration tests.

## Display rules

- Collapsed worked rows show the existing completion/in-progress indicator, scheduled date/times, authoritative paid duration, and actual lunch punches.
- Only explicit `scheduledShiftId` associations contribute. Unscheduled or same-day unrelated work cannot fill a row.
- Paid time uses the existing ShiftEngine durations summed across linked sessions. Lunch is unpaid; active lunch remains paused. Formatting floors to whole minutes using the existing recordDuration helper without changing underlying precision.
- Completed lunch shows recorded start/end and duration. Dates are included for punches outside the scheduled start date, including overnight/week-boundary work. Time formatting respects the device's 12/24-hour preference and locale.
- Missing lunch shows “No lunch recorded”. A missing lunch end remains “Lunch in progress” with the recorded start and elapsed duration. No estimated end or planned lunch is substituted. Domain validation already excludes invalid completed punch sequences.
- The model supports one lunch per WorkSession. When a schedule has several linked sessions with completed lunches, the collapsed row shows total duration/count, and expansion lists each recorded interval. An active interval is identified separately.
- Unworked rows show the appropriate week's `weeklyMinutes` value, including weekly overrides. Legacy per-shift values never determine that display.
- Advanced Schedule Details remains collapsed by default with retained legacy values and employer metadata. Scheduled dates/times are no longer repeated immediately under the same header.
- Employer schedule paid time retains its explicit source label. No employer decimal timecard data is imported, reconciled, or written to actual punches.

## Verification

Unit coverage includes actual versus planned values, no lunch, weekly overrides/week navigation, active work/lunch, multiple linked intervals, overnight and Saturday boundary, early/late clock-out, unfinished records, corrections/deletion, and model reconstruction. Repository coverage closes/reopens Room and verifies actual paid/lunch presentation from reloaded records. Existing completion/navigation emulator tests are retained and updated for the richer summary; persisted lunch values remain visible through UI recreation.

The read-only rendering test uses a 320dp width at normal and 200% font scale, verifies collapsed actual information and default-collapsed Advanced Details, and checks text layout for overflow. Its sample is held in memory. Full eligible emulator regression tests exclude only backup restoration acceptance, as requested. Device process death/reboot is not simulated by the in-memory rendering test; database reopen and UI recreation are covered separately.

## Physical-device checklist

1. Install the debug APK over the existing ShiftHUD app. Do not uninstall or clear storage. Confirm records/preferences remain intact.
2. Open a week with completed shifts. Without expansion, compare paid hours and lunch start/end/duration with History. A different planned lunch must not change actual values.
3. Check a completed shift without lunch: “No lunch recorded”. Check a working shift before lunch and an active lunch; neither should display a fabricated end time.
4. Check multiple linked work sessions. Confirm total lunch duration and expand to inspect individual intervals. Unscheduled work must not populate another shift's actuals.
5. Navigate Friday/Saturday and overnight shifts. Change the selected week's plan and confirm only upcoming planned values change. Verify 12/24-hour formatting.
6. Expand Advanced Schedule Details: legacy lunch and employer metadata remain available, labeled separately. Screenshot import and edit/delete actions must still work.
7. Repeat at large fonts/narrow width and with TalkBack. Scroll where needed; verify wrapping, completion status, paid time, and lunch text. Close/reopen the app and confirm values derive from the same saved punches.

Build results: `assembleDebug test lint assembleDebugAndroidTest` passed with backup restoration unit tests excluded. 864 tests per debug/release unit variant (1,728 executions), zero failures. Lint: zero errors, 16 existing warnings and one hint. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Rendered normal/200% screenshots are under `app/build/reports/schedule-actuals/` (local build artifacts).

## Changed files

- `ui/schedule/ScheduleActuals.kt`: pure derivation and localized actual/plan presentation.
- `ui/schedule/ScheduleScreen.kt`: collapsed summary and expanded individual intervals.
- `ui/components/ExpandableSection.kt`: optional rich subtitle and full-width text wrapping.
- `ScheduleActualsTest.kt`, `ScheduleCompletionRepositoryTest.kt`: presentation and persisted-record regression coverage.
- `ScheduleActualsDeviceTest.kt`, `ScheduleCompletionDeviceTest.kt`, `ScheduleNavigationDeviceTest.kt`, `UiRefinementTest.kt`: rendering/accessibility, live Room updates, restoration and existing actions.
- This delivery/acceptance document.

Final emulator results: all 31 eligible tests passed, including Schedule import/navigation/planning, completion, notifications, widget and large-font rendering regressions. The read-only post-test comparison confirmed every original Room table's contents and user preferences were unchanged. Original font/theme/notification permission were restored and the emulator stopped. No backup restoration was performed. Spoken TalkBack and physical-device/OEM rendering remain acceptance checks.
