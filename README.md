# ShiftHUD

ShiftHUD is a personal Android work-shift dashboard with a manually entered schedule. It is **not an official Publix application, payroll system, or authoritative employer timekeeping record**. It never authenticates against, scrapes, or integrates with employer systems. All data stays locally on the device; Android backup is disabled for this MVP.

## MVP-001

- Material 3 dashboard, Schedule, and Settings using Navigation Compose.
- Add, edit, delete, and chronologically view upcoming shifts; today's shift is labeled.
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

29 tests cover repository transactions, automatic association, delete/edit retention, database reopen, normal/invalid transitions, timestamp reconstruction, paid/store durations, lunch countdown and pause, completed-session freezing, chronological ordering, overnight/DST behavior, and backwards-clock handling. Reports are in `app/build/reports/tests/testDebugUnitTest/` and `app/build/reports/lint-results-debug.html`.

Manual smoke test: add today's schedule, edit it, clock in, start/end lunch, clock out; reopen during each state and verify durations continue correctly. Delete a linked schedule and verify recorded time is retained. Add an overnight shift and check the next-day label. Change the threshold and restart. A device reboot should preserve data; this MVP performs no background work.

## Roadmap

- MVP-001 Core + Manual Schedule + Shift Engine
- MVP-002 Glance Widget
- MVP-003 Notifications + Lunch Warnings
- MVP-004 Quick Find
- MVP-005 History / Statistics / Polish

Widgets, notifications, WorkManager, Quick Find, history UI, networking, authentication, automatic import, cloud sync, and analytics are intentionally absent.

## Verification for this implementation

Debug assemble and all 29 tests pass. Android lint passes with dependency-update advisories only; the compatible toolchain versions are intentionally pinned. Emulator smoke checks cover schedule creation, automatic association, the full clock-in/lunch/clock-out flow, settings display, and active-session recovery after a force-stop and APK reinstall. The Room tests additionally verify edit/delete behavior and concurrent clock-in rejection.
