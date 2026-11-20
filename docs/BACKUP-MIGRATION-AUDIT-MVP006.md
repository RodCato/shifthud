# MVP-006 backup, restore and phone migration audit

## Initial verdict (before fixes)

**NOT READY for complete migration.** Source inspection found missing authoritative stores. This is an audit of repository code, not an inspection of the user's production database. No production data was read, changed, deleted or restored. The current request explicitly authorizes isolated restore tests, superseding the earlier no-restoration-testing restriction for this audit only.

## Persistence inventory and original coverage

| Dataset | Actual storage | Authority / original export coverage |
|---|---|---|
| Schedules, employer OCR hours, lunch plans | Room `scheduled_shifts` | Authoritative; included |
| Punch/lunch history, corrections, manual sessions | Room `work_sessions`, optional schedule FK | Authoritative; included |
| Paychecks, periods/dates, decimal hours, gross/net, notes/reference/revision | Room `paychecks` | Authoritative; included |
| Taxes, deductions, earnings, noncash classification/category | Room `payroll_lines`, paycheck FK | Authoritative; included (schema 9) |
| Split deposits | Room `payroll_deposits`, paycheck FK | Authoritative; included |
| Paycheck source review confirmation | Existing payroll notes beginning `Paystub image reviewed.`; no verification column | Included with notes; computed work/deposit reconciliation is separate |
| Quick Find items, favorites, aliases/recent usage; aisle guide | Room `quick_find_items`, `aisle_guide` | Authoritative; included |
| Hourly rate history | `pay_rates` DataStore `history_v1` | Authoritative; included |
| Shift/lunch/weekly/planner preferences | `shift_preferences` DataStore | Included via allowlist, except `show_decimal_hours` causes export rejection |
| Reminder delivery receipts | Shift DataStore and reminder SharedPreferences | Runtime; excluded from portable backups, some captured for rollback |
| Frozen task records, completion, notes, source/lineage, endcap products, user-created records, truck workflows, template registration/archive markers | `frozen_tasks` DataStore | Authoritative; **omitted** |
| Daily float counts and bounded retry-ID history | Same Frozen DataStore (`daily_float_counts_v1`, `float_requests_v1`) | Authoritative counts; retry IDs retained for idempotency; **omitted**. No separate complete float-event ledger exists |
| Count route mapping, previous maps, target, cycles, per-door progress/skips, sessions, revisions | `frozen_count_route` DataStore `count_route_v1` | Authoritative; **omitted** |
| Employee Number | `employee_number` DataStore | Authoritative text, previously intentionally excluded; **omitted** |
| Tomorrow reminder settings | `upcoming_reminders` SharedPreferences | Included; runtime date/state/post receipt excluded |
| Lunch alert minimum/planned/snooze/repeat settings | `on_lunch_alerts` SharedPreferences | **Omitted**, ledger runtime-only |
| Paycheck reminder enabled/day/time | `paycheck_import_reminder` SharedPreferences | **Omitted**, delivered date runtime-only |
| Permission prompt history | `permission_prompts` SharedPreferences | Device-local; should not migrate |
| Weekday templates | Kotlin code, persisted instantiated records/registration in Frozen store | Code regenerable; existing instantiated/history state is not |
| Paystub/schedule OCR review drafts | SavedStateHandle/Compose saved state, not an app-owned durable dataset | Not portable; finish/save before migration |
| Endcap OCR review, crop/rotation, images | In-memory Compose state; temporary camera files in `cache/endcap_camera` deleted on disposal | Not portable; committed products are in Frozen tasks |
| Screenshot attachments | No supported persistent attachment model or retained image bytes found | No attachment bytes or paths to restore; original gallery images belong outside ShiftHUD |
| Payroll/weekly analytics and UI navigation | Derived from saved sources / temporary UI state | Recalculate; do not duplicate |
| WorkManager jobs, channels, permissions, widget placement | Android/WorkManager-managed stores | Regenerable or device-local; notification sounds only referenced, not copied |
| Restore journal | `noBackupFilesDir/restore` original JSON + commit marker | Sensitive local crash-recovery data, not portable content; app sandbox protected |

## Existing export/restore audit

Exports contain typed Room rows, stable IDs, FK references, SQLite sequence counters and three preference groups. AES-GCM authenticates payload and header; Argon2id derives the key from a user password with fresh salt/nonce. Format/payload/database/app versions and timestamps are recorded. SHA-256 validates the JSON payload; canonical equality verifies restored source data. The 32 MiB limit is enforced. No device keystore or original-device path is required to decrypt a file with its password.

