# MVP-006C.3 smart lunch alerts

## Dependency and behavior

Based on `mvp-006c2-final-ui-cleanup` at `36c3a2c` (PR #18 was verified open). No existing PR was merged or rebased.

Minimum alerts default on; planned alerts and automatic snooze repeats default off; default snooze is 10 minutes. Existing work-threshold warnings, their snooze preference, channel settings and automatic lunch-end preference remain unchanged. New settings live under **Notifications & Garmin → Lunch Alerts**.

The minimum deadline is persisted lunch start + 31 minutes. The optional planned deadline uses the existing weekly preference attributed to the active session's **clock-in workweek**, including Friday/Saturday overnight lunches. Pending deadlines recalculate after plan edits. A delivered minimum suppresses a planned deadline of 31 minutes, including when a plan is changed to 31 afterward. If minimum alerts are disabled, a planned-only 31-minute alert can still be delivered.

Each notification offers Snooze and Open ShiftHUD. Presets are 5/10/15/20/30 minutes; custom values are 1–120. Snooze targets the action time plus the selected duration, consumes its action receipt once, and replaces the single pending chain. Repeat enabled automatically schedules another interval after a snoozed notification; repeat disabled requires another explicit Snooze action. No action clocks in, ends lunch or writes a punch.

**Existing automatic lunch completion remains a separate feature.** If manual-only lunch completion is wanted, disable that existing setting. The new alerts do not enable, disable or invoke automatic completion; existing normal app/service reconciliation continues to honor the user's setting.

## Scheduling and recovery

The existing active-shift foreground loop now wakes at the next lunch deadline, with one unique persistent WorkManager request as fallback even without a widget. The worker reads Room and preferences afresh and never reconciles or writes lunch timestamps. Existing boot/package-replaced recovery refreshes lunch reminders and recovers the existing service. No new service, exact-alarm permission, wake lock, notification channel or Garmin SDK is introduced.

Receipts and targets persist in local `on_lunch_alerts` preferences with synchronous checked commits. Android's current receipt protects against an interrupted post/commit retry. Session ID, lunch-start timestamp and correction revision validate actions. Ended/clocked-out sessions cancel the notification and pending work. Corrected/new session identities reject stale actions. Permissions and channel enablement are checked before posting; blocked delivery remains retryable within the freshness window.

To avoid historical catch-up, a newly observed lunch does not replay already-past boundaries. An armed deadline more than 10 minutes overdue expires, and lunches older than 24 hours are not eligible. Android Doze, force-stop, battery restrictions and alert-rate limits can delay or prevent delivery; this is not an exact alarm guarantee. Reopening ShiftHUD recovers eligible pending work. Never-before-31 timing is enforced at delivery using persisted timestamps.

All production alerts reuse `lunch_reminders`, preserving user sound/vibration/DND. New production ID 1010 is distinct from active shift 1001, pre-lunch 1002, end 1003 and weekly/planner IDs. Test IDs 2005–2007 are independent, use the same channel, obey the existing test cooldown, and cannot consume real receipts or snooze targets. Watch delivery cannot be confirmed by Android posting.

The new local settings/receipts are device-local and do not extend the encrypted backup format. Backup/restore code and behavior were not changed or tested through restoration. No Room migration, payroll, historical analytics, schedule or weekly-planning calculation change is included.

## Physical Garmin acceptance

1. Install the debug APK over the current app; do not uninstall or clear data.
2. Check Android notification permission, Lunch reminders channel sound/vibration and Garmin Connect Smart Notifications for ShiftHUD. Do not bypass DND.
3. In Lunch Alerts, run each test at least three seconds apart. Verify phone delivery first, then watch forwarding. Watch action support is optional.
4. Enable minimum and planned alerts; set the active shift's weekly lunch to 48 minutes. Disable the separate automatic-end setting if testing manual-only completion.
5. Start lunch normally. Confirm END LUNCH stays prominent, minimum countdown reaches 31 actual minutes, and the phone/watch receive the minimum alert. Confirm the planned alert at 48 minutes.
6. Try Snooze (including custom 1-minute acceptance interval). Verify timing is relative to the tap and the lunch timer/punch stays unchanged. With repeat enabled, verify later repeated reminders; another Snooze must replace the pending chain.
7. End lunch or clock out before a pending reminder and verify cancellation. Try plan 31 and confirm no duplicate minimum/planned alerts.
8. Check an overnight Friday shift, process restart and reboot. Confirm late historical reminders do not replay. Check the phone's battery restrictions if alerts are delayed.

## Verification results

- Scoped full Gradle build, debug APK, unit tests and lint passed. Debug APK installed in place without uninstalling or clearing app data.
- 838 tests per debug/release unit variant, 1,676 executions, zero failures. Backup restoration tests (18 per variant and the backup restoration device class) and release packaging are explicitly excluded; repository test configuration is unchanged.
- 25 eligible emulator tests passed at 200% font/dark mode, including labeled lunch controls and existing Dashboard/Schedule/state tests. The new Settings selector was disambiguated from the search field.
- Actual minimum notification verified after 31 persisted elapsed minutes, on the existing lunch channel. Its real Android Snooze action created one one-minute target without changing punches.
- Reboot during snooze recovered the existing foreground service from `BOOT_COMPLETED` before opening the app (ID 1001, unchanged channel). The persisted snooze fired once at its target; refresh after cancellation did not replay it. Lunch punches remained unchanged.
- Manual end-lunch fixture action cancelled pending work and the production alert. All three real test alerts posted on the shared channel without changing the ended session. The test waits for Android posting and spaces manual tests three seconds apart.
- Lint: zero errors, 16 warnings, one hint. Two added KTX suggestions concern deliberately checked synchronous SharedPreferences commits. Diff check passed.
- Original emulator app files were restored and verified byte-for-byte; original notification permission and display settings restored. No physical-device data was accessed. The app backup restoration feature was not exercised.
- Garmin forwarding, audible/vibration behavior and phone-specific battery restrictions remain physical-device acceptance checks.
