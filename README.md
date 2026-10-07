# ShiftHUD

ShiftHUD is a personal Android work-shift dashboard with a manually entered schedule. It is **not an official Publix application, payroll system, or authoritative employer timekeeping record**. It never authenticates against, scrapes, or integrates with employer systems. All data stays locally on the device; Android backup is disabled for this MVP.

## Core features

- Material 3 Dashboard, Schedule, Quick Find, and Settings using Navigation Compose.
- Add, edit, delete, and chronologically view upcoming shifts; today's shift is labeled. Add/Edit uses a Material calendar and AM/PM clock pickers with an optional keyboard mode; dates display in the device locale.
- Clock in automatically links the earliest scheduled shift starting today, ordered by start time then ID. Multiple shifts on a date are supported in the schedule; the earliest is the deterministic dashboard/association choice in this MVP.
- Unscheduled clock-in, one lunch, clock-out, and completed paid/store totals.
- Configurable lunch threshold, default 360 active work minutes, in Preferences DataStore.
- Room persists event timestamps. Durations reconstruct from those timestamps after backgrounding, process death, or reboot. An active-shift foreground service drives widget redraws; it never owns or persists a timer.

## Architecture

One `:app` module. `domain/model` and `domain/usecase` contain Android-independent models and the fixed-clock-testable shift engine. `data/local` contains Room entities, DAO, and database; `data/repository` performs transactional session operations; `data/preferences` owns typed preference access. Compose screens live under `ui/dashboard`, `ui/schedule`, and `ui/settings`, and `ui/quickfind`. An application container supplies dependencies to a lifecycle ViewModel without a DI framework. Flow updates the UI; a lifecycle-observed tick only triggers recomputation, never timer writes.

The absence of a session represents NOT_STARTED. Persisted sessions use WORKING, ON_LUNCH, COMPLETE. A transaction rechecks active state to prevent double taps from creating duplicate sessions or applying stale transitions. One lunch is supported because the specified schema has one lunch start/end pair; subsequent lunch attempts are rejected. End lunch before clocking out. The latest completed session remains on the dashboard for its clock-out date; historical rows are retained for future history features. Dashboard Time Record also exposes the latest retained session after its clock-out date, with its dates and derived totals.

Schedules store local epoch-day and seconds-of-day; actual events store UTC epoch milliseconds. Scheduled end at or before start means the next calendar day (equal times mean 24 hours). `java.time` resolves schedule durations in a supplied zone, including DST. Schedules follow the device's local time zone; fixed workplace time zones are deferred. Actual elapsed time is based on instants. Manual clock changes are not authoritative: negative elapsed displays are clamped, and transitions earlier than the prior event are rejected.

Store time includes lunch. Paid/active time excludes completed **and currently elapsed** lunch, so paid time and lunch countdown pause during lunch. A negative countdown means the threshold was reached. Thresholds are personal preferences, not legal or employer policy advice. Planned lunch is informational and never deducted in place of an actual lunch.

Room schema version 2 is exported in `app/schemas` alongside version 1. Explicit migration 1→2 preserves sessions/schedules and adds captured auto-lunch duration, automatic-end provenance, and correction revision. The migration is tested by opening real v1 tables through Room and reopening the migrated database. No destructive migration fallback is enabled. Deleting a schedule sets its session association to null while preserving timestamps and totals.

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
- MVP-003 — Persistent Notification + Lunch Warnings + operational refinements [COMPLETE]
- MVP-004 — Quick Find [CURRENT]
- MVP-005 — History / Statistics / Polish

History/statistics/polish remain planned for MVP-005. Networking, authentication, automatic import, cloud sync, and analytics are not implemented. Quick Find and MVP-003 operational refinements are described below.

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

**Active shift updates** remains low importance, ongoing, silent, and only-alert-once. **Lunch reminders** requests HIGH importance on fresh installs; existing channel choices are preserved and Android/user settings control its sound, vibration, and visibility. Warning taps open Dashboard, and warning actions can start lunch directly. Immutable PendingIntents have distinct identities for each session/action, so an older notification cannot target a later shift. Actions reuse the same transactional repository/engine executor as widgets; duplicate or stale taps no-op and reconcile from persisted truth.

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

Full Gradle build, all **194 tests per variant (388 executions)**, and lint pass. Lint has zero errors and the 12 existing pinned-dependency advisories. All 81 prior tests are preserved. Added tests cover notification states/actions, one-lunch enforcement and stale-session rejection, each warning boundary, disabling/toggling, delayed execution, deduplication and DataStore reconstruction, threshold changes, permission suppression, timestamp immutability, and separate channels/immutable session-specific actions. The subsequent time-correction follow-up migrates this schema to version 2.

MVP-003 emulator verification (Pixel 9, Android 17/API 37, target 36): ongoing progress advanced silently with unchanged Room/WAL hashes; a two-minute threshold with a one-minute offset posted one current reminder. The reminder record was not reposted during the observed process-death window; its START LUNCH action recreated/reconciled the process, canceled the warning, and updated widget/notification. Lunch advanced to 1m with paid time paused; END LUNCH showed the completed interval and OPEN only. APK replacement restored the active service/card. Actual permission denial through the Android dialog preserved session and widget progress and displayed the Settings warning. Permission and default threshold/offsets were restored after testing. Clock-out produced final COMPLETE and removed both notifications and the foreground service. Real-device sound/vibration and OEM delivery were not measured.

### MVP-003 physical delivery fix: diagnosis and retest

The original code could reach the lunch threshold without any reminder even though the foreground service and Android channel worked. With a 10-minute threshold, default offsets 60/30/15 are all invalid; the separately enabled 5/1 offsets are required. Channel permission does not enable those app checkboxes. Settings now shows **saved reminder boundaries** (or explicitly says none apply), and warning rows are fully tappable. Each toggle queues its save rather than sharing the action busy guard, which previously could discard a second tap while the first save/refresh was busy.

A separate confirmed delivery defect was pre-post deduplication: the candidate was consumed before permission checks/`notify()`, and even an exception updating the ongoing notification could abort the later reminder post while retaining the consumed marker. The fix evaluates/posts/acknowledges the reminder first, keeps failed/blocked candidates retryable, and logs the outcome. The exact trigger on the reported physical device cannot be proven without its saved configuration/logs; channel settings alone cannot distinguish these paths.

