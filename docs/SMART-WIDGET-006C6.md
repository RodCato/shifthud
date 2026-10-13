# MVP-006C.6 — Smart Glance widget

## Dependency and scope

Based on `mvp-006c5-visual-hierarchy` (`1b3cacc`, PR #21, still open). This PR changes widget presentation and tests only. No business logic, payroll, WorkSession, schedule, historical analytics, weekly lunch plan, notification/snooze, Room schema, migration, or backup/restore implementation changes. No signed release build or Frozen Operations implementation.

## Presentation

The lunch clock uses the persisted lunch-start timestamp and a native RemoteViews Chronometer. Android animates the visible clock; no per-second application timer or new background scheduler is introduced. The existing active-shift service, widget refresh flow, fallback work and reboot recovery remain authoritative. Paid time uses the existing PayEstimator/ShiftEngine and weeklyTarget calculations. Today's attribution follows the existing clock-in workday convention, including overnight work.

Central presentation thresholds live in `WidgetMetrics.kt`:

- Before the existing C.3 31-minute lunch minimum: amber, rounded-up remaining minutes.
- Minimum reached: green.
- Weekly planned lunch reached: blue. The weekly preference is resolved using the active session's clock-in date, matching C.3 notifications.
- Five minutes beyond planned: orange, extended lunch. Five minutes is a presentation default; it changes no policy, alert or persisted plan.
- Precedence: before minimum, extended, planned, minimum. A planned value at/below the minimum therefore turns blue at 31 minutes. The planner's configurable lower bound is distinct from C.3's existing fixed 31-minute minimum notification rule.
- Weekly progress: green below 75%, blue from 75%, amber within two hours, green exactly at target, red above target. Red includes “Approval required”. Target comes from existing weekly settings.

Colors always have accompanying status text and exceed 4.5:1 contrast against the unchanged dark-green background. The primary lunch clock and action stay outside the secondary scrolling region. Expanded sizes include weekly progress and Room-backed Favorites/Aisle Guide; larger sizes display more references. At large font sizes secondary content can scroll. All existing action callbacks remain unchanged; heading navigation opens the Dashboard for the existing clock-out confirmation flow.

## Verification and limits

- Gradle build and debug/test APK assembly passed. Unit tests: 856 per variant (1,712 executions), zero failures. Lint: zero errors, 16 existing warnings and one hint. Existing exclusions for backup restoration tests and release packaging remain in effect.
- New pure presentation tests cover exact lunch/weekly boundaries, deterministic precedence, agreement with the C.3 minimum alert, paid-time pause/resume, actions, reconstruction from recorded timestamps and color contrast. Existing widget/action/Quick Find regression coverage is retained.
- All 30 eligible emulator regression tests passed; backup restoration acceptance remains excluded.
- Emulator rendering uses real Glance RemoteViews in an AppWidgetHostView across five sizes and four lunch states, at normal and 200% font sizes. Checks cover visible primary controls, timer prominence, colors, weekly content, Quick Find label fit and accessibility descriptions. The sample data exists only in memory; screenshots are rendered test views, not persisted work records.
- The live background 31-minute transition and active-session reboot scenario have not been verified on a device. Automatic approval review rejected replacing the existing emulator database with synthetic records. Permission to use a separate disposable emulator is pending; no such database fixture was run. Pure state reconstruction does not substitute for that device acceptance.
- Spoken TalkBack and Garmin forwarding require physical-device testing.

## Physical-device acceptance

1. Install the debug APK over the existing app without clearing storage. Confirm records, preferences, Quick Find and notifications remain present.
2. Add/resize the widget through compact, normal and expanded sizes. Confirm Clock In, Start Lunch, End Lunch, Open App/clock-out navigation and Quick Find retain their existing behavior.
3. During a real lunch, leave the app closed. Before 31 minutes verify amber/countdown; at 31 verify green/“Minimum reached” (unless the planned duration is already reached, when blue takes precedence). Check the native clock continues ticking.
4. At the configured weekly planned duration verify blue; five minutes later verify orange. Lunch alerts, snooze and Garmin forwarding should remain unchanged. Auto-end behavior, when enabled, remains authoritative.
5. Confirm today's paid time pauses during lunch and resumes afterward. Compare weekly paid/target/remaining against Dashboard. Verify threshold labels and progress at normal, approaching, near, reached and exceeded states.
6. Restart the app and reboot during an active session; check the recorded elapsed time and colors recover without changing punches. Confirm the widget updates without opening the app after recovery.
7. Repeat at large fonts and with TalkBack: primary timer/action remain visible, status meaning is spoken, secondary information scrolls, and Favorites/Aisle Guide open Quick Find. Edit a favorite/aisle in the app and verify refresh.
