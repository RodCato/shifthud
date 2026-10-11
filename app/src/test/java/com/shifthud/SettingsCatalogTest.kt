package com.shifthud

import com.shifthud.ui.settings.*
import org.junit.Assert.*
import org.junit.Test

class SettingsCatalogTest {
    private fun ids(query:String)=SETTINGS_CATEGORIES.flatMap{it.matchingEntries(query)}.map{it.id}.toSet()
    @Test fun everyExistingControlGroupHasOneHome() {
        val entries=SETTINGS_CATEGORIES.flatMap{it.entries}.map{it.id}
        assertEquals(entries.size,entries.distinct().size)
        assertEquals(setOf("threshold","auto","weekly","planner","upcoming","tests","end","warnings","snooze","channel","rates","paychecks","quick","backup","history","about"),entries.toSet())
    }
    @Test fun searchesLabelsAndRelatedWordsCaseInsensitively() {
        assertTrue("auto" in ids("AUTO lunch"));assertTrue("backup" in ids("restore"))
        assertTrue("tests" in ids("Garmin test"));assertTrue("channel" in ids("custom sound"))
        assertTrue("weekly" in ids("weekly sound"));assertTrue("history" in ids("backfill"))
        assertTrue("rates" in ids("effective dates"));assertTrue("planner" in ids("default lunch"))
        assertTrue("paychecks" in ids("screenshot"));assertTrue(ids("nonexistent controls").isEmpty())
    }
    @Test fun blankSearchIncludesAllAndCategoriesMatch() {
        assertEquals(16,ids("   ").size)
        assertTrue(ids("data management").containsAll(listOf("backup","history")))
        assertEquals(7,SETTINGS_CATEGORIES.size)
    }
}