The traced path is `ActiveShiftService` minute wake → `WidgetRefresh` → `ShiftNotifications` → Room snapshot/ShiftEngine active work → DataStore saved threshold and offset set → candidate → permission/channel recheck → warning `notify(1002, …)` → persisted success acknowledgement. The service's existing `durations(..., 360)` is used only to align the active-work/lunch tick, not to select warning boundaries; warning evaluation uses the saved threshold. Warning ID **1002** is distinct from foreground ID **1001**. The actual warning uses **lunch_reminders**, created before evaluation at **IMPORTANCE_HIGH (4)** on fresh installs (older DEFAULT channels are preserved). HIGH is heads-up eligible, not a guarantee; sound/drawer behavior follows Android/user settings. PendingIntents remain immutable, session-specific, and unrelated to candidate selection.

Boundary algorithm: keep enabled offsets `0 < offset < threshold`; a boundary is crossed whenever timestamp-derived active duration is **at least** `threshold − offset`. It need not equal the exact minute. Select the smallest crossed offset (latest boundary) and attempt at most that one. Retire older crossings, never the unposted current candidate. First observation or changed settings baseline past boundaries without replay; only future boundaries remain eligible. Lunch start/completion and clock-out suppress/cancel reminders. At or beyond the threshold, do not invent a pre-lunch alert. Existing delivery history remains compatible; use a fresh session when retesting an older APK's already-consumed markers.

**Exact physical retest (debug APK):**

1. Install/update this build, finish any prior active session, and open Settings **before clock-in**.
2. Enter **10** in **Lunch threshold (minutes)** and tap **Save**.
3. Under **Lunch warnings**, check **5 minutes before** and **1 minute before**. The 60/30/15 boxes may remain checked; they are ignored for this threshold. Confirm the saved-boundary line says **5m worked (5m before), 9m worked (1m before)**. Do not proceed if it says no enabled warnings apply.
4. Confirm app notifications and Android's **Lunch reminders** channel are allowed. Start logging with the command below.
5. Clock in to a fresh session. Do not start lunch or change settings during the first timing run.
6. Around **5 active minutes**, expect a **separate Lunch reminders card** titled approximately **Lunch due in 5 minutes** with START LUNCH and OPEN. It is separate from the silent ongoing card. Check the drawer; a heads-up banner is not guaranteed.
7. Through minutes 6–8, confirm no repeated 5-minute alert.
8. Around **9 active minutes**, expect one reminder titled approximately **Lunch due in 1 minute**, replacing the prior reminder card. Around minute 10, the early warning expires and the separate **Lunch time** attention notification now appears; that ongoing text is not evidence that an earlier reminder was posted.
9. For an action check, on another fresh session tap START LUNCH on either warning while it is present. Confirm ON_LUNCH in app/widget/ongoing card, no further warnings, and END LUNCH works. Alternatively test that action at minute 9 of the timing run, recognizing that lunch then pauses active work and changes the expected minute-10 state.
10. Clock out, confirm all active notification/service state is removed, and restore the normal threshold/toggles (360; 60/30/15 on; 5/1 off).

```sh
adb logcat -v time -s ShiftHUDWarning:D ShiftHUDNotification:W ActiveShiftService:W AndroidRuntime:E
```

Capture from before clock-in until after minute 10; do not clear logs after a failure. Debug-only `ShiftHUDWarning` records session ID, active milliseconds, saved threshold/offsets, valid offsets, candidate/boundary, consumed status/reason, runtime permission, app notification status, channel importance, `notifyInvoked`, API return/failure, ID/receipt, and dedup commit. It logs on existing refresh evaluations, not every second, and excludes schedule notes and personal content. `no_valid_offsets`, `baseline`, `blocked`, `already_consumed`, and `post_failed` explain distinct non-delivery paths. `notifyInvoked=true` followed by `notifyReturned=true` and `dedupCommitted=true posted=true` proves the posting API returned successfully; if no card is visible despite those lines, include Android version/device model and notification settings with the log. Exceptions escaping the notification path remain visible under `ShiftHUDNotification` without stopping persisted tracking.

Delivery-fix verification: full Gradle build and lint passed; **131 tests per variant (262 executions)** passed, with zero lint errors and the same 12 dependency advisories. Fifteen new regressions cover short thresholds, invalid offsets, inexact crossings, delayed selection, posting failure/suppression/retry, post-before-ack ordering, persistence, concurrent evaluations, and offset saves.

Before the snooze follow-up added threshold attention alerts, a real-time 10-minute run on Pixel 9/API 37 used threshold 10 and enabled offsets 60/30/15/5/1. Session 6 started at 18:42:13 emulator time: the separate **Lunch due in 5 minutes** card appeared at 18:47:13, no repost occurred at minutes 6–8, and **Lunch due in 1 minute** appeared at 18:51:13. Both had START LUNCH/OPEN. At 18:52:13, the ongoing card showed **Worked 10m · Lunch threshold reached**, the reminder was canceled/expired, and no new warning was posted. Logs show each notify call returning before its successful dedup commit. This run used real elapsed time, with no manual timestamp changes or refreshes.

A separate threshold-2/offset-1 session verified START LUNCH directly from the reminder: it canceled the warning and switched notification, Dashboard, and widget to ON_LUNCH. END LUNCH resumed WORKING with the completed interval and no countdown; clock-out removed active notifications/service state. Threshold 360 and offsets 60/30/15 were restored, with 5/1 disabled. Real-device sound/vibration and OEM delivery remain unverified.


### User-controlled attention reminders

Fresh `lunch_reminders` channels request HIGH importance, vibration, and public lock-screen visibility. The framework supplies the initial notification sound. Existing channels are inspected and left untouched: no deletion, recreation, forced promotion, or reset of user-selected sound/vibration occurs. An older DEFAULT channel remains DEFAULT until the user changes it in Android Settings. Individual reminders use CATEGORY_REMINDER, compatibility PRIORITY_HIGH, PUBLIC visibility, and auto-cancel; they never set sound/vibration or request DND bypass. Active shift updates remain LOW and silent. Post-before-acknowledgement delivery and blocked-post retries are unchanged.

Settings → **Lunch Reminder Status** reports actual app permission, channel enabled state, importance, configured/silent sound, and vibration. It refreshes on return from Android Settings. **OPEN NOTIFICATION SETTINGS** opens the Lunch reminders channel directly, with app-settings fallback where the channel screen is unavailable. Disabled, silent, and non-HIGH states receive informational guidance; Android choices remain authoritative. These diagnostics are available in debug and release builds.

