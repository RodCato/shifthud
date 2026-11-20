# MVP-006 employee number and Publix Pro refinements

Settings → Display includes an Employee Number text field with a numeric keyboard, Save and Clear. Surrounding whitespace is trimmed; leading zeros and arbitrary text length are retained. The existing ShiftPreferences facade exposes a dedicated local DataStore so the number is not mixed into exportable payroll/planner preferences or backup snapshots. Empty values remove the key. Successful changed writes trigger widget redraw only, preserving the existing settings notification callback. No new permissions, telemetry, authentication logic or notifications are introduced.

Large home-screen widgets (at least 320×480dp) display the saved number immediately below primary shift information and above the checklist. Empty values omit the row. The provider remains home-screen-only; explicit host-category filtering suppresses the number on keyguard or unknown hosts. The number is not part of shift/session state or notification models. Text is limited to two display lines to protect fixed controls; storage and editing have no length limit. Large-font sizing and task-space budgeting account for the new row.

Scheduled Frozen templates from November 10 onward add separate morning and afternoon Publix Pro Store Lists tasks, each with a stable daily ID and normal completion/history. Morning follows the initial existing priority task; afternoon sits with closing work. Pending SIMS Corrections appears next to Random Counts when available, otherwise near the closing work, labeled “When access permits.” It is a day-specific opportunity reminder excluded from automatic carryovers. Its description explicitly distinguishes the personal Count list from official QOH adjustments. No product database or Count Route changes are introduced.

Required operational carryovers, optional CBT behavior, Monday/Tuesday endcap guidance, old template order, and saved task history remain intact. Existing stable-ID merging protects completed/historical checklists and prevents duplicate task generation. No Room migration or task serialization change is required.

Files changed:
- ShiftHudApplication.kt; data/preferences/ShiftPreferences.kt and EmployeeNumberPreferences.kt.
- ui/settings/SettingsCatalog.kt, SettingsScreen.kt and EmployeeNumberSettings.kt.
- widget/ShiftHudWidget.kt and WidgetFrozenState.kt.
- frozen/FrozenDailyWorkflow.kt and FrozenTasksScreen.kt.
- Unit tests: EmployeeNumberTest.kt, PublixProChecklistTest.kt, SettingsCatalogTest.kt.
- Device tests: EmployeeNumberDeviceTest.kt, WidgetLayoutDeviceTest.kt, FrozenDailyDeviceTest.kt.

Verification: debug build and 1,053 unit tests per debug/release variant passed; backup restoration tests excluded. Lint: zero errors, 17 existing warnings, one hint. Five normal-font emulator cases passed after fixing the opportunity subtitle renderer, plus two 200%-font cases after adjusting employee-row sizing. Tests use synthetic employee numbers and isolated task/preference stores. Physical launcher refresh and persistence through a real device reboot remain device acceptance checks. No user data was cleared, PR merged, signed release generated, or physical deployment performed.
