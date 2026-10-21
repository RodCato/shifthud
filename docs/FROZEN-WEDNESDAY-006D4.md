# MVP-006D.4 — Wednesday Frozen truck workflow

Base: `mvp-006d3-tuesday-endcap-workflow`, commit
`6dd2b60c4ebb7bb30a15a3f7492e348d91d8ea18` (open PR #32).
Main was fetched and checked with fast-forward only. No dependency PR was merged.

## Explicit assignment and task phases

Start Wednesday Frozen Workflow appears inside Frozen Tasks, with the instruction
to start only when assigned to Frozen. It never assumes that a Wednesday work shift
is a Frozen assignment. The calendar starts on the device-local date and allows a
manual assignment date. Nothing is created by weekday, clock-in, midnight, or
truck arrival. Saved workflow navigation retains Monday and Tuesday history.

Nineteen template tasks cover Before Truck (6), Truck Receiving (4), Stocking (5),
and After Truck (4). Personal references explain receiving, department separation,
promotional merchandise and scanner-dependent tasks without automating official
store operations or tracking inventory quantities. All task controls and notes stay
available in any order, even before arrival. Completed remains collapsed by default.

While waiting, a compact expandable reference suggests backstock, ad-wall work,
tags, MASC, damages/donations, counts, cleaning and organization. These suggestions
create no extra task records and make no delivery-time/scanner assumptions.

## Truck event state

TRUCK ARRIVED appends an event using the actual Clock instant and device zone ID.
The displayed time honors the device's 12/24-hour preference and uses the recorded
zone, so later timezone changes do not reinterpret the recorded local arrival.
Receiving and Stocking move ahead of Before Truck and expand after arrival.
Carryovers remain first; no phase is locked or automatically completed.

Correct arrival / mark not arrived requires confirmation, appends an undo event,
and restores Waiting for Frozen delivery. Original arrival events remain visible
in Truck event history. Repeated identical actions do not append duplicate events.
Reconfirming after undo records a fresh actual timestamp. Truck changes do not write
tasks, WorkSessions, schedules, payroll or notifications.

## Compatible local persistence

The existing Frozen Preferences DataStore gains one optional `truck_workflows_v1`
JSON key (version 1). Each record has its original assignment date, stable task ID
references and arrival/undo events (state, instant, zone). Missing data means no
Wednesday workflow, not an automatically created one. Invalid data is surfaced
rather than reset. Existing task serialization and Endcap plan fields are unchanged.

Wednesday selection/membership uses `wednesday:YYYY-MM-DD`; existing Tuesday date
keys and Monday's empty selection are retained. This prevents a manually selected
Tuesday and Wednesday workflow on the same date from colliding. Newly added custom
tasks use the selected workflow's original date. The selected workflow survives
restart; midnight does not switch it.

Initialization and membership updates are atomic DataStore edits. A saved Wednesday
record acts as the initialization marker, including when a template step was
replaced by an original carryover. Completing that carryover and reopening therefore
cannot regenerate the skipped template task.

No Room schema/migration, backup format or restore changes. Frozen data remains
local-only as in D.1–D.3. Do not downgrade and edit these records with older builds,
which do not understand Wednesday selection IDs or the truck metadata.

## Carryover identity and explicit deduplication rules

Only actual persisted Carry over records originating on or before the selected
assignment date are included. Their original IDs, origin dates, descriptions, notes,
state and event history remain; workflow membership is appended without copying.
Completing a carryover updates that original record and records the actual completion
instant. Completed carryovers remain in the selected workflow's Completed section.

On first creation, known matching task identities reuse unfinished carryovers:

- D.1 freezer/ad-wall inspection (`frozen-2026-10-19-1`).
- D.1 ad-wall replenishment (`frozen-2026-10-19-4`).
- D.1 Random Counts (`frozen-2026-10-19-7`).
- Prior Wednesday steps identified by their stable assignment-date/step IDs.

Titles alone never establish equivalence. Renamed known tasks retain identity;
same-title custom tasks stay distinct. Tuesday's transition/build steps are not
silently treated as equivalent to Wednesday receiving or inspection. Separate Monday
damages and donation tasks are not merged with Wednesday's combined task.

Completed historical work never suppresses a fresh recurring occurrence. Multiple
already-existing carryovers remain separate records rather than being destructively
merged. Newly marked carryovers are also visible when reopening an existing workflow;
already-created recurring tasks are preserved, not retroactively deleted or merged.
All earlier completed task records remain intact.

## Verification and physical acceptance

Applicable verification excludes `BackupRoundTripTest` and
`BackupDeviceAcceptanceTest`; no backup restoration is performed.

```
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
./gradlew -I /private/tmp/shifthud-c8-tests.init.gradle \
assembleDebug test lint assembleDebugAndroidTest --max-workers=2 --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e notClass com.shifthud.backup.BackupDeviceAcceptanceTest \
com.shifthud.test/androidx.test.runner.AndroidJUnitRunner
```

Tests use synthetic tasks and isolated test-owned preference files. Unit coverage
includes concurrent/idempotent starts, Monday/Tuesday history, carryover identity and
notes, recurring type versus title matching, completion dates, manual assignment
and same-date workflow separation, future carryover exclusion, arrival/undo history,
12/24-hour display, phase order, unrestricted task transitions, custom tasks,
restart persistence and malformed-data protection. Device tests cover the real
start/date/navigation controls, arrival/undo, carryovers, pre-arrival After Truck
work, phase expansion, narrow screens, 200% fonts and visible text overflow.

Physical acceptance:

1. Install the debug APK as an upgrade; do not uninstall or clear data.
2. In Frozen Tasks, confirm Monday/Tuesday history and notes remain. Start Wednesday
   with the correct assignment date. Reopen it and verify no duplicated template or
   carryover tasks. Complete a real carryover from Wednesday and inspect its origin.
3. Before delivery, complete/reopen/block work in any phase; open While waiting.
   Verify no fixed arrival time or newly created suggestion tasks.
4. Press TRUCK ARRIVED at actual arrival. Verify the displayed local time, receiving/
   stocking prominence, and unchanged task progress. Test correction/undo, then
   reconfirm only when appropriate.
5. Restart the app/device and verify saved assignment date, arrival/history and task
   progress. Navigate to Monday and Tuesday and back without resetting anything.
6. Check narrow-screen layout, 200% text, TalkBack announcements/focus and 12/24-hour
   device settings. Spoken TalkBack and real-world delivery use remain manual checks.

No timekeeping, payroll, punch, lunch, decimal, planner, schedule OCR, endcap OCR,
endcap product grouping, notification/Garmin, widget or backup implementation changes.
No signed release. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Results and changed files

- Debug build: passed.
- Unit tests: 921 passed in each debug/release variant; zero failures/errors/skips.
- Eligible emulator suite: 49 passed on Pixel 9 / API 37.1.
- Isolated write/read tests passed across an actual emulator reboot (one test per phase).
- Original Room table contents were unchanged. Existing Frozen Tasks, shift and Glance
  preference files remained byte-identical after the full suite and reboot.
- Lint: zero errors; 16 existing warnings and one hint.
- Debug APK installed over the existing emulator app with `adb install -r`.
- No uninstall, clear-data operation or backup restoration testing.

Generated screenshots (not committed):
- `app/build/reports/frozen-wednesday/frozen-d4-waiting.png`
- `app/build/reports/frozen-wednesday/frozen-d4-receiving.png`
- `app/build/reports/frozen-wednesday/frozen-d4-large.png`

Changed files:
- `app/src/main/java/com/shifthud/frozen/FrozenTaskStore.kt`
- `app/src/main/java/com/shifthud/frozen/FrozenTasks.kt`
- `app/src/main/java/com/shifthud/frozen/FrozenTasksScreen.kt`
- `app/src/main/java/com/shifthud/frozen/FrozenTasksViewModel.kt`
- `app/src/main/java/com/shifthud/frozen/FrozenWorkflowControls.kt`
- `app/src/main/java/com/shifthud/frozen/FrozenTruckWorkflow.kt`
- `app/src/main/java/com/shifthud/frozen/FrozenTruckStatus.kt`
- `app/src/test/java/com/shifthud/FrozenWednesdayTest.kt`
- `app/src/androidTest/java/com/shifthud/frozen/FrozenWednesdayDeviceTest.kt`
- `app/src/androidTest/java/com/shifthud/frozen/FrozenWednesdayRestartDeviceTest.kt`
- `docs/FROZEN-WEDNESDAY-006D4.md`