Physical acceptance for this APK: update without uninstalling; inspect Lunch Reminder Status; open the channel shortcut; enable the channel, select your preferred sound, and choose vibration/banner behavior where available. Do not enable DND bypass for this test. Return and confirm the status reflects your choices. Before a fresh clock-in, save threshold **10**, enable **5 minutes before** and **1 minute before**, and confirm boundaries **5m/9m worked**. Expect separate reminders at approximately active minutes **5** and **9**, using the channel's chosen sound; the persistent Working card stays silent. At minute **10**, a separate **Lunch time** attention alert now appears alongside the silent ongoing threshold-reached state. Android/OEM, DND, and user settings can suppress sound/banner presentation. Restore normal threshold/offsets after testing. Use the `ShiftHUDWarning` logcat command above if delivery fails.

Attention-channel verification: full Gradle build, **137 tests per variant (274 executions)**, and lint passed (zero errors, 12 existing dependency advisories). Six new tests cover fresh defaults, existing DEFAULT/custom sound preservation over repeated startup, disabled/silent channels, Settings diagnostics and intent, and reminder channel/category/priority/visibility without per-notification sound overrides. Existing delivery-order and stale/repeated-action tests remain passing.

Emulator (Pixel 9/API 37) upgrade preserved the existing DEFAULT channel. The direct Android shortcut allowed selecting HIGH/banner, vibration, and Gentle Gong; returning to ShiftHUD showed the updated real state. A short threshold-2/offset-1 session posted a separate reminder whose Android notification record used HIGH importance and the selected Gentle Gong URI, while the ongoing record stayed LOW with null sound. START LUNCH, END LUNCH, and clock-out passed. Normal threshold/offsets were restored; the emulator channel retains the manually selected HIGH/Gentle Gong configuration. Audible playback and OEM banner behavior require physical testing.


### Threshold attention and repeatable lunch snooze

Early-warning offsets remain separate from the threshold event. When current active work first reaches/crosses the saved threshold and no lunch has started, **Lunch time** appears on the existing Lunch reminders channel, with START LUNCH, SNOOZE, and OPEN. At or beyond a snooze target, **Lunch reminder** shows **Snoozed reminder · Worked …** with the same actions. The silent ongoing card can continue to say threshold reached. No snooze countdown is added to the widget or Dashboard.

Settings → **Lunch snooze duration** offers **5/10/15/20 minutes**, default **10**. There is no existing debug-only settings mechanism, so no production one-minute snooze choice was added. Changing the setting affects future actions, not an existing target; an already-visible attention card's action label updates with only-alert-once behavior.

DataStore stores one `lunch_attention` record: session ID, event generation (zero for threshold), optional target active milliseconds, and successful-post acknowledgement. The generation identifies an event; it is not a timer counter. Snooze re-reads the authoritative Room session inside the serialized preferences edit and accepts only WORKING, no-lunch, threshold-reached sessions whose posted event receipt matches the action. The new target is **current timestamp-derived active work + configured duration**, with millisecond precision. It replaces the previous target. Repeated or stale action tokens cannot postpone twice or affect another session. WorkSession timestamps and the original threshold are untouched; The subsequent time-correction follow-up adds the explicit Room 1→2 migration.

The existing service/shared refresh evaluates `activeWork >= target`; its minute cadence means delivery can be up to roughly one tick later (and later still under Android execution restrictions). There is no new timer, worker, alarm, wake lock, or parallel background system. Failed/blocked posts remain unacknowledged and retryable. Successful posting precedes persisted acknowledgement, with matching Android receipts used to reconcile interrupted commits, subject to the same OS/DataStore atomicity limitation documented above. Delayed execution offers one current event, never a tick backlog. Initial recovery after the threshold offers the threshold event if none was acknowledged; early warnings still use their separate baseline rules.

Every existing repository change requests the same refresh. Dashboard, widget, or notification START LUNCH clears the attention record and card; ON_LUNCH, completed lunch, clock-out, and obsolete sessions suppress future alerts. Process recreation and the existing boot/package/Recents recovery paths load the persisted target. If background startup is restricted, the next permitted refresh reconciles it. User-selected channel sound/vibration/importance and DND remain authoritative; threshold/snooze cards use reminder semantics, never alarms or DND bypass.

**Short physical acceptance (no debug-only duration required):**

1. Update without uninstalling. Confirm Lunch Reminder Status and choose sound/banner/vibration in the direct Android channel settings as desired.
2. Finish any old session. Save **threshold 1 minute** and choose **snooze 5 minutes** before a fresh clock-in. Early offsets may remain as normal; all are invalid for this one-minute threshold, which does not disable the separate threshold event.
3. After about one active minute, expect **Lunch time**, **You've worked 1m**, and START LUNCH / SNOOZE 5M / OPEN. The ongoing card stays silent.
4. Tap SNOOZE 5M: the attention card disappears and the ongoing WORKING card remains. Record the tap time/active duration.
5. At the first existing minute tick after five additional active minutes, expect one **Lunch reminder**. There must be no per-tick repeats. A reboot/process recreation while waiting should preserve the target, where normal MVP recovery is permitted.
6. Tap SNOOZE 5M again. Expect one more reminder after five additional active minutes from this second tap, not from the original threshold. Tap START LUNCH; confirm ON_LUNCH in notification, widget, and Dashboard.
7. Verify no additional lunch alerts during lunch or after END LUNCH. Clock out and verify notification/service cleanup. Also try snoozing then starting lunch from Dashboard/widget before the target; no alert should occur at that obsolete target.
8. Restore threshold **360**, snooze **10**, and normal early offsets (**60/30/15 on**, **5/1 off**) or your original preferences.

Debug logcat filter remains `adb logcat -v time -s ShiftHUDWarning:D ShiftHUDAction:W ShiftHUDNotification:W ActiveShiftService:W AndroidRuntime:E`. Attention logs show event receipt, target active milliseconds, post invocation/return, and committed acknowledgement. Audible sound and OEM heads-up behavior require a physical device.

Snooze automated verification: full Gradle build and **163 tests per variant (326 executions)** passed; lint has **0 errors**, with the same 12 existing dependency advisories. Twenty-six new tests cover threshold/snooze crossing, millisecond targets, all durations, repeated/stale actions, failure and blocked-post acknowledgement safety, concurrent actions, preference changes, persistence/recovery, notification semantics, and repository-driven cleanup from Dashboard/widget/notification/clock-out. All prior tests remain passing.

