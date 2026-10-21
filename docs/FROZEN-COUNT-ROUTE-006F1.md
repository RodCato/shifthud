# MVP-006F.1 — Frozen Count Route

## Dependency and isolation

Branch: `mvp-006f1-frozen-count-route`.
Base: `a66d6e6f4515eb81ed80168212db516f466048ef` on `mvp-006d3a-endcap-ocr-reliability` (completed and verified PR #34), which includes the integrated Tuesday/Wednesday base `d647f0e`. Main was checked and fast-forwarded safely (already current).

The initial independent-base plan was changed after checking compatibility: the older Frozen task encoder could discard optional family metadata saved by PR #34 when editing a task. Depending on the completed OCR repair preserves that data format and the installed feature. This is not unfinished OCR work; its unit/device checks were verified before this task. Count Route does not change any Endcap implementation. No PR is merged. The shared FrozenTasksScreen entry preserves the family-choice callback as well as the new Count Route action.

## Workflow

Open **Frozen Tasks → Count Route**. No bottom-navigation destination was added.

Each aisle side contains a user-entered ordered list of physical sections. No section, department boundary, or door count is prepopulated. Classify each section as Frozen, Meat, Other, or leave ownership unknown; choose upright freezer doors or open freezer cases and its independent location count (1–200). Optional labels identify physical boundaries. Add/remove sections and move them earlier in walking order. Each side can have up to 100 sections, with no equality assumption between sides.

Only explicitly Frozen-owned sections with a known type/count enter the route. Meat/Other sections are resolved exclusions even without a count; they never consume a route number, count as skipped, or prevent coverage. Unknown ownership and unresolved Frozen sections remain incomplete. For open cases, the user defines the distinct physical counting locations during the walkthrough; the app does not invent divisions.

The first four directions remain A10 Right front→back, A10 Left back→front, A11 Right front→back, A11 Left back→front. Enter each side's sections in that walking order. Configure A12 side order and direction(s) explicitly or exclude unused sides. Eligible Frozen location numbers reset per aisle side and increase continuously across its Frozen sections, skipping non-Frozen sections. Route labels include the section and distinguish Door/Case. These are personal ShiftHUD labels, not official store numbers.

Earlier unclassified side totals become unknown-ownership sections in the editable map, retaining their numbers for review rather than assuming Frozen ownership. Version-1 cycles retain their exact immutable snapshots, counts, session notes and history. Legacy active routes show an ownership warning and cannot record further counts, resolve or revisit doors; explicitly finish them and start a newly classified route. No recorded progress is deleted. Version-2 persistence retains section metadata and validates door-to-section association.

The default target is five products per door (editable 1–100 before/during a cycle). It never completes a door automatically. +1 Count saves immediately; Undo applies to the current door. Explicit Complete Location confirmation allows zero/fewer/more counts than the target. Skip Location retains counts and an optional note, excludes the door from sampled coverage and offers Revisit. Navigation can revisit unresolved doors without erasing partial counts.

Pause retains the current location and count, closes the counting session and records its date. Resume opens another session at exactly that door. A process restart while active retains the same session; no date or work-shift event automatically resets it. Session history records net count changes (undoing a previous session's count can make the current session's net adjustment negative).

Finish Counting Cycle requires explicit confirmation and records completion time, sampled/product totals, skipped, partial and unvisited doors. Incomplete maps or unresolved doors are labeled partial coverage. The next cycle never starts automatically. Previous cycle records remain reviewable.

Manual submission status and notes belong to individual counting sessions, including finished cycles. They never gate route progress, perform an inventory adjustment or establish official approval. No credentials, product names, quantities, OCR, scanning or SIMS integration are added.

## Map changes and persistence

Each cycle owns an immutable snapshot of its route map, physical sections and locations. Map edits preview added/removed identifiers and direction/count changes, then require confirmation. They affect **future cycles only**. The active screen explains when its snapshot differs from the latest saved map. Finish the existing cycle (partial coverage is allowed) and explicitly start another to use corrected totals. No completed/skipped/partial records are removed.

The separate `frozen_count_route.preferences_pb` DataStore file holds versioned JSON for the map, default target, cycle snapshots, per-door state, current location, pause state, sessions and manual status/notes. Writes are atomic; each command carries an expected revision to make duplicate/stale writes harmless. The ViewModel also blocks pending writes and suppresses repeated identical actions for 350ms. Malformed/unsupported saved state reports an error instead of resetting progress.

No Room migration or timekeeping-table change. Existing Frozen task storage, carryover logic, Wednesday events, payroll, scheduling, notifications, Garmin, widget and backup/restore implementation are untouched. **Count Route data is not included in existing ShiftHUD backup exports**; expanding backup coverage is outside this request. On-device app/process/device restarts preserve it.

## Verification and artifacts

Applicable checks (excluding prohibited restoration tests):

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew -I /private/tmp/shifthud-c8-tests.init.gradle \
  assembleDebug test lint assembleDebugAndroidTest --max-workers=2 --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e notClass com.shifthud.backup.BackupDeviceAcceptanceTest \
  com.shifthud.test/androidx.test.runner.AndroidJUnitRunner
```

The local Gradle init script excludes `BackupRoundTripTest`. No backup restoration tests, signed release, uninstall or data clear are performed. Release *unit tests* are eligible; no release APK is packaged/signed.

New unit tests cover unknown/partial/complete maps, exclusions, order/directions, independent counts, targets, increments/undo, explicit sampling, pause/resume, skipped recovery, cycle history, immutable map corrections, duplicate/stale commands, manual approval and malformed/recreated persistence. Emulator tests use isolated synthetic stores for setup, counting, pause/skip/revisit/finish, manual approval, large text and the Frozen Tasks navigation callback. A separate three-phase test writes Door 5 at 3/5 paused, verifies after process restart, then verifies after emulator reboot and starts the next cycle without erasing history.

Final compatible-base build passed: `assembleDebug test lint assembleDebugAndroidTest`. All 950 eligible unit tests passed in each debug/release variant (1,900 executions); all 59 eligible emulator tests passed. The additional actual system-font-scale 2.0 check passed and restored the original 1.0 setting. Dialog button bounds are checked for overlap at large font size. After correcting compositor screenshot timing, all four Count Route UI tests and the actual 200% font check were rerun successfully. Lint reported 0 errors, 16 existing warnings and 1 hint.

All three persistence phases passed: write, process restart, and full emulator cold restart. The initial warm reboot stalled in Android startup; the same AVD was stopped and cold-booted without wiping storage, then the exact paused Door 5 at 3/5, map snapshots and cycle history passed readback. Read-only before/after comparisons confirmed every original Room table and all original preference files unchanged. The debug APK was installed in-place on the connected emulator; no physical device was connected.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
Local screenshots/logs: `app/build/reports/count-route-f1/` (synthetic data only).

## Physical acceptance

1. Install the compatible debug APK over the existing app. Do not uninstall or clear data.
2. Open Frozen Tasks → Count Route. Add sections in walking order and classify each as Frozen, Meat or Other. Save only location totals physically verified; leave uncertain ownership/counts unknown. Configure A12 direction(s) or exclude unused side(s).
3. Start a cycle, count three products, pause and reopen. Confirm the exact door and 3/5 remain; test target changes and Undo.
4. Skip a door with a note, complete another below target, then revisit the skipped door. Verify skipped doors are not sampled.
5. Correct a door count. Verify the preview explains affected future locations and the active snapshot stays unchanged.
6. Finish explicitly, review the history and manual submission status, then start the next cycle using the corrected map.
7. Reboot the device; verify saved progress. Check one-handed use, 200% text and spoken TalkBack labels on the physical screen.

No physical freezer mapping or spoken TalkBack session is claimed from emulator tests. The route supports the specified aisle sides, not arbitrary aisle insertion/reordering. Directions for A10/A11 remain intentionally fixed. Historical count totals are read-only; manual session status/notes remain editable. Changing a map cannot append/remove doors inside an active snapshot.

## Changed files

Production: new `frozen/count/CountRoute.kt`, `CountRouteStore.kt`, `CountRouteCodec.kt`, `CountRouteViewModel.kt`, `CountRouteScreen.kt`, `DoorMapScreen.kt`; navigation-only edits to `MainActivity.kt` and `frozen/FrozenTasksScreen.kt`.

Tests: new `CountRouteTest.kt`, `frozen/count/CountRouteDeviceTest.kt`, `frozen/count/CountRouteRestartDeviceTest.kt`. This document is the remaining file.

## Department-specific extension

This supersedes the original whole-side ownership assumption. Map corrections still affect future cycles only, preserving active/historical location identities and counts. New regression coverage verifies mixed ownership, open cases, continuous eligible numbering, opposite traversal directions, unknown ownership, exclusions without counts, section reorder/ownership corrections and version-1 compatibility. No Room or existing Frozen task persistence format was changed.

Extension build verification: `assembleDebug test lint assembleDebugAndroidTest` passed; 954 eligible unit tests per debug/release variant (1,908 executions). Lint remains 0 errors, 16 pre-existing warnings and 1 hint.

All 59 eligible emulator tests and the additional actual 200% system-font check passed for this extension. The editor test creates a Frozen section and a Meat section without a count, verifies only Frozen locations are generated, and exercises the new section fields. Updated synthetic screenshots/logs are in `app/build/reports/count-route-sections/`. Debug APK installed in-place; no uninstall or app-data clear.
