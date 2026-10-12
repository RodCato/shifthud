# Dynamic weekly lunch planning (MVP-006B.2)

The Dashboard’s **Edit Lunch Plan** opens a Saturday–Friday week selector and a single lunch input. Valid values are 31–240 minutes, bounded by the employer minimum in Settings. Editing previews the selected week immediately; **Save Weekly Plan** persists it, and **Cancel** discards the draft. Previous and future weeks are independent. Weeks without a saved plan inherit the saved default. The dialog identifies unsaved previews and distinguishes actual paid work, projected remaining work, target remaining, excess/shortfall, and recommended departure. No extra work is invented to fill a shortfall.

## Data and compatibility

- Weekly plans use a `weekly_lunch_plans` DataStore preference keyed by Saturday ISO date. Updates merge one week atomically. Backup validates Saturday keys and integer durations; missing preference in older backups means inheritance from the default.
- Production planner consumers use `weeklyPlannerSettings`, which combines the existing preferences with weekly plans. The legacy `PlannerSettings` calculation remains available for compatibility and regression coverage; legacy strategies no longer control the production weekly planner. If an employer minimum increases, effective lunch is bounded by the new minimum without deleting the saved assumption.
- Existing per-shift `plannedLunchMinutes`, `usesGlobalLunch`, and `lunchPlanApplied` values are retained unchanged. Schedule details identify them as legacy reference values. The lunch dialog explicitly explains that weekly projections take precedence. Schedule edits preserve those fields; new production schedules no longer capture a per-shift personal lunch. The former bulk-edit and custom-per-day UI is replaced with a weekly preference; no batch schedule rewrite occurs.
- Room **7 → 8** adds nullable `employerNetMinutes` and `employerLunchMinutes` to scheduled shifts. Existing rows receive NULL; no employer evidence is inferred from legacy personal overrides. Dates, times, IDs, notes, WorkSessions, payroll and pay rates are untouched.
- Import candidates persist displayed net hours and displayed/inferred employer lunch separately from personal preferences. Missing lunch does not require confirmation when date/time association is confident. Contradictory OCR still warns, impossible net hours cannot be imported, and existing duplicate/conflict protections remain. Replacements retain legacy personal fields and notes while updating employer evidence from the reviewed screenshot.
- Employer metadata is the captured screenshot evidence. Subsequent manual schedule-time changes retain that evidence for reference; reimport a changed employer schedule to update it. Dashboard labels employer hours separately and reports partial coverage rather than inventing missing net hours.
- Backup schema 8 includes the added columns and weekly preferences. Schema 6 and 7 backups upgrade in staging before validation/restoration; neither modifies the source backup. Encryption is unchanged.

## Calculations

Completed WorkSessions replace associated schedules with actual paid timestamp intervals. Active sessions retain the existing paid-time and remaining-lunch rules; a persisted automatic lunch target takes precedence over planning. Future saved spans subtract the effective weekly lunch, bounded at zero. Overlap/assignment warnings and early-departure safeguards are unchanged. Historical weeks use actual records as of now; future-week previews begin at Saturday midnight. Current-week Dashboard projections follow live schedule, punch and preference flows. Actual gross-pay and historical analytics remain unchanged; there is no separate projected-gross display in the existing planner.

The Oct 17–23, 2026 acceptance fixture contains five spans totaling 42 hours and employer-reported net of 37 hours:

| Weekly lunch | Personal projection | Shortfall to 40h |
| --- | --- | --- |
| 60m | 37h | 3h |
| 45m | 38h 15m | 1h 45m |
| 35m | 39h 05m | 55m |
| 31m | 39h 25m | 35m |

These are test fixtures, never production seed data.

## Physical-device acceptance

1. Make an encrypted backup before updating; confirm existing punches and payroll remain present after installation.
2. Import the Passport week. Confirm five shifts in one batch without individual lunch entry. Employer reported hours should total 37h. Cropped Friday must not become OFF.
3. Dashboard → Edit Lunch Plan → select Oct 17–23. Try 60, 45, 35, and 31; compare the table above. No early clock-out should be proposed for this short week.
4. Cancel a draft and reopen to confirm it was not saved. Save 35, restart the app, and confirm 35 persists. Save a different value for an adjacent week and verify independence.
5. Check Schedule editing shows date, start, end, and notes; retained legacy lunches and employer metadata are visible in details, with no per-shift lunch input.
6. During normal work, confirm paid work pauses during actual lunch and resumes after it; completed records replace projections. Weekly planning must not change the automatic lunch timer or recorded punches.
7. For a week with enough scheduled work, confirm the final-day recommendation updates with lunch choices and actual early/late punches. Employer approval is still required.
8. Export and restore an encrypted backup on a test installation; confirm both weekly plans and employer schedule evidence return. Existing notifications, widgets and Garmin forwarding should behave as before.

## Automated verification

Unit coverage includes the four acceptance totals, shortfalls, actual lunch precedence, active automatic lunch, early/late punches, Friday recommendation, overnight schedules, previous/future week boundaries, legacy override preservation, DataStore recreation, Room reopen, import metadata, defaults, schema 7 migration, older migration chains, and schema 6/7/8 backup compatibility. The opt-in emulator `WeeklyLunchDeviceTest` exercises live input previews, save, independent weeks, backup validation, and process restart against a disposable fixture. Ordinary device tests do not mutate user schedules.

Verified for this change: `./gradlew build test lint assembleDebugAndroidTest` passed; 802 tests per debug/release variant (1,604 executions), zero failures. Lint: zero errors, 14 existing dependency warnings. The 16-test Android suite passed at normal font size and again at 200% font, 320dp width, dark mode. Opt-in weekly preview/save and fresh-process restart checks also passed. A visual check confirmed the 35m/39h5m/55m result. Original emulator database/preferences were backed up before installation and restored with byte-for-byte verification afterward; no physical phone was connected.
