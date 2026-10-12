# MVP-006C.5 — Visual hierarchy

Based on C.4 (`8ff6b15`, PR #20), with C.2 and C.3 included through the existing dependency chain. No dependency PR is merged.

## UI changes

- Reusable metric presentation: Material 3 headlineLarge/bold primary values, headlineSmall/semibold secondary values, labelLarge labels and bodyMedium support. Existing page title tokens and light/dark colors are retained. There are no fixed text heights or custom font-scale overrides in production.
- Dashboard: actual worked hours and target lead the weekly card, followed by the existing progress/remaining display. A divider separates projected total, excess/shortfall and estimated clock-out. Assumptions remain in Planning Details; redundant actual/remaining lines there are removed. Weekly gross has its own secondary metric. History, analytics and calendar retain their expandable navigation.
- Schedule: employer schedule heading, selected week and completion count precede employer paid hours. A divider and Personal projection heading distinguish the user projection, target, excess/shortfall, actual/remaining time, lunch and estimated gross. Sources remain expandable. The existing lunch planner and C.4 derived indicators are unchanged.
- Settings: Notification Diagnostics is collapsed by default inside the existing searchable category. Each implemented alert has a TEST button and a separate expandable label for permission, app/channel status, importance, sound, vibration and Android settings. These details are hidden by default. Tests call the same existing notification APIs and channel IDs.
- Expandable headers use semibold title typography. Metrics merge label/value/support in logical accessibility order. Test and disclosure actions are separate, with expanded/collapsed semantics. Layouts remain in their existing single scroll container.

The changes are presentation-only. No WorkSession, schedule, payroll, historical, weekly lunch, notification scheduling, snooze, schema, migration, or backup/restore implementation changes. No signed release or Frozen Operations implementation.

## Acceptance on a physical device

1. Install the debug APK over the existing app; do not uninstall or clear storage. Verify records and preferences remain present.
2. Check Dashboard while off today, before a shift, working, on lunch and completed. Active actions must remain before weekly analytics. Actual hours should be the strongest weekly metric; projected total, excess/shortfall and estimated clock-out remain easy to distinguish.
3. Expand Planning Details, history, analytics and calendar. Verify all previous destinations remain available without duplicating the prominent weekly totals.
4. On Schedule, navigate weeks. Check employer hours versus personal projection, completion counts, checked/active/lunch indicators and unchanged off-day text. Open EDIT WEEKLY LUNCH PLAN and cancel; the existing dialog and selected week must be retained.
5. Search Settings for “test diagnostics” or “Garmin test”. Notification Diagnostics should be collapsed initially. Expand it; every existing TEST action should be available. Open one alert’s details and verify Android settings links. Test alerts at least three seconds apart and confirm real Android delivery; Garmin forwarding must be checked on the paired watch.
6. Repeat at 200% font size, narrow portrait and landscape, in light/dark themes. Scroll to all values and controls. Use TalkBack and keyboard navigation: labels precede values, disclosures announce state, and TEST is a separate action. No warning or completion meaning relies on color alone.
7. Rotate/recreate the screen with a disclosure open and verify restoration. Force-stop/reopen and confirm data/preferences and derived metrics remain intact; a fresh Diagnostics view starts collapsed.

## Files changed

- `ui/components/Metric.kt`, `ExpandableSection.kt`: shared metric and header hierarchy.
- `ui/dashboard/DashboardScreen.kt`, `WeeklyTargetSection.kt`: actual/projection/gross hierarchy.
- `ui/schedule/ScheduleScreen.kt`, `SchedulePlanningSummary.kt`: employer/personal grouping.
- `ui/settings/NotificationTestCenterSection.kt`, `SettingsCatalog.kt`: collapsed diagnostics and search terms.
- `DashboardPlanningDeviceTest.kt`, `SchedulePlanningDeviceTest.kt`, `UiRefinementTest.kt`, `VisualHierarchyDeviceTest.kt`: exact-value, progress, layout, accessibility, notification action and preservation assertions.
- This acceptance document.

## Verification

- Scoped full Gradle build and installable debug APK passed; 849 tests per debug/release unit variant (1,698 executions), zero failures.
- All 29 eligible emulator tests passed at 200% font/dark mode. All seven existing TEST buttons posted through real Android channels, with normal cooldowns and unchanged data/preferences.
- Added assertions cover primary/secondary/label type sizes, text overflow, exact progress value, search, collapsed diagnostics, advanced details and state restoration. Existing UI assertions retain their exact values using the new label/value nodes.
- Lint: zero errors, 16 existing warnings and one hint. Diff whitespace check passed.
- Temporary persisted October 10–16 records verified 2/5 completed, 16h 7m actual, 40h 19m projected and 19m excess using the existing planner. Cold launch reproduced the saved results. These are emulator fixtures only, not shipped defaults.
- Visual review covered 320dp portrait, light/dark themes and large-font landscape scrolling/rotation. The metric overflow regression was fixed by allowing values the full available width. Material color tokens, text warning labels, button semantics and touch targets are retained.
- Original emulator files restored and byte-verified; original notification permission, font, theme and rotation restored. Emulator stopped. No physical-device data accessed.
- Backup restoration tests and release packaging remain excluded under the existing verification scope. The app’s backup restoration feature was not exercised. Spoken TalkBack and Garmin watch delivery require physical-device acceptance.
