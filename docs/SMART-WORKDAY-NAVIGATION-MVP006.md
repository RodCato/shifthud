# MVP-006 — Smart scheduled workday navigation

Tasks now waits for the persisted schedule to load, then selects today if it contains a valid scheduled shift; otherwise it selects the earliest future shift date. If no future shift exists, it selects today. Weekday templates never determine whether the user works. Equal start/end times retain the domain's existing overnight interpretation.

Selection is local to the current screen visit. Schedule emissions do not change the selected date. Leaving and entering Tasks again uses the latest schedule. Previous/next arrows still move by calendar day, Today returns to the current calendar date, and Next Shift goes to the earliest saved shift strictly after today. The shortcut is omitted if unavailable or already selected. Shortcut buttons wrap at narrow widths. An automatically selected today still follows midnight as before; manual browsing is not reset at midnight.

Future days reuse `frozenPlanningRows` and `FrozenFuturePreview`. No future `openDaily` call occurs: viewing a future checklist does not persist tasks, complete work, start a session, or increment floats. Existing carryover projection scans the stored source tasks across intervening days off; no carryover records are deleted or duplicated by date selection.

Widget Tasks navigation passes a one-use “open today” hint, keeping the widget's current-day behavior. It does not change widget rendering, task actions, floats, timekeeping, or persistence. No Room migration, payroll, lunch, scheduling, notification, or task-store change is included.

## Changed files

- `FrozenWorkdayNavigation.kt`: pure initial/next scheduled-date selection.
- `FrozenDailyScreen.kt`: one-time schedule-aware entry, loading guard, manual navigation, midnight handling.
- `FrozenPlanningScreen.kt`: compact wrapping Next Shift shortcut.
- `MainActivity.kt`, `WidgetNavigation.kt`: consume/set the one-use widget today hint.
- `FrozenWorkdayNavigationTest.kt`: schedule-selection and carryover projection regressions.
- `FrozenDailyDeviceTest.kt`: delayed load, read-only future entry, live schedule edits, calendar arrows, Today/Next Shift, re-entry and widget today checks.

## Verification

Debug APK and Android test APK builds passed. All 1,075 unit tests per Debug/Release variant and all 11 FrozenDailyDeviceTest emulator cases passed. Lint reported zero errors, 17 existing warnings and one hint; git diff check passed. Backup restoration tests remain excluded; no app data is cleared and no physical device is deployed to. The installable debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

Physical-device check: on an unscheduled day, open Tasks and confirm the next scheduled date and read-only preview appear; browse back to the off-day, tap Today, then Next Shift. Edit/move a schedule and re-enter Tasks. Confirm the home widget still shows today's tasks and controls.
