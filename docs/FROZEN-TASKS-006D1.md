# MVP-006D.1 — Temporary Frozen checklist

Base: open PR #29, `mvp-006c13-precision-punch-diagnostics`, commit `64a71e5f987b6052625f1b4c0e2fa7c7141c016b`. Main safely checked; no PR merges. Feature branch: `mvp-006d1-temporary-frozen-checklist`.

## Behavior

Dashboard has a Frozen Tasks action below the shift actions; the four existing bottom tabs remain. Frozen Tasks opens the explicitly requested Monday, October 19, 2026 checklist, labeled 8 AM–5 PM. This label neither creates nor changes a schedule or WorkSession. The ten supplied tasks and personal reference notes are initialized atomically only on first opening. This is not a recurring Monday SOP and does not populate other shifts.

Check a task to complete it; uncheck to reopen. Expand a task for references, start/block/carryover actions, editable title/reference/notes and state history. Add task creates a new checklist item. Green indicates completed, amber in progress/carryover, muted red blocked and blue references. State labels accompany colors. Counts show completed, remaining and blocked items. Carryovers filters the same records for next-shift review; it does not copy tasks, change original dates or assign anyone else. Reopening retains previous completion events. Nothing is reset at midnight or triggered by clock-out.

The template is editable per task, and no later initialization overwrites edits. Personal field notes explicitly defer to management and store policy and are not described as official Publix SOPs.

## Persistence and boundaries

A separate `frozen_tasks.preferences_pb` DataStore holds a versioned JSON payload (`tasks_v1`): stable task IDs, original date, title, description, state, notes and timestamped state-change history. Atomic DataStore edits use the established persistence guard. Edits to task text preserve the latest state, avoiding stale-editor state overwrites. Invalid/unreadable data surfaces an error without reseeding or clearing saved tasks. Reopen the screen to retry a read failure.

Existing Room schema/tables, timekeeping/lunch/payroll/planning, decimal formatting, Punch Diagnostics, notifications/Garmin, widgets, schedule import and backup/restore implementation are unchanged. This temporary checklist is local-only and not included in the current encrypted backup format; integration/migration into the future Operations module remains future work. No backup restoration tests or signed release build.

## Physical acceptance

1. Upgrade-install the debug APK over existing ShiftHUD; do not uninstall or clear data.
2. Dashboard → FROZEN TASKS. Verify the Monday label, ten initial tasks and personal-reference disclaimer.
3. Complete a task, reopen it, expand Task history and verify both state events remain.
4. Start a task, mark blocked, add “Scanner unavailable,” save, and confirm blocked count and visible notes.
5. Carry an unfinished task to the next shift. Open Carryovers and verify the same task, notes and original date. Return it to do or start it when ready; no duplicate should appear.
6. Edit a task's title/reference; add a task if needed. Force-stop/reopen ShiftHUD. Confirm all task changes persist and the template has not duplicated.
7. Navigate Schedule weeks and return to Frozen Tasks; the checklist must remain independent. Check existing shift, lunch and paid-hour displays unchanged.
8. Check narrow-screen/200% fonts and spoken TalkBack checkbox, state and expansion announcements. References and task notes must wrap and remain scrollable.

## Verification

- Debug build, applicable unit tests, lint and instrumentation APK passed with JDK 17. 898 unit tests passed per debug/release variant, zero test failures/errors/skips. One intermediate JVM compiler SIGSEGV recovered on an unchanged-test rerun with two Gradle workers.
- Lint: zero errors, 16 existing warnings and one hint.
- Full eligible emulator run: 42 selected tests, initially one new layout-test failure; all existing tests passed. After control-width corrections and checking only visible/re-measured lazy-list text at 200% font, both Frozen UI tests passed. The targeted rerun also verifies real Dashboard navigation and Schedule next/previous week independence.
- Separate process-restart write/read phases both passed. A test-owned DataStore file retained notes, carryover and completed/reopened history through force-stop; it was removed after the read assertion. Production checklist data was not used for mutation fixtures.
- Read-only before/after comparisons confirmed all original Room rows and user configuration preferences unchanged. Opening the real MainActivity advanced existing `weekly_target_delivery` and `planner_delivery` bookkeeping to the current week through existing app lifecycle logic; those runtime receipt keys were not restored or modified by Frozen code. Other existing DataStore files remained byte-identical.
- Upgrade installation used `adb install -r`; no uninstall or application-data clear. No backup restoration tests.
- Screenshot fixtures: `app/build/reports/frozen-tasks/`, including checklist, blocked notes, history, large-font header and carryover. Real Compose components rendered at 320dp, with 200% font checks. Spoken TalkBack and physical-device checks remain manual.
- APK: `/Users/crod/Documents/ChatGPT/Publix work app/app/build/outputs/apk/debug/app-debug.apk`.

Changed files: MainActivity navigation, ShiftHudApplication store wiring, Dashboard action; four files under `frozen/` (model/template, store, ViewModel, screen); unit persistence tests; two emulator test classes; this document.