Snooze emulator verification (Pixel 9/API 37): session 9 posted Lunch time at 60,045 active ms. SNOOZE 5M persisted target **448,434 active ms**, dismissed attention, and kept the silent ongoing card. Actual reboot restarted the service via BOOT_COMPLETED and recovered that exact target without opening the app; APK replacement also retained it. At 480,037 active ms (the first existing minute tick after target), Lunch reminder posted once with the selected Gentle Gong channel sound in Android’s notification record. A second SNOOZE 5M replaced the target with **839,779 active ms**. Dashboard START LUNCH cleared the persisted event/target; Dashboard and widget agreed on ON_LUNCH. Automated tests additionally cross obsolete targets after lunch/completion and cover repeated expiration. No physical audible playback was measured.

After END LUNCH, both app and notification displayed the completed interval without countdown/snooze actions. Clock-out completed normally and removed the foreground service. Emulator threshold 360 and snooze 10 were restored, with normal early offsets unchanged.


## Time corrections and automatic lunch end

ShiftHUD remains a personal tracker. Corrections are user-entered personal records; automatic lunch end is an **inference/fallback**, not evidence of returning to work. Neither feature modifies employer payroll/timekeeping, and no employer integration exists. Persisted event timestamps remain the source of truth; paid, active, store, and lunch durations are always recalculated, never editable counters.

Dashboard → **TIME RECORD / EDIT TIME** shows the current/latest completed session, dates, times, and derived totals. Existing clock-in, lunch-start, lunch-end, and clock-out entries use the same Material date/clock pickers as Schedule. Missing events remain “Not recorded” and cannot be fabricated by the editor. Date-aware Instant comparisons enforce chronology and reject future times. Nonexistent daylight-saving local times are rejected; an edit in a repeated hour preserves the original UTC offset when valid. A concurrent session change rejects a stale editor rather than overwriting it. A completed lunch that existed before this update can be corrected; for a legacy lunch still in progress, manually END LUNCH and then correct its recorded end if needed.

Corrections use `ShiftRepository.correct` in a Room transaction: reread/check the original session, validate, persist the corrected event and revision, then use shared refresh to reread/reconcile and update app, widget, and notification. Invalid edits leave the old record untouched. Session identity determines the latest record, so moving clock-in earlier cannot make another older session unexpectedly become current. Editing an automatic lunch end clears its Auto flag; its former inferred timestamp no longer affects any calculation.

**Automatic lunch end** defaults ON, **60 minutes**. Normal choices are **30/45/60/90**; debug builds additionally offer **2 minutes (debug test)**. Release builds hide/reject the test choice and treat a debug-only saved default as 60. START LUNCH captures the current enabled/duration choice into the session in the same transaction as lunchStart. `autoLunchMinutes = null` means that lunch has no automatic target; otherwise the intended target is exactly **lunchStart + captured duration**. Settings changes, including the enabled switch, apply to future lunches only. An explicit correction to an in-progress lunchStart moves its target using the same captured duration.

The existing shared refresh transactionally reconciles overdue ON_LUNCH sessions before rendering. It writes **lunchEnd = intended target**, WORKING, and `lunchEndAutomatic = true`, even if execution resumes minutes later. Manual END LUNCH that commits first wins; later auto checks no-op. Auto checks are idempotent and do not add another lunch, timer service, worker, polling loop, alarm, or elapsed counter. The existing service chooses the earlier of its next displayed-minute tick and a future auto-end target, rereading after reconciliation. Failed overdue reconciliation retries at the normal cadence rather than spinning. Process/boot/package recovery uses the persisted session duration/start and performs overdue work at the next permitted refresh. Android may delay execution; the stored inferred timestamp still uses the intended target.

Provenance lives alongside timestamps in Room, so it cannot drift away from historical records. Version **2** adds nullable `autoLunchMinutes`, boolean `lunchEndAutomatic` (default false), and `correctionRevision` (default zero). Migration **1→2** uses additive ALTER TABLE statements, with no destructive fallback. Old timestamps, schedule links, and notes are retained. Legacy sessions receive no guessed automatic duration, so an already-running pre-upgrade lunch is left for manual completion/correction. Auto is displayed in completed-lunch text across Dashboard/widget/ongoing notification and in the time record. A manual lunch-end correction removes that label.

A committed correction increments a metadata revision, not a timer. Early-warning evaluation baselines newly crossed boundaries and preserves earlier deduplication. Threshold/snooze evaluation retires a target crossed by a correction using a separate `skipped` flag (never a false successful-post acknowledgement), preserves targets still ahead in active-work units, and invalidates old action receipts. Previously delivered events do not replay when a timestamp moves back and forth. Revision matching survives a crash between the Room edit and DataStore reconciliation. Completed manual/automatic lunch suppresses all future lunch countdowns, threshold alerts, and snooze. User-selected Lunch reminders sound/importance/vibration remain authoritative.

### Short physical acceptance

1. Update the debug APK without uninstalling. In Settings, leave **Automatically end lunch** ON and select **2 minutes (debug test)**.
2. Clock in and START LUNCH. Confirm ON_LUNCH. Optionally change the default to 60 or reboot while waiting; this lunch must keep its captured two-minute target.
3. Do not tap END LUNCH. Around two minutes, or upon the next permitted recovery refresh, confirm WORKING and **Lunch taken · start–target · 2m · Auto** in app/widget/ongoing notification. Paid work resumes from the target.
4. Open **TIME RECORD / EDIT TIME**, choose Lunch end, and use the time picker to move it earlier while keeping it at/after lunch start. Save correction. Confirm Auto disappears and paid/lunch totals change immediately.
5. Edit Clock in earlier by several minutes. Confirm paid/store totals increase and no burst of historical warnings occurs. Try an invalid order and confirm an inline error with no saved change.
6. Clock out. Edit completed Clock in again and confirm completed Dashboard/widget totals. Dates are selectable for overnight/historical records.
7. Restore auto-end **60 minutes**, or your normal preference. Verify ordinary manual END LUNCH before a target still wins. For migrated legacy lunches, use manual end then correct the end timestamp as needed.

Verification: full Gradle build, **194 tests per variant (388 executions)**, and lint passed (0 errors; 12 existing dependency advisories). Thirty-one new tests cover all event edits, chronology/future/DST errors, immutable failed/stale edits, totals and presentation, reminder/snooze correction baselines, default/configured/disabled auto-end, captured settings, exact/delayed/recovered target, manual precedence, provenance correction, service wake timing, and actual v1→v2 migration/reopen with preserved rows. No destructive migration or new timing system is used.

