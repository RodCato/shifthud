# MVP-006C.13 — Punch diagnostics

Dependency: open PR #28, `mvp-006c12-actual-punch-schedule`, base `2f62ce1d00243b6783940cdd9245732a76311ffb`. Main was safely fast-forward checked; no PRs merged.

## Presentation and calculation authority

Collapsed clocked times now show hours/minutes only. The secondary scheduled line may be omitted when both endpoints match at minute precision. Expanding the row reveals a separately collapsible **Punch Diagnostics** section (closed by default), which always retains scheduled timestamps, even when omitted from the overview.

Diagnostics display actual clock-in, lunch-out, lunch-in and clock-out in 24-hour `HH:mm:ss`, with dates for punches outside the scheduled date. Missing punches say `Not recorded`; active durations explicitly identify their as-of time. No scheduled timestamp substitutes for an actual punch.

Every duration component is returned by the existing `ShiftEngine.durations`; the diagnostic adapter only formats and aggregates these results. A read-only consistency check flags any exact store-minus-lunch versus paid mismatch rather than adjusting calculations. Multiple linked sessions are individually numbered, with their own punches/lunch/durations and an aggregate over those same explicitly linked sessions. Same-date unscheduled work is not silently associated. The model supports one lunch per WorkSession; multiple lunches are displayed across actual sessions without adding a new tracking model.

Overview timestamps omit seconds, not round stored values. Diagnostic timestamps and duration text omit fractional seconds; existing timestamps and exact durations are never changed. If source punches contain subseconds, the UI explains that displayed components can differ by a second due to omitted fractions. Decimal hours use the existing exact-duration formatter: two decimal places, HALF_UP. Independently rounded daily decimals need not sum to the weekly exact-duration total rounded once. Employer hundredths do not expose hidden punch seconds or establish an undocumented rounding policy.

The synthetic October 12 fixture (04:02–13:00:11, lunch 09:50–10:47) produces 08:58:11 store, 00:57:00 unpaid lunch, 08:01:11 paid through the unchanged engine. It is not production seed data or a correction to personal records.

## Physical acceptance

1. Upgrade-install the debug APK; do not uninstall or clear data.
2. Confirm the Schedule overview has no punch seconds/milliseconds and actual/scheduled minute discrepancies remain visible.
3. Expand a completed row, then Punch Diagnostics. Confirm actual second values, lunch interval, scheduled comparison and paid/decimal totals against recorded punches.
4. Check an exact minute match with nonzero seconds: overview stays compact while diagnostics retain the discrepancy.
5. Inspect multiple sessions/lunches, overnight dates and an active lunch with missing end punches.
6. Verify dark theme, 200% font, narrow-screen scrolling, TalkBack expand/collapse announcements and decimal preference behavior. Diagnostics intentionally retain decimal details regardless of the overview preference.

## Scope

No timekeeping or payroll calculation changes, historical record modifications, Room migration, backup/restore changes, schedule import, notifications/Garmin, widget or Frozen Operations changes. No backup restoration testing. No signed release build.

## Verification artifacts

- `assembleDebug test lint assembleDebugAndroidTest`: passed using JDK 17; backup restoration unit class excluded.
- 891 unit tests passed per debug/release variant, zero failures/errors/skips.
- Lint: zero errors, 16 existing warnings and one hint.
- Diagnostics rendering tests cover default collapse, expansion semantics, 320dp width, 200% font, individual lunches/sessions and decimal overview preference. Screenshots were visually inspected.
- Screenshots: `app/build/reports/punch-diagnostics/schedule-punches-*.png`, including `different-with-lunch`, `diagnostics-punches`, `diagnostics-durations`, `diagnostics-large-font`, `diagnostics-lunch-one`, `diagnostics-lunch-two`, `diagnostics-multiple-total`, `multiple` and `scheduled`. These use in-memory fixtures and real Schedule components.
- Debug APK: `/Users/crod/Documents/ChatGPT/Publix work app/app/build/outputs/apk/debug/app-debug.apk`. Upgrade-installed with `adb install -r`, without uninstall/data clear.

Changed files: `SchedulePunches.kt`, `ScheduleScreen.kt`, new `PunchDiagnostics.kt` and `PunchDiagnosticsView.kt`; updated `SchedulePunchesTest.kt` and `SchedulePunchesDeviceTest.kt`; new `PunchDiagnosticsTest.kt`; this document.

Final emulator verification: 39 eligible tests passed on Pixel 9 API 37.1; backup restoration instrumentation class excluded. Every original Room table's contents and saved user preferences matched the read-only pre-install baseline. Emulator theme/font/notification permission restored after testing. Spoken TalkBack and physical-device checks remain in the acceptance checklist above.
