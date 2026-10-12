# MVP-006C stabilization audit

## Dependency and scope

PR #15 was merged into `mvp-006b-schedule-screenshot-import` at `04ee5ae`; PR #14 remains open against the planner dependency. This branch starts at that verified merge, without rebasing, merging or rewriting existing branches. Main does not contain the full dependency stack.

No Room migration, payroll calculation, historical timekeeping rule, backup/restore code, encryption format, package identity, storage path, signing configuration, permission, or notification channel was changed. Frozen Operations is not implemented. Only debug APKs are packaged.

## Schedule and Dashboard

- Schedule defaults to the current Saturday–Friday week. Previous/next controls filter strictly by local scheduled start date, including overnight shifts. Selection uses saved Compose state and does not follow the clock when another week is being reviewed.
- All seven days are represented. Missing rows say **No shift saved** rather than claiming a cropped day is OFF. Month/year transitions and historical navigation use calendar arithmetic.
- Date/time/status rows remain expandable. Edit/delete actions precede a collapsed **Advanced schedule details** section containing retained legacy lunches and employer evidence. No stored override is removed.
- The header and import button scroll with the schedule so a large font does not consume the entire viewport. Week navigation has named accessibility actions and a heading; existing expandable controls expose button roles and expanded/collapsed state.
- Dashboard offers direct Schedule access. Clock-in precedes secondary navigation, working controls precede the gross estimate, and End Lunch precedes secondary lunch details.
- Weekly summaries distinguish actual work, employer-reported hours, and personal projections. Remaining projection detail is available under Planning Details. The lunch dialog labels its draft **UNSAVED PREVIEW**, shows employer evidence separately, and retains all existing calculations and independent weekly plans.

## Demonstrated performance issue

Previously Dashboard subscribed to monthly calendar data while the calendar was collapsed, and invoked `workCalendar()` on every tick even for the hidden weekly dialog. It now subscribes only while the calendar/record browser is open and skips calendar computation for the weekly-only dialog. Schedule status queries are bounded to the selected week instead of subscribing to all historical sessions. Reactive Room and timer updates remain intact; no caching architecture was introduced.

## Notification audit

| Trigger | Existing path / finding |
| --- | --- |
| App restart | Visible Activity refreshes timestamp-derived state and reschedules upcoming work. Persistent delivery receipts prevent duplicate alerts. |
| Device reboot / package replacement | Registered receivers recover an active foreground service and reschedule next-day work. No inactive session should start tracking. |
| Time/timezone changes | Upcoming receiver reevaluates local tomorrow and work scheduling. |
| Schedule import | Transaction commits once, redraws without auto-lunch writes, and reschedules next-day reminders. Worked/active replacements remain rejected. |
| Schedule edit/delete | Existing repository callbacks refresh active notifications and reschedule upcoming work. Reminder delivery reads current Room data rather than queued shift text. |
| Weekly lunch plan | **Fixed:** changed current-week assumptions now invalidate stale planner notices, refresh notification calculations without auto-lunch reconciliation, and wake an already-running refresh loop. Future-week edits do not disturb current-week alerts. No service starts merely because a plan is edited. |
| Invalid/no-longer-supported clock-out projection | **Fixed:** stale planner notifications are removed when a safe recommendation is no longer available. Consumed reminder boundaries remain consumed. |
| Clock-in/lunch start/lunch end/clock-out | Existing guarded transitions trigger the shared refresh path; foreground service starts/stops according to actual state. Completed lunch suppresses further lunch alerts. |
| Historical correction | Existing redraw-only handling and receipt revision/baseline logic remain; actual timestamps replace projections and old boundaries do not replay. |
| Test Center | Lunch, shift end, weekly, and upcoming tests use existing channel IDs and separate test IDs. User-selected sound/vibration/DND settings and real reminder receipts are unchanged. |
| Optional hourly milestones | Not implemented; the existing unavailable label remains. No channel or feature was fabricated. |

