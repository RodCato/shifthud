# MVP-006C.1 Schedule weekly planning

Based on `mvp-006c-stabilization-ux` at `94cdec6`, with PR #16 still open. This dependent branch does not merge or rebase any existing PR.

Schedule's saved Saturday–Friday selection now controls the weekly summary and the initial week in the existing lunch dialog. Saving updates the same weekly preferences observed by Dashboard; cancelling does not save. The dialog and selected week retain saved state across recreation.

The compact summary shows shift count, employer scheduled paid hours, weekly lunch assumption, actual paid time, remaining projection, projected total, target and shortfall/excess. All projections delegate to `clockOutPlan`; the underlying engine is unchanged. Completed punches replace schedules, active sessions use the existing logic, and historical missed shifts are not invented as work.

Valid imported employer net minutes are authoritative for the employer summary. Missing or out-of-span metadata falls back to the existing legacy/default lunch planning rules, with an explicit **Scheduled paid estimate** label and count of missing employer values. Missing lunches use the established default/minimum, never implicit zero. No saved shifts means employer hours are unknown. Every summary reminds the user that saved schedules may be incomplete.

Projected gross is a base-pay estimate using the existing integer-cents helper when one effective rate covers the week. It is omitted when rates are missing or change within the week, rather than guessing how projected hours should be allocated. This does not change payroll or historical analytics.

## Acceptance

1. Install the debug APK in place; do not uninstall or clear data.
2. Open Schedule and navigate to October 17–23, 2026 with the five imported shifts. Confirm 37h employer scheduled, and with 31-minute lunches, 39h25m projected / 35m shortfall against 40h.
3. Tap **EDIT WEEKLY LUNCH PLAN**. Confirm October 17–23 opens even when it is not the current week. Try 35 minutes, preview 39h5m, cancel and verify nothing saved. Reopen, save, and verify Schedule updates immediately.
4. Return to 31 minutes. Navigate to an adjacent week and verify its independent plan; restart the app and verify persistence.
5. Inspect an empty, partially entered, historical and overnight week. Confirm missing employer values are estimates and missing records never imply complete attendance.
6. Check 200% font, TalkBack and landscape scrolling. Primary schedule actions remain reachable without an oversized card.

No Room/schema change, backup/restore change or restoration testing, signed release build, Frozen Operations implementation, notification-channel change, historical record change or employer payroll change is included.

## Verification results

- Full scoped Gradle build, debug APK and instrumentation APK passed. Debug APK installed over the existing emulator app with `adb install -r`.
- 807 unit tests per debug/release variant (1,614 executions), zero failures. The temporary init script excludes 18 backup restoration tests per variant; release packaging is excluded. Repository test configuration is unchanged.
- Final eligible instrumentation suite: **21 tests passed** at 200% font / 320dp width / dark mode. Backup restoration device acceptance is explicitly excluded. The existing row-edit test now scrolls the lazy list to an offscreen row before finding it; its assertions remain intact.
- Additional fixture runs passed: five imported shifts, completed history and payroll preservation; saving 35m then 31m from Schedule; adjacent week remains 48m; saved plans persist after force-stop/restart. Emulator UI showed 37h employer, 39h25m personal projection and 35m shortfall.
- Lint: zero errors, 14 warnings and one hint. `git diff --check` passed.
- Original emulator files were restored and verified byte-for-byte; original display settings were restored. The app backup restoration feature was not used or tested. Physical data was not accessed.
- Physical phone installation, spoken TalkBack and the checklist above remain device acceptance checks.