Emulator verification (Pixel 9/API 37): upgrading the existing v1 installation to v2 preserved all **9 sessions and 1 schedule** byte-for-byte at the original column-value level. A new lunch captured the debug **2-minute** duration; changing the default to 60 during lunch and rebooting did not move it. Recovery automatically persisted lunchEnd exactly **120,000 ms** after lunchStart, with Auto provenance; widget and ongoing notification showed **2m · Auto**. A future picker correction was rejected without modifying the row. A valid lunch-end correction removed Auto and recalculated lunch/paid duration. Active clock-in correction, clock-out, and a completed clock-in edit all persisted. The completed edit from 1:18 PM to 1:15 PM increased paid/store totals by three minutes, and Dashboard/widget agreed (10m paid / 11m store). The default was restored to 60 minutes; the foreground service stopped after clock-out. Physical-device timing/sound/OEM restrictions remain subject to the acceptance checklist above.

## Dashboard gross-pay estimates

Dashboard shows **Estimated gross** for WORKING, ON_LUNCH, and COMPLETE, plus **THIS WEEK** paid time and gross. It uses the existing one-second Dashboard flow; no money counters or additional timers exist. Gross is computed from `ShiftEngine.durations(...).paid`, so lunch freezes earnings, manual/automatic lunch end resumes them, and timestamp corrections recalculate immediately. Widget and notification presentation are unchanged.

Settings → **Hourly rate** accepts USD $0.01–$1,000.00/hour, at most two decimal places (dot or comma decimal separator). Default is **$16.00/hour**. Choose an effective date using the calendar; saving the same date explicitly replaces that entry. A future raise preserves prior entries. The history is shown below the editor. The implicit $16 baseline applies before the first dated rate, including existing sessions; to correct an earlier rate, save a dated entry covering that period.

Rates use a separate Preferences DataStore (`pay_rates`, versioned `history_v1` key): a small, validated, sorted list of ISO effective dates and integer cents/hour. DataStore atomically merges updates, including concurrent saves; rates survive process recreation. This small settings history needs no relational joins or Room schema change. Missing history means the initial default; corrupt/unreadable history surfaces an unavailable estimate instead of silently resetting to $16, and corrupt history cannot be overwritten by a save. Room remains **version 2**, with the prior explicit 1→2 migration and all WorkSessions intact.

A session uses the rate effective on its **clock-in local date in the current device time zone**, even when it crosses a raise boundary. No intra-shift rate splitting occurs. A clock-in correction crossing an effective date intentionally reselects the rate. Changing the device time zone can change local date/week assignment near midnight; time-zone snapshots are not persisted in this MVP.

`PayEstimator` keeps calculation policy separate from UI/persistence for future overtime rules. Integer duration milliseconds × integer cents/hour are divided by 3,600,000 using `BigDecimal`; **HALF_UP** rounds once to integer cents, without floating-point multiplication or intermediate minute/hour rounding. Currency formatting uses exact decimal USD with localized separators. Weekly totals round each session's included contribution once, then sum cents, reconciling ordinary within-week shift totals.

The centralized `workWeekFor(date)` policy is **Saturday through Friday**, using the device local date. Each actual WorkSession belongs entirely to the week of its clock-in local date, including Friday→Saturday overnight shifts in Friday’s week. Paid and gross share this same session set and elapsed work is capped at now; sessions are not split at midnight or week boundaries. The original clock-in effective rate applies. Calendar boundaries handle DST without assuming seven 24-hour days. Future schedules never enter the estimator.

These are personal base-rate estimates, not employer payroll. Overtime premiums, taxes, withholding, bonuses, differentials, payroll-specific rounding, rate splitting, configurable week starts, and widget earnings are intentionally deferred.

Gross-pay verification: full `./gradlew build` passed, including **220 tests per variant (440 executions)** and lint (0 errors; 12 existing dependency advisories). Twenty-six added tests cover exact amounts, lunch exclusion/freeze/resumption, all timestamp corrections, active/completed totals, weekly aggregation and boundaries/DST, rounding/large values, effective dates, rate replacement/concurrent updates/reopen, corrupt-history rejection, and unchanged persisted sessions. All existing tests, including Room migration coverage, remain intact. Pixel 9/API 37 upgrade retained 10 sessions and 1 schedule at schema 2. Dashboard showed the existing completed shift and week at $2.74; saving a $16.50 rate effective tomorrow left both unchanged. Rate history survived process restart, and malformed numeric input was rejected without saving. Dashboard and Settings were visually inspected for clipping.


## MVP-004 — Quick Find

Quick Find is a personal, local-only memory aid answering “Where is this item in my store?” It is not a replacement for Publix Pro, inventory, stock availability, or official store data. There is no networking, scraping, employer API/authentication, or shipped aisle database. New installations and upgraded installations start with an empty Quick Find collection. User-entered data stays in the existing on-device Room database; Android backup remains disabled.

Open **Quick Find** from the bottom navigation (Material search icon), then type a name, alias, aisle/location, or location note. Search normalizes case with Locale.ROOT and collapses whitespace, including Unicode spaces. Partial substring matching is immediate over the small Flow-backed collection, without search-time database writes or heavyweight full-text indexing. Ranking: exact name → name prefix → exact alias → alias prefix → name contains → alias contains → location/note contains. Favorite, recent use, use count, normalized name, and ID deterministically resolve equal-ranked matches.

An empty query shows all **Favorites** independently of **Recent Finds** (latest eight intentionally selected records). Tapping a compact result opens details and atomically updates `lastUsedAt` and increments `useCount`. Merely appearing in a result or toggling a favorite does not count as use. Details offer Edit, Favorite/Unfavorite, and Delete with confirmation. Favorites and recent metadata persist across restarts.

**Add Item** and **Edit** share one form: required name and text aisle/location, optional note/comma-separated aliases, and favorite. Aliases are whitespace-normalized, lowercased, deduplicated, and stored as a JSON string array in one Room column; the domain model exposes a list for future CSV/JSON backup. Numeric/simple aisle values (4, 12A, 4-5) display with “Aisle”; named departments such as Frozen/Produce display unchanged. No display prefixes are stored. A transaction plus unique normalized-name index prevents duplicate names, including concurrent saves; the form offers **EDIT EXISTING ITEM** instead of silently inserting a duplicate. Multiple items may share a location.

Room **version 4** retains the explicit **2→3 migration** for `quick_find_items` and adds **3→4** for the editable `aisle_guide` table and normalized-location unique index. Existing schedule/session tables, timestamps, lunch metadata, pay-rate DataStore, and reminder settings are unchanged. The previous **1→2** migration remains registered so older installations migrate through the full chain. The exported schema is committed; no destructive fallback exists. The application container supplies `QuickFindRepository`/`QuickFindViewModel`, with no new DI framework or changes to the shift engine/service/notification architecture.

