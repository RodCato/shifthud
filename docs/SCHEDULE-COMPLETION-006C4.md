# MVP-006C.4 — Automatic schedule completion

Dependency: `mvp-006c3-smart-lunch-alerts` (PR #19, open). This PR is based on that branch; no dependency PR was merged.

## Read-only behavior

Schedule rows show an outlined box / Scheduled, active circle / Working, pause / On lunch, or checked box / Completed. Status is text as well as an icon, so color is not the only cue. Icons are decorative; TalkBack receives the date, time and status on the existing expand/collapse button. There is no completion toggle. Off-day rows and week navigation remain unchanged.

The selected Saturday–Friday week has an “N of M shifts completed” summary. Both rows and summary derive from Room observations. No completion flag or schema migration is introduced. A loading label avoids briefly presenting unknown records as completed or scheduled.

Only persisted `scheduledShiftId` associations count. Null/unlinked sessions remain unscheduled, even on the same date or overlapping a schedule. We deliberately do not infer new matches: the existing historical-entry path already establishes associations when appropriate. An unrelated same-date session cannot complete another shift. A schedule imported after unlinked work does not retroactively link that work.

An open linked lunch takes precedence, followed by other open linked work. A shift is completed only when all its linked sessions have valid clock-outs. Early and late clock-outs both qualify, with no requirement to reach scheduled end. This describes completion of recorded associated work, not proof that all scheduled hours were worked. A subsequent linked active session changes the status back to Working/On lunch.

The new query selects sessions by schedule IDs rather than clock-in date. Overnight work and corrected punches outside the displayed week still follow their explicit association. Schedule time/date edits retain that association; deleting/unlinking a session removes its contribution immediately. Navigating weeks changes the queried IDs. Reopening the app derives everything again from persisted records.

No changes to punches, payroll, analytics calculations, weekly lunch preferences, schedule import behavior, notifications, backup/restore, or Frozen Operations. No signed release build.

## Physical-device acceptance

1. Install the debug APK over the current installation, without uninstalling or clearing data.
2. Open Schedule. Verify the selected Saturday–Friday heading and completion count. An unworked scheduled row shows an unchecked box and Scheduled; off days keep their existing text.
3. Clock in to an associated schedule: Working appears. Start lunch: On lunch appears. End lunch: Working returns. Clock out early or late: checked box/Completed and the summary update.
4. Review an overnight shift and historical corrected record. Confirm each indicator follows its linked schedule, including punches across midnight/week boundaries.
5. Review multiple sessions: an active linked segment prevents completion; unrelated or unscheduled completed work must not check another schedule.
6. Correct/delete a historical session using the existing confirmation flow, then return to Schedule. Verify the indicator/count update. Edit a schedule or import new schedules and navigate previous/next weeks to check the selected count.
7. Expand/collapse a row. Restart the app and recheck derived statuses. At large system font size and on a narrow screen, confirm the status/date/time remain readable and scrollable.
8. With TalkBack, focus a row: hear date/time/status and expand/collapse action. There must be no checkbox toggle or separate focus stop for its icon.

## Verification

- Scoped Gradle build and debug APK passed. 849 unit tests per debug/release variant (1,698 executions), zero failures.
- All 26 eligible emulator tests passed, including the new live completion/count test at 200% font size with expansion restoration and read-only accessibility semantics. Visual inspection passed at 320dp width in light mode and dark mode at 200% font.
- Lint: zero errors, 16 existing warnings and one hint. Diff whitespace check passed.
- Temporary emulator fixtures were removed; all original app data files were restored and byte-verified, display settings restored, and emulator stopped.
- Existing scope exclusions retained: backup restoration unit/device tests and release APK packaging. Backup/restore implementation was not exercised or modified.
- Automated accessibility checks validate semantics, not audible TalkBack speech; physical-device TalkBack remains an acceptance step.
