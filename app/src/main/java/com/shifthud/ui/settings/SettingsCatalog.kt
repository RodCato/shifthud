package com.shifthud.ui.settings

data class SettingsEntry(val id:String,val terms:String)
data class SettingsCategory(val title:String,val subtitle:String,val entries:List<SettingsEntry>)
val SETTINGS_CATEGORIES=listOf(
    SettingsCategory("SHIFT TRACKING","Lunch threshold, automatic lunch completion",listOf(SettingsEntry("threshold","Lunch threshold active work minutes timekeeping"),SettingsEntry("auto","Automatically end lunch Automatic lunch end completion timer duration timekeeping"))),
    SettingsCategory("SCHEDULE & PLANNING","Weekly target, default lunch and clock-out plans",listOf(SettingsEntry("weekly","Weekly target tracking hours warnings 40 hour countdown notification sound vibration permission Garmin"),SettingsEntry("planner","Clock-out planning default planned lunch duration minimum permitted policy shorter custom lunches by scheduled day bulk apply alerts"))),
    SettingsCategory("NOTIFICATIONS & GARMIN","Reminder channels, sound, snooze and test alerts",listOf(SettingsEntry("lunchAlerts","Lunch Alerts minimum 31 planned lunch reminder repeat snooze custom Garmin test"),SettingsEntry("upcoming","Tomorrow next-day upcoming shift days off reminder time sound"),SettingsEntry("tests","Notification Diagnostics Test Center notifications Garmin watch lunch shift end weekly upcoming channels permission diagnostics"),SettingsEntry("end","Shift-end reminder snooze lead sound vibration permission"),SettingsEntry("warnings","Early lunch warnings reminders threshold 60 30 15 5 1 minutes"),SettingsEntry("snooze","Lunch snooze duration"),SettingsEntry("channel","Lunch reminder status custom sound vibration DND Android notification settings permission diagnostics"))),
    SettingsCategory("PAY & PAYROLL","Hourly rates, effective dates and paychecks",listOf(SettingsEntry("rates","Hourly pay rates USD per hour effective from dates history gross"),SettingsEntry("paychecks","Paychecks payroll reconciliation deposits screenshot import taxes"))),
    SettingsCategory("QUICK FIND","Items, favorites and your aisle guide",listOf(SettingsEntry("quick","Quick Find search favorites recent items aisle guide categories"))),
    SettingsCategory("DATA MANAGEMENT","Encrypted backups, restore and historical import",listOf(SettingsEntry("backup","Encrypted backup restore password data export"),SettingsEntry("history","Historical Data Import backfill previous shifts"))),
    SettingsCategory("ABOUT","Version, personal estimates and diagnostics",listOf(SettingsEntry("about","App version disclaimer employer timecard diagnostics personal estimates")))
)
fun settingsMatches(query:String,text:String):Boolean = query.trim().lowercase().split(Regex("\\s+")).all { it in text.lowercase() }
fun SettingsCategory.matchingEntries(query:String):List<SettingsEntry> = if(query.isBlank() || settingsMatches(query,"$title $subtitle")) entries else entries.filter { settingsMatches(query,"$title ${it.terms}") }
