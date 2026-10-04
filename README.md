# ShiftHUD

ShiftHUD is a personal Android work-shift dashboard with a manually entered schedule. It is **not an official Publix application, payroll system, or authoritative employer timekeeping record**. It never authenticates against, scrapes, or integrates with employer systems. All data stays locally on the device; Android backup is disabled for this MVP.

## Core features

- Material 3 dashboard, Schedule, and Settings using Navigation Compose.
- Add, edit, delete, and chronologically view upcoming shifts; today's shift is labeled. Add/Edit uses a Material calendar and AM/PM clock pickers with an optional keyboard mode; dates display in the device locale.
- Clock in automatically links the earliest scheduled shift starting today, ordered by start time then ID. Multiple shifts on a date are supported in the schedule; the earliest is the deterministic dashboard/association choice in this MVP.
- Unscheduled clock-in, one lunch, clock-out, and completed paid/store totals.
- Configurable lunch threshold, default 360 active work minutes, in Preferences DataStore.
- Room persists event timestamps. Durations reconstruct from those timestamps after backgrounding, process death, or reboot. An active-shift foreground service drives widget redraws; it never owns or persists a timer.

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

Full notification/reminder UX, lunch warnings, Quick Find, history UI, networking, authentication, automatic import, cloud sync, and analytics are intentionally absent. WorkManager is used only for widget refresh, not reminders.

## Verification for this implementation

Debug assemble and all unit tests pass. Android lint passes with dependency-update advisories only; the compatible toolchain versions are intentionally pinned. Emulator smoke checks cover schedule creation, automatic association, the full clock-in/lunch/clock-out flow, settings display, and active-session recovery after a force-stop and APK reinstall. The Room tests additionally verify edit/delete behavior and concurrent clock-in rejection.

## MVP-002 widget architecture

Add **ShiftHUD** from your launcher's widget picker. Jetpack Glance **1.2.0** renders OFF TODAY / upcoming, TODAY, WORKING, ON LUNCH, and SHIFT COMPLETE. Responsive layouts target **180 × 200 dp (compact)**, **280 × 240 dp (normal)**, and **320 × 300 dp (expanded)**. Launcher grid dimensions vary; smaller layouts prioritize state, key duration/countdown, and a 48 dp primary button. Normal adds scheduled out/store total; expanded adds next shift/lunch details and a clock-out navigation hint. Resize below the compact minimum is not supported.

`widget/WidgetState.kt` is a pure presentation mapper that calls the existing `ShiftEngine`. `ShiftHudWidget` reads Room and Preferences DataStore without an Activity or ViewModel. All widgets share the same repository/database; Glance has no separate session state. A repository snapshot is read transactionally, and Room/preference flows remain observed while Glance's finite composition session is alive. Closing the app does not discard shift timestamps.

Direct actions are **CLOCK IN**, **START LUNCH**, **END LUNCH**, and **refresh** (tap the Updated line). The action executor calls the existing repository transitions. Each callback includes the rendered session ID; clock-in also includes the date and previous session ID so stale/replayed buttons cannot start a new session after the old one completed. Invalid actions safely reread and refresh persisted state. Concurrent clock-ins are rejected inside the existing Room transaction. No Room schema or migration changed.

The header opens Dashboard. OFF TODAY / ADD SHIFT opens Schedule, where Add Shift is available. Completed sessions open Dashboard. **Clock-out is intentionally available only in the full Dashboard**, reached from the header or Open App; expanded WORKING also labels this path. Once the single supported lunch is complete, the primary button becomes Open App. Explicit destination intents distinguish Schedule from Dashboard and preserve ordinary launcher entry.

### Refresh strategy and timing limits

- Every successful repository write and lunch-threshold save invokes centralized `WidgetRefresh` **after persistence**. App and widget actions share this path. WORKING/ON_LUNCH starts or wakes `ActiveShiftService` before an immediate redraw; clock-out requests the final redraw before stopping the service. Failed redraws never undo successful database writes.
- The service owns one conflated coroutine loop. It rereads Room and requests all installed Glance instances to redraw near the next **elapsed-minute boundary**, using active work time or current lunch time. Repeated starts wake that same loop. A delayed tick immediately catches up from persisted event timestamps; there are no elapsed counters, per-second polls, timer writes, exact alarms, or wake locks. With no widgets installed it skips Glance updates.
- A unique **15-minute WorkManager job** remains a slow fallback only while an active session and installed widget exist. It cannot start a foreground service from the background. Provider updates request **30-minute** idle/schedule refreshes. The Updated line retains manual refresh, which also attempts active-service recovery after a widget interaction.
- Minute cadence is approximate. Doze, suspension, battery restrictions, and launcher delivery may delay a render. No exact wall-clock deadline is promised. Notification content changes only on state transitions, not every tick.

