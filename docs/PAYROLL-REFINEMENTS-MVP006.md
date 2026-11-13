# MVP-006 — Payroll reconciliation and paycheck reminders

## Behavior

Existing paycheck editing now supports noncash detail lines with description, integer-cent amount, nullable taxable status, optional category, and an explicit “Already included in reported gross” choice. Existing earnings lines can be reclassified; nothing is changed until Save Paycheck. Blank amounts and unknown tax status remain unknown. No gift-card classification is inferred from its name.

Reported gross and deposits are never rewritten. Calculations are:

- Cash wages = reported gross − noncash explicitly included in gross.
- Calculated cash net = cash wages − confirmed taxes and other deductions.
- Unreconciled difference = calculated cash net − reported cash net.

A one-cent difference is identified as possible rounding, but never silently accepted or removed. Matching tax totals corroborate component lines instead of being added twice. Conflicting summaries leave reconciliation incomplete. Work comparison uses cash wages so a noncash benefit does not produce a false wage discrepancy. Actual work durations and historical rates are unchanged.

The acceptance fixture reconciles $679.04 − $100.00 − $92.29 = $486.75, with $579.04 cash wages. This fixture is test-only. No historical paycheck is corrected automatically.

Monthly, yearly, and all-time reporting uses pay dates and displays gross compensation, cash wages, noncash compensation, taxable noncash, net cash, taxes, and deductions separately. Existing overlap quarantine and reactive saved-record flows remain in use. Gross already includes the applicable benefits; noncash details are not added to gross again.

## Persistence and compatibility

Room migration **8 → 9** adds three payroll_lines columns: nullable taxable, includedInGross (default 0), and nullable category. The existing kind string gains NONCASH. There are no new tables, no destructive migration, and no changes to existing payroll values, relationships, or IDs.

Draft serialization and repository mapping preserve the metadata. Backup schema validation/export version is advanced to 9; old schema 6–8 payloads receive only the new nullable/default metadata during decoding. The existing encryption and restoration architecture is unchanged. Backup restoration tests were not run, and no backup was restored.

## Reminder

Settings → Pay & Payroll → Import Publix Paycheck includes enable/disable, weekday, and the existing Material time picker. Each edit saves immediately. A new configuration defaults to enabled, Thursday 7:00 PM device-local time. Notification permission and Android notification settings are available inline; posting honors permission, app/channel blocking, sound, vibration, and DND.

A unique WorkManager one-time chain (`paycheck-import-reminder`) recalculates the next local-calendar occurrence. Edits replace pending work; disabling cancels it and the visible reminder. Reboot, package update, clock/timezone changes, and app opening re-evaluate the schedule. No exact alarm or foreground service is added. Channel `paycheck_import_reminders` and notification ID 1006 are separate from shift reminders.

Durable date receipts plus recovery from a notification already in the shade prevent repeated posting for one occurrence. Each new weekly occurrence can alert again. A complete saved paycheck with exactly that reminder date and balanced cash arithmetic suppresses delivery. Ambiguous overlaps, other pay dates, missing facts, or mismatches do not suppress it. No employer cadence is invented and no records or confirmation state are written.

Tapping opens the existing Paychecks route (previous Saturday–Friday week) with import and history access. This is independent of ChatGPT reminders.

Android may delay WorkManager for Doze, battery restrictions, or force-stop. A delayed occurrence is eligible for up to 24 hours; older occurrences are skipped rather than sending stale weekly reminders. Android local-time resolution moves nonexistent DST times forward and chooses the earlier offset for an overlap; the date receipt prevents a second alert in the repeated hour. Force-stopped apps resume recovery on reopening. Physical reboot/Doze and OEM scheduling timing remain device checks.

## Verification

- Debug build and Android test APK: passed.
- Unit tests: 1,069 per Debug/Release variant, zero failures; includes migration/reopen, ordinary and noncash payroll, exact cents, missing values, monthly/yearly totals, reminder persistence, changed schedule, cancellation, permissions, DST, shade recovery, unique WorkManager jobs, and notification destination. Existing timekeeping/planner/Frozen regressions pass.
- Emulator notification destination intent also opened Paychecks with Import Paystub Screenshot and earnings access.
- Emulator: four checks passed (paycheck correction, reminder settings, earnings navigation/reactivity, narrow-screen/200% font rendering).
- Lint: zero errors, 17 existing warnings, one hint.
- BackupRoundTripTest excluded to honor the no-restoration-testing constraint; its legacy fixtures and schema-version expectation were updated for future authorized runs.
- No app-data clearing, uninstall, physical-device deployment, signed release, or merge.

