# MVP-006D.3 — Tuesday Frozen workflow and reviewed endcap picking

Dependency: PR #31 (`mvp-006d2-task-grouping-carryovers`), base commit
`9f3c3000476a27c3ce106e0f509b26288f79c50a`. Main was fetched and fast-forward checked;
the unmerged D.2 branch is the implementation base. No dependency PR was merged.

## Workflow and persistence

Start Tuesday Frozen Workflow is an explicit Frozen-assignment action, available
on Tuesday and for manually selected assignment dates. The date picker starts on
the device's local calendar date. Nothing starts from clock-in, a weekday trigger,
or midnight. The saved workflow selection survives restart. Monday history and
other saved assignments remain available in Choose saved workflow / Monday history.

Twenty checklist tasks cover the eight morning transitions, three midshift tasks,
four build steps and five final-fill steps. The detailed final-fill steps incorporate
the three requested end-of-shift duties without duplicating those duties. Build and
final fill have independent task states. All sections can collapse; Completed
starts collapsed. Existing state controls, notes, origin dates and history remain.

The existing separate Frozen Tasks Preferences DataStore is extended compatibly:
`tasks_v1` keeps version 1 and adds optional `workflows` (assignment dates), `section`,
`sku` and `plan` fields to each task. Old records default these fields to empty.
`selected_workflow` stores the selected assignment, with empty meaning Monday.
No Room schema or migration is introduced. Initialization does not rewrite an
existing file, and malformed data still raises an error rather than resetting it.
Do not downgrade and edit these records using D.2: that older encoder does not know
the additional metadata.

Tuesday template IDs include assignment date and template step. Starting/reopening
uses an atomic DataStore edit and adds only missing IDs. Actual unresolved carryovers
are linked to the workflow on their original records, not copied. Completing one
appends a completion event on the original task and retains its origin, notes and
prior history. A carryover introduced later also remains in the selected workflow
after completion/reopening. Same-title tasks are not merged without identity evidence.

Endcap products have stable UUID task IDs. Display-plan identity is the confirmed
Saturday–Friday promotional week plus normalized display location. Deduplication
uses SKU when both entries have one, otherwise normalized full product name. Known
SKUs are considered before name-only rows; different SKUs/varieties stay distinct.
Reimporting a plan preserves existing progress. Editing also checks duplicates.
Removing a product requires confirmation. Only product tasks can be removed.

Frozen workflow data remains local-only, as in D.1/D.2; this feature does not change
the encrypted backup format or restore implementation.

## Image import

Android Photo Picker supports multiple images; the camera uses a narrowly scoped,
unexported FileProvider and temporary cache files. No storage/camera permission is
added. Temporary camera files are removed on leaving import and on its next opening
after interruption. Selected originals are never deleted. Drafts/images are not
persisted with tasks. Process death discards an unconfirmed review draft safely.

ML Kit Latin OCR runs on-device using the existing bundled recognition dependency.
The isolated adapter uses the same geometry model and decoding approach as the
existing OCR infrastructure, with explicit 90-degree rereading. Existing schedule
and paystub import code is unchanged. No image/text logging, uploads, accounts,
quantity tracking, inventory operations, prices, orders or alarms are added.

Each page independently identifies Aisle 10 Front — Center, stops at another display
heading, and uses detected table columns to select descriptions/SKUs. Without column
headers it conservatively handles leading numeric SKUs and strips trailing numeric
columns. Unknown locations produce a warning and no guessed products. Every page
requires location/product review; rows can be edited, added or removed. Promotional
week is shown and editable. Mixed display locations must be imported separately.
Only Confirm products and create pick list writes tasks, in one atomic operation.

Limitations: no real store sheet was supplied for this implementation. Blurred,
rotated, cropped, wrapped, unusual multi-column layouts and split display headings
may need rotation, manual location entry, or manual product corrections. OCR cannot
prove that all rows were captured. Rotation rereads and replaces that page's edits,
as explicitly stated on the control. Review drafts do not survive process death.
API 26–27 thumbnail orientation may differ for EXIF-rotated source images; OCR itself
applies EXIF orientation. Camera and Photo Picker behavior require physical-device
acceptance with the user's camera/gallery provider.

## Verification

Commands (backup restoration tests explicitly excluded):

