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
- MVP-002 — Glance Home-Screen Shift HUD [COMPLETE]
- MVP-003 — Persistent Notification + Lunch Warnings [CURRENT]
- MVP-004 — Quick Find
- MVP-005 — History / Statistics / Polish

Quick Find, history UI, networking, authentication, automatic import, cloud sync, and analytics remain deferred. MVP-003 notifications and personal lunch reminders are described below.

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
- Minute cadence is approximate. Doze, suspension, battery restrictions, and launcher delivery may delay a render. No exact wall-clock deadline is promised. Notification content also refreshes silently at the existing minute cadence.

### Foreground notification and Android recovery

The silent, low-importance **Active shift updates** channel now shows ongoing timestamp-derived work/lunch progress with direct lunch actions. Tapping it opens Dashboard. MVP-003 extends this same service; it does not add a second timing architecture.

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

Full Gradle build and all **81 unit tests per build variant** pass, including all existing MVP-001/MVP-002 tests. Added tests cover active-state decisions, final-refresh-before-stop ordering, background-start suppression, elapsed-minute alignment, timestamp-based catch-up, and refresh without repository writes. Lint passes with 0 errors and 12 dependency-update advisories. Emulator verification covered actual launcher placement, no-upcoming and today states, Schedule navigation, app-save-to-widget refresh, widget Clock In/Start Lunch/End Lunch, Dashboard agreement, safe Dashboard clock-out, completed totals, and process-death recovery (PID killed before END LUNCH). Compact/expanded launcher resizing and normal layout at a temporary emulator density were inspected; density was restored. Emulator reboot/unlock restored the widget and persisted completed totals without launching the app.

Two-instance placement was attempted but the test launcher did not place the additional instance; concurrent/shared-session safeguards are unit tested, while two-instance visual synchronization remains on the physical-device checklist. Manufacturer-specific battery restrictions and large-font/accessibility permutations also require physical-device validation. The MVP-003 extension of that infrastructure is documented below.

Active-refresh follow-up verification used the available Pixel 9 emulator (Android 17 / API 37, app target 36): widget WORKING advanced 0m → 1m → 2m → 3m → 4m without manual refresh, including a tick after confirmed Recents removal. ON LUNCH advanced 0m → 1m while paid time stayed fixed. Room database and WAL SHA-256 hashes were unchanged across that passive lunch tick. APK replacement, killing the process, and reboot/unlock each restored one foreground service without launching the app; system diagnostics showed PACKAGE_REPLACED and BOOT_COMPLETED exemptions. After lunch, work advanced 4m → 5m automatically; the notification opened the matching Dashboard. Clock-out rendered SHIFT COMPLETE immediately, and diagnostics confirmed no ActiveShiftService or posted ShiftHUD notification remained. No physical-device battery measurements were performed.

Completed-lunch presentation: WORKING retains its active-work countdown before lunch, ON LUNCH retains current lunch duration and paused paid time, and WORKING after lunch instead shows “Lunch taken” with the local start/end interval and timestamp-derived duration. Widget and Dashboard share the formatter; widget hour labels honor the device 12/24-hour preference. Compact widgets put duration beside “Lunch taken” and the interval below. The existing OPEN APP action, one-lunch restriction, engine, Room schema, and refresh service are unchanged. Presentation tests cover completed timestamps/duration, missing completion, overnight intervals, hour preference, and responsive detail selection.

Polish verification: full build passed with 81 tests per variant (162 executions), zero lint errors, and the same 12 dependency advisories. Emulator checks confirmed pre-lunch countdown, on-lunch duration, completed-lunch Dashboard/widget agreement, continued active-work progression, and unclipped compact/normal/expanded presentations. Temporary display density changes were restored.

## MVP-003 notifications and personal lunch warnings

The existing `ActiveShiftService` and serialized `WidgetRefresh` path now refresh the ongoing notification as well as all widgets. Before lunch it shows worked time and “Lunch due in …”, with START LUNCH and OPEN. On lunch it shows lunch duration and paused paid time, with END LUNCH and OPEN. After lunch it shows resumed work and the completed lunch interval/duration, with OPEN only. Expanded notification text includes scheduled out when linked. Completed sessions remove the ongoing notification and preserve the existing final widget refresh. Notification shade space and action visibility depend on Android and whether the card is expanded.

