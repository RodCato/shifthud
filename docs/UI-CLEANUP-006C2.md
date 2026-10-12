# MVP-006C.2 Dashboard and Schedule cleanup

## Scope and dependency

Based on `mvp-006c1-schedule-planning` at `3874a67` (PR #17 still open). No existing branch is merged or rebased. All changes are presentation helpers, Compose UI, tests and documentation. Domain calculations, repositories, preferences, notifications, payroll, historical records and Room are unchanged.

## Presentation

Dashboard's weekly card shows worked/target, progress, remaining paid time, projected total, shortfall/excess and any authoritative final-day clock-out recommendation. The Dashboard lunch-edit button is removed. Schedule retains **EDIT WEEKLY LUNCH PLAN**, its selected-week navigation and the existing editor/persistence.

**View Planning Details** retains employer metadata completeness, lunch assumption, actual/remaining/component durations, schedule warnings, final-day assumptions, rounding notes and manager approval disclaimer. The existing accessible expandable component preserves expansion through saved state and exposes expanded/collapsed semantics. Shift actions, lunch timer/return controls, completed paid/gross and next-shift placement are unchanged.

Employer wording is shared by Schedule, Dashboard details and the lunch dialog:

- All valid imported net hours: `Scheduled: … (employer-reported)`.
- No valid net hours: `Scheduled: … (estimated)`.
- Mixed sources: `Scheduled: ~… (partially estimated)`.
- No saved shifts: scheduled hours remain unknown.

These labels reuse the prior Schedule presentation adapter's totals and validation without changing calculations. Completeness counts are available only in expandable details.

Schedule owns the open lunch dialog outside the lazy list. A recreation regression found during large-font testing showed that an offscreen item could otherwise remove the dialog. The existing editor, selected week and save path are preserved; its saved open state no longer depends on item visibility.

## Rounding

Existing duration formatting floors worked and projected totals to minutes. Flooring both worked and remaining could visually lose a minute. Weekly remaining and shortfall displays now round **up**, so a whole-minute target agrees with displayed worked plus remaining. Positive excess below one minute is shown as `<1m`, rather than `0m`.

Independently rounded component rows can still differ from their aggregate by one minute; details explain this. Clock-out recommendations retain their existing seconds when needed. No stored timestamp, precise duration, payroll rounding or overtime rule changes.

## Physical acceptance

1. Install the debug APK in place without uninstalling or clearing data.
2. Dashboard: check the compact weekly card and confirm there is no Edit Lunch Plan button. Expand Planning Details and verify assumptions, completeness and warnings remain accessible.
3. Schedule: navigate to the desired week, verify estimated/employer-reported wording, and edit that week's lunch plan. Cancel must leave it unchanged; saving must update that week only.
4. Check working, on-lunch, completed and off-today states. Primary shift controls and timers should retain their positions.
5. Check 200% font, landscape and spoken TalkBack. Expand/collapse controls should announce state; every action must remain reachable by scrolling.
6. Restart the app and confirm selected-week planning and existing records remain intact.

No backup/restore changes or restoration testing, signed release build, Frozen Operations, historical data changes or payroll changes are included.

## Verification

- Full scoped Gradle build, debug APK and instrumentation APK passed. Debug APK installs in place without clearing data.
- 815 tests per debug/release unit variant, 1,630 executions, zero failures. As in prior sprints, a temporary init script excludes the 18 backup restoration tests per variant; the backup restoration device class and release packaging are excluded. No repository assertions or test configuration were disabled.
- Lint: zero errors, 14 warnings and one hint. Diff check passed.
- Final emulator suite: **23 tests passed**, including large-font Dashboard, countdown/excess/recommendation, restored expandable details, Schedule dialog recreation and existing shift-state coverage. The initially failing recreation assertion was preserved and passes after lifting the dialog outside the lazy list.
- Temporary fixture checks passed for selected-week saving, adjacent-week independence and real process restart. Schedules, completed punches and payroll fixture were unchanged. Final normal-font Dashboard was visually inspected.
- Original emulator files were restored and byte-verified; display settings were returned to their prior values. No physical-device data was accessed. File cleanup did not exercise the application's backup restoration feature.
- Spoken TalkBack and in-place physical-device acceptance remain the checklist above.
