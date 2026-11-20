# MVP-006 — One-save paystub import and review

## Root cause and scope

The current branch did not have a separate net-pay verification form. It recognized Net Pay/Net Earnings, but not Publix Pay Amount, including the wrapped Pay / Amount label adjacent to YTD Pay. Therefore net remained blank even when the screenshot contained the amount. User-reviewed import provenance and the derived work/net/deposit reconciliation were separate concepts but not clearly labeled.

Pay Amount is now a primary reported-net source. Existing Net Pay recognition remains available. Conflicting labels, duplicate candidates, multiple amounts, malformed decimals, unlabeled values and ambiguous YTD rows remain unknown. A standalone Pay Amount label followed by exactly one amount is accepted. The bounded wrapped Pay [current amount] YTD Pay / Amount layout uses only the value explicitly preceding YTD, while retaining the YTD boundary for subsequent parsing.

A recognized tax total is retained as a single summary deduction only when no tax components were captured. It is never added on top of components. The November 19 acceptance fixture produces gross 617.76, net 537.51, taxes 80.25, hours 38.61, and the correct pay period/date.

## Review and persistence

OCR completion opens the existing editable review dialog. The checkbox reads “I've reviewed the extracted paycheck details and confirm they match my paystub.” The primary action is Save Verified Paycheck. Net appears once as an editable field; there is no second net entry for verification.

Confirmation is keyed to the current reviewed draft and hours inputs, kept only in memory, and reset by edits or review recreation. Changing a value and changing it back does not resurrect confirmation. Verified save requires the pay date, hours, gross, net and calculable deductions. That one confirmation also accounts for the deduction list. Existing discrepancy acknowledgement remains for saving deliberately reviewed differences. Missing information can be saved separately using Save draft, which does not append reviewed provenance or assert complete deductions.

The existing persisted `Paystub image reviewed.` provenance records user confirmation; there is no new verification database column or migration. Historical notes, amounts and records are not rewritten. Paychecks displays confirmed review separately from the derived work/deposit reconciliation status. Confirmation means the user reviewed the source, not that recorded shifts or bank deposits necessarily match. Existing manual partial records, history and earnings aggregation remain unchanged.

The existing repository performs one atomic save, retains period uniqueness/revision guards and blocks duplicate import overwrites. An existing paycheck must still be opened explicitly for editing. No automatic update/merge of historical amounts was introduced. Saved drafts retain the existing saved-record analytics semantics; analytics itself was not redesigned.

## Arithmetic and safety

Calculations continue through existing `calculatedCashNet`, `cashNetDifference`, tax-summary checks, and noncash classification. Reconciled cash values show “Reconciled — $537.51 net pay”; discrepancies retain their exact cent difference. Noncash already included in gross is subtracted only under the existing explicit inclusion choice. No gross, net, tax or deposit is forced to match. Split deposits remain separate and are not fabricated from Pay Amount.

On-device ML Kit, Photo Picker, screenshot lifetime, duplicate guards, workweek boundaries, payroll analytics, widgets, and shift/lunch behavior are preserved. No real paystub image or payroll seed data was added. The example is an acceptance test fixture only.

## Files

- `domain/paystub/PaystubExtraction.kt`: Pay Amount recognition and standalone tax-total preservation.
- `ui/payroll/PaycheckEditor.kt`: single verification checkbox/action, reset behavior, arithmetic status and unverified draft save.
- `ui/payroll/PaychecksScreen.kt`: distinguish persisted source review from work/deposit reconciliation.
- `ui/paystub/PaystubImportScreen.kt`: open review after OCR completes.
- `PayAmountImportTest.kt`, `PaystubCurrentColumnsTest.kt`, `PaystubImportPersistenceTest.kt`: extraction, legacy layout, financial, uniqueness/history regression cases.
- `PaystubVerificationDeviceTest.kt`: save without typing net, edit/reset, interrupted review and unverified draft checks.

## Device acceptance

Import the November 19 screenshot. Confirm 617.76 gross, 80.25 taxes, 537.51 net, 38.61 hours and November 19 pay date. Review the other fields, check the verification box, then Save Verified Paycheck. No net reentry should be needed. Re-importing must be blocked from silently duplicating/replacing the record. Try changing a reviewed amount and reopening an interrupted review: confirmation must be unchecked. Actual device screenshot OCR remains a physical-device check; automated tests use synthetic recognized text and the real review components.

## Verification results

- Debug build and Android test APK build passed.
- 1,084 unit tests per Debug/Release variant passed, including existing payroll, noncash, timekeeping and Frozen regressions.
- Five emulator tests passed: one-checkbox save with no net retyping, edit/revert and interrupted-review reset, unverified draft, existing noncash correction, and reminder settings regression.
- Lint: zero errors, 18 warnings (dependency-version advisories and existing lunch SharedPreferences style warnings), one hint. No new implementation warning.
- No database migration, backup restoration testing, app-data clearing, uninstall, physical-device deployment, or signed release.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
