# MVP-006C.11 — Dual time display

Base: `mvp-006c10-schedule-visual-hierarchy`, commit `abd7bf627d6c3955f7d400c21f584d5ccec89cbe` (open PR #26). Main was fast-forward checked. No dependency PRs merged.

## Implementation

`ui/time/DecimalTime.kt` formats exact Duration seconds plus nanoseconds with BigDecimal division and explicit HALF_UP rounding to two places. NumberFormat supplies the locale decimal separator and exactly two digits; formatted strings never feed calculations. The display utility is separate from the unchanged payroll decimal/reconciliation engine.

Settings → DISPLAY → Show decimal hours defaults to enabled and uses the existing DataStore. It only writes the new display key; it invokes no planning, notification or widget refresh callback. Disabling removes the new secondary labels. No Room migration or backup format change.

Schedule rows carry their existing exact paid Duration into presentation, retaining hours/minutes as primary, mint paid values, blue lunch, status indicators and row expansion. The selected week's personal projected total gets its decimal equivalent. Employer scheduled metadata remains labeled separately and is not treated as an employer timecard.

Dashboard Weekly Hours shows exact worked and projected totals from the existing planner. Analytics period totals/daily rows and historical day/session/time-record details show secondary decimal paid hours from the same durations as their primary displays. No new timer or polling.

Paychecks uses existing `reportedHours` and the matching personal estimate. Its additional comparison is **ShiftHUD minus Publix**; the existing payroll reconciliation below remains **employer minus ShiftHUD**, explicitly labeled. Differences are calculated by subtracting exact seconds and employer decimal hours converted arithmetically to seconds before rounding the display; no employer punches are inferred or saved. Missing employer data omits the comparison. No new timecard import.

## Precision disclosure

Personal punches can include seconds. Employer values displayed in hundredths may already be rounded and do not reveal original seconds. `8.01h` mathematically represents 8h0m36s, but is not proof of those original punch timestamps. Daily rounded labels can sum differently from a weekly total rounded once from exact durations. Differences may reflect timing, rounding or employer rules; the app never forces totals to match. This explanation is available in Settings and the paycheck comparison.

## Physical acceptance

1. Upgrade-install `app/build/outputs/apk/debug/app-debug.apk`; never uninstall or clear data.
2. Confirm Show decimal hours starts enabled, then disable/re-enable it. Hours/minutes remain primary. Restart and confirm the selection persists.
3. In Schedule, inspect completed actuals, active work/lunch and future projections; navigate weeks and change a weekly lunch plan. Decimal labels must follow the same exact data without altering actual punches.
4. Compare Dashboard weekly worked time, Analytics historical week/day totals and time-record details. Example 8h4m30s displays 8.08 decimal hours while the primary remains 8h4m.
5. In Paychecks, select a period with existing reported hours. Confirm clearly separate sources and signed difference. Missing employer hours must not produce a fabricated comparison.
6. Check narrow-screen scrolling, 200% fonts and spoken TalkBack labels on the physical device. No clipped paid values or unintended checkbox actions.

No historical records, payroll calculations, timekeeping, lunch planning, weekly targets, notifications, Garmin, widgets, OCR/import, backup/restore or Frozen Operations behavior changed. No signed release or backup restoration testing.

## Verification results

- `assembleDebug test lint assembleDebugAndroidTest` passed. 879 unit tests per debug/release variant (1,758 executions), zero failures/errors. Existing backup restoration exclusions retained.
- All 38 eligible emulator tests passed across the full suite and targeted reruns. A transient Compose measure/layout failure in the completion test passed unchanged in isolation. The dialog font test was corrected to use actual Android system font configuration instead of changing a parent Compose density that does not represent the dialog's separate window.
- Actual 200% Android font scale verified against the text layout's measured scale, with visible, scrollable and unclipped decimal text. Schedule tests cover 320dp/200% layout, row expansion, labels, week navigation and live lunch changes. Dashboard and historical-record screenshots inspected.
- Settings UI toggle passed. Separate prepare/force-stop/verify instrumentation phases confirmed the preference survives an actual app-process restart. The original key (including absence) was restored afterward.
- Lint: zero errors, 16 existing warnings and one hint. `git diff --check` passed.
- Upgrade-installed only (`adb install -r`), without uninstall or data clear. Before/after read-only comparison confirmed all original Room table contents and user preference bytes unchanged. Emulator font scale, night mode and notification permission restored; emulator stopped.
- Screenshots are in-memory test fixtures using real production components, not seeded user records. Local artifacts under `app/build/reports/dual-time/`: `schedule.png`, `dashboard.png`, `history.png`, `history-large.png` (actual 200% system font), `schedule-large.png`.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.

Physical spoken TalkBack, device-specific font/display rendering, and comparison against an actual employer timecard remain acceptance checks. No claim is made about hidden employer seconds or official payroll agreement.

## Changed files

- `app/src/androidTest/java/com/shifthud/ui/DashboardPlanningDeviceTest.kt`
- `app/src/main/java/com/shifthud/data/preferences/ShiftPreferences.kt`
- `app/src/main/java/com/shifthud/ui/analytics/AnalyticsScreen.kt`
- `app/src/main/java/com/shifthud/ui/dashboard/TimeRecordDialog.kt`
- `app/src/main/java/com/shifthud/ui/dashboard/WeeklyTargetSection.kt`
- `app/src/main/java/com/shifthud/ui/dashboard/WorkDayDetailsDialog.kt`
- `app/src/main/java/com/shifthud/ui/payroll/PaychecksScreen.kt`
- `app/src/main/java/com/shifthud/ui/schedule/ScheduleActuals.kt`
- `app/src/main/java/com/shifthud/ui/schedule/SchedulePlanningSummary.kt`
- `app/src/main/java/com/shifthud/ui/schedule/ScheduleScreen.kt`
- `app/src/main/java/com/shifthud/ui/settings/SettingsCatalog.kt`
- `app/src/main/java/com/shifthud/ui/settings/SettingsScreen.kt`
- `app/src/test/java/com/shifthud/SettingsCatalogTest.kt`
- `app/src/androidTest/java/com/shifthud/ui/DecimalDisplayDeviceTest.kt`
- `app/src/main/java/com/shifthud/ui/time/DecimalHoursText.kt`
- `app/src/main/java/com/shifthud/ui/time/DecimalTime.kt`
- `app/src/test/java/com/shifthud/DecimalPreferencesTest.kt`
- `app/src/test/java/com/shifthud/DecimalTimeTest.kt`
- `docs/DUAL-TIME-DISPLAY-006C11.md`