Existing policy/infrastructure tests cover permission denial, channel settings, deduplication, snooze generations, schedule changes, blocked delivery retry, local-midnight expiry, and persisted receipts. Android may delay WorkManager in Doze or deny background foreground-service starts; existing visible-app recovery remains the fallback. Garmin watch delivery is not observable by Android notification posting.

## Integrity findings

Schedule import/replacement checks remain transactional and retain stable IDs, notes, and legacy lunches. Replacement of a worked/active schedule is rejected. Deleting a schedule intentionally clears its WorkSession foreign-key association; timestamps, actual paid duration and payroll facts remain unchanged. New regression coverage verifies that distinction. Existing correction, historical analytics, pay, Saturday attribution, migration and weekly-lunch tests remain in the eligible suite.

## Verification commands and exclusions

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
./gradlew -I /private/tmp/shifthud-006c-tests.init.gradle \
  build test lint assembleDebugAndroidTest -x assembleRelease -x packageRelease
```

The temporary init script excludes `**/backup/BackupRoundTripTest.class` (18 tests per variant), honoring the explicit prohibition on backup restoration testing. No repository test configuration or assertion is disabled or weakened. Other unit suites, including existing migration tests, run normally. Instrumentation excludes `com.shifthud.backup.BackupDeviceAcceptanceTest`; it does not invoke backup restoration phases. Release packaging is excluded, rather than adding a release signing configuration.

Emulator preservation uses temporary filesystem copies taken while the app is stopped, including database, DataStore, shared preferences and WorkManager files. Returning those files afterward is cleanup of temporary test data; the application's backup/restore feature is not exercised. Notification permission and display settings are returned to their prior values.

## Physical-device checklist

1. Install the debug APK over the existing app. Do not uninstall or clear app data.
2. Schedule: navigate to Oct 17–23; verify five shifts, Saturday 4–11 PM, and no invented Thursday/Friday shifts. Navigate back to Oct 10–16 and confirm recorded work still appears.
3. Expand a shift: verify normal date/time/notes and actions; open Advanced schedule details only when legacy metadata is needed.
4. Edit Lunch Plan: select Oct 17–23 and 31 minutes; verify 39h25m projected and 35m shortfall, with no unauthorized extension. Confirm another week's plan is independent.
5. Check primary controls in normal working/lunch states, 200% font, landscape, and TalkBack. Confirm week navigation focus order and readable tab labels on the physical screen.
6. Use each of the four Settings notification tests; verify Android delivery and Garmin forwarding separately. Test actual reminders during normal work and after restart/reboot, including the phone's battery restrictions.
7. Confirm actual punches, historical totals and paycheck facts remain unchanged. Backup/restore is outside this sprint's acceptance scope.

## Results

- Full scoped Gradle build, debug APK and instrumentation APK: passed. Debug APK installed in place over the existing emulator app.
- Unit tests: 796 per variant, 1,592 executions, zero failures/errors/skips among eligible tests. The 18 restoration tests per variant were explicitly excluded as above.
- Lint: zero errors, 14 warnings and one hint.
- Instrumentation: all 19 eligible tests pass across the full run and focused rerun. The full run initially had one keyboard setup failure; explicitly requesting keyboard input mode fixed the test setup. All three schedule-navigation tests then passed with their focus/activation assertions retained.
- Emulator: 320dp width, 200% font, dark theme, portrait/landscape scrolling; five imported shifts and selected-week filtering; 31-minute plan projects 39h25m with 35m shortfall. Completed punches and payroll fixture remained unchanged.
- Four real Android test notifications posted successfully using existing channels, without changing preference receipts or completed work.
- Actual emulator reboot recovered `ActiveShiftService` automatically before opening the app (`BOOT_COMPLETED`, foreground ID 1001, `active_shift_updates`). Persisted active clock-in, completed history and payroll assertions passed afterward.
- Original emulator app-data files were returned and verified byte-for-byte; original notification permission and display settings were restored. No physical-device data was accessed.
- Spoken TalkBack output, Garmin forwarding, phone-specific battery restrictions and in-place physical-device installation remain physical acceptance checks.
