# MVP-006D.7 — Daily float counter and Frozen widget

Base: `codex/mvp-006d6-weekly-task-planning` at `2f6dfdba39f245474fa041055729565476decfd4` (PR #39). Existing Frozen task storage, Glance responsive sizes, shift actions, paid-time/weekly metrics and refresh architecture are reused.

## One persisted counter

`FrozenTaskStore` stores a `daily_float_counts_v1` date-to-integer map in its existing Preferences DataStore. The app and widget use this same store; there is no widget copy, WorkSession association, timer, inferred float count or productivity score. Each +/-1 operation atomically updates the count and its command receipt. Counts cannot go negative. The date is calculated at transaction time from the current device zone, so a stale yesterday widget counts today's tap against today. Reads for a new date return zero without deleting historical totals.

Tasks presents +/-1 on today's checklist. Past dates show their saved count read-only. Saturday–Friday week and calendar-month totals derive from the saved daily map. No chart or payroll estimate is involved. Rapid app taps bypass the task-editor busy gate and commit even if the view model is subsequently cleared.

The last 1,024 command IDs are retained for retry deduplication, including across process restart. Glance does not expose a stable unique ID for an individual tap delivery. A token attached to a rendered button would also reject legitimate rapid successive taps, so each callback invocation gets a new command ID. Replays of a known command ID are deduplicated; separately delivered Android callback invocations cannot reliably be distinguished from distinct user taps. Launcher-level delivery/throttling remains outside the app's guarantee.

## Widget content and actions

Expanded widgets replace Favorites/Aisle Guide rendering and subscriptions with today's persisted Frozen summary: date, merged completion count and the full ordered checklist. Unfinished responsibilities precede subdued, checked completed tasks. Exact host dimensions and measured wrapped text determine the visible prefix; omitted tasks have an explicit “+N more tasks — Open Tasks” link. Archived, dismissed and associate-resolved sources are excluded using the existing daily-row derivation. The Tasks footer and overflow link open Tasks. All-complete and no-recurring-work states have explicit messages.

Widget initialization uses the existing idempotent daily initializer without changing the app's selected workflow or triggering another redraw. Repeated refreshes do not duplicate tasks or mark anything completed. Future previews remain read-only.

The upper shift/lunch area and weekly metrics retain their existing calculation paths. All expanded widgets pin the float controls above the existing shift action and the OPEN APP / TASKS / QUICK FIND footer. Secondary information can scroll on short or large-font hosts; it cannot push controls off-screen. Task checkboxes complete/reopen the shared persisted daily task without opening the app. Tasks navigation and overflow links still open today’s Tasks. Compact/normal variants retain their shift-focused controls. Favorites and Aisle Guide remain available in Quick Find; their application models, CRUD and search behavior are unchanged.

Float callbacks execute only the counter command. A successful Frozen-store mutation requests `WidgetRefresh.redrawHistorical()`, the existing redraw-only path. This does not reconcile lunch, start a service or refresh notifications. Glance observes task/count flows during its composition session, and updateAll refreshes installed instances. Duplicate idempotent task initialization avoids redundant redraws.

Date/time-zone/boot/package broadcasts request a redraw only. Existing active refresh and system widget update scheduling remain in place. Android may defer background widget redraws while asleep or idle; counter writes always use the current local date, even before the next redraw. No new alarms, foreground service or notification channel is introduced.

Navigation uses explicit MainActivity destinations. Tasks widget entry replaces an already-open future preview and opens today's checklist. Quick Find retains its direct-tab focus behavior; Open App goes to Dashboard.

## Data and verification

No Room schema change or migration. Shift/lunch records, payroll, schedules, task history/templates, the November 1 archival baseline, Endcap OCR, Count Route, Favorites and Aisle Guide are preserved. Backup implementation is unchanged. No app-data clearing, backup restoration testing, signed release, physical-device deployment or PR merge is performed.

Executed checks:
- Debug build and Android-test APK passed.
- 1,033 unit tests passed in each debug/release variant (backup round-trip/restoration tests excluded).
- Lint: zero errors, 17 existing warnings and one hint.
- 13 existing task/workflow emulator tests passed.
- Two float/Glance emulator tests passed after correcting test-harness issues (a non-Unit test return and an assertion that initially checked only the immediate clickable parent).
- The shared counter test verifies three widget-executor increments, rapid app increments and a correction against the same store.
- Real RemoteViews cover compact, normal, expanded, tall and large sizes plus lunch-threshold states. Shift controls, pinned float targets and footer links remain visible; Favorites/Aisle Guide text is absent.
- Synthetic expanded/compact widget and float-control screenshots were inspected. No real payroll records or screenshots are added to Git.

Physical acceptance:
1. Install the debug APK in place; do not clear data. Resize a widget to the large expanded layout.
2. Tap +1 FLOAT three times on the launcher, then open Tasks and confirm the same count. Increment in Tasks and verify the widget updates; correct with -1 and confirm the zero floor.
3. Complete/dismiss a task and verify the summary changes without duplicate carryovers.
4. Browse a future date in Tasks, then tap the widget's TASKS shortcut; it must return to today. Verify OPEN APP and QUICK FIND destinations.
5. Check Favorites/Aisle Guide inside Quick Find, and existing start/end lunch controls.
6. Verify restart/reboot and midnight/time-zone rollover on the physical device; previous totals must remain in history. Check launcher-specific tap delivery, large fonts, TalkBack and shorter widget scrolling.

Full-checklist follow-up: actual RemoteViews tests cover eleven persisted-style synthetic titles, four unfinished followed by seven complete, with all rows visible at 320 × 1100dp; smaller hosts explicitly indicate omitted tasks. No refresh architecture or task persistence changes. Physical launcher sizing and accessibility remain device acceptance checks.

Follow-up validation: 1,039 unit tests per debug/release variant pass (backup restoration excluded); debug build and lint pass with 17 existing warnings and one hint. Actual RemoteViews layout tests pass at normal and 200% font sizes; shared float-counter device test passes. Glance task columns are grouped within the ten-child limit.

## Interactive task checkboxes

Native Glance checkboxes use stable source task IDs, the displayed date, an explicit requested completion state, and a SHA-256 fingerprint of the rendered source records. The existing task DataStore compares the fingerprint and applies the same grouped daily completion operation in one atomic edit. Repeated deliveries or stale actions after an app edit, restart, complete/reopen cycle, or date rollover cannot overwrite newer state. Rejected actions redraw persisted truth. No widget database, new preference keys, network access, or Room migration is introduced.

Source dates, notes, lineage and earlier history are preserved; accepted transitions append existing-format history events. Both widget and Tasks observe the same store. Successful saves use the existing redraw callback, and the action also reconciles the checkbox after success or rejection. No confirmation or app launch is involved.

Each checkbox has a minimum 48dp row target, with extra height for wrapping. Accessible controls require more height than the earlier static text rows; smaller widgets show fewer rows with the explicit overflow link. All eleven fit in the tall test host. Float and navigation actions remain fixed.

Device acceptance: check and uncheck a task offline, open Tasks and verify both states; edit in Tasks and verify widget ordering/totals; rapidly repeat taps and verify no duplicate history transitions. Check carryover notes and grouped completion, midnight refresh, launcher resizing, and TalkBack checked-state announcements. Automated device tests use isolated task data and do not alter user records.
