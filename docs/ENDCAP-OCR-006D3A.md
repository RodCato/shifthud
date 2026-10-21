# MVP-006D.3A — Endcap OCR repair

## Dependency and scope

Branch `mvp-006d3a-endcap-ocr-reliability` is based on `d647f0e47e9e5dc83f8fbcdf92e95b979e6ebefc` on `mvp-006d3-tuesday-endcap-workflow` (PR #32). PR #33 (Wednesday) was already merged into that branch by the time of preflight. No dependency PR was merged by this change.

## Confirmed failure path

The previous `parseEndcapPage` only enabled product extraction after one aligned OCR row matched `aisle 10 front center`. `if (!target) return@forEach` then dropped every product whenever the heading was split, missed, or different. A split heading in the dense synthetic sheet reproduces this with **real bundled ML Kit recognition**: product text exists, no single-line heading matches the old gate, and the new parser recovers six target products plus a separately identified unrelated display product. Unit fixtures also cover a completely missing heading.

This confirms a parser data-loss defect, not the exact OCR quality of the user's unavailable physical photographs. Local diagnostics are needed on those photos to distinguish image/recognition failures from parser failures. The promotional week comes from the selected workflow date, not OCR.

The previous decoder capped the longest edge at 3,000 pixels. That could reduce dense print readability but is not proven to have caused the reported failure. The new decoder bounds decoded pixels at 12 million and longest edge at 6,000; region decoding reads the selected crop from the original before sampling. EXIF/manual rotation, mirrored orientation handling and bounded previews share one decoder. There is no additional JPEG recompression or cloud OCR.

## Behavior

- OCR keeps blocks, lines, elements and bounding boxes. Geometry joins aligned columns, split location headings and close wrapped description lines. SKU varieties stay separate during extraction; ignored price/quantity columns do not become required fields.
- Location no longer gates extraction. Unknown and other-display rows remain visible, **unselected**. Users explicitly include rows and confirm the display. Multiple displays are imported separately.
- Local, collapsed diagnostics show actual ML Kit text, dimensions/orientation, block/line/candidate counts, display detection and rejected rows with reasons. No document logging or upload is added.
- Camera and multipage Photo Picker are retained. Crop shows the oriented original; drag a rectangle or use accessible boundary sliders. Rotation/crop/retry offers preserve-and-add or explicit replacement; failure leaves old rows intact.
- Editable names/SKUs, source text, include/exclude, manual rows and page confirmation remain. Empty recognition explains crop/rotate/manual recovery. A second confirmation previews proposed task additions or existing records that will be retained.
- Optional family fields, explicit suggested-family acceptance, rename/split/move/ungroup and layout categories precede confirmation. A family creates one representative-case task with individually retained eligible varieties. Additional varieties can be chosen later in the expanded checklist. Final Fill remains independent.
- Same-plan reimports retain original IDs, edits, progress, notes and chosen varieties. Explicitly confirmed new varieties can extend an existing family. Original names/SKUs are retained as identity aliases to prevent corrected rows being duplicated by a retry/reimport. Conflicting category assignments within a family require correction.

## Persistence and protection

No Room migration/schema change. Optional `family`, `category`, `eligible`, `chosen`, `sourceNames` and `sourceSkus` fields extend the existing Frozen DataStore JSON records; absent fields decode to empty/default values. Existing task/workflow IDs, dates, histories, notes and truck events retain their semantics. Imported families and chosen varieties are persisted; image URIs, previews, OCR diagnostics and unsaved review drafts are memory-only. Camera images use the existing transient cache and are cleaned on leaving/next opening import.

No timekeeping, payroll, schedule, notifications, Garmin, widget, backup/restore, carryover generation or Wednesday truck-event implementation is changed. Monday/Tuesday/Wednesday regression tests remain. Tests use isolated fixture stores. No uninstall, app-data clearing, restore testing or signed release is used.

## Verification

Debug build and both unit-test variants pass: 933 tests per variant, zero failures/errors/skips. Lint: zero errors, 16 existing warnings and one hint. Only debug APKs were assembled; compiling the release unit-test variant does not sign/package a release APK.

Commands:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew -I /private/tmp/shifthud-c8-tests.init.gradle \
  assembleDebug test lint assembleDebugAndroidTest --max-workers=2 --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e notClass com.shifthud.backup.BackupDeviceAcceptanceTest \
  com.shifthud.test/androidx.test.runner.AndroidJUnitRunner
```

The init script excludes `BackupRoundTripTest` as requested. Other unit tests are retained. The eligible emulator suite excludes backup restoration acceptance. A separate `EndcapFamilyRestartDeviceTest` runs with `-e familyPhase write`, force-stops the process, then runs with `-e familyPhase read`, using its own fixture file.

Synthetic real-engine tests use dense 2400×3000 printed tables, narrow columns, a merged/split display heading, wrapped descriptions, multiple displays, SKU variants, EXIF rotation, manual rotation, cropping, mild perspective distortion and bounded smaller previews. The UI path recognizes products, corrects a name, preserves edits through retry, selects a crop, confirms a family and generates the picking checklist. Manual fallback is exercised at 320dp/200% font. Camera and multipage activity-result contracts use simulated provider responses with actual image bytes and actual OCR; this does not claim testing every vendor camera/gallery UI.

Final emulator result: **54 eligible tests passed** on Pixel 9/API 37.1, plus separate write/read process-restart phases (one test each). The complete photo → OCR → review/correction → confirmation → checklist path passed. Early test-harness screenshot/preview timing failures were corrected; an unchanged Schedule layout timing test passed on targeted rerun and the final full suite.

The debug APK installed with `adb install -r`, confirming compatible signing without uninstall/clear. Read-only before/after comparisons found **all original Room table contents identical** and byte-identical Frozen task, Shift preferences, Glance and ML Kit preferences. Monday/Tuesday history, carryovers and Wednesday arrival data were preserved. Build, emulator and restart logs accompany the local screenshot artifacts.

## Limits and physical-device retest

- No original store photographs were available; no physical-device verification is claimed. Synthetic success does not establish universal photograph reliability.
- Severe perspective, blur, glare, unusual headers/columns or vertically centered location cells can leave rows unassigned or require correction. There is no perspective rectification or semantic product catalog. Inspect source text and the original before confirmation.
- Location association is conservative and based on recognized heading boundaries, not detection of printed grid lines. Missing location headings cannot safely establish ownership of a row; explicitly select and confirm those candidates.
- Grouping is edited in import review. Saved checklist eligible choices, title/reference/notes and progress remain editable; reimport does not silently reorganize saved families.
- Unsaved review state does not survive process death. Camera originals are transient; only confirmed products and checklist state persist.
- TalkBack semantics and large text are covered, but a spoken screen-reader session and vendor-specific picker/camera behavior still need device testing.

Physical acceptance:
1. Install the debug APK over the compatible existing debug app (do not uninstall/clear data).
2. Import both original photos. Expand diagnostics and check recognized text, dimensions, rows and rejection reasons.
3. Crop to the target display; test EXIF/manual rotation. Verify unknown rows remain visible and unrelated sections are excluded.
4. Correct a description/SKU, retry with keep-edits, group only chosen varieties, select categories and confirm the additions preview.
5. Mark a representative case completed, choose another eligible variety, restart the app and reimport. Confirm edits/progress/IDs persist and Final Fill stays independent.
6. Confirm Monday/Tuesday history, carryovers and Wednesday arrival status remain unchanged. Try manual entry if OCR is poor.

## Changed files

All production changes are under `app/src/main/java/com/shifthud/frozen/`:
`EndcapProducts.kt`, `EndcapOcr.kt`, `EndcapImage.kt` (new), `EndcapCropDialog.kt` (new), `EndcapImportScreen.kt`, `FrozenTasks.kt`, `FrozenTaskStore.kt`, `FrozenTasksViewModel.kt`, `FrozenTasksScreen.kt`.

Tests: `app/src/test/java/com/shifthud/EndcapReliabilityTest.kt` (new), `FrozenTuesdayTest.kt`; `app/src/androidTest/java/com/shifthud/frozen/EndcapReliabilityDeviceTest.kt` (new), `EndcapFamilyRestartDeviceTest.kt` (new), `FrozenTuesdayDeviceTest.kt`. This document is the remaining changed file.

Synthetic screenshots are local build artifacts in `app/build/reports/endcap-d3a/screenshots/`: `preview.png`, `diagnostics.png`, `candidates.png`, `correction.png`, `confirmed.png`, `checklist.png`, `manual-large.png`. No internal store documents are committed.
