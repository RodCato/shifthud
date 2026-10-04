# ShiftHUD

ShiftHUD is a personal Android work-shift dashboard with a manually entered schedule. It is **not an official Publix application, payroll system, or authoritative employer timekeeping record**. It never authenticates against, scrapes, or integrates with employer systems. All data stays locally on the device; Android backup is disabled for this MVP.

## Core features

- Material 3 dashboard, Schedule, and Settings using Navigation Compose.
- Add, edit, delete, and chronologically view upcoming shifts; today's shift is labeled. Add/Edit uses a Material calendar and AM/PM clock pickers with an optional keyboard mode; dates display in the device locale.
- Clock in automatically links the earliest scheduled shift starting today, ordered by start time then ID. Multiple shifts on a date are supported in the schedule; the earliest is the deterministic dashboard/association choice in this MVP.
- Unscheduled clock-in, one lunch, clock-out, and completed paid/store totals.
- Configurable lunch threshold, default 360 active work minutes, in Preferences DataStore.
- Room persists event timestamps. Returning after backgrounding, process death, or reboot reconstructs durations when the app opens; no background timer service is needed.

## Architecture

One `:app` module. `domain/model` and `domain/usecase` contain Android-independent models and the fixed-clock-testable shift engine. `data/local` contains Room entities, DAO, and database; `data/repository` performs transactional session operations; `data/preferences` owns typed preference access. Compose screens live under `ui/dashboard`, `ui/schedule`, and `ui/settings`. An application container supplies dependencies to a lifecycle ViewModel without a DI framework. Flow updates the UI; a lifecycle-observed tick only triggers recomputation, never timer writes.

The absence of a session represents NOT_STARTED. Persisted sessions use WORKING, ON_LUNCH, COMPLETE. A transaction rechecks active state to prevent double taps from creating duplicate sessions or applying stale transitions. One lunch is supported because the specified schema has one lunch start/end pair; subsequent lunch attempts are rejected. End lunch before clocking out. The latest completed session remains on the dashboard for its clock-out date; historical rows are retained for future history features.

Schedules store local epoch-day and seconds-of-day; actual events store UTC epoch milliseconds. Scheduled end at or before start means the next calendar day (equal times mean 24 hours). `java.time` resolves schedule durations in a supplied zone, including DST. Schedules follow the device's local time zone; fixed workplace time zones are deferred. Actual elapsed time is based on instants. Manual clock changes are not authoritative: negative elapsed displays are clamped, and transitions earlier than the prior event are rejected.

Store time includes lunch. Paid/active time excludes completed **and currently elapsed** lunch, so paid time and lunch countdown pause during lunch. A negative countdown means the threshold was reached. Thresholds are personal preferences, not legal or employer policy advice. Planned lunch is informational and never deducted in place of an actual lunch.

Room schema version 1 is exported in `app/schemas`. Future schema changes must increment the version, add explicit migrations, and test retained data. No destructive migration fallback is enabled. Deleting a schedule sets its session association to null while preserving timestamps and totals.

## Local build

Requirements: JDK 17, Android SDK platform 36, Android build tools, internet access for the first dependency resolution. Minimum supported device is Android 8.0 / API 26. Open the repository in Android Studio or set `ANDROID_HOME` to your SDK (alternatively set `sdk.dir` in an untracked `local.properties`).

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17) # macOS; use your JDK path elsewhere
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Install on an emulator/device with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

The unit tests cover date/time picker conversions and labels, saved edit values, repository transactions, automatic association, delete/edit retention, database reopen, normal/invalid transitions, timestamp reconstruction, paid/store durations, lunch countdown and pause, completed-session freezing, chronological ordering, overnight/DST behavior, and backwards-clock handling. Reports are in `app/build/reports/tests/testDebugUnitTest/` and `app/build/reports/lint-results-debug.html`.

Manual smoke test: add today's schedule, edit it, clock in, start/end lunch, clock out; reopen during each state and verify durations continue correctly. Delete a linked schedule and verify recorded time is retained. Add an overnight shift and check the next-day label. Change the threshold and restart. A device reboot should preserve data; the widget uses the bounded refresh strategy below.

## Roadmap

- MVP-001 — Core + Manual Schedule + Shift Engine [COMPLETE]
- MVP-002 — Glance Home-Screen Shift HUD [CURRENT]
- MVP-003 — Persistent Notification + Lunch Warnings
- MVP-004 — Quick Find
- MVP-005 — History / Statistics / Polish

Notifications, lunch warnings, Quick Find, history UI, networking, authentication, automatic import, cloud sync, and analytics are intentionally absent. WorkManager is used only for widget refresh, not reminders.

## Verification for this implementation

Debug assemble and all unit tests pass. Android lint passes with dependency-update advisories only; the compatible toolchain versions are intentionally pinned. Emulator smoke checks cover schedule creation, automatic association, the full clock-in/lunch/clock-out flow, settings display, and active-session recovery after a force-stop and APK reinstall. The Room tests additionally verify edit/delete behavior and concurrent clock-in rejection.

## MVP-002 widget architecture

Add **ShiftHUD** from your launcher's widget picker. Jetpack Glance **1.2.0** renders OFF TODAY / upcoming, TODAY, WORKING, ON LUNCH, and SHIFT COMPLETE. Responsive layouts target **180 × 200 dp (compact)**, **280 × 240 dp (normal)**, and **320 × 300 dp (expanded)**. Launcher grid dimensions vary; smaller layouts prioritize state, key duration/countdown, and a 48 dp primary button. Normal adds scheduled out/store total; expanded adds next shift/lunch details and a clock-out navigation hint. Resize below the compact minimum is not supported.