Normal/expanded widgets place a compact **QUICK FIND** shortcut beside the existing 48dp primary action. Compact widgets omit it. The explicit MainActivity destination intent is distinct from Dashboard/Schedule, supports cold and existing-activity launches, and requests search focus and the keyboard where Android permits. Bottom-navigation entry also focuses search. The widget has no text input, and notifications gain no extra action.

### Device acceptance

1. Upgrade the APK without uninstalling; confirm existing schedule, time records, and pay-rate history.
2. Open Quick Find and verify focus/keyboard. Add Worcestershire Sauce, location 4, note “Condiments · lower shelf”, aliases “worc, steak sauce”.
3. Search `worc`, `WORC`, `steak`, and `lower`; verify immediate compact results. Select it, favorite it, close, and clear search. Verify Favorites and Recent Finds.
4. Edit its location/note and confirm searches reflect the new values. Try adding the same name with different case/whitespace; use EDIT EXISTING ITEM.
5. Add Honey and Frozen Pizza (location Frozen); confirm “Aisle 2” and “Frozen”, not “Aisle Frozen”. Inspect several compact rows with the keyboard visible.
6. On a normal/expanded widget tap QUICK FIND with the app stopped and again while it is open on another tab. Verify direct navigation, focus, and keyboard. Confirm compact widgets retain the primary action.
7. Delete a test record: cancel once, then confirm. Restart and verify saved records/favorites/recents persist.
8. During an active shift, search/edit items and verify paid time and ongoing notification continue. Check normal lunch/clock-out actions.

MVP-005 retains history/statistics/polish. Quick Find CSV/JSON import/export, cloud sync, fuzzy/misspelling search, and large-data indexing are deferred. The plain domain fields and JSON alias list leave future backup straightforward.

### Initial MVP-004 verification

Full `./gradlew build` passes with **259 tests per variant (518 executions)**, including all prior tests and 39 new Quick Find/search/repository/migration/navigation tests. Lint reports **0 errors and the same 12 existing dependency advisories**. Schema comparison confirms the two existing Room tables are identical between exports 2 and 3. Automated upgrade tests cover both v1→v3 and v2→v3, retaining schedule fields, completed/active session timestamps and lunch/correction metadata, and effective-dated pay history; new Quick Find records survive reopening. A real navigation-controller regression test covers returning to Dashboard after direct widget entry instead of restoring the child stack.

