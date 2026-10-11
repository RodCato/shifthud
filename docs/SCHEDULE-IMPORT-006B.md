# MVP-006B · Schedule screenshot import

## Dependency and scope

Based on `0dd3c66` on `mvp-005d-smart-clockout-planner`: PR #13 (MVP-006A) is merged into this branch, but PR #12 is still open against `mvp-005b-paycheck-reconciliation`. The import PR targets that planner branch, not main. No merges are performed by this task.

Schedule → **Import Schedule Screenshot** uses Android Photo Picker and the bundled Latin ML Kit recognizer already used for paystubs. The shared decoder retains its resolution bounds, orientation handling, cancellation cleanup and memory-only image lifecycle. An optional reading-order function adds schedule column handling; the existing paystub default is unchanged. The manifest continues to remove Internet permission; no storage permission, cloud endpoint, Passport login or scraping is added.

## Recognition and review

Geometry joins horizontally aligned text in list rows. Aligned weekday headers instead partition calendar columns and read each column top-to-bottom. Column associations are flagged for review. Parsing supports ISO, English month/day and numeric month/day dates; explicit AM/PM, compact `4p` and unambiguous 24-hour times; time ranges and separately labeled Start/End values. Bare ambiguous `4:00` stays unknown. Missing/cropped fields stay blank while recognized fields populate. Original OCR, including store/department labels, is available in collapsed details and is never executed or treated as instructions.

A fully recognized Saturday–Friday range with an explicit year can initialize the week. Otherwise the user must select and confirm the screenshot's Saturday and year. December/January is resolved inside that confirmed range; there is no current-year fallback. Printed weekday mismatches are flagged without shifting dates. Changing the week reparses source text and explicitly warns that it resets row edits. Raw text and review fields survive saved-instance restoration; screenshot bytes/URIs are not retained.

Confidence is a deterministic parsing classification, not ML Kit probability or employer verification. Rows and date/time fields distinguish high confidence, review required and unknown. High-confidence rows are ready for select-all; uncertain rows require acknowledgement after correction. Unknown required fields cannot be imported. Users can add missing shifts, edit date/time with the existing Material pickers, change lunch, select individual rows, skip rows, or cancel the final confirmation without writes. OFF rows are informational only; absent days are never assumed OFF.

## Lunch and totals

- Explicit lunch minutes take precedence, with a warning if they disagree with displayed net hours.
- Otherwise start/end span minus displayed paid/net duration proposes lunch, labeled **inferred from displayed net hours**.
- Without either, the saved global default is a clearly labeled proposal, not an employer fact.
- Decimal hours use exact decimal-to-minute conversion. Sub-minute or impossible values are not rounded or invented.
- Lunch must satisfy the existing policy minimum and be shorter than the shift. Overnight ends are on the following date.
- Review shows recognized paid total and any mismatch against screenshot weekly net. Replacements keep existing lunch plans, so their saved total can differ from the recognized total; the review explains this.

## Conflict and persistence rules

Exact date/start/end duplicates are skipped even if OCR proposes a different lunch; existing overrides are retained. A changed same-day shift is a possible update and shows both versions. Keep existing/skip is the default; replacement requires an explicit checkbox and final confirmation. Multiple or cross-date overlaps cannot be imported and require manual schedule review. Worked/active associated schedules cannot be replaced. Replacement preserves IDs, notes and lunch provenance.

The repository verifies the reviewed snapshot again inside the existing guarded Room transaction. It rechecks week, policy, overlaps and replacement ownership. All selected writes succeed together or roll back; there are no intermediate per-row commits. Repeat imports are idempotent. Summary reports imported/updated/duplicate/conflict counts; failed transactions keep the review for correction.

Room observations update Schedule, Dashboard and planner projections. After commit, the existing redraw-only widget hook is used, avoiding live timer reconciliation and lunch-alert delivery. The existing evening reminder reschedule hook runs once per batch. A reminder-enqueue failure reports a deferred refresh rather than misreporting committed data as a failed import. No Room migration, WorkSession write, payroll write, hourly-rate edit, or notification algorithm change is introduced.

## Acceptance and limitations

The supplied October 17–23, 2026 fixture is test-only: five visible shifts, Saturday 4 PM–11 PM, 37 paid hours and five inferred 60-minute lunches. Thursday/Friday are absent, not OFF. The actual Passport image was not attached; fixture OCR proves the bundled recognizer/parser path, not every Passport layout. Blurry, rotated/skewed, multi-range or irregular-column images may need corrections or manually added rows. English/Latin labels are supported; arbitrary employer formats are not guaranteed. A changed same-day non-overlapping shift is conservatively reviewed as a possible update rather than automatically creating a second shift.

Physical acceptance (install over the existing app; never clear data):