Export snapshots use a shared DataGate and Room transactions. SAF output is written, closed, reopened and byte-compared before success. SAF cannot promise atomic replacement or provider fsync: interrupted writes can leave a partial file, which import must reject; user must not overwrite their only good backup. Success proves readback from the provider, not future cloud durability.

Restore validates in an isolated Room staging database before replacement, checks counts/types/FKs and supported versions, then uses an original-state journal, cross-store rollback, WAL checkpoint, canonical equality and a durable commit marker. Active shifts block export and replacement. Restore replaces, never merges. Older database schemas 6–8 are upgraded in memory. Newer unsupported formats/schemas fail before writes. Wrong password, GCM tampering, unsupported data and missing source files produce errors.

## Risks ranked before fixes

1. **Critical:** omitted Frozen/count/float stores mean a successful backup is not a complete migration.
2. **High:** display preference export rejection; missing lunch/paycheck/employee settings. Employee Number inclusion must be disclosed because older behavior intentionally kept it out of exports.
3. **High:** CountRoute writes bypass DataGate, so adding backup coverage alone would not make cross-store replacement safe.
4. **High:** old backups cannot reconstruct omitted data; compatibility must warn explicitly rather than imply complete recovery.
5. **Medium:** lunch alert settings are cached in a StateFlow and must be reloaded after restore; new reminder scheduling must also be rebuilt.
6. **Medium:** post-restore refresh must not create new daily task records merely to redraw widgets.
7. **Medium:** canonical equality currently covers only included groups; extend verification and reports to all supported stores and test clean-state migration/restart.
8. **Limitations:** no durable screenshot attachments/OCR drafts exist; custom sounds/permissions/widgets require new-phone setup. Export requires clock-out. A 32 MiB bound applies. SAF is not an atomic filesystem.

## Initial test evidence

At this stage: source inspection only. Prior payroll/feature tests do not establish complete backup coverage. Clean-state migration and failure tests are pending; no migration-success claim is made.

## Proposed focused remediation

Extend the existing typed preference payload and version its expanded coverage; retain existing encryption, Room model and transaction/journal architecture. Include all authoritative stores, validate through their existing decoders, guard count-route writes, reload affected runtime settings, and add explicit coverage/integrity reporting. Run synthetic clean-state, restart, duplicate, corruption and interruption tests. No major architecture replacement is proposed.

## Implemented fixes

The existing encrypted envelope remains format 1; its payload is now version 2. All eight supported preference groups are included, alongside the same seven Room tables and ID sequences. No Room migration or payroll/timekeeping calculation change was introduced. New backups include Frozen task/truck/endcap records, carryover and template markers, float totals and retry IDs, count maps/cycles/progress, Employee Number, decimal display preference, lunch-alert settings and paycheck-reminder settings. Runtime notification receipts remain device-local in portable files but are captured for rollback.

Legacy payload-1 files remain readable with an explicit omitted-data warning. They cannot recover records that were never exported; replacement clears omitted stores. Update the old phone and create a fresh backup whenever possible. Older app versions cannot read payload 2: install this or a newer compatible version on the destination.

Count-route edits now use the existing DataGate so they cannot interleave with replacement. The existing journal covers all supported stores; lunch settings reload after successful replacement or rollback, and reminders are rescheduled. Preview shows integrity PASS, record/workflow counts and coverage warnings. Replacement still requires explicit confirmation. Errors show FAIL; successful verified replacement shows PASS, with a separate warning if runtime refresh is deferred.

`compareBackupState` produces per-table/group canonical SHA-256 comparisons, counts, and ID-sequence verification. Its source-data comparison includes exact integer cents, decimal-hour strings, timestamps, IDs/FKs, payroll provenance, all task fields/history and count/float state. Derived analytics are not serialized; the acceptance test separately recomputes historical paid duration and gross/reconciliation differences.

## Verification results

