# MVP-006F.1A — Simple Frozen door counts

## Branch and scope

`codex/mvp-006f1a-simple-door-counts`, based on Count Route commit `e2f6485e78970a1756b4faf7ed0eee67f4a19de3` (open PR #35). This supersedes the section editor described in MVP-006F.1. No dependency PR is merged.

## User workflow

Open **Door Map Setup**, enter five counts and tap **SAVE DOOR COUNTS**:

- Aisle 10 — Right
- Aisle 10 — Left
- Aisle 11 — Right
- Aisle 11 — Left
- Aisle 12 — Frozen

All entries mean Frozen-owned upright freezer doors. Blank means unknown; zero explicitly means none. Partial maps can be saved and used immediately. There are no department/type selectors, section labels, exclusion controls or direction prompts. Counts are independent. Numeric input is validated; the 40,000 upper bound accommodates the maximum sum of previously supported sections without truncating imported data.

New routes follow A10 Right front→back, A10 Left back→front, A11 Right front→back, A11 Left back→front, then the single A12 Frozen final segment. A12 does not assume a left/right layout. Door numbers restart at 1 for each segment. Zero and unknown segments produce no doors; unknown segments prevent claiming complete coverage.

Start a cycle, tap +1 Count, Undo, **Complete Door / Next Door**, Pause or Resume. Completing a door advances immediately without a second confirmation and without requiring the target count. Repeated completion taps are guarded across adjacent doors. The adjustable five-product target, skipped-door revisits, manual session notes/statuses and cycle history remain available. Main counting controls precede the cycle summary.

## Existing data

The separate Count Route JSON format advances to version 3; versions 1 and 2 remain readable. No Room schema changes.

Unambiguous old configurations convert automatically: only known Frozen upright-door totals are summed, non-Frozen/excluded sections contribute zero, and both known A12 sides combine into one count. The entire original map is retained in `previousMaps`. Unknown totals remain unknown rather than becoming zero.

Old unclassified counts, open cases, unresolved section metadata or a partially known A12 total require confirmation. The original map remains authoritative and unchanged until the user reviews the five fields and confirms saving. Uncertain fields are left blank rather than guessed. Cancelling does not save. The original map is archived when confirmation is accepted.

Active and finished cycle maps are never renumbered, merged or reset. Their current door, partial/completed/skipped counts, target, session timestamps, notes and status remain exact. An old active route with unconfirmed ownership can be explicitly confirmed as Frozen doors to resume in place without losing progress. If uncertain, the user can cancel and leave it preserved. New setup counts apply to the next cycle only. Old cycle labels can retain old side/section identifiers because rewriting them would misidentify saved progress.

The new archive and confirmation flag survive process restart. Existing backup export/restore implementation is unchanged; Count Route remains outside those exports.

## Verification

Use JDK 17 and an init script excluding `**/backup/BackupRoundTripTest.class`:

```sh
./gradlew -I /private/tmp/shifthud-f1a-tests.init.gradle assembleDebug test lint assembleDebugAndroidTest --max-workers=2 --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e notClass com.shifthud.backup.BackupDeviceAcceptanceTest com.shifthud.test/androidx.test.runner.AndroidJUnitRunner
```

Unit coverage includes five-field order, independent counts, zeros/unknowns, partial setup, automatic next-door traversal, pause/recreated-store persistence, unchanged old cycle snapshots, safe A12 summation, ambiguous cases/ownership, archive persistence and explicit old-route confirmation. Device tests cover the compact setup, save/start/count/skip/revisit/pause/history, duplicate completion taps, narrow/large text, map edits without resetting active counts and cancel/confirm of ambiguous old data.

No backup restoration tests, signed release build, uninstall or app-data clearing. No changes to timekeeping, payroll, scheduling, lunch, Frozen Tasks/carryovers/Wednesday, Endcap OCR, notifications, widget or backup/restore implementations.

## Physical acceptance

Install the debug APK over the existing application. Enter only Frozen-owned door totals, using zero for a side with no Frozen doors and blank for anything not yet mapped. Confirm five fields fit comfortably; save, start, count below/above the target, complete a door and verify the next configured door. Pause midway through a door, reopen the app and verify the same count/position. Check 200% font and TalkBack on the physical device. If upgrading an existing map, verify any confirmation totals before accepting; original cycles and history must remain unchanged.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Screenshots and logs: `app/build/reports/count-route-f1a/`.

Final results: debug build and all 959 eligible unit tests per debug/release variant passed (1,918 executions). All 60 eligible emulator tests, the additional actual 200% system-font check and separate write/process-restart phases passed. The restart check retained Door 5 at 3/5, four completed doors, paused state, session note/status and the active map snapshot after changing future counts. Lint: 0 errors, 16 existing warnings and 1 hint. Debug APK installed in-place on the emulator; physical-device acceptance remains.

Read-only before/after comparisons confirmed that all original Room records and preference files are unchanged.