**Active shift updates** remains low importance, ongoing, silent, and only-alert-once. **Lunch reminders** is a separate default-importance channel; Android/user settings control its sound, vibration, and visibility. Warning taps open Dashboard, and warning actions can start lunch directly. Immutable PendingIntents have distinct identities for each session/action, so an older notification cannot target a later shift. Actions reuse the same transactional repository/engine executor as widgets; duplicate or stale taps no-op and reconcile from persisted truth.

Settings defaults enable **60, 30, and 15 minutes before** the personal active-work threshold (default 360 minutes). Each has an independent checkbox. Optional 5-minute and 1-minute offsets, disabled by default, support short shifts/testing. Offsets at or above the configured threshold are ignored. This is a personal preference, not law, employer policy, payroll enforcement, or an official Publix requirement.

Warning decisions derive active work from Room event timestamps through `ShiftEngine`. The existing approximately minute-level service loop checks crossed boundaries; it owns no timer counters. The existing 15-minute WorkManager widget fallback also reconciles notifications when delivered (only scheduled with installed widgets); there is no separate reminder job or exact alarm. Android sleep/Doze, notification permission/channel settings, force-stop, user-stop, OEM restrictions, and scheduler delays can postpone or prevent delivery. Reminders are approximate personal aids, never deadline guarantees.

DataStore records only warning delivery metadata: session ID, configuration, and consumed offsets. A new session/first observation establishes a baseline and skips already-past boundaries. Changes to threshold or enabled offsets cancel the displayed reminder and baseline past boundaries without immediately replaying history. Future unconsumed offsets remain eligible; an offset consumed earlier in the same session cannot repeat after changing settings back. Starting lunch suppresses every remaining pre-lunch warning, including after completed lunch; clock-out cancels the warning and clears its lifecycle metadata.

A delayed check consumes all crossed offsets and offers only the most recent relevant one, using **current remaining time** in the message. No burst of old “60m/30m” warnings occurs. After the threshold is reached, obsolete pre-lunch warnings are suppressed/canceled. A reminder also has a timeout at the remaining threshold interval. DataStore serializes candidate evaluation, posting, and acknowledgement. The current candidate is consumed only **after** `NotificationManager.notify()` returns successfully (or Android still has its matching notification receipt). Blocked/failed posting remains retryable while that boundary is relevant. Older crossed boundaries are retired, so retrying never produces a historical burst. Successful acknowledgements survive process recreation. If a process dies after posting but before committing DataStore, the stable notification ID plus per-session/offset receipt in Android active notifications avoids reposting an already visible reminder. Android and DataStore cannot commit atomically: if that receipt is also removed before recovery, a duplicate remains possible in this narrow window; failed posts are never deliberately treated as delivered. A successful API call is not proof of audible/visible presentation by the OS. No Room migration or elapsed/lunch timer counters were introduced.

