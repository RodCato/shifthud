# MVP-006 widget timekeeping audit

The checklist/float redesign preserved the existing CLOCK IN (scheduled days), START LUNCH and END LUNCH widget actions. Reviewing the state factory from before that redesign confirms CLOCK OUT was never implemented as a widget operation. Unscheduled/off-day widgets previously offered schedule navigation; completed sessions and work after lunch offered OPEN APP. The screenshot of SHIFT COMPLETE alone was not evidence of removed active controls.

## Corrected behavior

- No active session, including unscheduled work: CLOCK IN.
- Working before lunch: START LUNCH and CLOCK OUT.
- On lunch: END LUNCH and CLOCK OUT.
- Working after the single supported lunch: CLOCK OUT; no second lunch action.
- Completed: CLOCK IN NEW SESSION.

Actions use WidgetActionExecutor, ShiftRepository and ShiftEngine. Clock-out uses the expected session ID and active state in the existing atomic repository transaction. During lunch it composes engine.endLunch followed by engine.clockOut in that transaction; the engine still disallows clock-out with an open lunch. No second implementation or changes to engine calculations are introduced. Clock-in retains the previous-session/date preconditions, so repeated or old actions cannot create duplicate sessions. Existing repository and callback refresh paths maintain service and notification updates.

Timekeeping buttons remain fixed outside the scrollable content, before floats and navigation. Small hosts can scroll status/details without pushing actions off-screen. Expanded task budgeting reserves the controls; the unfinished-first prefix drops completed rows first. Interactive task checkboxes, source-record guards, float storage and navigation are unchanged.

## Verification and limits

Unit regression covers state presentation, full widget action lifecycle, concurrent duplicate clock-out, stale-state/session rejection, new-session duplicate protection and persistence across closing/reopening a disk-backed Room database. Existing Frozen, float, payroll, calculation, reminder and service-related tests remain included (backup restoration tests are deliberately excluded).

Actual Glance RemoteViews device tests exercise all five display states across compact, normal and expanded hosts, plus checklist/float/Tasks synchronization and enlarged fonts. They validate control visibility and callback wiring; they do not substitute for physical-launcher PendingIntent delivery or a real reboot with an active personal session.

Physical acceptance: clock in unscheduled; start/end lunch; clock out working; start another session; clock out during lunch and verify both timestamps; repeat taps and inspect session history; reboot with an active test session and verify recovery. Confirm checklist, floats and weekly totals remain correct. No physical-device deployment, schema migration, signed release, backup restoration or merge is performed by this change.

Final results: debug APK built successfully; 1,041 unit tests per debug/release variant passed; four normal-font emulator tests and two 200%-font renderer tests passed. Lint reported zero errors, 17 existing warnings and one hint. The large-font tests found and verified fixes for narrow button labels and completed-state footer clipping. No app data was cleared; emulator display/font settings were restored.