1. Open Schedule → Import Schedule Screenshot and select the real Passport image.
2. Verify the screenshot week/year, the five visible shifts and Saturday **4 PM**, plus the **37h** screenshot total.
3. Review inferred/default lunches and any weekday/column/total warnings. Correct uncertain fields; leave cropped/unverified days absent.
4. Select shifts and confirm. Check compact Schedule rows, Dashboard next-shift display and the weekly planner for the selected week.
5. Check evening reminders against imported shifts and installed widgets after redraw. No lunch or historical alert should be generated by the import.
6. Reimport the image: duplicates should be skipped. Test a changed shift by keeping existing first, then explicitly replacing an unworked schedule; its lunch override/ID should remain.
7. Restart and verify persistence. Try cancellation, a partial selection, large fonts and TalkBack. Confirm actual punches and payroll remain unchanged.

## Verification results

- Full Gradle `build test lint assembleDebugAndroidTest` passed: 768 unit tests per variant (1,536 executions), including all existing regression coverage. The 38 new unit tests cover extraction, incomplete/cropped fields, AM/PM, rollover, column geometry, inference, duplicates, explicit replacement, stable IDs, partial selection, restart, rollback, protected work/payroll and refresh hooks.
- Lint: zero errors, 14 dependency-update notices.
- API 37 emulator at 320dp width: real bundled ML Kit OCR recognized the generated acceptance image. The real Android Photo Picker flow prefilled all five fixture shifts and the 37-hour total. Explicit confirmation imported five; repeating the same flow skipped five duplicates. App restart retained five schedules and Dashboard showed the correct 4 PM next shift. Database comparison confirmed WorkSessions, paychecks, payroll lines and deposits unchanged.
- Existing device tests plus import OCR/review/cancel tests pass at normal text and at 200% text in dark mode. The manifest's lack of Internet permission is asserted by the on-device OCR test. No real Passport image, TalkBack session or physical Garmin verification was available; use the physical checklist above.
- Final APK: all 14 standard device tests passed at 200% text, plus the explicitly enabled confirmed-import persistence test passed separately. Original emulator database/preferences were restored and byte-verified after acceptance; display settings were restored and the synthetic gallery image removed.

## MVP-006B.1 · Passport badge/body reconstruction

Physical testing exposed a gap in the original acceptance image: it put each date, time and net duration on a single text line. Passport instead puts weekday/date badges in a left column and multiple shift-detail lines in the center. Flattening those lines before reconstruction split dates from times. A standalone `10/17/2026` header also did not meet the old range-header rule.

The OCR adapter now exposes blocks, lines, elements and bounding rectangles. Paystub importing retains its previous line-based reading order. Schedule importing retains bounded geometry in saved review state and reconstructs Passport rows before converting their text to field values. It matches weekday badges to nearby shift/OFF body anchors, uses following body starts/badges as boundaries, and excludes weekly totals from row bodies. OCR emission order does not determine association. Detached, ambiguous and cropped bodies do not receive arbitrary dates.

A valid full Saturday header establishes the Saturday–Friday week. Weekdays map to their actual offsets, not their position in the visible screenshot, so a cropped screenshot starting Monday still maps Monday to +2. Printed day numbers and numeric month/day/year badges validate the result; disagreements stay unresolved for correction. Missing/invalid/ambiguous headers still require confirmation, which reparses retained geometry. Explicit OFF rows are informational, and a cropped Friday badge is not interpreted as OFF.

Compact review headers now show friendly weekday/date, times, recognized paid duration and lunch provenance. A matching 37-hour total is labeled a consistency check, not proof that all seven days were captured. Existing transactional persistence, duplicate handling, conflict review, schedule replacement protection and notification/widget hooks are unchanged.

The new regression image is generated in test code with a left badge column, offset central start/end/job/store/net lines, right chevrons, a standalone header, five shifts, Thursday OFF and a cropped Friday. No personal screenshot is committed. The actual physical-device screenshot was not attached in the available task files; irregular layouts, OCR mistakes and uncertain badge associations still require review.

Retest on the physical device: install over the existing app, select the same Passport screenshot, verify October 17–23 and five complete shifts (Saturday 4 PM–11 PM), 6/7/8/8/8 paid hours, five inferred 60-minute lunches, Thursday OFF and no fabricated Friday. Confirm the 37-hour consistency message. Review/import the intended rows, reimport to check duplicate skipping, and restart to confirm persistence. Actual work/lunch punches and payroll must remain unchanged.

MVP-006B.1 verification: full build/test/lint passed with 786 tests per variant (1,572 executions), zero lint errors and 14 dependency-update notices. All 15 standard device tests passed at 200% text/320dp width, plus the explicit geometry-OCR import/duplicate persistence test passed separately. The real Photo Picker flow on the final APK produced the confirmed October 17–23 week, five complete shifts, 37 paid hours, Thursday OFF and a cropped-Friday warning; explicit confirmation imported five, and app restart retained the correct Saturday 4 PM next-shift display. The original emulator database/preferences were restored and byte-verified after testing, and the synthetic image was removed. No Room schema, persistence repository, conflict/transaction logic, WorkSession, payroll or notification changes were made by this follow-up.
