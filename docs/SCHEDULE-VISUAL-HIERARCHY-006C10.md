# MVP-006C.10 — Schedule hierarchy and color

Base: `mvp-006c9-daily-hours-breakdown` at `c974632223dfaa529ab9351d848e11a486ec8915` (open PR #25). Main was fast-forward checked; this change depends on the unmerged C.9 stack.

## Presentation

- A compact Material tonal surface separates the weekly schedule summary from daily rows. Employer values retain the largest type and primary foreground; estimate/source disclosures remain visible.
- Mint highlights personal projections and actual paid time. Soft blue highlights lunch. Target text and progress share neutral (more than one hour short), amber (within one hour short), mint (exact target), or Material error (excess) styling. All states retain explicit labels; no target or progress math changes.
- Dates use medium-weight body text; scheduled times use larger semibold title text. Existing row dividers, read-only completion indicators and expansion behavior remain. On-lunch indicators use blue; completed/working indicators remain mint.
- Schedule semantic colors are centralized with dark/light variants. Other screens keep their existing styling; the shared expandable component only gains an optional heading slot.
- Actual lunch and actual paid values remain unchanged. No timers, polling, state transitions or calculation changes.

## Scope protection

No Room schema/migration, punches, lunch records, payroll, weekly-planner math, imports, notifications, alarms, Garmin, widgets, backup/restore or Frozen Operations changes. No backup restoration testing. Debug APK only; no signed release. No automatic merging.

## Physical-device acceptance

Install `app/build/outputs/apk/debug/app-debug.apk` over the existing app, without uninstalling or clearing data.

1. Open a future Schedule week; verify weekly surface, source disclosure, mint daily paid values and blue lunch values. Two rows should be readily visible at normal font size on a narrow screen.
2. For the 7h/8h/9h/9h/9h fixture, change weekly lunch from 60m to 35m. Confirm 6h/7h/8h rows become 6h25m/7h25m/8h25m immediately and weekly 37h becomes 39h5m.
3. Inspect completed and active weeks; recorded lunches remain actual, status labels and checkboxes remain read-only, and expansion still works.
4. Check a shortfall, exact target and excess. Text must identify the state independently of color.
5. At 200% font scale, scroll through summary and rows. Verify wrapping, spoken TalkBack date/time/paid/lunch labels, expansion action and usable touch targets on the physical device.

## Verification

- Debug build, unit tests, instrumentation build and lint passed. 873 unit tests per debug/release variant (1,746 executions), zero failures. Backup restoration test exclusions retained.
- All 35 eligible emulator tests passed across the full suite and targeted reruns. Initial failures identified the new screenshot fixture's button-width overflow and an inherited overly broad Dashboard assertion. Both were corrected. Final five-test Schedule hierarchy/summary/navigation run passed; the corrected Dashboard test also passed.
- Verified 60m → 35m live projection updates, completed actual rendering, 320dp width, 200% font wrapping, expansion/read-only checkbox semantics, and text contrast >=4.5:1 against both dark Schedule surfaces. Amber/red target text has explicit shortfall/excess labels. Device TalkBack speech and real display contrast remain physical checks.
- Lint: 0 errors, 16 existing warnings, 1 hint. `git diff --check` passed.
- Upgrade-installed via `adb install -r`. Read-only before/after comparison confirmed all original Room table contents and user preference bytes unchanged. No uninstall/data clear. Emulator permission/display settings restored and emulator stopped.
- Screenshot fixtures use in-memory schedules and the real planner/presentation components; they do not seed the application database. The full-screen capture uses the existing Schedule navigation test.

Screenshots under `app/build/reports/schedule-hierarchy/` (local build artifacts):
- `full-screen.png`: full Schedule screen with selected week and two upcoming rows.
- `60m-narrow.png`: future week at 60m lunch, 320dp.
- `35m-narrow.png`: same week updated to 35m without navigation.
- `completed.png`: completed week with actual paid and lunch intervals.
- `large-font.png`: completed row and summary at 200% font scale.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Changed files

- `app/src/androidTest/java/com/shifthud/ui/SchedulePlanningDeviceTest.kt`
- `app/src/androidTest/java/com/shifthud/ui/ScheduledHoursSummaryDeviceTest.kt`
- `app/src/androidTest/java/com/shifthud/ui/UiRefinementTest.kt`
- `app/src/main/java/com/shifthud/ui/components/ExpandableSection.kt`
- `app/src/main/java/com/shifthud/ui/schedule/ScheduleCompletionIndicator.kt`
- `app/src/main/java/com/shifthud/ui/schedule/SchedulePlanningSummary.kt`
- `app/src/main/java/com/shifthud/ui/schedule/ScheduleScreen.kt`
- `app/src/androidTest/java/com/shifthud/ui/ScheduleHierarchyDeviceTest.kt`
- `app/src/main/java/com/shifthud/ui/schedule/ScheduleColors.kt`
- `docs/SCHEDULE-VISUAL-HIERARCHY-006C10.md`