### Foreground notification and Android recovery

The silent, low-importance **Active shift updates** channel shows an ongoing **ShiftHUD / Working · tap to open** or **On lunch · tap to open** notification. Tapping it opens Dashboard. This is service infrastructure only; MVP-003 lunch warnings and full notification controls remain deferred.

Target SDK is 36, minimum 26. The manifest declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, and `RECEIVE_BOOT_COMPLETED`. API 34+ uses the `specialUse` service type with a manifest explanation for user-initiated personal shift tracking. It does not impersonate data synchronization or health tracking. Google Play distribution requires a special-use declaration/review; approval is not assumed. See [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

Android 12+ restricts background service starts. Visible app launches and widget interactions may start it; passive WorkManager refresh does not try. Boot/package-replacement receivers read Room and start only for an active session, under the applicable system-broadcast exemption after unlock. Android 15 restricts boot starts for several types; this service uses specialUse. Denied starts are logged and leave persisted state/manual refresh intact. See [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

`START_STICKY` lets Android recreate a killed service, including a null-intent restart. The service promotes immediately, then reads Room; no active session means final redraw and shutdown. `stopWithTask=false` allows ordinary Recents dismissal without stopping tracking. Visible app launch also reconciles service state. Restart timing and OEM behavior are not guaranteed, and force-stop/Android's user-stop controls are not bypassed. See [START_STICKY](https://developer.android.com/reference/android/app/Service#START_STICKY).

On API 33+, the app requests notification permission once when an active session is visible. A widget action does not force an Activity open merely to prompt. Denial does not prevent the foreground service: Android still exposes it in Task Manager, while its notification may be absent from the drawer. The permission-prompt preference is UI metadata, never a timer. See [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

### Active refresh physical-device acceptance test

1. Add ShiftHUD widget.
2. Clock in.
3. Do NOT touch manual refresh.
4. Observe WORKING · 0m.
5. Wait >1 minute.
6. Confirm widget automatically advances to at least WORKING · 1m.
7. Continue several minutes and confirm progression.
8. Start lunch.
9. Confirm lunch duration automatically advances.
10. End lunch.
11. Confirm WORKING duration resumes correctly.
12. Remove ShiftHUD app from Recents.
13. Confirm active widget updates continue.
14. Open app and verify same authoritative state.
15. Clock out.
16. Confirm widget receives final COMPLETE refresh.
17. Confirm active refresh service stops after completion.

Also verify battery usage is not driven by per-second work. Test permission denial, reboot/unlock with an active session, process recreation, and multiple widget instances on the target physical device. Read service state with `adb shell dumpsys activity services com.shifthud`; completion should leave no ActiveShiftService. OS delivery and battery behavior require real-device validation.

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

Full Gradle build and all **76 unit tests per build variant** pass, including all existing MVP-001/MVP-002 tests. Added tests cover active-state decisions, final-refresh-before-stop ordering, background-start suppression, elapsed-minute alignment, timestamp-based catch-up, and refresh without repository writes. Lint passes with 0 errors and 12 dependency-update advisories. Emulator verification covered actual launcher placement, no-upcoming and today states, Schedule navigation, app-save-to-widget refresh, widget Clock In/Start Lunch/End Lunch, Dashboard agreement, safe Dashboard clock-out, completed totals, and process-death recovery (PID killed before END LUNCH). Compact/expanded launcher resizing and normal layout at a temporary emulator density were inspected; density was restored. Emulator reboot/unlock restored the widget and persisted completed totals without launching the app.

Two-instance placement was attempted but the test launcher did not place the additional instance; concurrent/shared-session safeguards are unit tested, while two-instance visual synchronization remains on the physical-device checklist. Manufacturer-specific battery restrictions and large-font/accessibility permutations also require physical-device validation. Full notification/lunch-warning UX remains MVP-003; the active-service notification described above is included.

Active-refresh follow-up verification used the available Pixel 9 emulator (Android 17 / API 37, app target 36): widget WORKING advanced 0m → 1m → 2m → 3m → 4m without manual refresh, including a tick after confirmed Recents removal. ON LUNCH advanced 0m → 1m while paid time stayed fixed. Room database and WAL SHA-256 hashes were unchanged across that passive lunch tick. APK replacement, killing the process, and reboot/unlock each restored one foreground service without launching the app; system diagnostics showed PACKAGE_REPLACED and BOOT_COMPLETED exemptions. After lunch, work advanced 4m → 5m automatically; the notification opened the matching Dashboard. Clock-out rendered SHIFT COMPLETE immediately, and diagnostics confirmed no ActiveShiftService or posted ShiftHUD notification remained. No physical-device battery measurements were performed.