Pixel 9/API 37 emulator upgrade preserved all **10 preexisting WorkSessions and 1 schedule** field-for-field. Pay-rate and reminder preference files retained identical SHA-256 hashes (only Glance's cached layout changed). Quick Find began empty. Manual acceptance covered item creation, upper/lowercase/alias/note search, favorites/recents, duplicate-name warning and Edit Existing, location/note edits, numeric/named location labels, multiple compact results above the keyboard, deletion cancel/confirm, and persistence after APK replacement/process recreation. The actual widget shortcut opened focused search with the keyboard after background process death and during an active shift. Android force-stop disables the widget until the app is reopened; ordinary process-death recovery worked. While searching during a test shift, the foreground service and silent ongoing notification remained active; returning to Dashboard showed paid time and gross advancing normally. Physical-device/OEM keyboard and launcher variations remain for the documented acceptance checklist; no physical device was connected.


### Editable Aisle Guide and expanded widget references

Quick Find has a separate **AISLE GUIDE** section and **+ ADD AISLE** control. Save a text aisle/location and comma-separated categories; tap a mapping to edit it or delete it with confirmation. Empty/duplicate normalized locations and empty categories are rejected. Category spacing is cleaned and duplicate categories removed; Room stores plain comma-separated values, while presentation uses centered dots. Numeric aisles sort naturally (1, 2, 10), followed by named departments. Guide mappings participate in the same case/whitespace-insensitive substring search: searching “rice” finds a saved “Pasta, Rice” mapping. Item results and guide results have distinct sections; selecting a guide opens its editor without changing item recent-use counts.

Both guide data and favorite items come directly from Room. **No store defaults are compiled or seeded**, and the widget keeps no independent copy. Room **3→4** adds only the guide table/index; all preexisting tables and preferences remain intact. Item save/edit/delete/favorite/unfavorite and guide save/edit/delete invoke the existing shared widget refresh after persistence, outside the transaction. Glance also observes the Room flows during live compositions. Searching does not write data, and item selections do not change the widget's stable alphabetical favorite order.

The **320×300 dp expanded** layout includes up to one favorite and two naturally ordered guide rows. After completed lunch it reserves extra room for the lunch interval/duration by showing at most one guide row. A new **320×360 dp tall** layout supports two favorites and three guide rows (two after completed lunch). Headers indicate additional hidden records; tapping references or QUICK FIND opens the complete searchable list. Guide rows use bold location labels, secondary categories wrapping to two lines, modest spacing, and subtle separators between rows. Full values remain available in Quick Find. The shift headline, critical lunch/paid/out information, and 48dp primary action retain priority; optional expanded next-shift/clock-out hints yield their space to references. Compact and normal layouts remain shift-focused, with the existing normal QUICK FIND shortcut. There is no second timer, widget database, service, or notification action.

Acceptance: add mappings through Quick Find, search a category, edit it, and return Home without tapping Refresh. Verify changed content on the expanded widget. Favorite/unfavorite or edit an item and repeat; delete a mapping with confirmation and verify it disappears. Restart the app to verify persistence. Resize compact/normal/expanded/tall and exercise WORKING/ON_LUNCH/completed-lunch states; primary shift controls must stay usable. Examples in documentation/tests are not application defaults.

Guide follow-up verification: full Gradle build and **280 tests per variant (560 executions)** pass; lint has 0 errors and the same 12 existing dependency advisories. Twenty-one added tests cover guide CRUD/validation/concurrent duplicates, category search/normalization/natural ordering, refresh-after-commit for guide and favorite mutations, bounded reference layouts, completed-lunch space, and v3→v4 preservation/reopen of schedules, sessions, favorites/aliases/usage, and pay history. Schema export comparison confirms all three preexisting tables are unchanged.

Emulator upgrade preserved **11 sessions, 1 schedule, and 2 Quick Find items** field-for-field; pay-rate/reminder preference hashes were unchanged. UI-entered aisle examples appeared in the expanded widget without manual refresh. Searching rice found the guide entry; appending a category immediately updated both search and widget. Unfavoriting removed the widget favorite and favoriting restored it. WORKING, ON_LUNCH, and completed-lunch layouts retained readable critical information and usable primary actions; completed lunch reduced guide rows and showed an overflow count. Widget START/END LUNCH and Dashboard clock-out passed. Test data exists only in the emulator, never in shipped defaults. Tall/compact/normal reference bounds are unit-tested; OEM launcher/font variations remain physical-device acceptance work.

### Add previous shift (focused operational enhancement)

Dashboard → **ADD PREVIOUS SHIFT** creates a normal, completed WorkSession from actual historical punches. Use the existing Material date/time pickers for the start date, clock in/out, and optional lunch start/end. Both lunch endpoints must be supplied or both left blank. Store time, lunch, paid time, and estimated gross are derived before saving; they are never editable counters. Effective-dated pay uses the clock-in date, not today's rate. These are manually entered personal records and estimates: the employer timecard remains authoritative, and ShiftHUD never changes employer payroll.

The selected date is the clock-in date. Wall times earlier than clock in resolve to the following day, and the form shows that date beside each affected punch. Equal clock-in/out is rejected. Chronology, future clock-out, and nonexistent daylight-saving times are validated before persistence and again within the insertion transaction. Repeated DST wall times resolve to the first occurrence (shown in the form guidance); Edit Time retains an existing offset. The preview uses elapsed instants, not formatted strings.

`ShiftRepository.addHistorical` inserts directly in COMPLETE state with no simulated clock/lunch actions. Half-open interval overlap checks run in the same Room transaction: exact duplicates and partial overlaps are rejected with **EDIT EXISTING / CANCEL**. Back-to-back or separated sessions on one date remain valid. Historical time corrections also reject overlaps. A schedule is linked only when exactly one schedule on the clock-in date overlaps at least half of both its planned duration and the actual session. Ambiguous/nonmatching entries remain unscheduled; planned times never change.

Room **version 5** adds only `work_sessions.manuallyEntered INTEGER NOT NULL DEFAULT 0` through explicit **4→5** migration. Existing records remain ordinary live-recorded records; all earlier migrations stay registered. Manually entered sessions retain their provenance through normal time corrections, contribute through the same session Flow to weekly totals and future history, and appear in **TIME RECORD / EDIT TIME**. All completed sessions, including ordinary recorded sessions, offer confirmed per-record deletion. Active sessions cannot be deleted. Deletion removes their hours/gross and does not affect employer records.

The work week is **Saturday–Friday**. An existing Saturday October 3 record contributes automatically to Tuesday October 6's THIS WEEK total (October 3–9), with no re-entry or data rewrite. A record within the displayed week immediately updates hours/gross without restarting; editing/deleting it does the same. Do not reverse-engineer lunch punches from an aggregate like 8.10 decimal hours; use the actual employer punches and allow for display rounding.

Current-session queries prioritize active state, then actual end/start timestamps, rather than insertion ID. Dashboard/widget still filter completed sessions by today's date. Adding an older record cannot displace an active or more recent completed record. Historical insert/edit/delete uses a redraw-only widget callback: no lunch reconciliation, snooze, notification refresh, or foreground-service synchronization is invoked.

Device acceptance: enter the actual missing shift punches, compare the derived paid duration and gross before saving, verify the normal completed record, unchanged today's Dashboard/widget, correct week inclusion, no new service/notification, normal Edit Time behavior, and confirmed deletion. Emulator fixtures are synthetic and must not be treated as the user's employer punches. This work does not begin general MVP-005 history/statistics development.

Historical-entry verification: full Gradle build, **318 tests per variant / 636 passing executions**, and lint (0 errors; 12 existing dependency advisories). Thirty-five new tests cover validation/overnight/DST, effective rates, transactional overlap/concurrency, schedule matching, current Dashboard/widget selection, no service/notification/lunch-reconciliation side effects, reactive insertion/correction/deletion totals, and v4→v5 migration/reopen. Earlier migration and persistence tests remain in the suite, now validating through schema 5.

Pixel 9/API 37 emulator upgrade retained 12 sessions, 1 schedule, 2 Quick Find items, all guide mappings, and preference hashes. Synthetic Monday punches added 9h/$144.00 to THIS WEEK while Dashboard stayed OFF TODAY and the widget retained today's state. Duplicate entry offered EDIT EXISTING; a normal clock-out correction reduced the contribution to 8h/$128.00; confirmed deletion restored the exact original totals and database records. No ActiveShiftService was started. The actual Saturday employer punches were not supplied, and no physical device was connected; that acceptance check remains pending.

### Saturday–Friday work-week correction

`domain/pay/WorkWeek.kt` is the single date-boundary helper for all work-week reporting. October 3–9, 2026 is one week; October 10 starts the next. Weekly paid time and gross use the same actual-session selection by local clock-in date. Friday→Saturday stays in Friday's week, Saturday→Sunday stays in Saturday's week, and active/future punches remain capped at now. This replaces the initial Monday-start/calendar-clipping assumption without changing insertion, duplicate protection, schedules, or persisted timestamps. Room remains version 5; no migration or data rewrite is needed.

The supplied October 3 punches (04:00, lunch 09:47–10:41, out 13:00) contribute **8h 06m / $129.60 at $16/hour** on October 6. A regression reads an already-persisted manual row and confirms this contribution without re-inserting it, then verifies duplicate rejection and unchanged stored values. Nineteen new tests cover all weekdays, rollover, overnight/time-zone/year boundaries, matching paid/gross session sets, the Dashboard label, and existing-record inclusion; prior Monday-start tests were updated rather than removed.

Verification: full Gradle build, **337 tests per variant / 674 passing executions**, lint 0 errors with the same 12 dependency warnings, and diff review. Installing over the existing Pixel 9/API 37 emulator app changed THIS WEEK from 0h 12m/$3.42 to 1h 11m/$19.18 using its existing weekend records. All 12 sessions, the schedule, Quick Find items, aisle mappings, and preferences were unchanged. Dashboard stayed OFF TODAY and the widget retained today's state. No records were deleted/re-added. Physical-device upgrade acceptance remains unverified because only the emulator was connected.

### Dashboard Work Calendar and session reconciliation

**WORK CALENDAR** sits directly below THIS WEEK, with Saturday-first columns, previous/next month navigation, and a five/six-row grid. Dashboard entry defaults to the current month; inserting a historical shift does not change the selected month. Days use distinct symbols as well as color: **● worked/completed**, **▶ active (including lunch)**, **○ scheduled without recorded work**, and **– no shift**. Today has an outline, the active Saturday–Friday week has a subtle background, and accessible descriptions give full dates/weekdays and combined states. Day targets are at least 48dp wide; narrow windows can pan the grid.

Worked indicators come only from WorkSessions, including unscheduled work. A past or future schedule alone never implies attendance. Tap a day to see each actual session's punches, lunch, paid/store duration, and effective-rate gross, with a daily aggregate when there are multiple sessions. Planned times remain separately labeled. **EDIT TIME** opens the existing Time Record editor for that specific ID; **DELETE SHIFT RECORD** appears for every COMPLETE session, requires confirmation, and is unavailable for WORKING/ON_LUNCH sessions. Room updates refresh day details, indicators, and applicable weekly totals automatically. No records are automatically hidden, merged, deleted, or corrected to match an expected total.

Tap **THIS WEEK** or **VIEW WEEK BREAKDOWN** to inspect every included session. `PayEstimator.weekBreakdown` returns one ordered `SessionBreakdown`; both Dashboard totals and the displayed list use that exact result. Existing `week()` delegates to the same result. Selection still uses the centralized Saturday–Friday helper and local clock-in date; each contribution retains its historical effective rate and per-session cent rounding. Duplicate and zero-duration records remain visible for diagnosis. Normal duration labels use whole minutes (for example 3m or 6h 19m); individual displayed minutes may not sum exactly to the displayed aggregate because sub-minute precision is retained internally. The underlying breakdown/aggregate durations and gross remain exact; even sub-minute sessions remain listed. Missing rates show unavailable gross rather than hiding sessions/hours; failed/loading weekly reads are not presented as zero totals.

Calendar cells are ephemeral domain presentation models. Dedicated Room Flow queries fetch only clock-ins within the displayed month's padded work-week grid (35/42 days) and scheduled dates within that range. Weekly records have a separate bounded seven-day query, and selected records are observed by ID so editing also works outside the visible month. Query boundaries use local calendar midnights (including DST). Neither calendar navigation nor weekly reconciliation loads the full historical WorkSession collection. One shared existing Dashboard tick updates elapsed values; Room queries restart for relevant range/zone changes and react to database invalidations, not every second. No calendar dependency, stored cells, migration, timestamp rewrite, or new timer counter was added; Room remains schema 5.

Validation: full Gradle build; **372 tests per variant / 744 passing executions**; lint 0 errors and 12 existing dependency warnings. Thirty-five new tests cover month padding/navigation, all day states, multiple sessions/daily totals, Saturday-first/current-week alignment, overnight dates, exact breakdown/aggregate reconciliation, visible unexpected/zero-duration records, effective data bounds/DST, reactive insert/edit/delete/clock-out/schedule changes, and read-only browsing. Existing tests are preserved.

Pixel 9/API 37 emulator: upgrade preserved all 12 sessions, schedule, Quick Find/guide data, and preferences. Calendar indicators exposed worked October 4/5 dates, a multi-session day showed its aggregate and individual punches, and EDIT TIME opened the correct old record without offering deletion for ordinary recorded sessions. Weekly details matched Dashboard exactly at **1h 11m 57.142s / $19.18**. September/October navigation worked without changing totals or data. Only the emulator was connected; the user's physical-device **29h20m / $469.53** discrepancy has not been diagnosed or altered. Use the new week breakdown on that device to inspect each contribution before deciding whether any punch needs correction.


### Delete any completed shift record

Work Calendar → date → specific session → **DELETE SHIFT RECORD** now applies to both normally recorded and manually added COMPLETE sessions. Time Record offers the same action. A shared destructive confirmation displays the full date, actual start/end (including an overnight end date), paid minutes, and the warning that only the personal ShiftHUD record is removed—not the employer timecard. There is no delete-day action. WORKING/ON_LUNCH records have no delete control and are rejected by the repository and DAO even if a stale/direct caller attempts deletion.

Deletion checks the selected record against its current Room value in a transaction, then deletes strictly by stable ID and COMPLETE state. Stale/concurrent confirmations cannot erase an updated or different session. ScheduledShift rows, effective-dated pay history, unrelated records, and current/today selection are preserved. Existing Room Flows refresh day details, calendar markers, weekly breakdown, paid hours/gross, and Dashboard; widget redraw follows persistence. Remaining same-day sessions retain the worked marker; removing the last one yields scheduled-only or no-shift state as appropriate. Totals are recomputed, never manually decremented.

Metadata audit: auto-lunch/provenance/correction fields live inside the WorkSession row and disappear with it. After Room commits, a non-cancellable handoff serializes with notification refresh to remove only warning-delivery and lunch-attention/snooze JSON whose stored session ID matches the deleted ID. A displayed warning is cancelled only when its receipt's full ID component matches (ID 1 does not match ID 10). No global reminder settings, pay rates, or other session's warning/snooze/notification state is cleared. Notification actions already revalidate their session ID against Room, so stale actions cannot resurrect a deleted record. This cleanup does not start/reconcile the active shift or invoke a new alert. Room and DataStore remain separate persistence operations; cleanup failures are surfaced, while widget redraw still runs.

All normal duration displays use minute precision: 6h 19m, 3m, 1h 55m. Calendar punch labels also omit seconds. The formatter floors the presentation only; persisted instants, elapsed calculations, and gross pay keep their original precision. Widget/notification duration formatters were already minute-based and remain so. No Room migration or timestamp rewrite is introduced; schema stays version 5.

Validation: full Gradle build passed; **387 tests per variant / 774 passing executions**, no failures or skips. Lint reports 0 errors and the same 12 dependency advisories. Fifteen new tests cover ordinary/manual completed deletion, both active-state guards at repository/DAO level, exact IDs and concurrent deletion, same-day indicators, schedule preservation, paid/gross recalculation, current-state safety, scoped warning/snooze/receipt cleanup, pay-history preservation, and minute display with precise gross. Existing tests remain intact.

Pixel 9/API 37 emulator acceptance: confirmed nine existing Sunday October 4 development records individually through Day Details. After the first deletion, weekly gross immediately fell from $19.18 to $17.67 and Sunday remained worked. After the last, Sunday became scheduled-only and Dashboard/week breakdown both showed **12m / $3.42**, derived from the three remaining Monday records. Database comparison confirmed only the selected Sunday IDs were removed; all three Monday rows (including timestamps), schedule, Quick Find/guide data, and preference hashes were unchanged. Confirmation styling/date/punches/paid minutes/disclaimer and minute-only weekly labels were inspected. No physical device was connected, so the user's physical Sunday records, Saturday/Tuesday sessions, Wednesday/Friday schedules, and reported 29h20m/$469.53 total remain unverified and untouched.