`widget/WidgetState.kt` is a pure presentation mapper that calls the existing `ShiftEngine`. `ShiftHudWidget` reads Room and Preferences DataStore without an Activity or ViewModel. All widgets share the same repository/database; Glance has no separate session state. A repository snapshot is read transactionally, and Room/preference flows remain observed while Glance's finite composition session is alive. Closing the app does not discard shift timestamps.

Direct actions are **CLOCK IN**, **START LUNCH**, **END LUNCH**, and **refresh** (tap the Updated line). The action executor calls the existing repository transitions. Each callback includes the rendered session ID; clock-in also includes the date and previous session ID so stale/replayed buttons cannot start a new session after the old one completed. Invalid actions safely reread and refresh persisted state. Concurrent clock-ins are rejected inside the existing Room transaction. No Room schema or migration changed.

The header opens Dashboard. OFF TODAY / ADD SHIFT opens Schedule, where Add Shift is available. Completed sessions open Dashboard. **Clock-out is intentionally available only in the full Dashboard**, reached from the header or Open App; expanded WORKING also labels this path. Once the single supported lunch is complete, the primary button becomes Open App. Explicit destination intents distinguish Schedule from Dashboard and preserve ordinary launcher entry.

### Refresh strategy and timing limits

- Every successful repository write (schedule add/edit/delete and all shift transitions) and lunch-threshold preference save invokes the centralized `WidgetRefresh` hook **after persistence**. It requests updates for all placed instances and updates active Glance compositions. Widget callbacks also refresh after invalid/stale actions. A refresh failure is logged without undoing or misreporting a successful persisted write.
- One unique **15-minute WorkManager job** is maintained only while a WORKING/ON_LUNCH session and at least one installed widget exist. It is canceled after completion or removal of the last widget. WorkManager 2.10.5 is an explicit dependency because these APIs are used directly; Glance itself also uses WorkManager internally. This job has no notification or warning behavior.
- Android's provider `updatePeriodMillis` requests **30-minute** updates for schedule/date rollover and recovery, including while idle. Adding/resizing a widget reads persisted data and reconciles the active refresh job. WorkManager restores scheduled work after reboot; launcher/platform widget updates also reconstruct persisted state when delivered.
- No per-second or per-minute loop, foreground service, exact alarm, or incrementing persisted counter exists. Each render derives durations from the engine's timestamps. Values use whole minutes and are snapshots, **not a guaranteed live clock**. The Updated line states the calculation time and offers manual refresh.
- Doze, battery restrictions, launcher behavior, force-stop, and OS scheduling can delay updates. A force-stop through Settings/ADB is stronger than removing Recents and can suspend background delivery until the app is opened. Startup after a reboot can require first unlock. The 15/30-minute cadences are requests, never exact-time guarantees.

Design references checked for the selected version: [Glance releases](https://developer.android.com/jetpack/androidx/releases/glance), [Glance lifecycle and updates](https://developer.android.com/develop/ui/compose/glance/glance-app-widget), [responsive layouts](https://developer.android.com/develop/ui/compose/glance/build-ui), and [WorkManager periodic timing](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work). Glance renders RemoteViews and cannot use an always-running Compose timer on the launcher.

### MVP-002 physical-device checklist

1. Install/update the debug APK, retaining existing schedule data.
2. Add the ShiftHUD widget to the home screen.
3. Verify today's scheduled shift and its start countdown.
4. Tap CLOCK IN on the widget.
5. Open Dashboard and confirm WORKING.
6. Remove the app from Recents (do not force-stop).
7. Confirm the widget still displays persisted state and remains usable.
8. Tap START LUNCH without opening the app first.
9. Open Dashboard and confirm ON_LUNCH.
10. Tap END LUNCH on the widget.
11. Verify paid/active time excludes lunch and countdown uses active work.
12. Open Dashboard from the widget header and CLOCK OUT.
13. Verify completed paid/store totals on the widget.
14. Add, edit, delete a schedule and change the threshold in-app; verify immediate refresh requests and updated widget content.
15. Reboot, unlock, and verify persisted state reconstruction and subsequent actions.
16. Resize through compact, normal, and expanded sizes; check readability/clipping, including large system fonts.
17. Place two instances; act on one and confirm both refresh. Rapidly tap old actions and verify no duplicate session/lunch.
18. Check OFF TODAY with a next shift and with no upcoming shifts; verify Schedule navigation. Check an overnight active session across midnight.
19. Compare Updated timestamps after manual refresh and a passive active-session update; verify correctness without assuming an exact delivery deadline.

### MVP-002 verification recorded

Debug build and all **63 unit tests** pass, including all 38 MVP-001 tests. Lint passes with 0 errors and 12 dependency-update advisories. Emulator verification covered actual launcher placement, no-upcoming and today states, Schedule navigation, app-save-to-widget refresh, widget Clock In/Start Lunch/End Lunch, Dashboard agreement, safe Dashboard clock-out, completed totals, and process-death recovery (PID killed before END LUNCH). Compact/expanded launcher resizing and normal layout at a temporary emulator density were inspected; density was restored. Emulator reboot/unlock restored the widget and persisted completed totals without launching the app.

Two-instance placement was attempted but the test launcher did not place the additional instance; concurrent/shared-session safeguards are unit tested, while two-instance visual synchronization remains on the physical-device checklist. Exact passive delivery timing, manufacturer-specific battery restrictions, and large-font/accessibility permutations also require physical-device validation. No notification/lunch-warning behavior is included; that remains MVP-003.