```
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
./gradlew -I /private/tmp/shifthud-c8-tests.init.gradle \
assembleDebug test lint assembleDebugAndroidTest --max-workers=2 --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e notClass com.shifthud.backup.BackupDeviceAcceptanceTest \
com.shifthud.test/androidx.test.runner.AndroidJUnitRunner
```

Results: debug build successful; 912 unit tests passed in each debug/release variant.
Lint: zero errors, 16 existing warnings and one hint. All 46 eligible emulator tests
passed on Pixel 9 / API 37.1. The final three D.3 UI/OCR checks also passed with
the production theme, including visible text-overflow assertions at 200% font.
Both separate-process write/read phases passed (one test each). Backup restoration
tests were excluded. Original Room table contents remained unchanged; the existing
Frozen Tasks, shift preferences, and Glance preferences files remained byte-identical.
The APK was installed with `adb install -r`; no uninstall or clear-data operation.

Screenshots (generated, not committed):
- `app/build/reports/frozen-tuesday/frozen-d3-normal.png`
- `app/build/reports/frozen-tuesday/frozen-d3-large.png`
- `app/build/reports/frozen-tuesday/frozen-d3-review.png`

Unit coverage: template idempotence/concurrency, original Monday completion and
notes/history, carryover completion and reopening, late carryovers, date selection,
restart persistence, independent build/final-fill, distinct assignment history,
reviewed multi-page products, SKU/name deduplication, varieties, product editing and
removal, invalid drafts, old payload compatibility, geometry/reading order,
quantity exclusion, cropped/other displays and numeric variety names.

Device coverage uses synthetic sheets and isolated test-owned DataStores. It checks
real ML Kit recognition, multi-page confirmation, editable manual review and cancel,
Tuesday carryover controls, narrow/200% text, and independent build/final-fill.
Separate write/read instrumentation phases verify actual process restart.
No real store document is committed. Existing production Room/Frozen data is copied
read-only for before/after comparison, never restored or cleared.

## Physical acceptance

1. Upgrade using the debug APK; do not uninstall or clear app data.
2. Mark an existing Monday task Carry to next Frozen shift; retain a handoff note.
3. Start Tuesday workflow and choose the assignment date. Reopen it twice and verify
   no duplicates, then complete that carryover from Tuesday. Check Monday origin and
   notes/history, including the new completion event.
4. With workplace permission, photograph/import two relevant sheet pages. Test
   Photo Picker, camera and Rotate. Verify each page's display, varieties and SKUs;
   add missing rows, delete unrelated rows, confirm promotional week, then confirm.
5. Pick a product, reopen it, edit it, and verify repeated import preserves progress.
   Cancel another review and verify no new tasks. Remove a product with confirmation.
6. Complete Build tasks and verify Final Fill is still unfinished. Restart the app
   and verify selection, product progress, Monday carryover completion and notes.
7. Check dark/light theme, 200% font size and TalkBack focus/labels on the physical
   device. Actual screen-reader speech and camera/provider interactions remain manual.

No timekeeping, punch, lunch, payroll, decimal, planner, schedule import,
notification/Garmin, widget, or backup/restore implementation changes. No signed
release APK is built. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Changed files

- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/endcap_camera_paths.xml`
- `app/src/main/java/com/shifthud/frozen/FrozenTasks.kt`
- `app/src/main/java/com/shifthud/frozen/FrozenTaskStore.kt`
- `app/src/main/java/com/shifthud/frozen/FrozenTasksViewModel.kt`
- `app/src/main/java/com/shifthud/frozen/FrozenTasksScreen.kt`
- `app/src/main/java/com/shifthud/frozen/FrozenWorkflowControls.kt`
- `app/src/main/java/com/shifthud/frozen/EndcapProducts.kt`
- `app/src/main/java/com/shifthud/frozen/EndcapOcr.kt`
- `app/src/main/java/com/shifthud/frozen/EndcapImportScreen.kt`
- `app/src/test/java/com/shifthud/FrozenTuesdayTest.kt`
- `app/src/androidTest/java/com/shifthud/frozen/FrozenTuesdayDeviceTest.kt`
- `app/src/androidTest/java/com/shifthud/frozen/FrozenTuesdayRestartDeviceTest.kt`
- `docs/FROZEN-TUESDAY-006D3.md`