The existing sticky, boot/package-update, Recents, and visible-interaction recovery paths remain. Android 12+ background-start restrictions still apply; the passive worker does not start a service. Target 36 retains the specialUse foreground type/permission and its Play-distribution review requirement. Notification actions use an explicit non-exported receiver; OPEN uses an Activity PendingIntent directly, with no notification trampoline. See [Android notification actions/channels](https://developer.android.com/develop/ui/compose/notifications/create-notification) and [foreground-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

On API 33+, the visible app explains notifications before the existing one-time permission request. Declining leaves Room tracking, service operation, and widgets intact where Android permits; no background action opens an Activity to request permission. Settings indicates when app notifications or the Lunch reminders channel are blocked and links to Android notification settings. The app does not repeatedly prompt. With permission denied, Android can expose foreground activity in Task Manager without a notification-drawer card.

### MVP-003 physical-device acceptance

Record the original threshold/toggles. Temporarily set the threshold to **10 minutes**, enable **5 minutes before** and **1 minute before**, and start a fresh session. Default 60/30/15 offsets exceed that threshold and will be skipped. A faster smoke check can use threshold 3 with only the 1-minute test offset (expected boundary at 2 active minutes).

1. Clock in.
2. Confirm the ongoing notification appears.
3. Compare widget and notification WORKING duration.
4. Wait through minute updates and confirm they are silent.
5. At 5 active minutes (10-minute configuration), confirm one warning.
6. Wait another minute; confirm it does not repeat.
7. Tap START LUNCH from the warning or ongoing notification.
8. Confirm Dashboard and widget show ON_LUNCH.
9. Confirm remaining warnings do not fire, even beyond their former boundary.
10. Confirm the lunch timer advances while paid time stays paused.
11. Tap END LUNCH from the ongoing notification.
12. Confirm widget/Dashboard return to WORKING.
13. Confirm there is no post-lunch countdown or warning.
14. Expand the notification and confirm the completed lunch interval/duration.
15. Remove the app from Recents; confirm minute tracking continues.
16. Clock out in Dashboard.
17. Confirm the ongoing notification disappears and widget shows COMPLETE.
18. Wait past remaining boundaries; confirm no later warning.
19. Restore the original threshold/toggles (normally 360 and 60/30/15 enabled; 5/1 disabled).

Also deny POST_NOTIFICATIONS on API 33+ before a fresh test: confirm the Settings indication, no warning cards, and unchanged session/widget tracking. Re-enable in Android Settings and verify only the currently relevant unposted warning or future boundaries remain eligible, without a burst of older warnings. Test service process death/reboot, settings changes across a boundary, repeated/stale actions, notification-channel blocking, and device-specific battery restrictions. Physical sound/vibration and OEM background behavior require real-device validation.

### MVP-003 automated verification

Full Gradle build, all **131 tests per variant (262 executions)**, and lint pass. Lint has zero errors and the 12 existing pinned-dependency advisories. All 81 prior tests are preserved. Added tests cover notification states/actions, one-lunch enforcement and stale-session rejection, each warning boundary, disabling/toggling, delayed execution, deduplication and DataStore reconstruction, threshold changes, permission suppression, timestamp immutability, and separate channels/immutable session-specific actions. Room schema remains version 1.

MVP-003 emulator verification (Pixel 9, Android 17/API 37, target 36): ongoing progress advanced silently with unchanged Room/WAL hashes; a two-minute threshold with a one-minute offset posted one current reminder. The reminder record was not reposted during the observed process-death window; its START LUNCH action recreated/reconciled the process, canceled the warning, and updated widget/notification. Lunch advanced to 1m with paid time paused; END LUNCH showed the completed interval and OPEN only. APK replacement restored the active service/card. Actual permission denial through the Android dialog preserved session and widget progress and displayed the Settings warning. Permission and default threshold/offsets were restored after testing. Clock-out produced final COMPLETE and removed both notifications and the foreground service. Real-device sound/vibration and OEM delivery were not measured.

### MVP-003 physical delivery fix: diagnosis and retest

The original code could reach the lunch threshold without any reminder even though the foreground service and Android channel worked. With a 10-minute threshold, default offsets 60/30/15 are all invalid; the separately enabled 5/1 offsets are required. Channel permission does not enable those app checkboxes. Settings now shows **saved reminder boundaries** (or explicitly says none apply), and warning rows are fully tappable. Each toggle queues its save rather than sharing the action busy guard, which previously could discard a second tap while the first save/refresh was busy.

A separate confirmed delivery defect was pre-post deduplication: the candidate was consumed before permission checks/`notify()`, and even an exception updating the ongoing notification could abort the later reminder post while retaining the consumed marker. The fix evaluates/posts/acknowledges the reminder first, keeps failed/blocked candidates retryable, and logs the outcome. The exact trigger on the reported physical device cannot be proven without its saved configuration/logs; channel settings alone cannot distinguish these paths.

The traced path is `ActiveShiftService` minute wake → `WidgetRefresh` → `ShiftNotifications` → Room snapshot/ShiftEngine active work → DataStore saved threshold and offset set → candidate → permission/channel recheck → warning `notify(1002, …)` → persisted success acknowledgement. The service's existing `durations(..., 360)` is used only to align the active-work/lunch tick, not to select warning boundaries; warning evaluation uses the saved threshold. Warning ID **1002** is distinct from foreground ID **1001**. The actual warning uses **lunch_reminders**, created before evaluation at **IMPORTANCE_DEFAULT (3)**. This importance does not promise a heads-up banner; sound/drawer behavior follows Android/user settings. PendingIntents remain immutable, session-specific, and unrelated to candidate selection.

Boundary algorithm: keep enabled offsets `0 < offset < threshold`; a boundary is crossed whenever timestamp-derived active duration is **at least** `threshold − offset`. It need not equal the exact minute. Select the smallest crossed offset (latest boundary) and attempt at most that one. Retire older crossings, never the unposted current candidate. First observation or changed settings baseline past boundaries without replay; only future boundaries remain eligible. Lunch start/completion and clock-out suppress/cancel reminders. At or beyond the threshold, do not invent a pre-lunch alert. Existing delivery history remains compatible; use a fresh session when retesting an older APK's already-consumed markers.

**Exact physical retest (debug APK):**

1. Install/update this build, finish any prior active session, and open Settings **before clock-in**.
2. Enter **10** in **Lunch threshold (minutes)** and tap **Save**.
3. Under **Lunch warnings**, check **5 minutes before** and **1 minute before**. The 60/30/15 boxes may remain checked; they are ignored for this threshold. Confirm the saved-boundary line says **5m worked (5m before), 9m worked (1m before)**. Do not proceed if it says no enabled warnings apply.
4. Confirm app notifications and Android's **Lunch reminders** channel are allowed. Start logging with the command below.
5. Clock in to a fresh session. Do not start lunch or change settings during the first timing run.
6. Around **5 active minutes**, expect a **separate Lunch reminders card** titled approximately **Lunch due in 5 minutes** with START LUNCH and OPEN. It is separate from the silent ongoing card. Check the drawer; a heads-up banner is not guaranteed.
7. Through minutes 6–8, confirm no repeated 5-minute alert.
8. Around **9 active minutes**, expect one reminder titled approximately **Lunch due in 1 minute**, replacing the prior reminder card. Around minute 10, the ongoing card may say threshold reached and the reminder expires; that ongoing text is not evidence that an earlier reminder was posted.
9. For an action check, on another fresh session tap START LUNCH on either warning while it is present. Confirm ON_LUNCH in app/widget/ongoing card, no further warnings, and END LUNCH works. Alternatively test that action at minute 9 of the timing run, recognizing that lunch then pauses active work and changes the expected minute-10 state.
10. Clock out, confirm all active notification/service state is removed, and restore the normal threshold/toggles (360; 60/30/15 on; 5/1 off).

```sh
adb logcat -v time -s ShiftHUDWarning:D ShiftHUDNotification:W ActiveShiftService:W AndroidRuntime:E
```

Capture from before clock-in until after minute 10; do not clear logs after a failure. Debug-only `ShiftHUDWarning` records session ID, active milliseconds, saved threshold/offsets, valid offsets, candidate/boundary, consumed status/reason, runtime permission, app notification status, channel importance, `notifyInvoked`, API return/failure, ID/receipt, and dedup commit. It logs on existing refresh evaluations, not every second, and excludes schedule notes and personal content. `no_valid_offsets`, `baseline`, `blocked`, `already_consumed`, and `post_failed` explain distinct non-delivery paths. `notifyInvoked=true` followed by `notifyReturned=true` and `dedupCommitted=true posted=true` proves the posting API returned successfully; if no card is visible despite those lines, include Android version/device model and notification settings with the log. Exceptions escaping the notification path remain visible under `ShiftHUDNotification` without stopping persisted tracking.

Delivery-fix verification: full Gradle build and lint passed; **131 tests per variant (262 executions)** passed, with zero lint errors and the same 12 dependency advisories. Fifteen new regressions cover short thresholds, invalid offsets, inexact crossings, delayed selection, posting failure/suppression/retry, post-before-ack ordering, persistence, concurrent evaluations, and offset saves.

A real-time 10-minute run on Pixel 9/API 37 used threshold 10 and enabled offsets 60/30/15/5/1. Session 6 started at 18:42:13 emulator time: the separate **Lunch due in 5 minutes** card appeared at 18:47:13, no repost occurred at minutes 6–8, and **Lunch due in 1 minute** appeared at 18:51:13. Both had START LUNCH/OPEN. At 18:52:13, the ongoing card showed **Worked 10m · Lunch threshold reached**, the reminder was canceled/expired, and no new warning was posted. Logs show each notify call returning before its successful dedup commit. This run used real elapsed time, with no manual timestamp changes or refreshes.

A separate threshold-2/offset-1 session verified START LUNCH directly from the reminder: it canceled the warning and switched notification, Dashboard, and widget to ON_LUNCH. END LUNCH resumed WORKING with the completed interval and no countdown; clock-out removed active notifications/service state. Threshold 360 and offsets 60/30/15 were restored, with 5/1 disabled. Real-device sound/vibration and OEM delivery remain unverified.