- Standard debug build: PASS. Full debug unit suite: **1,104 tests, zero failures/errors/skips**, including 25 backup/crypto tests. Android lint: **0 errors, 18 warnings, 1 hint** (dependency/existing-code warnings).
- Expanded rollback tests change every destination preference group and inject failures after the journal, Room replacement, every one of eight preference groups, final verification and checkpoint. Original runtime state is compared after rollback. Simulated process-death tests exercise those stages and committed-state recovery.
- Emulator: separately packaged **`com.shifthud.migrationaudit`**, Android emulator only. The production package was not installed, cleared, restored or inspected. Synthetic source exported using the real encrypted backup manager; audit-package data was cleared to create a clean installation state; encrypted file/password were transferred; real restore passed; force-stop/restart equality passed.
- Emulator comparison: **2 schedules, 6 sessions, 2,000 Quick Find items, 1 aisle entry, 1 paycheck, 3 payroll lines, 1 deposit**, all eight preference groups and SQLite ID sequences. Included **4 Frozen task fixtures** (completed, carryover, archived, committed endcap product), truck event, two count cycles (finished/skipped and paused partial), two float dates, retry IDs, employee leading zeros and reminder settings.
- Historical derived checks: **2,123 paid minutes**, **56,612 cents** calculated gross at the fixture rate; employer hours **35.39**, employer gross **56,624 cents**, net/deposit **49,610 cents**, tax lines **7,014 cents**. The expected 24-second hours and 12-cent gross differences remain unchanged. Source-review notes remain intact. These are test fixtures, not production seed records.
- Duplicate replacement twice: PASS, no added records. Replacement over an existing marked destination: PASS. Actual process kill after checkpoint and recovery on the next process start: PASS; original destination marker and settings recovered. A subsequent restore/restart comparison also passed.
- Wrong password, corrupt authentication tag, truncation and missing file: rejected without modifying the dataset. Unsupported format/schema, missing preference groups, invalid FKs/duplicate IDs/timestamps and malformed workflow content: rejected in unit tests. Legacy schema 6/7 and payload-1 compatibility: PASS.
- Insufficient storage is tested through injected write/storage failures and rollback, not by filling the emulator disk. Output close failure/readback mismatch and oversize limits are covered. A device that remains out of space may require freeing space and restarting to finish recovery; the app blocks ordinary writes and retains its journal.
- Attachments: **not applicable, not a passed attachment-copy test**. No persistent image attachment feature exists. Missing attachment bytes cannot be repaired by this patch. In-progress OCR reviews and gallery screenshots are outside supported saved state.

## Reproducing the clean-state test

Use synthetic fixtures only. The runner checks both APK package IDs and emulator identity before installing or clearing anything. Destructive instrumentation phases also reject a normal production package. A negative safety check with the normal `com.shifthud` APK correctly refused execution.

```sh
./gradlew assembleDebug assembleDebugAndroidTest -PmigrationAudit=true
python3 scripts/verify-migration-emulator.py --serial emulator-5554 --sdk "$ANDROID_HOME"
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The runner reports its temporary output directory, including per-phase logs and `verification.txt` with PASS/FAIL counts and hashes. Rebuild without `migrationAudit` for the normal installable debug APK: `app/build/outputs/apk/debug/app-debug.apk`. No signed release is created.

## Remaining limits and physical-phone migration checklist

**Supported saved-state migration passed clean-state restore and restart testing.** This is not proof that an old incomplete backup contains omitted records, nor a guarantee about an untested phone/document provider.

1. Keep the original phone and its data intact. Install a compatible fixed build normally, without uninstalling or clearing data. Clock out and finish/save any OCR reviews.
2. Create a new encrypted backup in Settings → Data Management → Backup & Restore. Save it as a new file; retain another copy and keep the password separately. Do not overwrite the only known-good backup.
3. Transfer the encrypted file and original gallery screenshots separately. Install a compatible ShiftHUD build on the destination. Validate/preview counts and coverage before explicitly confirming replacement.
4. Require a verified PASS. Check payroll dates/amounts/review notes, representative punches and lunch edits, completed/carryover tasks, count-route pause position and float history. Restart and recheck before retiring the old phone.
5. Grant notifications as desired; reselect custom sounds and re-add widgets. Channels, OS permissions, widget placements and cloud-document availability are not portable app state.

An installed widget's normal daily initializer can register today's missing template/carryover links after restore, just as opening Tasks does. It preserves existing stable task IDs, completed state and history; it does not auto-complete work. This legitimate current-day initialization is separate from the exact restored-source comparison. The emulator migration test had no installed audit widget; physical launcher/day-rollover behavior remains a device check. Existing Frozen regression tests passed.

SAF provider interruption/atomicity and genuine disk exhaustion were not tested on physical hardware. Custom sound references were inspected/tested for warnings, not copied. No persistent attachments were opened after migration because none are supported. No user production data, real paystub image, or telemetry payload was used. The sensitive rollback journal remains app-private/no-backup plaintext until cleanup; exported files remain encrypted. No major storage redesign was made.
