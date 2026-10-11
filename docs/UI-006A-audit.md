# MVP-006A screen inventory and UI audit

Base: `e8b6970` on open PR #12 (`mvp-005d-smart-clockout-planner`). Main lacks this dependency stack. This branch targets PR #12's branch, with no merges performed.

| Screen / surfaces | Finding | Refinement / retained behavior |
| --- | --- | --- |
| Dashboard (off duty, working, lunch, complete) | Large planner card precedes essential actions; calendar and record controls always expand the page | Current shift/actions first; lunch adds the existing persisted expected-return timestamp; weekly card/gross next; collapsible time records, weekly breakdown/analytics/paychecks, and work calendar |
| Weekly Hours card | Long repeated qualification text | Short visible qualification, full explanation retained in planning details; Adjust lunch plan remains direct |
| Schedule / date-time editor / deletion | Oversized cards expose Edit/Delete everywhere; only upcoming days visible | Compact expandable rows with state and lunch; current Saturday–Friday summary and day-off rows, future shifts chronologically; original editor, validation and confirmation handlers retained |
| Settings | Every control shown in one scrolling page | Seven collapsed categories, descriptive subtitles and keyword search. Search opens matching control groups automatically. Stored settings and save handlers unchanged |
| Shift tracking controls | Threshold separated from auto-lunch completion | Threshold and automatic lunch controls grouped together |
| Schedule/planning controls | Weekly target and lunch strategies hard to reach | Dedicated category; lunch plan preview accessible directly from Dashboard and Settings |
| Notification controls | Channel diagnostics, test alerts, snoozes and reminders scattered | Notifications & Garmin category holds upcoming, test center, shift end, lunch warnings/snooze/channel controls. Weekly-target warnings stay beside weekly target, searchable by sound/notification keywords |
| Pay / hourly rate history | Rates buried below notification test controls | Pay & Payroll category with existing rate/effective-date editor plus direct Paychecks shortcut |
| Quick Find / item and aisle editors | Search-first screen already compact; no independent settings exist | Existing screen, favorites, recent finds, CRUD and widget effects retained; Settings links to the tab and explains there are no separate preferences |
| Work Analytics / day details | Already uses period controls and lazy rows | Retained. Discovered through Dashboard's labeled secondary section; weekly breakdown still opens its existing detail dialog |
| Paychecks / payroll editor / deposits | Access previously required Analytics navigation | Direct links from Dashboard and Settings. Back label is destination-neutral; all period selectors, editing, exact differences, arithmetic warnings and confirmations retained |
| Paystub screenshot import / review | Already separates recognition details and explicit review | Retained, reachable through direct Paychecks link. Photo Picker, local OCR, editable review and save confirmation untouched |
| Historical shift import | Long reference/import review is intentional, but entry point buried | Searchable Data Management entry; original reference rows, conflict checks, time-zone confirmation and import action retained |
| Backup & Restore / preview / replacement | Critical workflow already isolated in a scrollable dialog | Searchable Data Management entry. Password, SAF, preview and destructive confirmation unchanged |
| Weekly Hours Planner / bulk lunch confirmation | Explicit hypothetical preview and confirmation already present | Existing dialog and business logic retained behind clearer Dashboard/Settings access |
| Time Record / previous shift / historical deletion | Actions added to Dashboard clutter | One collapsed Time records & history section. Existing picker validation, correction, delete confirmation and record browser retained |
| Bottom navigation / notification-widget routes | Four tabs and deep links established | Same routes, tab selection and intent handling; only new callbacks to existing destinations |
| Theme | Existing system light/dark Material palette and mint dark primary | Preserved; no forced theme change. Shared section headers use theme text colors, 64dp minimum targets, merged screen-reader semantics and expanded/collapsed state descriptions |

## Settings inventory

All existing settings are retained: lunch threshold; automatic lunch switch/duration including debug option; early warning offsets; lunch snooze; lunch permission/channel diagnostics and Android sound settings; weekly tracking/target/warning offsets/channel settings; default lunch/minimum/strategies/custom dates/bulk preview/planner leads; shift-end enabled/lead/snooze/channel settings; upcoming enabled/time/days-off/channel settings; four real notification test channels; hourly effective-dated rates; encrypted backup/restore; historical import. About adds the existing package version and explanatory text only. There are no separate hourly-chime or payroll preferences to invent. Android owns custom channel sounds.

Search matches all query words, case-insensitively, against category names and control-group labels/keywords. A category match exposes its controls; a specific query exposes matching groups only. Collapsing a section does not save a preference. Expansion/query use saved UI state; fresh screens start collapsed. Durable settings remain in their existing repositories.

## Scope and accessibility

No Room schema, repositories, calculations, planner algorithms, notifications, encryption, imports, Quick Find storage or widgets changed. Expected-return display is a read of the existing `autoLunchEndTarget`, not a new timer. Schedule status labels read existing associations and clock-in dates. Day off explicitly means no saved schedule; it does not infer employer availability. Current-week ordering begins Saturday, followed by future saved dates.

Existing Material controls retain keyboard focus, labels and touch targets. Expandable headers are full-width buttons with at least 64dp height; text wraps without fixed-height cards. Primary actions remain outside expandable sections. Existing dark mint accent and light theme follow Android. Dialogs/editors retain their established scroll behavior.

Physical checks: verify each shift state and its visible actions; search lunch/sound/test/default/pay/backup; expand/collapse categories; open Analytics, Paychecks, import and backup without saving; expand/edit/cancel-delete schedule rows; test narrow width, maximum text size, TalkBack and a hardware keyboard. Run real notifications and Garmin forwarding as a regression check. No destructive action is required for UI acceptance.

## Verification

- Full Gradle `build test lint assembleDebugAndroidTest` succeeds. All 730 unit tests per variant pass (1,460 executions), including existing preferences, week-boundary, planner, payroll, repository and backup regression coverage.
- Lint: zero errors; 14 dependency-version notices (the additional notice is the test-only Compose BOM).
- Nine new Compose interaction tests pass at normal text and at 200% font on a 320dp-wide API 37 emulator. They cover saved expansion restoration, Settings expansion/search, all four Garmin test-alert entry points, backup dialog, historical/payroll callbacks, all four Dashboard states, direct planner access, and schedule expand/edit/cancel-delete.
- Assembled-app checks include collapsed Settings, automatic search expansion and opening the real backup dialog in dark mode at 200% text. Text wraps and content scrolls; the existing bottom-tab labels wrap at extreme font sizes. No production setting, schedule, payroll or shift action is submitted during UI checks.
- Manual TalkBack, hardware-keyboard traversal, physical phone layouts and actual Garmin delivery remain physical acceptance checks. Automated semantic interactions do not substitute for those checks.
- Final APK: all 11 instrumentation tests pass at 200% text, including existing read-only backup and planner device regressions. Original emulator database/preferences were restored and verified byte-for-byte; font/night/display settings were restored after testing.
