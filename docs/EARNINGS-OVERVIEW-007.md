# MVP-007 — Documented earnings overview

## Integration and data audit

Base: `codex/mvp-006f1a-simple-door-counts` at `df1d60e201fe88be8d26fa00d48dede1d4fcacc7` (open PR #36). That integration chain includes paycheck reconciliation, reviewed OCR imports, and the latest Count Route changes. Main does not contain the whole unmerged stack. This PR depends on #36 and does not merge any dependency.

`Paycheck` / `PaycheckEntity` already store work-period boundaries, nullable pay date, decimal employer hours, nullable integer-cent gross/net, `deductionsComplete`, notes/reference/revision, labeled deduction/earning lines, and dated deposits. There is no separate persisted confirmation status: manual save and OCR review confirmation are the existing commit boundaries. Unsaved OCR drafts never enter the repository. Saved partial records contain confirmed *entered values*, not necessarily a complete paycheck. Reconciliation is independently derived against recorded work and is not a payroll-confirmation gate.

Room has a unique work-period constraint, and the repository rejects duplicate periods and stale revisions. No database, repository, parser, reconciliation, or backup format changes are needed.

## Derived semantics

- Exact BigDecimal sums from integer cents and saved decimal hours; no binary currency arithmetic. Unknown values show unavailable or a known subtotal plus missing count, never an implied zero. Percentages require complete gross and corresponding net/tax fields across the selected records.
- All Time includes undated records. Calendar Year and Month use pay date, with direct selectors and previous/next controls. Monthly records open the existing paycheck details.
- Saved deduction labels are not a typed tax taxonomy. Recognizable tax/withholding/Social Security/Medicare/FICA labels are tax components; recognizable non-tax deduction labels are other deductions. Unclassified labels leave totals unknown and require review. Original labels remain visible. This conservative heuristic cannot classify every employer label.
- A total-tax row is used once, never added to individual tax components. Conflicting summaries and repeated deduction rows are flagged; total-deduction rows cannot safely establish a tax split and require review. Explicit confirmation of complete deductions is required for complete tax/other totals. No missing taxes are inferred from net or deposits. Arithmetic discrepancies retain the original payroll amounts.
- One saved identity is counted once. Overlapping work periods are conservatively quarantined for review, not merged/deleted. Equal amounts in non-overlapping periods are distinct. Potential overlapping legitimate payments require resolving their period metadata in the existing editor. The current payroll model supports only one paycheck per exact period and has no employer identity or separate duplicate-resolution state.
- Coverage uses Saturday–Friday weeks with recorded sessions, not every calendar week. Complete enclosing payroll periods cover work; partial overlaps are uncertain. No reliable payday cadence exists, so unrecorded past weeks say “Paystub not yet recorded,” never overdue. Recorded sessions alone cannot certify that all work was entered.
- Pending work is all recorded completed work in unmatched weeks, independent of pay-date filters (no pay date exists yet). Existing historical-rate/paid-duration estimation is reused. Matched weeks are excluded; ambiguous/incomplete matching suppresses exact pending totals. No estimated take-home amount is produced.
- Separate repository flows feed saved payroll and coverage; opening this view does not run OCR or write anything. Reconciliation changes cannot alter employer totals.

## Verification / device acceptance

Use synthetic records only. Automated unit tests cover aggregates, missing fields, summary tax rows, precision, duplicates, filtering, coverage, and repository change/reopen persistence. Device tests cover responsive 320dp rendering, 200% font density, headings/actions, filters, expansion, detail callback, and reactive edits/removal. Existing eligible suites run too; backup restoration tests are deliberately excluded.

Physical checks:
1. Open Analytics → Paychecks → View Earnings Overview. Compare gross/net/hours against saved records; incomplete fields must remain labeled.
2. Select a year/month and expand a monthly summary. Open a paycheck, edit it with existing controls, and return; totals should change immediately. Cancel an OCR import; totals should not change.
3. Check custom deduction labels and mismatch warnings; correct only against the original paystub. Overlapping periods must remain excluded until reviewed.
4. Confirm pending estimates remain separate and matched weeks disappear from pending work.
5. Check TalkBack spoken navigation, large system fonts, screen rotation, and process restart on a physical device. Automated semantics checks do not verify spoken TalkBack behavior.

No app data clearing, uninstall, backup restoration, signed release, or external payroll upload is part of this feature.

## Build evidence

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (debug signing only).
- `assembleDebug test lint assembleDebugAndroidTest` passed with the restoration-test exclusion init script.
- 985 unit tests passed in each debug/release test variant (26 added); no failures or skipped tests within the eligible suites. Release unit tests do not create a release APK.
- Lint: zero errors, 17 warnings in unchanged dependency/notification files, one existing state-boxing hint. No earnings-file findings.
- Two targeted earnings device tests passed. Screenshots in `app/build/reports/earnings/` contain synthetic records only.
- Large-font checks verify rendered line bounds, height, and absence of ellipsis. Compose's intrinsic button measurements can retain a larger paragraph width even when every glyph fits, so paragraph width alone is not treated as clipping.
- Full eligible emulator suite: 62 tests passed on Pixel 9, API 37.1 (backup restoration class excluded).
- Installed-app navigation smoke test passed: Dashboard → Paychecks → Earnings Overview, including the empty state.
- Read-only comparison before/after installation, tests, and navigation: all nine original ShiftHUD database tables unchanged; all original preferences byte-identical. No restoration was performed.
