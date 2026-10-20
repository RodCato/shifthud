# MVP-006D.2 — Carryover audit and task grouping

Base: open PR #30, `mvp-006d1-temporary-frozen-checklist`, commit `53e399fc435dae8925e0009270fc60fa08b12e4a`. No PR merges. Branch: `mvp-006d2-task-grouping-carryovers`.

## D.1 audit (before changes)

The expanded task's Carry to next shift button calls `FrozenTasksViewModel.state`, then `FrozenTaskStore.setState(id, CARRY_OVER)`. This atomically updates the same record and appends a state-history event. The title, original date, notes and ID are preserved. Repeated requests for the current state do not add history or records. Carryovers was only a filter over that same checklist, not a transfer to a new checklist.

Carryover requires explicit selection. There is no midnight, clock-out, next-shift-open or scheduled-date hook. Unfinished tasks do not transfer automatically, and remain on the original Monday checklist. Initialization only seeds an absent store; it never resets an existing checklist. No storage correction or preference migration is needed.

## D.2 behavior

Grouping is derived from the existing list, preserving original order and IDs. TODO, IN_PROGRESS and BLOCKED appear under ACTIVE TASKS; CARRY_OVER appears only under CARRYOVERS; COMPLETED appears only under COMPLETED. Counts come from this disjoint partition. No storage code, payload version, preferences migration or Room schema changed.

Carryovers starts expanded; Completed starts collapsed on a fresh screen. Both have stable saveable expansion booleans independent of their count labels, so completing/reopening a task does not reset a section unexpectedly. Expansion survives saved-instance restoration. Active tasks always appear first. Completed titles are visually secondary. Headers support button semantics, expand/collapse announcements, keyboard activation and minimum 48dp targets.

Expanded unfinished tasks explicitly offer **Carry to next Frozen shift**. **Remove from carryover** returns a task to TODO without removing its record or history. Completing a carryover moves it to Completed; reopening moves it to Active. Carried tasks display their original date in the row; notes and references remain available in expanded details. Existing completed tasks are never reseeded, rewritten or recreated by grouping.

The temporary checklist remains on its original date and has no automatic midnight, next-shift or clock-out transitions. The unchanged local-only storage is still outside the existing encrypted backup format.

## Physical acceptance

1. Upgrade-install the debug APK over ShiftHUD. Do not uninstall or clear data.
2. Open Frozen Tasks with existing completed items: verify they are counted under collapsed COMPLETED, with unfinished items first.
3. Expand Completed, inspect notes/references, and uncheck a task. It should move to Active with the same notes/history; counters update immediately.
4. Expand an unfinished task, select Carry to next Frozen shift, and verify it appears once under Carryovers with its original date. Collapse/expand the section.
5. Edit carried notes, remove from carryover, carry again, complete it, and reopen from Completed. Verify no duplicate tasks or inflated totals.
6. Force-stop/reopen: existing completion/carryover/notes/history/custom tasks must remain. Check Schedule week navigation and actual timekeeping remain independent.
7. Check narrow/200% font wrapping, TalkBack header/checkbox announcements and keyboard expansion.

No timekeeping, payroll, Room schema, Punch Diagnostics, schedule import, notifications/Garmin, widgets or backup/restore changes. No backup restoration testing or signed release build.

## Verification artifacts

- Debug build, applicable unit tests, lint and instrumentation APK passed with JDK 17 and two Gradle workers. 902 unit tests passed per debug/release variant, zero failures/errors/skips. Backup restoration unit class excluded.
- Lint: zero errors; 16 existing warnings and one hint.
- Grouped UI tests cover completed default collapse/expansion, saved-instance restoration, reopening, carrying/removing/completing/reopening the same task, notes, counters, 320dp/200% font rendering and keyboard activation. Keyboard injection uses the same explicit Android keyboard input-mode setup as the existing Schedule tests. Screenshots visually inspected.
- Screenshots: `app/build/reports/frozen-grouping/frozen-d2-{active-first,completed-collapsed,completed-expanded,carryover,large-header,large-active,large-completed}.png`. These render real components with in-memory fixtures, without modifying production task states.
- APK: `/Users/crod/Documents/ChatGPT/Publix work app/app/build/outputs/apk/debug/app-debug.apk`. Upgrade-installed with `adb install -r`, without uninstall or data clear.

Changed files: `FrozenTasksScreen.kt`; new `FrozenTaskGroups.kt`; new `FrozenTaskGroupsTest.kt`; updated `FrozenTaskStoreTest.kt`, `FrozenTasksDeviceTest.kt`, `FrozenRestartDeviceTest.kt`; this document. The production task store and model are unchanged.

Final verification: all 42 eligible emulator tests passed. Both separate process-restart phases passed, including persisted COMPLETED and CARRY_OVER records, notes, IDs and history. Read-only comparison confirmed every original Room row unchanged, and the original Frozen checklist, shift preferences and Glance preference files byte-identical after upgrade/testing. Emulator font/theme/notification permission restored. No original checklist records or completion states were modified. Backup restoration instrumentation class excluded.
