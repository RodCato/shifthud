# MVP-006C.12 — Actual Schedule punches

Base: `mvp-006c11-dual-time-display`, `dc24d270caae8a8a41c58d88caaf068dfdd8cfa7` (open PR #27). Main was fast-forward checked; no dependency PRs merged.

## Presentation and authority

Schedule now uses the existing explicitly linked WorkSessions for its primary clocked line. A completed session shows `CLOCKED: start – end`; a live session shows `CLOCKED IN: start · WORKING` or `ON LUNCH`, never a presumed end. Future/unworked schedules show `Scheduled: start – end`. While records load, the clocked line is explicitly loading.

Scheduled values remain muted secondary text whenever they differ. The duplicate scheduled line is omitted only when a single session's start and end Instants match the scheduled Instants exactly, including seconds/fractions. Hours/minutes remain the default; existing nonzero seconds/fractions are displayed so a discrepancy cannot be hidden by minute formatting. Device 12/24-hour preference and locale are respected. Dates accompany punches outside the schedule date, including overnight ends.

Multiple sessions show a count and an expansion prompt; expanded rows show each separately numbered recorded interval. No first-in/last-out span is fabricated across breaks. Mixed completed/active sessions retain actual clock-in information in the collapsed summary and separate intervals in details. Unscheduled work is never matched by date alone. Domain models already reject missing clock-in and invalid completed clock-out records; absent linked records never receive invented punches.

The expansion action is named by date so TalkBack does not announce an unlabeled scheduled interval as the actual interval. Paid hours, decimal hours, lunch details, completion status, weekly totals and all calculation/persistence paths remain unchanged.

The regression fixture's 04:00–13:00 work and 09:50–10:47 lunch are exactly 57m unpaid, 8h3m paid and 8.05 decimal hours; displayed totals are derived, not adjusted to illustrative numbers.

## Physical acceptance

1. Install the debug APK over ShiftHUD; do not uninstall or clear data.
2. With scheduled 04:02–13:00 and actual 04:00–13:00, confirm collapsed `CLOCKED: 4:00 AM – 1:00 PM` and secondary `Scheduled: 4:02 AM – 1:00 PM`.
3. Edit scheduled start/end: clocked times, actual paid/decimal hours and actual lunch must remain unchanged. Correct a punch through the existing editor: the clocked display should update independently.
4. For an exact match, confirm the duplicate scheduled line is omitted. Check second-level differences remain visible.
5. Inspect active work, active lunch, overnight and multiple-session days. Expand multiple sessions and verify every separate interval; no guessed clock-out or continuous span across sessions.
6. Check future rows, week navigation, decimal preference off/on, 200% font wrapping and spoken TalkBack expansion/read-only completion semantics.

No Room migration, historical punch writes, lunch-record changes, payroll/planning changes, notifications, Garmin, widgets, OCR, backup/restore or Frozen Operations work. No backup restoration tests or signed release.

## Verification

- `assembleDebug test lint assembleDebugAndroidTest`: passed using JDK 17. Backup restoration unit tests explicitly excluded.
- Unit tests: 886 debug and 886 release-variant tests passed, zero failures/errors/skips. No signed release build produced.
- Android lint: zero errors; 16 existing warnings and one hint.
- New presentation regression covers matching/differing punches, independent schedule/punch corrections, actual paid/lunch/decimal stability, multiple sessions, active work/lunch, overnight dates/precision, missing and unscheduled records.
- Emulator presentation checks: 320dp layout, 200% font, decimal toggle, accessible expansion button and read-only completion indicator. Spoken TalkBack and physical-device behavior remain manual acceptance checks.
- Screenshots (local, ignored build artifacts): `app/build/reports/schedule-punches/schedule-punches-{matching,different-with-lunch,large-font,working,on-lunch,scheduled,multiple}.png`. These are in-memory fixtures rendered with the actual Schedule components, not persisted user data.
- Installable debug APK: `/Users/crod/Documents/ChatGPT/Publix work app/app/build/outputs/apk/debug/app-debug.apk`. Installed over the existing emulator application with `adb install -r`; no uninstall or data clear.

## Files changed

- `app/src/main/java/com/shifthud/ui/schedule/SchedulePunches.kt`: derived actual/scheduled timestamp presentation.
- `app/src/main/java/com/shifthud/ui/schedule/ScheduleScreen.kt`: heading integration and separate expanded session intervals.
- `app/src/test/java/com/shifthud/SchedulePunchesTest.kt`: source-authority and presentation regressions.
- `app/src/androidTest/java/com/shifthud/ui/SchedulePunchesDeviceTest.kt`: rendering/accessibility checks and screenshot fixtures.
- `docs/ACTUAL-SCHEDULE-PUNCHES-006C12.md`: implementation, verification and acceptance notes.

Final emulator result: 39 eligible tests passed on Pixel 9 API 37.1, including existing navigation, weekly planning, actual-hours and widget regressions. Backup restoration instrumentation class excluded. Read-only before/after comparison confirmed every original Room table's contents and the user preferences bytes unchanged. Emulator font, theme and notification permission were restored after testing.