## Physical-device acceptance

1. Open the existing November 5 paycheck and Edit. Add noncash compensation (or reclassify its existing earnings detail): description Thanksgiving gift card, amount 100.00, Taxable, category Gift Card if desired, Already included in reported gross checked. Review then Save.
2. Verify reported gross remains 679.04, cash wages 579.04, net cash/deposit 486.75, and cash difference is zero. Verify monthly/lifetime gross has not increased by another 100.00.
3. In Settings → Pay & Payroll, enable the reminder and allow notifications. Set the device-local day/time a few minutes ahead, close the app, and allow for Android delivery delays. Tap the notification to open Paychecks. Change the schedule, then disable it and verify cancellation. Restore Thursday 7 PM if desired.
4. Check the same behavior after reboot and under the device's normal battery policy. A complete paycheck with that exact pay date intentionally suppresses the reminder.

## Files changed

- `app/schemas/com.shifthud.data.local.ShiftDatabase/9.json`
- `app/src/androidTest/java/com/shifthud/payroll/EarningsDeviceTest.kt`
- `app/src/androidTest/java/com/shifthud/payroll/PayrollRefinementDeviceTest.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/shifthud/MainActivity.kt`
- `app/src/main/java/com/shifthud/ShiftHudApplication.kt`
- `app/src/main/java/com/shifthud/backup/BackupManager.kt`
- `app/src/main/java/com/shifthud/backup/BackupSnapshot.kt`
- `app/src/main/java/com/shifthud/data/local/ShiftDatabase.kt`
- `app/src/main/java/com/shifthud/data/local/entity/PayrollEntities.kt`
- `app/src/main/java/com/shifthud/data/repository/PayrollRepository.kt`
- `app/src/main/java/com/shifthud/domain/payroll/EarningsOverview.kt`
- `app/src/main/java/com/shifthud/domain/payroll/Payroll.kt`
- `app/src/main/java/com/shifthud/domain/payroll/ReconciliationPresentation.kt`
- `app/src/main/java/com/shifthud/domain/paystub/PaystubExtraction.kt`
- `app/src/main/java/com/shifthud/notification/paycheck/PaycheckReminders.kt`
- `app/src/main/java/com/shifthud/ui/payroll/EarningsOverviewScreen.kt`
- `app/src/main/java/com/shifthud/ui/payroll/PaycheckEditor.kt`
- `app/src/main/java/com/shifthud/ui/payroll/PaychecksScreen.kt`
- `app/src/main/java/com/shifthud/ui/payroll/PayrollDraft.kt`
- `app/src/main/java/com/shifthud/ui/settings/PaycheckReminderSettingsSection.kt`
- `app/src/main/java/com/shifthud/ui/settings/SettingsCatalog.kt`
- `app/src/main/java/com/shifthud/ui/settings/SettingsScreen.kt`
- `app/src/test/java/com/shifthud/AisleGuideMigrationTest.kt`
- `app/src/test/java/com/shifthud/AnalyticsRepositoryTest.kt`
- `app/src/test/java/com/shifthud/BackfillRepositoryTest.kt`
- `app/src/test/java/com/shifthud/CalendarRepositoryTest.kt`
- `app/src/test/java/com/shifthud/EarningsOverviewTest.kt`
- `app/src/test/java/com/shifthud/HistoricalMigrationTest.kt`
- `app/src/test/java/com/shifthud/HistoricalRepositoryTest.kt`
- `app/src/test/java/com/shifthud/NoncashPayrollTest.kt`
- `app/src/test/java/com/shifthud/NoncashPersistenceTest.kt`
- `app/src/test/java/com/shifthud/PayRatePersistenceTest.kt`
- `app/src/test/java/com/shifthud/PaycheckReminderTest.kt`
- `app/src/test/java/com/shifthud/PayrollMigrationTest.kt`
- `app/src/test/java/com/shifthud/PaystubImportPersistenceTest.kt`
- `app/src/test/java/com/shifthud/QuickFindMigrationTest.kt`
- `app/src/test/java/com/shifthud/ScheduleCompletionRepositoryTest.kt`
- `app/src/test/java/com/shifthud/SettingsCatalogTest.kt`
- `app/src/test/java/com/shifthud/TimeRecordMigrationTest.kt`
- `app/src/test/java/com/shifthud/WeeklyLunchMigrationTest.kt`
- `app/src/test/java/com/shifthud/backup/BackupRoundTripTest.kt`
