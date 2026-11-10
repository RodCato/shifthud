# MVP-006 Frozen checklist refinements — November 9, 2026

New daily templates from November 9 onward append Monday's “Manage Outgoing Endcap Inventory” and Monday/Tuesday's “Check Endcap Before Opening Ad Cases.” Existing template IDs and task order are preserved. Tuesday's existing transition descriptions now explain evaluating outgoing merchandise for home-shelf transfer before backstock; saved descriptions are not overwritten. Reset planning and OCR are unchanged.

“Check Available CBTs / GRS Training” is generated for scheduled dates with an existing Frozen template/checklist. The application supplies saved schedule dates from the existing Room repository. Undefined Thursday/Saturday routines are not invented. Future planning uses the same schedule eligibility. Schedule changes recheck the current app/widget checklist.

The optional property is derived from the generated task's exact stable daily ID, never from its editable title. It requires no new persisted fields, database tables or migration. The task appears under OPTIONAL · LOW PRIORITY in Tasks, and with an Optional label in the widget. It uses normal shared completion/history operations. It is excluded from automatic carryovers and never automatically completed; completed instances remain in their source day's history. No overdue state is created.

New items merge by stable identity into existing unfinished current/future checklists. Completed checklists and previously generated historical checklists are not expanded. Concurrent opening is idempotent. The Monday-to-Tuesday endcap-first responsibility uses the existing carryover grouping with preserved source records rather than displaying duplicate responsibilities.

Changed production files: ShiftHudApplication.kt, FrozenDailyWorkflow.kt, FrozenTasks.kt, FrozenTaskStore.kt, FrozenPlanning.kt, FrozenDailyScreen.kt, WidgetFrozenState.kt and ShiftHudWidget.kt. Tests: FrozenChecklistRefinementsTest.kt and FrozenDailyDeviceTest.kt.

Verification covers scheduled Monday/Tuesday/other defined weekdays, optional exclusion from carryovers, history and restart persistence, app/widget completion, stable merges, historical protection, and Saturday–Friday previews. Device checks use isolated synthetic task data; no user records are cleared. Existing float, timekeeping, payroll, notifications, OCR and backup formats are unchanged.

Results: debug build passed; 1,047 tests passed per debug/release variant (backup restoration excluded); five emulator checks passed; lint reported no errors, 17 existing warnings and one hint. Emulator checks covered the new checklist rows and optional section, shared app/widget completion, float controls and widget timekeeping/rendering.
